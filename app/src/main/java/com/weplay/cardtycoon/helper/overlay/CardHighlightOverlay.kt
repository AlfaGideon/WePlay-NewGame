package com.weplay.cardtycoon.helper.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.weplay.cardtycoon.helper.board.BoardDefinition
import com.weplay.cardtycoon.helper.model.RecommendationResult
import com.weplay.cardtycoon.helper.model.RiskLevel
import com.weplay.cardtycoon.helper.model.StrategySettings

/**
 * Full-screen 100% pass-through transparent Canvas overlay that highlights:
 *  1) The best recommended card among the 4 hand cards at the bottom of the screen,
 *  2) Lethal / bankruptcy cards with a clear red warning border,
 *  3) The target landing tile (and UFO teleport target tile) directly on the board!
 */
class CardHighlightOverlay(context: Context) : View(context) {

    private var recommendation: RecommendationResult? = null
    private var settings: StrategySettings = StrategySettings()

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
        color = Color.argb(235, 0, 184, 92)
    }

    private val badgeBgDanger = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(225, 211, 47, 47)
    }

    private val badgeBgUfo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 124, 77, 255)
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
        color = Color.argb(235, 0, 230, 118)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rec = recommendation ?: return
        val best = rec.bestMove ?: return

        // 1. Highlight recommended card & lethal cards at the bottom
        if (settings.showCardHighlightOverlay) {
            for ((rank, move) in rec.rankedMoves.withIndex()) {
                val box = move.card.screenBox
                if (box.width() < 20f || box.height() < 20f) continue

                if (rank == 0) {
                    val expand = RectF(box.left - 8f, box.top - 8f, box.right + 8f, box.bottom + 8f)
                    canvas.drawRoundRect(expand, 24f, 24f, bestGlowPaint)
                    canvas.drawRoundRect(expand, 24f, 24f, bestBorderPaint)

                    // Draw "★ ЛУЧШИЙ" pill right above the recommended card
                    val pillW = box.width().coerceAtLeast(150f)
                    val pillH = 44f
                    val cx = box.centerX()
                    val pillRect = RectF(cx - pillW / 2f, box.top - pillH - 12f, cx + pillW / 2f, box.top - 12f)
                    canvas.drawRoundRect(pillRect, 22f, 22f, badgeBgBest)
                    canvas.drawText("★ ЛУЧШИЙ", cx, pillRect.centerY() + 10f, badgeTextPaint)
                } else if (move.riskLevel == RiskLevel.LETHAL) {
                    val expand = RectF(box.left - 4f, box.top - 4f, box.right + 4f, box.bottom + 4f)
                    canvas.drawRoundRect(expand, 22f, 22f, dangerBorderPaint)
                    val cx = box.centerX()
                    val pillRect = RectF(cx - 72f, box.top - 44f, cx + 72f, box.top - 8f)
                    canvas.drawRoundRect(pillRect, 18f, 18f, badgeBgDanger)
                    canvas.drawText("⚠ РИСК", cx, pillRect.centerY() + 9f, badgeTextPaint)
                }
            }
        }

        // 2. Highlight target tile on the board (and UFO teleport target if applicable!)
        val cal = rec.snapshot.calibration
        if (settings.showTargetTileHighlight && cal != null && cal.confidence >= 0.7f) {
            val targetSpec = BoardDefinition.TILES[best.targetTileIndex]
            val (tx, ty) = cal.tileCenter(targetSpec.u, targetSpec.v)
            val radius = (46f * cal.scale).coerceIn(24f, 80f)
            canvas.drawCircle(tx, ty, radius, tileRingPaint)

            val labelRect = RectF(tx - 64f, ty - radius - 42f, tx + 64f, ty - radius - 6f)
            canvas.drawRoundRect(labelRect, 16f, 16f, badgeBgBest)
            canvas.drawText("ХОД ${best.card.label}", tx, labelRect.centerY() + 9f, badgeTextPaint)

            // If landing on UFO, also draw dashed arrow & purple target circle on recommended destination tile!
            val ufoIdx = best.recommendedUfoTile
            if (ufoIdx != null && ufoIdx != best.targetTileIndex) {
                val destSpec = BoardDefinition.TILES[ufoIdx]
                val (dx, dy) = cal.tileCenter(destSpec.u, destSpec.v)
                canvas.drawLine(tx, ty, dx, dy, ufoLinePaint)
                canvas.drawCircle(dx, dy, radius, ufoRingPaint)

                val ufoPill = RectF(dx - 76f, dy - radius - 42f, dx + 76f, dy - radius - 6f)
                canvas.drawRoundRect(ufoPill, 16f, 16f, badgeBgUfo)
                canvas.drawText("НЛО → #${ufoIdx}", dx, ufoPill.centerY() + 9f, badgeTextPaint)
            }
        }
    }
}
