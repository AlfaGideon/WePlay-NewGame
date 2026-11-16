package com.weplay.cardtycoon.helper.vision

import android.graphics.Bitmap
import com.weplay.cardtycoon.helper.model.PlayerColor
import kotlin.math.max
import kotlin.math.min

/**
 * Fast allocation-light HSV color helpers calibrated empirically against WePlay "Карточный магнат" screenshots.
 */
object HsvUtils {

    data class VividCounts(
        var orange: Int = 0,
        var green: Int = 0,
        var blue: Int = 0,
        var red: Int = 0,
        var yellowFace: Int = 0,
        var mud: Int = 0
    ) {
        fun forColor(color: PlayerColor): Int = when (color) {
            PlayerColor.ORANGE -> orange
            PlayerColor.GREEN -> green
            PlayerColor.BLUE -> blue
            PlayerColor.RED -> red
        }

        fun maxPlayerColor(): Pair<PlayerColor, Int> {
            var bestColor = PlayerColor.ORANGE
            var bestCount = orange
            if (green > bestCount) { bestColor = PlayerColor.GREEN; bestCount = green }
            if (blue > bestCount) { bestColor = PlayerColor.BLUE; bestCount = blue }
            if (red > bestCount) { bestColor = PlayerColor.RED; bestCount = red }
            return bestColor to bestCount
        }
    }

    data class PastelCounts(
        var orange: Int = 0,
        var green: Int = 0,
        var blue: Int = 0,
        var red: Int = 0
    ) {
        fun forColor(color: PlayerColor): Int = when (color) {
            PlayerColor.ORANGE -> orange
            PlayerColor.GREEN -> green
            PlayerColor.BLUE -> blue
            PlayerColor.RED -> red
        }

        fun maxPlayerColor(): Pair<PlayerColor, Int> {
            var bestColor = PlayerColor.ORANGE
            var bestCount = orange
            if (green > bestCount) { bestColor = PlayerColor.GREEN; bestCount = green }
            if (blue > bestCount) { bestColor = PlayerColor.BLUE; bestCount = blue }
            if (red > bestCount) { bestColor = PlayerColor.RED; bestCount = red }
            return bestColor to bestCount
        }
    }

    /**
     * Converts packed ARGB pixel into (H in 0..360, S in 0..1, V in 0..1) into [outHsv].
     */
    fun pixelToHsv(argb: Int, outHsv: FloatArray) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val df = mx - mn

        val h = when {
            df <= 1e-5f -> 0f
            mx == r -> (60f * ((g - b) / df) + 360f) % 360f
            mx == g -> (60f * ((b - r) / df) + 120f) % 360f
            else -> (60f * ((r - g) / df) + 240f) % 360f
        }
        val s = if (mx > 1e-5f) df / mx else 0f
        outHsv[0] = h
        outHsv[1] = s
        outHsv[2] = mx
    }

    fun countVividInRect(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        step: Int = 1
    ): VividCounts {
        val x0 = left.coerceIn(0, bitmap.width - 1)
        val y0 = top.coerceIn(0, bitmap.height - 1)
        val x1 = right.coerceIn(x0 + 1, bitmap.width)
        val y1 = bottom.coerceIn(y0 + 1, bitmap.height)
        val w = x1 - x0
        val h = y1 - y0
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x0, y0, w, h)

        val counts = VividCounts()
        val hsv = FloatArray(3)
        val weight = step * step

        var y = 0
        while (y < h) {
            val rowOffset = y * w
            var x = 0
            while (x < w) {
                pixelToHsv(pixels[rowOffset + x], hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val v = hsv[2]

                // Vivid player colors calibrated to reject muted terracotta courtyard (S ~ 0.40..0.50)
                if (hue in 16f..38f && sat > 0.62f && v > 0.82f) {
                    counts.orange += weight
                } else if (hue in 135f..178f && sat > 0.48f && v > 0.35f) {
                    counts.green += weight
                } else if (hue in 198f..232f && sat > 0.58f && v > 0.55f) {
                    counts.blue += weight
                } else if ((hue >= 345f || hue <= 10f) && sat > 0.64f && v > 0.55f) {
                    counts.red += weight
                }

                if (hue in 38f..58f && sat > 0.45f && v > 0.76f) {
                    counts.yellowFace += weight
                }

                if (hue in 18f..36f && sat > 0.45f && v in 0.32f..0.78f) {
                    counts.mud += weight
                }
                x += step
            }
            y += step
        }
        return counts
    }

    fun countPastelOvalInRect(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        maxSat: Float = 0.62f,
        minVal: Float = 0.76f,
        step: Int = 1
    ): PastelCounts {
        val x0 = left.coerceIn(0, bitmap.width - 1)
        val y0 = top.coerceIn(0, bitmap.height - 1)
        val x1 = right.coerceIn(x0 + 1, bitmap.width)
        val y1 = bottom.coerceIn(y0 + 1, bitmap.height)
        val w = x1 - x0
        val h = y1 - y0
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x0, y0, w, h)

        val counts = PastelCounts()
        val hsv = FloatArray(3)
        val weight = step * step

        var y = 0
        while (y < h) {
            val rowOffset = y * w
            var x = 0
            while (x < w) {
                pixelToHsv(pixels[rowOffset + x], hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val v = hsv[2]
                if (sat in 0.16f..maxSat && v >= minVal) {
                    when {
                        hue in 12f..38f -> counts.orange += weight
                        hue in 135f..185f -> counts.green += weight
                        hue in 195f..238f -> counts.blue += weight
                        hue >= 330f || hue <= 12f -> counts.red += weight
                    }
                }
                x += step
            }
            y += step
        }
        return counts
    }
}
