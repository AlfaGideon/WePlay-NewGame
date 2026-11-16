package com.weplay.cardtycoon.helper.vision

import android.graphics.Bitmap
import com.weplay.cardtycoon.helper.model.BoardCalibration

/**
 * Resolution-independent auto-calibrator for the 28-tile isometric board.
 *
 * Locates the bright sky-blue UFO tile at (7, 7) and the bright green GO tile at (0, 0)
 * along the screen centerline, deriving exact isometric basis vectors (dx, dy) for any phone resolution.
 */
class BoardCalibrator {

    companion object {
        // Empirical isometric aspect ratio dx / dy of the game's orthographic board camera
        private const val ISO_ASPECT_RATIO = 1.402f
    }

    private var cachedCalibration: BoardCalibration? = null

    fun calibrate(bitmap: Bitmap): BoardCalibration {
        val w = bitmap.width
        val h = bitmap.height

        val cx0 = (0.42f * w).toInt()
        val cx1 = (0.58f * w).toInt()
        val stripW = (cx1 - cx0).coerceAtLeast(1)

        // 1. Detect UFO (sky-blue tile at top vertex, y in [0.18H, 0.36H])
        val uy0 = (0.18f * h).toInt()
        val uy1 = (0.36f * h).toInt()
        val ufo = findColorCentroidAndTopFace(
            bitmap, cx0, uy0, cx1, uy1,
            topFaceRatio = 0.42f
        ) { hue, sat, v ->
            hue in 195f..222f && sat > 0.45f && v > 0.70f
        }

        // 2. Detect GO (warm green tile at bottom vertex, y in [0.54H, 0.72H])
        val gy0 = (0.54f * h).toInt()
        val gy1 = (0.72f * h).toInt()
        val go = findColorCentroidAndTopFace(
            bitmap, cx0, gy0, cx1, gy1,
            topFaceRatio = 0.36f
        ) { hue, sat, v ->
            hue in 95f..138f && sat > 0.35f && v > 0.55f
        }

        if (ufo != null && go != null) {
            val verticalSpan = go.topFaceY - ufo.topFaceY
            // Validate sanity: span should be roughly 28%..48% of portrait screen height
            val minSpan = h * 0.25f
            val maxSpan = h * 0.52f
            if (verticalSpan in minSpan..maxSpan && kotlin.math.abs(go.centerX - ufo.centerX) < stripW * 0.45f) {
                val x0 = (go.centerX + ufo.centerX) / 2f
                val dy = verticalSpan / 14f
                val dx = dy * ISO_ASPECT_RATIO
                val cal = BoardCalibration(
                    originX = x0,
                    originY = go.topFaceY,
                    dx = dx,
                    dy = dy,
                    screenWidth = w,
                    screenHeight = h,
                    confidence = 1.0f
                )
                cachedCalibration = cal
                return cal
            }
        }

        // Use cached calibration if screen dimensions match
        cachedCalibration?.let { cached ->
            if (cached.screenWidth == w && cached.screenHeight == h) {
                return cached.copy(confidence = 0.75f)
            }
        }

        // Normalized fallback matching 1080x2460 reference geometry
        val fallbackDx = w * (87.5f / 1080f)
        val fallbackDy = fallbackDx / ISO_ASPECT_RATIO
        return BoardCalibration(
            originX = w * 0.50f,
            originY = h * (1520f / 2460f),
            dx = fallbackDx,
            dy = fallbackDy,
            screenWidth = w,
            screenHeight = h,
            confidence = 0.4f
        )
    }

    private data class LandmarkHit(
        val centerX: Float,
        val topFaceY: Float,
        val pixelCount: Int
    )

    private inline fun findColorCentroidAndTopFace(
        bitmap: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        topFaceRatio: Float,
        predicate: (Float, Float, Float) -> Boolean
    ): LandmarkHit? {
        val left = x0.coerceIn(0, bitmap.width - 1)
        val top = y0.coerceIn(0, bitmap.height - 1)
        val right = x1.coerceIn(left + 1, bitmap.width)
        val bottom = y1.coerceIn(top + 1, bitmap.height)
        val bw = right - left
        val bh = bottom - top
        val pixels = IntArray(bw * bh)
        bitmap.getPixels(pixels, 0, bw, left, top, bw, bh)

        val hsv = FloatArray(3)
        var sumX = 0L
        var count = 0
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE

        // Step by 2px for speed
        var y = 0
        while (y < bh) {
            val row = y * bw
            var x = 0
            while (x < bw) {
                HsvUtils.pixelToHsv(pixels[row + x], hsv)
                if (predicate(hsv[0], hsv[1], hsv[2])) {
                    sumX += (left + x)
                    val gy = top + y
                    if (gy < minY) minY = gy
                    if (gy > maxY) maxY = gy
                    count++
                }
                x += 2
            }
            y += 2
        }

        if (count < 120 || minY >= maxY) return null
        val centerX = sumX.toFloat() / count
        val topFaceY = minY + (maxY - minY) * topFaceRatio
        return LandmarkHit(centerX, topFaceY, count)
    }
}
