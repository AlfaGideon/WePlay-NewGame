package com.weplay.cardtycoon.helper.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import com.weplay.cardtycoon.helper.board.BoardDefinition
import com.weplay.cardtycoon.helper.model.RecommendationResult
import com.weplay.cardtycoon.helper.model.RiskLevel
import com.weplay.cardtycoon.helper.model.StrategySettings

/**
 * Full-screen 100% pass-through transparent Canvas overlay.
 *
 * Maps MediaProjection bitmap coordinates precisely into overlay Canvas coordinates using
 * real screen dimensions + getLocationOnScreen() offset so highlights align pixel-for-pixel
 * even on devices with camera cutouts, status bars, or navigation bars.
 */
class CardHighlightOverlay(context: Context) : View(context) {

    private var recommendation: RecommendationResult? = null
    private var settings: StrategySettings = StrategySettings()
    private val viewLoc = IntArray(2)

    private val bestGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        color = Color.argb(120, 0, 230, 118)
    }

    private val bestBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = Color.rgb(0, 230, 118)
    }

    private val dangerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.argb(215, 255, 68, 88)
    }

    private val badgeBgBest = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(238, 0, 184, 92)
    }

    private val badgeBgDanger = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 211, 47, 47)
    }

    private val badgeBgUfo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(232, 124, 77, 255)
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val tileRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.argb(238, 0, 230, 118)
    }

    private val ufoRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = Color.argb(235, 179, 136, 255)
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }

    private val ufoLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.argb(200, 179, 136, 255)
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }

    fun update(result: RecommendationResult?, currentSettings: StrategySettings) {
        recommendation = result
        settings = currentSettings
        invalidate()
    }

    private fun readRealScreenSize(): Pair<Float, Float> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width().toFloat().coerceAtLeast(1f) to b.height().toFloat().coerceAtLeast(1f)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.toFloat().coerceAtLeast(1f) to dm.heightPixels.toFloat().coerceAtLeast(1f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rec = recommendation ?: return
        val best = rec.bestMove ?: return
        val snap = rec.snapshot

        // Exact bitmap-pixel -> overlay-view coordinate mapping (eliminates status-bar/cutout offset!)
        getLocationOnScreen(viewLoc)
        val (realW, realH) = readRealScreenSize()
        val scaleX = realW / snap.bitmapWidth.coerceAtLeast(1).toFloat()
        val scaleY = realH / snap.bitmapHeight.coerceAtLeast(1).toFloat()
        val offX = viewLoc[0].toFloat()
        val offY = viewLoc[1].toFloat()

        fun mapX(bx: Float): Float = bx * scaleX - offX
        fun mapY(by: Float): Float = by * scaleY - offY
        fun mapRect(r: RectF): RectF = RectF(mapX(r.left), mapY(r.top), mapX(r.right), mapY(r.bottom))

        // 1. Highlight ONLY actual cards in hand: best card + lethal cards
        if (settings.showCardHighlightOverlay) {
            for ((rank, move) in rec.rankedMoves.withIndex()) {
                val rawBox = move.card.screenBox
                if (rawBox.width() < 20f || rawBox.height() < 20f) continue
                val box = mapRect(rawBox)

                if (rank == 0) {
                    val expand = RectF(box.left - 8f, box.top - 8f, box.right + 8f, box.bottom + 8f)
                    canvas.drawRoundRect(expand, 24f, 24f, bestGlowPaint)
                    canvas.drawRoundRect(expand, 24f, 24f, bestBorderPaint)

                    val pillW = box.width().coerceAtLeast(156f)
                    val pillH = 44f
                    val cx = box.centerX()
                    val pillRect = RectF(cx - pillW / 2f, box.top - pillH - 10f, cx + pillW / 2f, box.top - 10f)
                    canvas.drawRoundRect(pillRect, 22f, 22f, badgeBgBest)
                    canvas.drawText("★ ${move.card.label} ЛУЧШИЙ", cx, pillRect.centerY() + 10f, badgeTextPaint)
                } else if (move.riskLevel == RiskLevel.LETHAL) {
                    val expand = RectF(box.left - 4f, box.top - 4f, box.right + 4f, box.bottom + 4f)
                    canvas.drawRoundRect(expand, 22f, 22f, dangerBorderPaint)
                    val cx = box.centerX()
                    val pillRect = RectF(cx - 72f, box.top - 42f, cx + 72f, box.top - 6f)
                    canvas.drawRoundRect(pillRect, 18f, 18f, badgeBgDanger)
                    canvas.drawText("⚠ РИСК", cx, pillRect.centerY() + 9f, badgeTextPaint)
                }
            }
        }

        // 2. Highlight target tile on the board for the exact same best.card
        val cal = snap.calibration
        if (settings.showTargetTileHighlight && cal != null && cal.confidence >= 0.7f) {
            val targetSpec = BoardDefinition.TILES[best.targetTileIndex]
            val (rawTx, rawTy) = cal.tileCenter(targetSpec.u, targetSpec.v)
            val tx = mapX(rawTx)
            val ty = mapY(rawTy)
            val radius = (44f * cal.scale * scaleX).coerceIn(24f, 78f)
            canvas.drawCircle(tx, ty, radius, tileRingPaint)

            val labelRect = RectF(tx - 68f, ty - radius - 42f, tx + 68f, ty - radius - 6f)
            canvas.drawRoundRect(labelRect, 16f, 16f, badgeBgBest)
            canvas.drawText("ХОД ${best.card.label}", tx, labelRect.centerY() + 9f, badgeTextPaint)

            val ufoIdx = best.recommendedUfoTile
            if (ufoIdx != null && ufoIdx != best.targetTileIndex) {
                val destSpec = BoardDefinition.TILES[ufoIdx]
                val (rawDx, rawDy) = cal.tileCenter(destSpec.u, destSpec.v)
                val dx = mapX(rawDx)
                val dy = mapY(rawDy)
                canvas.drawLine(tx, ty, dx, dy, ufoLinePaint)
                canvas.drawCircle(dx, dy, radius, ufoRingPaint)

                val ufoPill = RectF(dx - 76f, dy - radius - 42f, dx + 76f, dy - radius - 6f)
                canvas.drawRoundRect(ufoPill, 16f, 16f, badgeBgUfo)
                canvas.drawText("НЛО → #${ufoIdx}", dx, ufoPill.centerY() + 9f, badgeTextPaint)
            }
        }
    }
}
