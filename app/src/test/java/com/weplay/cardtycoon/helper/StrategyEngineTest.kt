package com.weplay.cardtycoon.helper

import com.weplay.cardtycoon.helper.board.BoardDefinition
import com.weplay.cardtycoon.helper.model.BoardSnapshot
import com.weplay.cardtycoon.helper.model.HandCard
import com.weplay.cardtycoon.helper.model.PlayerColor
import com.weplay.cardtycoon.helper.model.PlayerState
import com.weplay.cardtycoon.helper.model.RiskLevel
import com.weplay.cardtycoon.helper.model.StrategySettings
import com.weplay.cardtycoon.helper.strategy.StrategyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyEngineTest {

    private val engine = StrategyEngine()

    @Test
    fun avoidsLethalOpponentRentUsingBackwardCard() {
        val tiles = BoardDefinition.defaultEmptyTiles().toMutableList()
        // Tile 3 has Green's lethal 1200 rent; Tile 27 is empty
        tiles[3] = tiles[3].copy(owner = PlayerColor.GREEN, displayedRent = 1200, houseLevel = 1)

        val players = mapOf(
            PlayerColor.BLUE to PlayerState(PlayerColor.BLUE, money = 900, position = 1, isLocal = true),
            PlayerColor.GREEN to PlayerState(PlayerColor.GREEN, money = 2000, position = 14, rentMultiplier = 3.0)
        )
        val snapshot = BoardSnapshot(
            round = 10,
            tiles = tiles,
            players = players,
            localPlayer = PlayerColor.BLUE,
            handCards = listOf(HandCard(0, 2), HandCard(1, -2))
        )

        val result = engine.evaluate(snapshot)
        assertEquals(-2, result.bestMove!!.card.steps)
        val lethalMove = result.rankedMoves.first { it.card.steps == 2 }
        assertEquals(RiskLevel.LETHAL, lethalMove.riskLevel)
    }

    @Test
    fun ufoPicksFreePropertyOrFallsBackToCheapestWhenNoneFree() {
        // Case 1: Free high-value property available
        val tiles = BoardDefinition.defaultEmptyTiles().toMutableList()
        for (idx in BoardDefinition.PROPERTY_INDICES) {
            if (idx != 20) {
                tiles[idx] = tiles[idx].copy(owner = PlayerColor.GREEN, displayedRent = 900, houseLevel = 1)
            }
        }
        val players = mapOf(
            PlayerColor.BLUE to PlayerState(PlayerColor.BLUE, money = 1500, position = 10, isLocal = true),
            PlayerColor.GREEN to PlayerState(PlayerColor.GREEN, money = 2200, position = 16, rentMultiplier = 3.0)
        )
        val snapFree = BoardSnapshot(
            round = 8,
            tiles = tiles,
            players = players,
            localPlayer = PlayerColor.BLUE,
            handCards = listOf(HandCard(0, 4)) // 10 + 4 = 14 (UFO)
        )
        val resFree = engine.evaluate(snapFree)
        assertEquals(20, resFree.bestMove!!.recommendedUfoTile)

        // Case 2: NO free properties at all -> picks cheapest rent tile (tile 1, rent 100)
        val allOwned = tiles.toMutableList()
        allOwned[20] = allOwned[20].copy(owner = PlayerColor.GREEN, displayedRent = 1200, houseLevel = 1)
        allOwned[1] = allOwned[1].copy(owner = PlayerColor.ORANGE, displayedRent = 100, houseLevel = 1)
        val snapFull = snapFree.copy(tiles = allOwned)
        val resFull = engine.evaluate(snapFull)
        assertEquals(1, resFull.bestMove!!.recommendedUfoTile)
    }

    @Test
    fun walletTargetsRichestOpponent() {
        val tiles = BoardDefinition.defaultEmptyTiles()
        val players = mapOf(
            PlayerColor.BLUE to PlayerState(PlayerColor.BLUE, money = 1000, position = 8, isLocal = true),
            PlayerColor.ORANGE to PlayerState(PlayerColor.ORANGE, money = 600, position = 20),
            PlayerColor.RED to PlayerState(PlayerColor.RED, money = 2850, position = 19)
        )
        val snapshot = BoardSnapshot(
            round = 7,
            tiles = tiles,
            players = players,
            localPlayer = PlayerColor.BLUE,
            handCards = listOf(HandCard(0, 3)) // 8 + 3 = 11 (WALLET)
        )
        val result = engine.evaluate(snapshot)
        assertNotNull(result.bestMove!!.specialAdvice)
        assertTrue(result.bestMove!!.specialAdvice!!.contains("Красный"))
    }

    @Test
    fun swordsRecommendsAllianceAgainstDominantLeaderAndDuelWhenLeading() {
        // Dominant opponent Green owns huge 1200 rents -> should recommend ALLIANCE (50/50)
        val tilesDom = BoardDefinition.defaultEmptyTiles().toMutableList()
        for (idx in listOf(8, 9, 10, 12, 13, 15, 16)) {
            tilesDom[idx] = tilesDom[idx].copy(owner = PlayerColor.GREEN, displayedRent = 1200, houseLevel = 1)
        }
        val players = mapOf(
            PlayerColor.BLUE to PlayerState(PlayerColor.BLUE, money = 1000, position = 5, isLocal = true),
            PlayerColor.GREEN to PlayerState(PlayerColor.GREEN, money = 2000, position = 20, rentMultiplier = 3.0)
        )
        val snapAlliance = BoardSnapshot(
            round = 12,
            tiles = tilesDom,
            players = players,
            localPlayer = PlayerColor.BLUE,
            handCards = listOf(HandCard(0, 2)) // 5 + 2 = 7 (SWORDS)
        )
        val resAlliance = engine.evaluate(snapAlliance)
        assertTrue(resAlliance.bestMove!!.specialAdvice!!.contains("СОЮЗ"))

        // Now flip: Blue dominates right ahead of Green -> should recommend DUEL (x2)
        val tilesLead = BoardDefinition.defaultEmptyTiles().toMutableList()
        for (idx in listOf(22, 23, 25, 26, 27, 1, 2)) {
            tilesLead[idx] = tilesLead[idx].copy(owner = PlayerColor.BLUE, displayedRent = 900, houseLevel = 1)
        }
        val snapDuel = snapAlliance.copy(tiles = tilesLead)
        val resDuel = engine.evaluate(snapDuel)
        assertTrue(resDuel.bestMove!!.specialAdvice!!.contains("ДУЭЛЬ"))
    }

    @Test
    fun pinkWheelValueScalesUpWithOwnedPortfolio() {
        val emptyTiles = BoardDefinition.defaultEmptyTiles()
        val richTiles = emptyTiles.toMutableList()
        for (idx in listOf(3, 8, 15, 20, 22)) {
            richTiles[idx] = richTiles[idx].copy(owner = PlayerColor.BLUE, displayedRent = 800, houseLevel = 1)
        }
        val players = mapOf(
            PlayerColor.BLUE to PlayerState(PlayerColor.BLUE, money = 1400, position = 19, isLocal = true),
            PlayerColor.RED to PlayerState(PlayerColor.RED, money = 1400, position = 5)
        )
        val hand = listOf(HandCard(0, 2)) // 19 + 2 = 21 (PINK WHEEL)

        val scoreEmpty = engine.evaluate(
            BoardSnapshot(round = 10, tiles = emptyTiles, players = players, localPlayer = PlayerColor.BLUE, handCards = hand)
        ).bestMove!!.immediateScore

        val scoreRich = engine.evaluate(
            BoardSnapshot(round = 10, tiles = richTiles, players = players, localPlayer = PlayerColor.BLUE, handCards = hand)
        ).bestMove!!.immediateScore

        assertTrue("Pink wheel should be much stronger with 5 owned properties", scoreRich > scoreEmpty + 400.0)
    }

    @Test
    fun round5RealScreenshotScenarioPlansFullHandCombo() {
        val tiles = BoardDefinition.defaultEmptyTiles().toMutableList()
        fun own(idx: Int, owner: PlayerColor, rent: Int) {
            tiles[idx] = tiles[idx].copy(owner = owner, displayedRent = rent, houseLevel = 1)
        }
        own(5, PlayerColor.GREEN, 200)
        own(8, PlayerColor.BLUE, 400)
        own(9, PlayerColor.BLUE, 300)
        own(10, PlayerColor.GREEN, 200)
        tiles[12] = tiles[12].copy(hasMudTrap = true)
        own(15, PlayerColor.BLUE, 400)
        own(16, PlayerColor.GREEN, 600)
        own(17, PlayerColor.ORANGE, 300)
        own(19, PlayerColor.RED, 200)
        own(23, PlayerColor.ORANGE, 200)
        own(27, PlayerColor.GREEN, 600)

        val players = mapOf(
            PlayerColor.ORANGE to PlayerState(PlayerColor.ORANGE, money = 2415, position = 21),
            PlayerColor.GREEN to PlayerState(PlayerColor.GREEN, money = 1881, position = 16, rentMultiplier = 2.0),
            PlayerColor.BLUE to PlayerState(PlayerColor.BLUE, money = 975, position = 23, isLocal = true, hammerProgress = 3),
            PlayerColor.RED to PlayerState(PlayerColor.RED, money = 2670, position = 19)
        )
        val snap = BoardSnapshot(
            round = 5,
            tiles = tiles,
            players = players,
            localPlayer = PlayerColor.BLUE,
            handCards = listOf(HandCard(0, 5), HandCard(1, 7), HandCard(2, 7), HandCard(3, 2))
        )
        val res = engine.evaluate(snap, StrategySettings())
        val best = res.bestMove!!
        assertEquals(4, best.bestComboSteps.size)
        assertTrue("Best move should be safe and high positive EV", best.totalScore > 300.0)
        assertTrue("Best move should not be lethal", best.riskLevel != RiskLevel.LETHAL)
    }
}
