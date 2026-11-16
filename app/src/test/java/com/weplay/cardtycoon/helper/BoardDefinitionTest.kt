package com.weplay.cardtycoon.helper

import com.weplay.cardtycoon.helper.board.BoardDefinition
import com.weplay.cardtycoon.helper.model.TileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardDefinitionTest {

    @Test
    fun boardHasExactly28TilesWithExpectedSpecials() {
        assertEquals(28, BoardDefinition.TILES.size)
        assertEquals(TileType.GO, BoardDefinition.TILES[0].type)
        assertEquals(TileType.CHANCE, BoardDefinition.TILES[4].type)
        assertEquals(TileType.SWORDS, BoardDefinition.TILES[7].type)
        assertEquals(TileType.WALLET, BoardDefinition.TILES[11].type)
        assertEquals(TileType.UFO, BoardDefinition.TILES[14].type)
        assertEquals(TileType.MYSTERY_BAG, BoardDefinition.TILES[18].type)
        assertEquals(TileType.MULTIPLIER_WHEEL, BoardDefinition.TILES[21].type)
        assertEquals(TileType.CHANCE, BoardDefinition.TILES[24].type)
        assertEquals(20, BoardDefinition.PROPERTY_INDICES.size)
    }

    @Test
    fun forwardMoveWrapsCounterClockwiseAndDetectsGoPass() {
        val landOnGo = BoardDefinition.step(23, 5)
        assertEquals(0, landOnGo.targetIndex)
        assertTrue(landOnGo.passedGo)
        assertTrue(landOnGo.landedOnGo)

        val passGo = BoardDefinition.step(23, 7)
        assertEquals(2, passGo.targetIndex)
        assertTrue(passGo.passedGo)
        assertFalse(passGo.landedOnGo)

        val noPass = BoardDefinition.step(5, 6)
        assertEquals(11, noPass.targetIndex)
        assertFalse(noPass.passedGo)
    }

    @Test
    fun backwardMoveWrapsClockwiseWithoutTriggeringGoPass() {
        val backAcrossZero = BoardDefinition.step(2, -4)
        assertEquals(26, backAcrossZero.targetIndex)
        assertFalse(backAcrossZero.passedGo)
        assertFalse(backAcrossZero.landedOnGo)

        val backToGo = BoardDefinition.step(3, -3)
        assertEquals(0, backToGo.targetIndex)
        assertFalse(backToGo.passedGo)
        assertTrue(backToGo.landedOnGo)
    }
}
