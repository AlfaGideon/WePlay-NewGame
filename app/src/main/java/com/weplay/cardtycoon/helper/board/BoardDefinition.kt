package com.weplay.cardtycoon.helper.board

import com.weplay.cardtycoon.helper.model.TileSpec
import com.weplay.cardtycoon.helper.model.TileState
import com.weplay.cardtycoon.helper.model.TileType

/**
 * Exact 28-tile board topology of WePlay "Карточный магнат".
 * Indexed 0..27 in COUNTER-CLOCKWISE forward movement order starting at GO (index 0).
 */
object BoardDefinition {

    const val TILE_COUNT = 28

    val TILES: List<TileSpec> = listOf(
        TileSpec(0, 0, 0, TileType.GO, 0, "GO"),
        TileSpec(1, 0, 1, TileType.PROPERTY, 100, "Кл.1 (100)"),
        TileSpec(2, 0, 2, TileType.PROPERTY, 200, "Кл.2 (200)"),
        TileSpec(3, 0, 3, TileType.PROPERTY, 400, "Кл.3 (400)"),
        TileSpec(4, 0, 4, TileType.CHANCE, 0, "Карточка «?»"),
        TileSpec(5, 0, 5, TileType.PROPERTY, 100, "Кл.5 (100)"),
        TileSpec(6, 1, 5, TileType.PROPERTY, 300, "Кл.6 (300)"),
        TileSpec(7, 2, 5, TileType.SWORDS, 0, "Мечи"),
        TileSpec(8, 3, 5, TileType.PROPERTY, 400, "Кл.8 (400)"),
        TileSpec(9, 3, 6, TileType.PROPERTY, 300, "Кл.9 (300)"),
        TileSpec(10, 3, 7, TileType.PROPERTY, 100, "Кл.10 (100)"),
        TileSpec(11, 4, 7, TileType.WALLET, 0, "Кошелёк"),
        TileSpec(12, 5, 7, TileType.PROPERTY, 200, "Кл.12 (200)"),
        TileSpec(13, 6, 7, TileType.PROPERTY, 200, "Кл.13 (200)"),
        TileSpec(14, 7, 7, TileType.UFO, 0, "НЛО"),
        TileSpec(15, 7, 6, TileType.PROPERTY, 400, "Кл.15 (400)"),
        TileSpec(16, 7, 5, TileType.PROPERTY, 300, "Кл.16 (300)"),
        TileSpec(17, 7, 4, TileType.PROPERTY, 300, "Кл.17 (300)"),
        TileSpec(18, 7, 3, TileType.MYSTERY_BAG, 0, "Мешок «?»"),
        TileSpec(19, 6, 3, TileType.PROPERTY, 200, "Кл.19 (200)"),
        TileSpec(20, 5, 3, TileType.PROPERTY, 400, "Кл.20 (400)"),
        TileSpec(21, 5, 2, TileType.MULTIPLIER_WHEEL, 0, "Барабан x1–x4"),
        TileSpec(22, 5, 1, TileType.PROPERTY, 400, "Кл.22 (400)"),
        TileSpec(23, 5, 0, TileType.PROPERTY, 200, "Кл.23 (200)"),
        TileSpec(24, 4, 0, TileType.CHANCE, 0, "Карточка «?»"),
        TileSpec(25, 3, 0, TileType.PROPERTY, 100, "Кл.25 (100)"),
        TileSpec(26, 2, 0, TileType.PROPERTY, 100, "Кл.26 (100)"),
        TileSpec(27, 1, 0, TileType.PROPERTY, 300, "Кл.27 (300)")
    )

    val SPECIAL_INDICES: Set<Int> = TILES
        .filter { it.type != TileType.PROPERTY }
        .map { it.index }
        .toSet()

    val PROPERTY_INDICES: List<Int> = TILES
        .filter { it.type == TileType.PROPERTY }
        .map { it.index }

    data class StepResult(
        val fromIndex: Int,
        val steps: Int,
        val targetIndex: Int,
        val passedGo: Boolean,
        val landedOnGo: Boolean
    )

    fun step(fromIndex: Int, steps: Int): StepResult {
        val normalizedFrom = ((fromIndex % TILE_COUNT) + TILE_COUNT) % TILE_COUNT
        val raw = normalizedFrom + steps
        val target = ((raw % TILE_COUNT) + TILE_COUNT) % TILE_COUNT
        val passedGo = steps > 0 && raw >= TILE_COUNT
        val landedOnGo = target == 0
        return StepResult(
            fromIndex = normalizedFrom,
            steps = steps,
            targetIndex = target,
            passedGo = passedGo,
            landedOnGo = landedOnGo
        )
    }

    fun forwardDistance(fromIndex: Int, toIndex: Int): Int {
        return ((toIndex - fromIndex) % TILE_COUNT + TILE_COUNT) % TILE_COUNT
    }

    fun backwardDistance(fromIndex: Int, toIndex: Int): Int {
        return ((fromIndex - toIndex) % TILE_COUNT + TILE_COUNT) % TILE_COUNT
    }

    fun defaultEmptyTiles(): List<TileState> = TILES.map { spec ->
        TileState(
            index = spec.index,
            type = spec.type,
            basePrice = spec.basePrice,
            owner = null,
            displayedRent = spec.basePrice,
            houseLevel = 0
        )
    }
}
