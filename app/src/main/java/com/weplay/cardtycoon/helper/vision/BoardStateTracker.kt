package com.weplay.cardtycoon.helper.vision

import com.weplay.cardtycoon.helper.board.BoardDefinition
import com.weplay.cardtycoon.helper.model.BoardSnapshot
import com.weplay.cardtycoon.helper.model.PlayerColor
import com.weplay.cardtycoon.helper.model.TileState
import com.weplay.cardtycoon.helper.model.TileType

/**
 * Temporal smoothing across consecutive captured frames.
 *
 * Prevents transient animations (floating "+100" popups, jumping pawns, card-play banners)
 * from erasing known house ownerships or OCR-read rent values, while cleanly resetting when
 * a player goes bankrupt or a new game starts.
 */
class BoardStateTracker {

    private var lastRound: Int = 0
    private val rememberedOwners = arrayOfNulls<PlayerColor>(BoardDefinition.TILE_COUNT)
    private val rememberedRents = IntArray(BoardDefinition.TILE_COUNT)
    private val rememberedMoney = HashMap<PlayerColor, Int>()
    private val rememberedPositions = HashMap<PlayerColor, Int>()
    private var lastLocalPlayer: PlayerColor = PlayerColor.BLUE

    fun reset() {
        lastRound = 0
        rememberedOwners.fill(null)
        rememberedRents.fill(0)
        rememberedMoney.clear()
        rememberedPositions.clear()
    }

    fun smooth(raw: BoardSnapshot, pawnTiles: Set<Int>): BoardSnapshot {
        // Detect new match reset (round drops sharply back to 1..2 from >= 5)
        if (lastRound >= 5 && raw.round in 1..2) {
            reset()
        }
        if (raw.round in 1..raw.maxRounds) {
            lastRound = raw.round
        }

        val bankruptSet = raw.players.values.filter { it.isBankrupt }.map { it.color }.toSet()

        // Clear remembered tiles owned by newly bankrupt players
        for (i in 0 until BoardDefinition.TILE_COUNT) {
            if (rememberedOwners[i] in bankruptSet) {
                rememberedOwners[i] = null
                rememberedRents[i] = BoardDefinition.TILES[i].basePrice
            }
        }

        // Smooth tile owners & rents
        val smoothedTiles = raw.tiles.map { tile ->
            if (tile.type != TileType.PROPERTY) {
                tile
            } else {
                val detOwner = tile.owner
                val prevOwner = rememberedOwners[tile.index]
                val finalOwner = when {
                    detOwner != null && detOwner !in bankruptSet -> {
                        rememberedOwners[tile.index] = detOwner
                        detOwner
                    }
                    // If a pawn is currently obscuring the tile, keep previously confirmed owner
                    tile.index in pawnTiles && prevOwner != null && prevOwner !in bankruptSet -> prevOwner
                    else -> {
                        rememberedOwners[tile.index] = null
                        null
                    }
                }

                val finalRent = when {
                    finalOwner == null -> tile.basePrice
                    tile.displayedRent >= tile.basePrice -> {
                        rememberedRents[tile.index] = tile.displayedRent
                        tile.displayedRent
                    }
                    rememberedRents[tile.index] >= tile.basePrice -> rememberedRents[tile.index]
                    else -> tile.basePrice
                }

                tile.copy(
                    owner = finalOwner,
                    displayedRent = finalRent,
                    houseLevel = if (finalOwner != null) tile.houseLevel.coerceAtLeast(1) else 0
                )
            }
        }

        // Smooth player money & pawn positions (handles mid-jump animations)
        val smoothedPlayers = raw.players.mapValues { (color, p) ->
            if (p.isBankrupt) {
                rememberedMoney[color] = 0
                rememberedPositions.remove(color)
                p
            } else {
                val finalMoney = if (p.money > 0) {
                    rememberedMoney[color] = p.money
                    p.money
                } else {
                    rememberedMoney[color] ?: 1500
                }
                val finalPos = if (p.position != null) {
                    rememberedPositions[color] = p.position
                    p.position
                } else {
                    rememberedPositions[color]
                }
                p.copy(money = finalMoney, position = finalPos)
            }
        }

        lastLocalPlayer = raw.localPlayer

        return raw.copy(
            round = if (raw.round in 1..raw.maxRounds) raw.round else lastRound.coerceAtLeast(1),
            tiles = smoothedTiles,
            players = smoothedPlayers
        )
    }
}
