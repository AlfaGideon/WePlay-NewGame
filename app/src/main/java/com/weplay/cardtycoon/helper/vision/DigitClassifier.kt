package com.weplay.cardtycoon.helper.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Image preprocessing for crisp ML Kit OCR on stylized game graphics + structural fallback classifier
 * for bold single-digit WePlay hand cards (1..9).
 */
object DigitClassifier {

    /**
     * Extracts the SOLID interior colored glyph of a card digit (avoiding hollow outlines and corner arrows!)
     * using saturation + luminance gating followed by a 3x3 majority filter.
     *
     * Verified on both bright active cards and dimmed opponent-turn cards.
     * Returns null if no valid digit glyph is present (prevents hallucinating cards from noise).
     */
    fun preprocessCardDigit(cardBitmap: Bitmap): Bitmap? {
        val w = cardBitmap.width
        val h = cardBitmap.height
        val x0 = (w * 0.18f).toInt()
        val x1 = (w * 0.82f).toInt().coerceAtLeast(x0 + 1)
        val y0 = (h * 0.24f).toInt()
        val y1 = (h * 0.74f).toInt().coerceAtLeast(y0 + 1)
        val cw = x1 - x0
        val ch = y1 - y0
        if (cw < 16 || ch < 16) return null

        val pixels = IntArray(cw * ch)
        cardBitmap.getPixels(pixels, 0, cw, x0, y0, cw, ch)

        val borderLums = ArrayList<Int>(cw * 2 + ch * 2)
        for (x in 0 until cw) {
            borderLums.add(luminance(pixels[x]))
            borderLums.add(luminance(pixels[(ch - 1) * cw + x]))
        }
        for (y in 0 until ch) {
            borderLums.add(luminance(pixels[y * cw]))
            borderLums.add(luminance(pixels[y * cw + cw - 1]))
        }
        borderLums.sort()
        val bgLum = borderLums[borderLums.size / 2]
        val minFillLum = (bgLum * 0.48f).toInt()

        // Core colored digit interior: high saturation (>0.40) and brighter than the dark outer border
        val raw = BooleanArray(cw * ch)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val sat = if (mx > 0) (mx - mn).toFloat() / mx.toFloat() else 0f
            val lum = (r * 30 + g * 59 + b * 11) / 100
            raw[i] = sat > 0.40f && lum >= minFillLum
        }

        // 3x3 majority filter (>= 6 neighbors) eliminates anti-aliased 1px outline halos completely
        val out = IntArray(cw * ch)
        var blackCount = 0
        for (y in 0 until ch) {
            val rowOff = y * cw
            for (x in 0 until cw) {
                var nb = 0
                if (x in 1 until cw - 1 && y in 1 until ch - 1) {
                    for (dy in -1..1) {
                        val rIdx = (y + dy) * cw
                        for (dx in -1..1) {
                            if (raw[rIdx + x + dx]) nb++
                        }
                    }
                }
                if (nb >= 6) {
                    out[rowOff + x] = Color.BLACK
                    blackCount++
                } else {
                    out[rowOff + x] = Color.WHITE
                }
            }
        }

        // Reject empty or noisy crops so we NEVER invent fake cards
        val fillRatio = blackCount.toFloat() / (cw * ch).toFloat()
        if (fillRatio < 0.04f || fillRatio > 0.65f) {
            return null
        }

        val tight = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        tight.setPixels(out, 0, cw, 0, 0, cw, ch)

        val pad = 28
        val scale = 2
        val padded = Bitmap.createBitmap(cw * scale + pad * 2, ch * scale + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(
            tight,
            Rect(0, 0, cw, ch),
            Rect(pad, pad, pad + cw * scale, pad + ch * scale),
            null
        )
        tight.recycle()
        return padded
    }

    fun preprocessWhiteTextOnColor(pillBitmap: Bitmap, skipLeftFraction: Float = 0.28f): Bitmap {
        val w = pillBitmap.width
        val h = pillBitmap.height
        val x0 = (w * skipLeftFraction).toInt().coerceIn(0, w - 1)
        val cw = (w - x0).coerceAtLeast(1)
        val pixels = IntArray(cw * h)
        pillBitmap.getPixels(pixels, 0, cw, x0, 0, cw, h)

        val out = IntArray(cw * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val isWhiteText = r > 205 && g > 205 && b > 205
            out[i] = if (isWhiteText) Color.BLACK else Color.WHITE
        }
        val bin = Bitmap.createBitmap(cw, h, Bitmap.Config.ARGB_8888)
        bin.setPixels(out, 0, cw, 0, 0, cw, h)

        val pad = 20
        val scale = 2
        val padded = Bitmap.createBitmap(cw * scale + pad * 2, h * scale + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bin, Rect(0, 0, cw, h), Rect(pad, pad, pad + cw * scale, pad + h * scale), null)
        bin.recycle()
        return padded
    }

    fun preprocessTilePrice(patch: Bitmap): Bitmap {
        val w = patch.width
        val h = patch.height
        val pixels = IntArray(w * h)
        patch.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = (r * 30 + g * 59 + b * 11) / 100
            val spread = maxOf(r, g, b) - minOf(r, g, b)
            val isDigit = spread < 35 && lum in 110..215
            out[i] = if (isDigit) Color.BLACK else Color.WHITE
        }
        val bin = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bin.setPixels(out, 0, w, 0, 0, w, h)

        val pad = 16
        val scale = 2
        val padded = Bitmap.createBitmap(w * scale + pad * 2, h * scale + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bin, Rect(0, 0, w, h), Rect(pad, pad, pad + w * scale, pad + h * scale), null)
        bin.recycle()
        return padded
    }

    fun parseCardMagnitude(rawText: String): Pair<Int?, Boolean> {
        val cleaned = rawText.trim()
        val hasMinus = cleaned.contains('-') || cleaned.contains('−') || cleaned.contains('–')
        val mapped = buildString {
            for (ch in cleaned) {
                when (ch) {
                    in '0'..'9' -> append(ch)
                    'l', 'I', '|' -> append('1')
                    'Z', 'z' -> append('2')
                    'S', 's' -> append('5')
                    'B' -> append('8')
                    'O', 'o', 'D' -> append('0')
                    'T', '/' -> append('7')
                }
            }
        }
        val num = mapped.toIntOrNull()
        val valid = if (num != null && num in 1..12) num else null
        return valid to hasMinus
    }

    fun parsePositiveInt(rawText: String, minVal: Int = 0, maxVal: Int = 999999): Int? {
        val digits = buildString {
            for (ch in rawText) {
                when (ch) {
                    in '0'..'9' -> append(ch)
                    'O', 'o' -> append('0')
                    'I', 'l' -> append('1')
                    'S', 's' -> append('5')
                    'B' -> append('8')
                }
            }
        }
        val v = digits.toIntOrNull() ?: return null
        return if (v in minVal..maxVal) v else null
    }

    /**
     * Conservative structural fallback classifier for crisp solid-black single digits (1..9).
     * Returns null when ambiguous rather than guessing a nonexistent card.
     */
    fun fallbackClassifySingleDigit(binarizedPadded: Bitmap): Int? {
        val w = binarizedPadded.width
        val h = binarizedPadded.height
        val pixels = IntArray(w * h)
        binarizedPadded.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w; var maxX = 0; var minY = h; var maxY = 0
        var blackCount = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((pixels[row + x] and 0xFF) < 128) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    blackCount++
                }
            }
        }
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        if (bw < 18 || bh < 32 || blackCount < 300) return null

        val aspect = bw.toFloat() / bh.toFloat()
        if (aspect < 0.45f) return 1
        if (aspect > 1.25f) return null // Likely two digits or non-digit

        val z = FloatArray(9)
        for (zy in 0 until 3) {
            val y0 = minY + (bh * zy) / 3
            val y1 = minY + (bh * (zy + 1)) / 3
            for (zx in 0 until 3) {
                val x0 = minX + (bw * zx) / 3
                val x1 = minX + (bw * (zx + 1)) / 3
                var cnt = 0
                var tot = 0
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        if ((pixels[row + x] and 0xFF) < 128) cnt++
                        tot++
                    }
                }
                z[zy * 3 + zx] = if (tot > 0) cnt.toFloat() / tot else 0f
            }
        }

        val holes = countEnclosedHoles(pixels, w, h, minX, minY, maxX, maxY)
        if (holes >= 2) return 8
        if (holes == 1) {
            val topWeight = z[0] + z[1] + z[2]
            val botWeight = z[6] + z[7] + z[8]
            return when {
                z[6] < 0.25f && z[7] < 0.35f -> 4
                topWeight > botWeight + 0.15f -> 9
                botWeight > topWeight + 0.15f -> 6
                else -> 6
            }
        }

        val botTotal = z[6] + z[7] + z[8]
        val topTotal = z[0] + z[1] + z[2]
        if (topTotal > botTotal + 0.45f && z[6] < 0.28f && z[8] < 0.28f) return 7
        if (z[0] > z[2] && z[3] > z[5] * 0.85f && z[8] > 0.35f) return 5
        if (z[3] < 0.32f && botTotal > 1.2f) return 2
        if (z[2] + z[5] + z[8] > z[0] + z[3] + z[6] + 0.35f) return 3
        return if (abs(z[0] - z[2]) < 0.15f && z[3] < 0.35f) 2 else null
    }

    private fun countEnclosedHoles(
        pixels: IntArray,
        stride: Int,
        fullH: Int,
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int
    ): Int {
        val x0 = (minX - 2).coerceAtLeast(0)
        val y0 = (minY - 2).coerceAtLeast(0)
        val x1 = (maxX + 2).coerceAtMost(stride - 1)
        val y1 = (maxY + 2).coerceAtMost(fullH - 1)
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val visited = BooleanArray(bw * bh)
        val queue = IntArray(bw * bh)

        fun isWhite(lx: Int, ly: Int): Boolean {
            val c = pixels[(y0 + ly) * stride + (x0 + lx)]
            return (c and 0xFF) >= 128
        }

        fun flood(startX: Int, startY: Int): Int {
            var head = 0
            var tail = 0
            val startIdx = startY * bw + startX
            visited[startIdx] = true
            queue[tail++] = startIdx
            var area = 0
            while (head < tail) {
                val cur = queue[head++]
                area++
                val cx = cur % bw
                val cy = cur / bw
                if (cx > 0) {
                    val ni = cur - 1
                    if (!visited[ni] && isWhite(cx - 1, cy)) { visited[ni] = true; queue[tail++] = ni }
                }
                if (cx + 1 < bw) {
                    val ni = cur + 1
                    if (!visited[ni] && isWhite(cx + 1, cy)) { visited[ni] = true; queue[tail++] = ni }
                }
                if (cy > 0) {
                    val ni = cur - bw
                    if (!visited[ni] && isWhite(cx, cy - 1)) { visited[ni] = true; queue[tail++] = ni }
                }
                if (cy + 1 < bh) {
                    val ni = cur + bw
                    if (!visited[ni] && isWhite(cx, cy + 1)) { visited[ni] = true; queue[tail++] = ni }
                }
            }
            return area
        }

        for (x in 0 until bw) {
            if (!visited[x] && isWhite(x, 0)) flood(x, 0)
            val bot = (bh - 1) * bw + x
            if (!visited[bot] && isWhite(x, bh - 1)) flood(x, bh - 1)
        }
        for (y in 0 until bh) {
            val left = y * bw
            if (!visited[left] && isWhite(0, y)) flood(0, y)
            val right = y * bw + (bw - 1)
            if (!visited[right] && isWhite(bw - 1, y)) flood(bw - 1, y)
        }

        val minHoleArea = ((bw * bh) * 0.012f).toInt().coerceAtLeast(18)
        var holes = 0
        for (y in 1 until bh - 1) {
            for (x in 1 until bw - 1) {
                val idx = y * bw + x
                if (!visited[idx] && isWhite(x, y)) {
                    val area = flood(x, y)
                    if (area >= minHoleArea) holes++
                }
            }
        }
        return holes
    }

    private fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 30 + g * 59 + b * 11) / 100
    }
}
