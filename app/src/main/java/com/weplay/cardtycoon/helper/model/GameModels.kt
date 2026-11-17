package com.weplay.cardtycoon.helper.model

import android.graphics.RectF

enum class PlayerColor(
    val displayName: String,
    val shortEmoji: String,
    val argbColor: Int,
    val cornerLabel: String
) {
    ORANGE("Оранжевый", "🟠", 0xFFFF8C24.toInt(), "Верх-лево"),
    GREEN("Зелёный", "🟢", 0xFF17B86A.toInt(), "Верх-право"),
    BLUE("Синий", "🔵", 0xFF288BFF.toInt(), "Низ-лево"),
    RED("Красный", "🔴", 0xFFFF4252.toInt(), "Низ-право")
}

enum class TileType(val labelRu: String) {
    GO("GO (Старт)"),
    PROPERTY("Территория"),
    CHANCE("Карточка «?»"),
    SWORDS("Мечи (Дуэль/Союз)"),
    WALLET("Кошелёк (Кража)"),
    UFO("НЛО (Телепорт)"),
    MYSTERY_BAG("Мешок «?» (бонус/ловушка)"),
    MULTIPLIER_WHEEL("Барабан x1–x4")
}

data class TileSpec(
    val index: Int,
    val u: Int,
    val v: Int,
    val type: TileType,
    val basePrice: Int,
    val shortName: String
)

data class TileState(
    val index: Int,
    val type: TileType,
    val basePrice: Int,
    val owner: PlayerColor? = null,
    val displayedRent: Int = basePrice,
    val houseLevel: Int = 0,
    val hasMudTrap: Boolean = false,   // Какашка: подскальзывается и теряет деньги
    val hasBombTrap: Boolean = false,  // Бомба: взрыв и больница на 2 хода
    val hasIncomeDebuff: Boolean = false
) {
    val isFreeProperty: Boolean
        get() = type == TileType.PROPERTY && owner == null

    fun effectiveRent(ownerRentMultiplier: Double = 1.0): Int {
        if (type != TileType.PROPERTY || owner == null) return 0
        if (hasIncomeDebuff) {
            return if (displayedRent > 0) displayedRent else (basePrice * 0.5).toInt()
        }
        val estimated = (basePrice * (1 + houseLevel.coerceAtLeast(1) - 1).coerceAtLeast(1) * ownerRentMultiplier).toInt()
        return if (displayedRent >= basePrice) displayedRent else estimated
    }
}

data class PlayerState(
    val color: PlayerColor,
    val money: Int = 1500,
    val position: Int? = null,
    val isBankrupt: Boolean = false,
    val isLocal: Boolean = false,
    val isCurrentTurn: Boolean = false,
    val hammerProgress: Int = 0, // 0..4 segments
    val rentMultiplier: Double = 1.0
)

data class HandCard(
    val slotIndex: Int,
    val steps: Int, // Positive = forward CCW, Negative = backward CW
    val screenBox: RectF = RectF(),
    val confidence: Float = 1.0f,
    val isActive: Boolean = true
) {
    val label: String
        get() = if (steps >= 0) "+$steps" else "$steps"
}

data class BoardCalibration(
    val originX: Float,
    val originY: Float,
    val dx: Float,
    val dy: Float,
    val screenWidth: Int,
    val screenHeight: Int,
    val confidence: Float
) {
    fun tileCenter(u: Int, v: Int): Pair<Float, Float> {
        val cx = originX + dx * (v - u)
        val cy = originY - dy * (u + v)
        return cx to cy
    }

    val scale: Float
        get() = dx / 87.2f
}

data class BoardSnapshot(
    val round: Int = 1,
    val maxRounds: Int = 30,
    val tiles: List<TileState>,
    val players: Map<PlayerColor, PlayerState>,
    val localPlayer: PlayerColor,
    val handCards: List<HandCard>,
    val isMyTurn: Boolean = false,
    val calibration: BoardCalibration? = null,
    val bitmapWidth: Int = 1080,
    val bitmapHeight: Int = 2460,
    val timestampMs: Long = System.currentTimeMillis(),
    val statusNote: String = ""
) {
    val roundsRemaining: Int
        get() = (maxRounds - round).coerceAtLeast(1)

    fun player(color: PlayerColor): PlayerState =
        players[color] ?: PlayerState(color = color)

    fun aliveOpponents(): List<PlayerState> =
        players.values.filter { !it.isBankrupt && it.color != localPlayer }
}

data class StrategySettings(
    val preferredSeat: PlayerColor? = null, // null = auto-detect
    val autoScanEnabled: Boolean = true,
    val autoScanIntervalMs: Long = 1000L,
    val showCardHighlightOverlay: Boolean = true,
    val showTargetTileHighlight: Boolean = true,
    val goPassBonus: Int = 500,
    val goLandBonus: Int = 700,
    val pinkWheelWeight: Double = 1.0, // Барабан x1..x4
    val ufoWeight: Double = 1.0,
    val walletStealRatio: Double = 0.25,
    val swordsWeight: Double = 1.0,
    val chanceValue: Int = 150,
    val mysteryBagValue: Int = 40,     // Может содержать ловушки (какашка/бомба)!
    val mudPenalty: Int = 250,         // Какашка: потеря денег
    val bombPenalty: Int = 420,        // Бомба: больница на 2 хода
    val riskAversion: Double = 1.25
)

enum class RiskLevel(val labelRu: String) {
    SAFE("Безопасно"),
    MODERATE("Умеренный риск"),
    DANGEROUS("Опасно"),
    LETHAL("Угроза банкротства!")
}

data class MoveEvaluation(
    val card: HandCard,
    val targetTileIndex: Int,
    val targetTitle: String,
    val actionHeadline: String,
    val totalScore: Double,
    val immediateScore: Double,
    val lookaheadScore: Double,
    val immediateCashDelta: Int,
    val passedGo: Boolean,
    val riskLevel: RiskLevel,
    val specialAdvice: String? = null,
    val recommendedUfoTile: Int? = null,
    val bestComboSteps: List<Int> = emptyList(),
    val reasons: List<String> = emptyList()
)

data class RecommendationResult(
    val snapshot: BoardSnapshot,
    val rankedMoves: List<MoveEvaluation>,
    val summaryWarning: String? = null
) {
    val bestMove: MoveEvaluation?
        get() = rankedMoves.firstOrNull()
}
