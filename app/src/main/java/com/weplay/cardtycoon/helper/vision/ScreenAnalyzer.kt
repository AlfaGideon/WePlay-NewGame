package com.weplay.cardtycoon.helper.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.weplay.cardtycoon.helper.board.BoardDefinition
import com.weplay.cardtycoon.helper.model.BoardCalibration
import com.weplay.cardtycoon.helper.model.BoardSnapshot
import com.weplay.cardtycoon.helper.model.HandCard
import com.weplay.cardtycoon.helper.model.PlayerColor
import com.weplay.cardtycoon.helper.model.PlayerState
import com.weplay.cardtycoon.helper.model.StrategySettings
import com.weplay.cardtycoon.helper.model.TileState
import com.weplay.cardtycoon.helper.model.TileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Complete on-device Computer Vision pipeline for reading live frames of WePlay "Карточный магнат".
 *
 * Calibrated and verified against real 1080x2460 WePlay gameplay frames:
 *  - Auto-calibrates the 28-tile isometric board from GO + UFO landmarks
 *  - Detects all 4 player pawns (argmax over 28 tiles per alive color)
 *  - Detects property owners (including when a pawn stands directly over a house!) and mud traps
 *  - Dynamically segments 1..4 bottom hand cards, identifies forward (+) vs backward (-),
 *    and reads digits via ML Kit OCR + structural fallback classifier
 *  - Reads all 4 player money pills, bankrupt state, round number, hammer progress, and active turn
 */
class ScreenAnalyzer {

    private val calibrator = BoardCalibrator()
    private val tracker = BoardStateTracker()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private data class NormRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    companion object {
        // Normalized money pill bounding boxes relative to screen W x H (verified on 1080x2460)
        private val MONEY_PILLS = mapOf(
            PlayerColor.ORANGE to NormRect(0.030f, 0.270f, 0.196f, 0.298f),
            PlayerColor.GREEN to NormRect(0.804f, 0.270f, 0.970f, 0.298f),
            PlayerColor.BLUE to NormRect(0.030f, 0.670f, 0.196f, 0.697f),
            PlayerColor.RED to NormRect(0.804f, 0.670f, 0.970f, 0.697f)
        )

        // Card-back peek zones directly above each avatar circle (used to auto-detect local player seat)
        private val CARDBACK_ZONES = mapOf(
            PlayerColor.ORANGE to NormRect(0.065f, 0.162f, 0.148f, 0.183f),
            PlayerColor.GREEN to NormRect(0.852f, 0.162f, 0.935f, 0.183f),
            PlayerColor.BLUE to NormRect(0.065f, 0.556f, 0.148f, 0.577f),
            PlayerColor.RED to NormRect(0.852f, 0.556f, 0.935f, 0.577f)
        )

        private val ROUND_BOX = NormRect(0.390f, 0.178f, 0.610f, 0.208f)
    }

    fun resetMemory() {
        tracker.reset()
    }

    fun close() {
        textRecognizer.close()
    }

    suspend fun analyze(bitmap: Bitmap, settings: StrategySettings): BoardSnapshot =
        withContext(Dispatchers.Default) {
            val w = bitmap.width
            val h = bitmap.height

            // 1. Auto-calibrate board
            val cal = calibrator.calibrate(bitmap)
            val scale = cal.scale.coerceIn(0.45f, 2.2f)
            fun s(px: Float): Int = (px * scale).roundToInt()

            // 2. Detect bankrupt status and OCR money pills for all 4 players
            val bankruptMap = HashMap<PlayerColor, Boolean>()
            val moneyMap = HashMap<PlayerColor, Int>()
            for ((color, nr) in MONEY_PILLS) {
                val crop = safeCrop(bitmap, nr)
                if (crop == null) {
                    bankruptMap[color] = false
                    moneyMap[color] = 0
                    continue
                }
                val whiteRatio = computeWhiteRatio(crop)
                if (whiteRatio > 0.35f) {
                    bankruptMap[color] = true
                    moneyMap[color] = 0
                } else {
                    bankruptMap[color] = false
                    val prep = DigitClassifier.preprocessWhiteTextOnColor(crop, skipLeftFraction = 0.28f)
                    val text = recognizeText(prep)
                    prep.recycle()
                    moneyMap[color] = DigitClassifier.parsePositiveInt(text, 0, 500_000) ?: 0
                }
                crop.recycle()
            }

            // 3. Read round number (e.g. "05 / 30")
            val round = readRoundNumber(bitmap) ?: 1

            // 4. Detect bottom hand cards dynamically (1..4 cards)
            val handCards = detectAndReadHandCards(bitmap)
            val isMyTurn = handCards.any { it.isActive }

            // 5. Auto-detect local player seat (or honor user preference)
            val localColor = settings.preferredSeat ?: detectLocalPlayerSeat(bitmap, handCards.isNotEmpty())

            // 6. Detect all alive player pawns (argmax across 28 tiles per color)
            val pawns = detectPawns(bitmap, cal, scale, bankruptMap)
            val pawnByTile = HashMap<Int, PlayerColor>()
            for ((color, tileIdx) in pawns) {
                if (tileIdx != null) pawnByTile[tileIdx] = color
            }

            // 7. Detect property houses, mud traps, and live displayed rents
            val tiles = detectTiles(bitmap, cal, scale, pawnByTile, localColor)

            // 8. Detect hammer progress (0..4) next to local player avatar
            val hammerProgress = detectHammerProgress(bitmap, localColor)

            // 9. Assemble PlayerState map
            val players = PlayerColor.entries.associateWith { color ->
                val isBk = bankruptMap[color] == true
                PlayerState(
                    color = color,
                    money = moneyMap[color] ?: 0,
                    position = if (isBk) null else pawns[color],
                    isBankrupt = isBk,
                    isLocal = color == localColor,
                    isCurrentTurn = color == localColor && isMyTurn,
                    hammerProgress = if (color == localColor) hammerProgress else 0
                )
            }

            val rawSnapshot = BoardSnapshot(
                round = round,
                maxRounds = 30,
                tiles = tiles,
                players = players,
                localPlayer = localColor,
                handCards = handCards,
                isMyTurn = isMyTurn,
                calibration = cal,
                timestampMs = System.currentTimeMillis(),
                statusNote = if (cal.confidence >= 0.9f) "Поле откалибровано" else "Ожидание поля..."
            )

            tracker.smooth(rawSnapshot, pawnByTile.keys)
        }

    private fun detectPawns(
        bitmap: Bitmap,
        cal: BoardCalibration,
        scale: Float,
        bankruptMap: Map<PlayerColor, Boolean>
    ): Map<PlayerColor, Int?> {
        fun s(px: Float): Int = (px * scale).roundToInt()
        val areaScale = scale * scale
        val result = HashMap<PlayerColor, Int?>()

        for (color in PlayerColor.entries) {
            if (bankruptMap[color] == true) {
                result[color] = null
                continue
            }
            var bestScore = 0f
            var bestTile: Int? = null

            for (spec in BoardDefinition.TILES) {
                val (cxF, cyF) = cal.tileCenter(spec.u, spec.v)
                val cx = cxF.roundToInt()
                val cy = cyF.roundToInt()

                val hat = HsvUtils.countVividInRect(bitmap, cx - s(28f), cy - s(110f), cx + s(28f), cy - s(66f), step = 2)
                val hatPx = hat.forColor(color)
                if (hatPx < (160f * areaScale)) continue

                val face = HsvUtils.countVividInRect(bitmap, cx - s(24f), cy - s(74f), cx + s(24f), cy - s(42f), step = 2)
                if (face.yellowFace < (65f * areaScale)) continue

                val body = HsvUtils.countVividInRect(bitmap, cx - s(32f), cy - s(46f), cx + s(32f), cy - s(6f), step = 2)
                val bodyPx = body.forColor(color)
                if (bodyPx < (130f * areaScale)) continue

                val score = hatPx * 1.5f + bodyPx * 1.0f + face.yellowFace.coerceAtMost((400f * areaScale).toInt()) * 1.5f
                if (score > bestScore) {
                    bestScore = score
                    bestTile = spec.index
                }
            }
            result[color] = bestTile
        }
        return result
    }

    private suspend fun detectTiles(
        bitmap: Bitmap,
        cal: BoardCalibration,
        scale: Float,
        pawnByTile: Map<Int, PlayerColor>,
        localColor: PlayerColor
    ): List<TileState> {
        fun s(px: Float): Int = (px * scale).roundToInt()
        val areaScale = scale * scale

        return BoardDefinition.TILES.map { spec ->
            if (spec.type != TileType.PROPERTY) {
                TileState(
                    index = spec.index,
                    type = spec.type,
                    basePrice = 0,
                    owner = null,
                    displayedRent = 0
                )
            } else {
                val (cxF, cyF) = cal.tileCenter(spec.u, spec.v)
                val cx = cxF.roundToInt()
                val cy = cyF.roundToInt()

                var owner: PlayerColor? = null
                var hasMud = false

                if (spec.index in pawnByTile) {
                    // Pawn standing on tile: inspect left/right pastel pad wings right on tile surface
                    val leftWing = HsvUtils.countPastelOvalInRect(
                        bitmap, cx - s(48f), cy - s(8f), cx - s(24f), cy + s(14f),
                        maxSat = 0.56f, minVal = 0.80f, step = 1
                    )
                    val rightWing = HsvUtils.countPastelOvalInRect(
                        bitmap, cx + s(24f), cy - s(8f), cx + s(48f), cy + s(14f),
                        maxSat = 0.56f, minVal = 0.80f, step = 1
                    )
                    val combined = HsvUtils.PastelCounts(
                        orange = leftWing.orange + rightWing.orange,
                        green = leftWing.green + rightWing.green,
                        blue = leftWing.blue + rightWing.blue,
                        red = leftWing.red + rightWing.red
                    )
                    val (bestC, bestPx) = combined.maxPlayerColor()
                    if (bestPx >= (110f * areaScale)) {
                        owner = bestC
                    }
                } else {
                    val roof = HsvUtils.countVividInRect(bitmap, cx - s(28f), cy - s(46f), cx + s(28f), cy - s(6f), step = 2)
                    if (roof.mud >= (500f * areaScale) && roof.orange < (250f * areaScale)) {
                        hasMud = true
                    } else {
                        val oval = HsvUtils.countPastelOvalInRect(
                            bitmap, cx - s(34f), cy - s(22f), cx + s(34f), cy + s(6f),
                            maxSat = 0.65f, minVal = 0.74f, step = 2
                        )
                        val narrowTop = HsvUtils.countVividInRect(
                            bitmap, cx - s(20f), cy - s(64f), cx + s(20f), cy - s(12f), step = 2
                        )

                        var bestColor: PlayerColor = PlayerColor.ORANGE
                        var bestScore = 0f
                        for (c in PlayerColor.entries) {
                            val total = narrowTop.forColor(c) + roof.forColor(c) * 0.5f + oval.forColor(c) * 0.8f
                            if (total > bestScore) {
                                bestScore = total
                                bestColor = c
                            }
                        }
                        val hasOval = oval.forColor(bestColor) >= (75f * areaScale)
                        val hasRoof = narrowTop.forColor(bestColor) >= (200f * areaScale)
                        if ((hasRoof && bestScore >= 280f * areaScale) || (hasOval && bestScore >= 240f * areaScale)) {
                            owner = bestColor
                        }
                    }
                }

                // Read actual printed rent on opponent-owned properties (captures live 2x/3x/4x hammer & debuffs!)
                var displayedRent = spec.basePrice
                if (owner != null && owner != localColor && spec.index !in pawnByTile) {
                    val priceCrop = safeCropPixels(
                        bitmap,
                        cx - s(38f), cy - s(4f),
                        cx + s(44f), cy + s(42f)
                    )
                    if (priceCrop != null) {
                        val prep = DigitClassifier.preprocessTilePrice(priceCrop)
                        val txt = recognizeText(prep)
                        prep.recycle()
                        priceCrop.recycle()
                        val parsed = DigitClassifier.parsePositiveInt(txt, 50, 20_000)
                        if (parsed != null && parsed % 50 == 0) {
                            displayedRent = parsed
                        }
                    }
                }

                TileState(
                    index = spec.index,
                    type = TileType.PROPERTY,
                    basePrice = spec.basePrice,
                    owner = owner,
                    displayedRent = displayedRent,
                    houseLevel = if (owner != null) 1 else 0,
                    hasMudTrap = hasMud
                )
            }
        }
    }

    private suspend fun detectAndReadHandCards(bitmap: Bitmap): List<HandCard> {
        val w = bitmap.width
        val h = bitmap.height
        val y0 = (0.718f * h).toInt()
        val y1 = (0.828f * h).toInt()
        val x0 = (0.115f * w).toInt()
        val x1 = (0.885f * w).toInt()
        val bandW = (x1 - x0).coerceAtLeast(1)
        val bandH = (y1 - y0).coerceAtLeast(1)

        val pixels = IntArray(bandW * bandH)
        bitmap.getPixels(pixels, 0, bandW, x0, y0, bandW, bandH)

        // Segment card columns where pixels differ from the lavender/purple street background (B > G + 10)
        val colMask = BooleanArray(bandW)
        for (x in 0 until bandW) {
            var nonLavender = 0
            var y = 0
            while (y < bandH) {
                val c = pixels[y * bandW + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if ((b - g < 8) || (r - b > 10)) {
                    nonLavender++
                }
                y += 2
            }
            val ratio = nonLavender.toFloat() / (bandH / 2f).coerceAtLeast(1f)
            colMask[x] = ratio > 0.65f
        }

        // Collect contiguous horizontal card segments
        val minCardW = (0.105f * w).toInt()
        val maxCardW = (0.215f * w).toInt()
        val cardBounds = ArrayList<Pair<Int, Int>>()
        var segStart = -1
        for (x in 0..bandW) {
            val active = x < bandW && colMask[x]
            if (active && segStart == -1) {
                segStart = x
            } else if (!active && segStart != -1) {
                val segW = x - segStart
                if (segW in minCardW..maxCardW) {
                    cardBounds.add((x0 + segStart) to (x0 + x))
                }
                segStart = -1
            }
        }

        if (cardBounds.isEmpty() || cardBounds.size > 4) return emptyList()

        val cards = ArrayList<HandCard>(cardBounds.size)
        for ((slotIdx, pair) in cardBounds.withIndex()) {
            val (cx0, cx1) = pair
            val cardCrop = safeCropPixels(bitmap, cx0, y0, cx1, y1) ?: continue

            // Analyze card interior color (forward green vs backward red/orange) and brightness (my turn!)
            val stats = inspectCardColorAndBrightness(cardCrop)
            val prep = DigitClassifier.preprocessCardDigit(cardCrop)
            val ocrText = recognizeText(prep)
            val (ocrMag, ocrMinus) = DigitClassifier.parseCardMagnitude(ocrText)
            val magnitude = ocrMag ?: DigitClassifier.fallbackClassifySingleDigit(prep)

            prep.recycle()
            cardCrop.recycle()

            if (magnitude != null && magnitude in 1..12) {
                val isNegative = ocrMinus || stats.isRedBackward
                val signedSteps = if (isNegative) -magnitude else magnitude
                cards.add(
                    HandCard(
                        slotIndex = slotIdx,
                        steps = signedSteps,
                        screenBox = RectF(cx0.toFloat(), y0.toFloat(), cx1.toFloat(), y1.toFloat()),
                        confidence = if (ocrMag != null) 0.95f else 0.72f,
                        isActive = stats.brightness > 0.60f
                    )
                )
            }
        }
        return cards
    }

    private data class CardVisualStats(
        val isRedBackward: Boolean,
        val brightness: Float
    )

    private fun inspectCardColorAndBrightness(card: Bitmap): CardVisualStats {
        val x0 = (card.width * 0.14f).toInt()
        val x1 = (card.width * 0.86f).toInt().coerceAtLeast(x0 + 1)
        val y0 = (card.height * 0.14f).toInt()
        val y1 = (card.height * 0.86f).toInt().coerceAtLeast(y0 + 1)
        val cw = x1 - x0
        val ch = y1 - y0
        val pixels = IntArray(cw * ch)
        card.getPixels(pixels, 0, cw, x0, y0, cw, ch)

        var sumR = 0L; var sumG = 0L; var sumB = 0L
        var cnt = 0
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            sumR += (c shr 16) and 0xFF
            sumG += (c shr 8) and 0xFF
            sumB += c and 0xFF
            cnt++
            i += 3
        }
        if (cnt == 0) return CardVisualStats(false, 0.5f)
        val meanR = sumR.toFloat() / cnt
        val meanG = sumG.toFloat() / cnt
        val meanB = sumB.toFloat() / cnt
        val brightness = (meanR + meanG + meanB) / (3f * 255f)
        // Forward cards have green tint (G > R); backward cards have warm red/orange/pink tint (R > G + 8)
        val isRedBackward = (meanR - meanG) > 8f
        return CardVisualStats(isRedBackward, brightness)
    }

    private fun detectLocalPlayerSeat(bitmap: Bitmap, hasHandCards: Boolean): PlayerColor {
        if (!hasHandCards) return PlayerColor.BLUE
        // The local player's cards are in the bottom hand, so they have NO card backs peeking above their avatar
        var minCardBacks = Int.MAX_VALUE
        var bestColor = PlayerColor.BLUE
        for ((color, nr) in CARDBACK_ZONES) {
            val crop = safeCrop(bitmap, nr) ?: continue
            val pixels = IntArray(crop.width * crop.height)
            crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
            var cbPx = 0
            for (c in pixels) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 180 && (r - g) > 50 && (r - b) > 60) cbPx++
            }
            crop.recycle()
            if (cbPx < minCardBacks) {
                minCardBacks = cbPx
                bestColor = color
            }
        }
        return bestColor
    }

    /**
     * Reads the 4-segment circular golden progress ring around the hammer icon next to the local avatar.
     */
    private fun detectHammerProgress(bitmap: Bitmap, localColor: PlayerColor): Int {
        val w = bitmap.width
        val h = bitmap.height
        // On Bottom-Left seat (standard), hammer circle sits near (0.252W, 0.654H), radius ~0.044W
        val (cxNorm, cyNorm) = when (localColor) {
            PlayerColor.BLUE -> 0.252f to 0.654f
            PlayerColor.RED -> 0.748f to 0.654f
            PlayerColor.ORANGE -> 0.252f to 0.254f
            PlayerColor.GREEN -> 0.748f to 0.254f
        }
        val cx = (cxNorm * w).toInt()
        val cy = (cyNorm * h).toInt()
        val radius = (0.044f * w).toInt()
        if (cx - radius < 0 || cx + radius >= w || cy - radius < 0 || cy + radius >= h) return 0

        // Sample the 4 ring quadrants at 45°, 135°, 225°, 315° for bright golden fill (H in 38..58, S > 0.55, V > 0.80)
        val angles = doubleArrayOf(-Math.PI / 4, Math.PI / 4, 3 * Math.PI / 4, -3 * Math.PI / 4)
        val hsv = FloatArray(3)
        var filled = 0
        for (ang in angles) {
            var goldHits = 0
            for (dr in -3..3) {
                val px = (cx + kotlin.math.cos(ang) * (radius + dr)).toInt().coerceIn(0, w - 1)
                val py = (cy + kotlin.math.sin(ang) * (radius + dr)).toInt().coerceIn(0, h - 1)
                HsvUtils.pixelToHsv(bitmap.getPixel(px, py), hsv)
                if (hsv[0] in 35f..58f && hsv[1] > 0.55f && hsv[2] > 0.78f) {
                    goldHits++
                }
            }
            if (goldHits >= 2) filled++
        }
        return filled.coerceIn(0, 3)
    }

    private suspend fun readRoundNumber(bitmap: Bitmap): Int? {
        val crop = safeCrop(bitmap, ROUND_BOX) ?: return null
        val text = recognizeText(crop)
        crop.recycle()
        // Expected formats: "05 / 30", "12/30", "5 / 30"
        val match = Regex("(\\d{1,2})\\s*/\\s*30").find(text)
        val parsed = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        return if (parsed != null && parsed in 1..30) parsed else null
    }

    private fun computeWhiteRatio(bitmap: Bitmap): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var white = 0
        for (c in pixels) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r > 215 && g > 215 && b > 215) white++
        }
        return white.toFloat() / pixels.size.coerceAtLeast(1)
    }

    private fun safeCrop(bitmap: Bitmap, nr: NormRect): Bitmap? {
        val x0 = (nr.x0 * bitmap.width).toInt()
        val y0 = (nr.y0 * bitmap.height).toInt()
        val x1 = (nr.x1 * bitmap.width).toInt()
        val y1 = (nr.y1 * bitmap.height).toInt()
        return safeCropPixels(bitmap, x0, y0, x1, y1)
    }

    private fun safeCropPixels(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Bitmap? {
        val x0 = left.coerceIn(0, bitmap.width - 1)
        val y0 = top.coerceIn(0, bitmap.height - 1)
        val x1 = right.coerceIn(x0 + 1, bitmap.width)
        val y1 = bottom.coerceIn(y0 + 1, bitmap.height)
        val cw = x1 - x0
        val ch = y1 - y0
        if (cw < 4 || ch < 4) return null
        return Bitmap.createBitmap(bitmap, x0, y0, cw, ch)
    }

    private suspend fun recognizeText(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val input = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(input)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text ?: "")
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume("")
                }
        }
}
