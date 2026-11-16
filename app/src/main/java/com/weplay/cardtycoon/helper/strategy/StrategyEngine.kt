package com.weplay.cardtycoon.helper.strategy

import com.weplay.cardtycoon.helper.board.BoardDefinition
import com.weplay.cardtycoon.helper.model.BoardSnapshot
import com.weplay.cardtycoon.helper.model.HandCard
import com.weplay.cardtycoon.helper.model.MoveEvaluation
import com.weplay.cardtycoon.helper.model.PlayerColor
import com.weplay.cardtycoon.helper.model.PlayerState
import com.weplay.cardtycoon.helper.model.RecommendationResult
import com.weplay.cardtycoon.helper.model.RiskLevel
import com.weplay.cardtycoon.helper.model.StrategySettings
import com.weplay.cardtycoon.helper.model.TileState
import com.weplay.cardtycoon.helper.model.TileType
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-turn game-theory advisor for WePlay "Карточный магнат".
 *
 * Evaluates every card in the current hand using:
 *  - Exact lookahead across all remaining card permutations in hand (up to 4! = 24),
 *  - Full rule modeling (CCW movement, GO bonus, 4-segment hammer rent scaling,
 *    UFO free-tile teleportation, Wallet target selection, Swords Duel 2x vs Alliance 50/50,
 *    Pink Wheel x1..x10 income multiplier, Chance sabotage, mud traps),
 *  - Opponent position & landing threat projection,
 *  - Dynamic cash safety buffer and bankruptcy avoidance.
 */
class StrategyEngine {

    companion object {
        private const val LOOKAHEAD_DISCOUNT = 0.72
        private const val BANKRUPTCY_PENALTY = 12_000.0

        // Empirical step probabilities for opponents when their hand is hidden
        private val FORWARD_STEP_PROBS = mapOf(
            1 to 0.08, 2 to 0.11, 3 to 0.12, 4 to 0.12,
            5 to 0.11, 6 to 0.10, 7 to 0.08, 8 to 0.06
        )
        private val BACKWARD_STEP_PROBS = mapOf(
            1 to 0.05, 2 to 0.05, 3 to 0.04, 4 to 0.03,
            5 to 0.03, 6 to 0.02
        )
    }

    private data class SimState(
        val position: Int,
        val cash: Int,
        val hammerProgress: Int,
        val boughtTiles: Set<Int>,
        val upgradedTiles: Map<Int, Int>,
        val ownRentMultiplier: Double
    )

    private data class SingleStepEval(
        val score: Double,
        val cashDelta: Int,
        val nextState: SimState,
        val passedGo: Boolean,
        val riskLevel: RiskLevel,
        val actionHeadline: String,
        val specialAdvice: String?,
        val recommendedUfoTile: Int?,
        val reasons: List<String>
    )

    fun evaluate(
        snapshot: BoardSnapshot,
        settings: StrategySettings = StrategySettings()
    ): RecommendationResult {
        val me = snapshot.player(snapshot.localPlayer)
        if (me.isBankrupt) {
            return RecommendationResult(
                snapshot = snapshot,
                rankedMoves = emptyList(),
                summaryWarning = "Игрок обанкротился — ходы недоступны."
            )
        }
        val myPos = me.position
        if (myPos == null) {
            return RecommendationResult(
                snapshot = snapshot,
                rankedMoves = emptyList(),
                summaryWarning = "Фишка игрока (${me.color.displayName}) не найдена на поле."
            )
        }
        if (snapshot.handCards.isEmpty()) {
            return RecommendationResult(
                snapshot = snapshot,
                rankedMoves = emptyList(),
                summaryWarning = "Карты в руке не обнаружены. Можно ввести вручную."
            )
        }

        val initialState = SimState(
            position = myPos,
            cash = me.money,
            hammerProgress = me.hammerProgress.coerceIn(0, 3),
            boughtTiles = emptySet(),
            upgradedTiles = emptyMap(),
            ownRentMultiplier = estimatePlayerRentMultiplier(snapshot, me.color)
        )

        val dangerBuffer = computeSafeCashBuffer(snapshot, me.color)
        val oppLandingHeat = computeOpponentLandingHeat(snapshot)

        val evaluations = snapshot.handCards.mapIndexed { idx, card ->
            val firstStep = evaluateSingleMove(
                snapshot = snapshot,
                state = initialState,
                steps = card.steps,
                settings = settings,
                dangerBuffer = dangerBuffer,
                oppHeat = oppLandingHeat,
                collectReasons = true
            )

            val remaining = snapshot.handCards.filterIndexed { otherIdx, _ -> otherIdx != idx }
            val (futureScore, bestTailSteps) = if (remaining.isNotEmpty() && firstStep.riskLevel != RiskLevel.LETHAL) {
                solveBestContinuation(
                    snapshot = snapshot,
                    state = firstStep.nextState,
                    remainingCards = remaining,
                    settings = settings,
                    dangerBuffer = dangerBuffer,
                    oppHeat = oppLandingHeat,
                    depth = 1
                )
            } else {
                0.0 to emptyList()
            }

            // Also consider future danger zone after playing this card (what awaits in 1..8 steps ahead)
            val sectorSafetyAdjust = evaluateSectorAhead(
                snapshot = snapshot,
                fromTile = firstStep.nextState.position,
                myColor = me.color,
                myCash = firstStep.nextState.cash,
                settings = settings
            )

            val total = firstStep.score + LOOKAHEAD_DISCOUNT * futureScore + sectorSafetyAdjust
            val targetSpec = BoardDefinition.TILES[BoardDefinition.step(myPos, card.steps).targetIndex]
            val combo = listOf(card.steps) + bestTailSteps

            val finalReasons = firstStep.reasons.toMutableList()
            if (bestTailSteps.isNotEmpty()) {
                val comboStr = combo.joinToString(" → ") { if (it >= 0) "+$it" else "$it" }
                finalReasons.add("План руки: $comboStr")
            }

            MoveEvaluation(
                card = card,
                targetTileIndex = targetSpec.index,
                targetTitle = targetSpec.shortName,
                actionHeadline = firstStep.actionHeadline,
                totalScore = total,
                immediateScore = firstStep.score,
                lookaheadScore = LOOKAHEAD_DISCOUNT * futureScore,
                immediateCashDelta = firstStep.cashDelta,
                passedGo = firstStep.passedGo,
                riskLevel = firstStep.riskLevel,
                specialAdvice = firstStep.specialAdvice,
                recommendedUfoTile = firstStep.recommendedUfoTile,
                bestComboSteps = combo,
                reasons = finalReasons
            )
        }.sortedByDescending { it.totalScore }

        val topWarning = when {
            evaluations.all { it.riskLevel == RiskLevel.LETHAL } ->
                "Внимание: все карты ведут к критическому штрафу — выбирайте наименьший убыток!"
            me.money < dangerBuffer ->
                "Низкий запас денег (${me.money}): приоритет — выживание и проход GO."
            else -> null
        }

        return RecommendationResult(
            snapshot = snapshot,
            rankedMoves = evaluations,
            summaryWarning = topWarning
        )
    }

    /**
     * Exact recursion over remaining cards in the current hand (max 3! = 6 branches per first card).
     * Memoizes equivalent card step values at the same level so duplicate cards (e.g. [7, 7]) are fast.
     */
    private fun solveBestContinuation(
        snapshot: BoardSnapshot,
        state: SimState,
        remainingCards: List<HandCard>,
        settings: StrategySettings,
        dangerBuffer: Int,
        oppHeat: DoubleArray,
        depth: Int
    ): Pair<Double, List<Int>> {
        if (remainingCards.isEmpty() || depth > 3) return 0.0 to emptyList()

        var bestScore = Double.NEGATIVE_INFINITY
        var bestSeq: List<Int> = emptyList()
        val seenSteps = HashSet<Int>()

        for (i in remainingCards.indices) {
            val steps = remainingCards[i].steps
            if (!seenSteps.add(steps)) continue

            val stepEval = evaluateSingleMove(
                snapshot = snapshot,
                state = state,
                steps = steps,
                settings = settings,
                dangerBuffer = dangerBuffer,
                oppHeat = oppHeat,
                collectReasons = false
            )

            val tail = if (remainingCards.size > 1 && stepEval.riskLevel != RiskLevel.LETHAL) {
                val nextRemaining = remainingCards.filterIndexed { idx, _ -> idx != i }
                solveBestContinuation(
                    snapshot = snapshot,
                    state = stepEval.nextState,
                    remainingCards = nextRemaining,
                    settings = settings,
                    dangerBuffer = dangerBuffer,
                    oppHeat = oppHeat,
                    depth = depth + 1
                )
            } else {
                0.0 to emptyList()
            }

            val total = stepEval.score + LOOKAHEAD_DISCOUNT * tail.first
            if (total > bestScore) {
                bestScore = total
                bestSeq = listOf(steps) + tail.second
            }
        }
        return bestScore to bestSeq
    }

    private fun evaluateSingleMove(
        snapshot: BoardSnapshot,
        state: SimState,
        steps: Int,
        settings: StrategySettings,
        dangerBuffer: Int,
        oppHeat: DoubleArray,
        collectReasons: Boolean
    ): SingleStepEval {
        val myColor = snapshot.localPlayer
        val stepResult = BoardDefinition.step(state.position, steps)
        val tile = snapshot.tiles[stepResult.targetIndex]
        val reasons = if (collectReasons) mutableListOf<String>() else mutableListOf()

        var cash = state.cash
        var cashDelta = 0
        var score = 0.0

        // 1. Passing GO bonus (forward moves only; landing directly on GO is handled in TileType.GO)
        if (stepResult.passedGo && !stepResult.landedOnGo) {
            cash += settings.goPassBonus
            cashDelta += settings.goPassBonus
            score += settings.goPassBonus.toDouble()
            if (collectReasons) reasons.add("Проход GO: +${settings.goPassBonus}")
        }

        // 2. Mud trap penalty (if tile has brown mud/poop obstacle)
        if (tile.hasMudTrap) {
            score -= settings.mudPenalty
            if (collectReasons) reasons.add("Ловушка (грязь) на клетке: -${settings.mudPenalty}")
        }

        // Resolve effective ownership including tiles already bought earlier in our lookahead combo
        val effectiveOwner: PlayerColor? = when {
            tile.index in state.boughtTiles -> myColor
            else -> tile.owner
        }

        var nextPos = tile.index
        var nextHammer = state.hammerProgress
        var nextBought = state.boughtTiles
        var nextUpgraded = state.upgradedTiles
        var nextMult = state.ownRentMultiplier
        var riskLevel = RiskLevel.SAFE
        var actionHeadline: String
        var specialAdvice: String? = null
        var ufoTarget: Int? = null

        val roundsLeft = snapshot.roundsRemaining
        val lateGameFactor = (roundsLeft.toDouble() / 16.0).coerceIn(0.18, 1.35)
        val myPortfolioBase = computeMyPortfolioBase(snapshot, myColor, state.boughtTiles)

        when (tile.type) {
            TileType.GO -> {
                cash += settings.goLandBonus
                cashDelta += settings.goLandBonus
                score += settings.goLandBonus.toDouble() + 80.0 // safe square bonus
                actionHeadline = "Старт GO (+${settings.goLandBonus})"
                if (collectReasons) reasons.add("Попадание на GO: +${settings.goLandBonus}, безопасно")
            }

            TileType.PROPERTY -> {
                when (effectiveOwner) {
                    null -> {
                        // FREE PROPERTY: compare Buy vs Skip
                        val buyCost = tile.basePrice
                        val canAfford = cash >= buyCost
                        val postBuyCash = cash - buyCost

                        // Expected future income from owning this tile
                        val hitFactor = 1.0 + oppHeat[tile.index] * 2.4
                        val expectedRentIncome =
                            buyCost * state.ownRentMultiplier * 0.42 * lateGameFactor * hitFactor

                        // Hammer 4-segment upgrade synergy: every 4 purchases boosts ALL territories!
                        val newHammer = state.hammerProgress + 1
                        val hammerBonus = computeHammerSynergyBonus(
                            newHammer = newHammer,
                            portfolioBase = myPortfolioBase + buyCost,
                            currentMult = state.ownRentMultiplier,
                            roundsLeft = roundsLeft
                        )

                        // Cash safety penalty if buying leaves us dangerously low on cash
                        val buyCashPenalty = computeLiquidityPenalty(
                            cashAfter = postBuyCash,
                            dangerBuffer = dangerBuffer,
                            riskAversion = settings.riskAversion
                        )

                        val buyNetValue = expectedRentIncome + hammerBonus - buyCost * 0.62 - buyCashPenalty
                        val skipValue = -15.0 // slight opportunity cost of empty step

                        if (canAfford && buyNetValue > skipValue && postBuyCash > 0) {
                            cash = postBuyCash
                            cashDelta -= buyCost
                            score += buyNetValue
                            nextBought = state.boughtTiles + tile.index
                            if (newHammer >= 4) {
                                nextHammer = 0
                                nextMult = state.ownRentMultiplier + 1.0
                            } else {
                                nextHammer = newHammer
                            }
                            riskLevel = classifyCashRisk(postBuyCash, dangerBuffer)
                            actionHeadline = if (newHammer >= 4) {
                                "Купить за $buyCost (МОЛОТОК 4/4!)"
                            } else {
                                "Купить за $buyCost"
                            }
                            if (collectReasons) {
                                reasons.add("Свободная кл.#${tile.index} ($buyCost): выгодная покупка")
                                if (newHammer >= 4) {
                                    reasons.add("Заполняет молоток 4/4 — рост дохода со всех территорий!")
                                } else {
                                    reasons.add("Прогресс молотка: $newHammer/4")
                                }
                                if (oppHeat[tile.index] > 0.22) {
                                    reasons.add("Соперники рядом сзади — высокий шанс аренды!")
                                }
                            }
                        } else {
                            score += skipValue
                            riskLevel = classifyCashRisk(cash, dangerBuffer)
                            actionHeadline = if (!canAfford) {
                                "Свободна ($buyCost — не хватает)"
                            } else {
                                "Не покупать (беречь кеш)"
                            }
                            if (collectReasons) {
                                reasons.add(
                                    if (!canAfford) "Недостаточно денег на покупку ($buyCost)"
                                    else "Безопаснее пропустить покупку и сохранить запас денег"
                                )
                            }
                        }
                    }

                    myColor -> {
                        // OWN PROPERTY: zero rent + optional upgrade
                        val upgradeCost = tile.basePrice
                        val curLv = (tile.houseLevel.coerceAtLeast(1)) + (state.upgradedTiles[tile.index] ?: 0)
                        val canUpgrade = curLv < 3 && cash - upgradeCost >= (dangerBuffer * 0.75).toInt()
                        val hitFactor = 1.0 + oppHeat[tile.index] * 2.5
                        val upgradeRoi = upgradeCost * 0.45 * lateGameFactor * hitFactor - upgradeCost * 0.65

                        if (canUpgrade && upgradeRoi > 40.0) {
                            cash -= upgradeCost
                            cashDelta -= upgradeCost
                            score += 90.0 + upgradeRoi // safe square + upgrade ROI
                            nextUpgraded = state.upgradedTiles + (tile.index to ((state.upgradedTiles[tile.index] ?: 0) + 1))
                            actionHeadline = "Своя клетка — улучшить ($upgradeCost)"
                            if (collectReasons) {
                                reasons.add("Своя территория (аренда 0), выгодный апгрейд за $upgradeCost")
                            }
                        } else {
                            score += 90.0
                            actionHeadline = "Своя клетка (безопасно)"
                            if (collectReasons) {
                                reasons.add("Своя территория — безопасно, без штрафа")
                            }
                        }
                        riskLevel = classifyCashRisk(cash, dangerBuffer)
                    }

                    else -> {
                        // OPPONENT PROPERTY: must pay rent!
                        val oppMult = estimatePlayerRentMultiplier(snapshot, effectiveOwner)
                        val rent = tile.effectiveRent(oppMult)
                        cash -= rent
                        cashDelta -= rent

                        // Paying rent hurts twice: you lose rent AND opponent gains rent!
                        val oppLeaderWeight = if (isOpponentLeading(snapshot, effectiveOwner)) 1.35 else 1.15
                        var rentScore = -rent * oppLeaderWeight

                        if (cash <= 0) {
                            rentScore -= BANKRUPTCY_PENALTY
                            riskLevel = RiskLevel.LETHAL
                        } else {
                            val liqPen = computeLiquidityPenalty(cash, dangerBuffer, settings.riskAversion)
                            rentScore -= liqPen
                            riskLevel = when {
                                rent >= 600 || cash < dangerBuffer * 0.5 -> RiskLevel.DANGEROUS
                                rent >= 300 -> RiskLevel.MODERATE
                                else -> RiskLevel.MODERATE
                            }
                        }
                        score += rentScore
                        actionHeadline = if (riskLevel == RiskLevel.LETHAL) {
                            "БАНКРОТСТВО! Штраф -$rent"
                        } else {
                            "Чужая (${effectiveOwner.shortEmoji}) штраф -$rent"
                        }
                        if (collectReasons) {
                            reasons.add("Аренда ${effectiveOwner.displayName}: -$rent (останется $cash)")
                            if (riskLevel == RiskLevel.LETHAL) {
                                reasons.add("КРИТИЧНО: ведёт к банкротству!")
                            }
                        }
                    }
                }
            }

            TileType.UFO -> {
                // UFO: teleport to ANY free tile, or cheapest tile if no free tiles remain!
                val ufoDecision = chooseBestUfoDestination(
                    snapshot = snapshot,
                    state = state.copy(cash = cash),
                    settings = settings,
                    dangerBuffer = dangerBuffer,
                    oppHeat = oppHeat
                )
                ufoTarget = ufoDecision.targetIndex
                nextPos = ufoDecision.targetIndex
                cash = ufoDecision.nextState.cash
                cashDelta += ufoDecision.cashDelta
                nextHammer = ufoDecision.nextState.hammerProgress
                nextBought = ufoDecision.nextState.boughtTiles
                nextMult = ufoDecision.nextState.ownRentMultiplier
                riskLevel = ufoDecision.riskLevel

                score += ufoDecision.score * settings.ufoWeight
                actionHeadline = "НЛО → кл.#${ufoDecision.targetIndex} (${ufoDecision.shortAction})"
                specialAdvice = "НЛО: телепорт на кл.#${ufoDecision.targetIndex} (${ufoDecision.shortAction})"
                if (collectReasons) {
                    reasons.add("НЛО — выбор лучшей свободной/дешёвой клетки")
                    reasons.add("Цель телепорта: кл.#${ufoDecision.targetIndex} — ${ufoDecision.shortAction}")
                }
            }

            TileType.WALLET -> {
                // WALLET: choose an opponent to steal cash from!
                val richestOpp = snapshot.aliveOpponents().maxByOrNull { it.money }
                if (richestOpp != null && richestOpp.money > 0) {
                    val stolen = (richestOpp.money * settings.walletStealRatio).toInt().coerceAtLeast(80)
                    cash += stolen
                    cashDelta += stolen
                    // You gain `stolen` AND rival loses `stolen`
                    score += stolen * 1.45
                    actionHeadline = "Кошелёк → красть у ${richestOpp.color.shortEmoji} (~+$stolen)"
                    specialAdvice = "Кошелёк: выбирай ${richestOpp.color.displayName} (${richestOpp.money})"
                    if (collectReasons) {
                        reasons.add("Кража у ${richestOpp.color.displayName} (${richestOpp.money}): ~+$stolen и ослабление лидера")
                    }
                } else {
                    score += 120.0
                    actionHeadline = "Кошелёк (кража)"
                }
                riskLevel = classifyCashRisk(cash, dangerBuffer)
            }

            TileType.SWORDS -> {
                // SWORDS: choose an opponent for Duel (2x mutual rent) OR Alliance (50/50 profit & loss)!
                val swordsPlan = chooseBestSwordsTarget(snapshot, myColor, state.boughtTiles, settings)
                score += swordsPlan.score * settings.swordsWeight
                actionHeadline = swordsPlan.headline
                specialAdvice = swordsPlan.advice
                if (collectReasons) {
                    reasons.add(swordsPlan.reason)
                }
                riskLevel = classifyCashRisk(cash, dangerBuffer)
            }

            TileType.MULTIPLIER_WHEEL -> {
                // PINK WHEEL: spin x1..x10 multiplier to income!
                // Scales dramatically with how much property rent power you already own.
                val myTotalRent = computeMyTotalRentPower(snapshot, myColor, state.boughtTiles, state.ownRentMultiplier)
                val baseWheelEv = 130.0 + myTotalRent * 0.28 * lateGameFactor.coerceAtMost(1.1)
                val wheelScore = baseWheelEv * settings.pinkWheelWeight
                score += wheelScore
                actionHeadline = if (myTotalRent >= 800) {
                    "Барабан x1–x10 (ТОП при ваших домах!)"
                } else {
                    "Барабан x1–x10 (множитель дохода)"
                }
                specialAdvice = "Розовый барабан: множитель x1–x10 к доходу (текущая база аренды: $myTotalRent)"
                if (collectReasons) {
                    reasons.add("Барабан x1–x10: буст дохода, особенно силён с ${countMyProperties(snapshot, myColor, state.boughtTiles)} терр.")
                }
                riskLevel = classifyCashRisk(cash, dangerBuffer)
            }

            TileType.CHANCE -> {
                // YELLOW '?': random sabotage event (jail opponent, block rent, turn tile to mud, etc.)
                val leaderRent = snapshot.aliveOpponents()
                    .maxOfOrNull { computeOpponentTotalRent(snapshot, it.color) } ?: 0
                val sabotageBonus = min(leaderRent * 0.08, 180.0)
                val chanceScore = settings.chanceValue + sabotageBonus
                score += chanceScore
                actionHeadline = "Карточка «?» (подстава/событие)"
                val topThreatOpp = snapshot.aliveOpponents()
                    .maxByOrNull { computeOpponentTotalRent(snapshot, it.color) }
                if (topThreatOpp != null && leaderRent > 0) {
                    specialAdvice = "Если выпадет подстава — бей по ${topThreatOpp.color.displayName}"
                }
                if (collectReasons) {
                    reasons.add("Случайное событие/подстава (тюрьма, блок дохода, грязь)")
                }
                riskLevel = classifyCashRisk(cash, dangerBuffer)
            }

            TileType.MYSTERY_BAG -> {
                val bagScore = settings.mysteryBagValue.toDouble()
                score += bagScore
                actionHeadline = "Мешок «?» (бонус)"
                if (collectReasons) {
                    reasons.add("Фиолетовый мешок: безопасная клетка с бонусом")
                }
                riskLevel = classifyCashRisk(cash, dangerBuffer)
            }
        }

        val updatedState = SimState(
            position = nextPos,
            cash = cash,
            hammerProgress = nextHammer,
            boughtTiles = nextBought,
            upgradedTiles = nextUpgraded,
            ownRentMultiplier = nextMult
        )

        return SingleStepEval(
            score = score,
            cashDelta = cashDelta,
            nextState = updatedState,
            passedGo = stepResult.passedGo,
            riskLevel = riskLevel,
            actionHeadline = actionHeadline,
            specialAdvice = specialAdvice,
            recommendedUfoTile = ufoTarget,
            reasons = reasons
        )
    }

    private data class UfoDecision(
        val targetIndex: Int,
        val score: Double,
        val cashDelta: Int,
        val nextState: SimState,
        val riskLevel: RiskLevel,
        val shortAction: String
    )

    private fun chooseBestUfoDestination(
        snapshot: BoardSnapshot,
        state: SimState,
        settings: StrategySettings,
        dangerBuffer: Int,
        oppHeat: DoubleArray
    ): UfoDecision {
        val myColor = snapshot.localPlayer
        val roundsLeft = snapshot.roundsRemaining
        val lateGameFactor = (roundsLeft.toDouble() / 16.0).coerceIn(0.18, 1.35)
        val myPortfolioBase = computeMyPortfolioBase(snapshot, myColor, state.boughtTiles)

        val freeProperties = snapshot.tiles.filter {
            it.type == TileType.PROPERTY && it.owner == null && it.index !in state.boughtTiles && !it.hasMudTrap
        }

        if (freeProperties.isNotEmpty()) {
            var best: UfoDecision? = null
            for (tile in freeProperties) {
                val cost = tile.basePrice
                val canBuy = state.cash >= cost && (state.cash - cost) > 0
                val hitFactor = 1.0 + oppHeat[tile.index] * 2.5
                val sectorAfter = evaluateSectorAhead(snapshot, tile.index, myColor, state.cash - if (canBuy) cost else 0, settings)

                val decision = if (canBuy) {
                    val newHammer = state.hammerProgress + 1
                    val hammerBonus = computeHammerSynergyBonus(
                        newHammer, myPortfolioBase + cost, state.ownRentMultiplier, roundsLeft
                    )
                    val liqPen = computeLiquidityPenalty(state.cash - cost, dangerBuffer, settings.riskAversion)
                    val rentEv = cost * state.ownRentMultiplier * 0.45 * lateGameFactor * hitFactor
                    val sc = rentEv + hammerBonus - cost * 0.55 - liqPen + sectorAfter + 120.0
                    val nextSt = state.copy(
                        position = tile.index,
                        cash = state.cash - cost,
                        hammerProgress = if (newHammer >= 4) 0 else newHammer,
                        boughtTiles = state.boughtTiles + tile.index,
                        ownRentMultiplier = if (newHammer >= 4) state.ownRentMultiplier + 1.0 else state.ownRentMultiplier
                    )
                    UfoDecision(
                        targetIndex = tile.index,
                        score = sc,
                        cashDelta = -cost,
                        nextState = nextSt,
                        riskLevel = classifyCashRisk(nextSt.cash, dangerBuffer),
                        shortAction = "купить за $cost"
                    )
                } else {
                    val sc = 40.0 + sectorAfter
                    UfoDecision(
                        targetIndex = tile.index,
                        score = sc,
                        cashDelta = 0,
                        nextState = state.copy(position = tile.index),
                        riskLevel = classifyCashRisk(state.cash, dangerBuffer),
                        shortAction = "своб. ($cost)"
                    )
                }
                if (best == null || decision.score > best.score) {
                    best = decision
                }
            }
            return best!!
        }

        // No free properties left -> rule says you can pick a cheaper tile!
        val candidateTiles = snapshot.tiles.filter { it.type == TileType.PROPERTY }
        var bestFallback: UfoDecision? = null
        for (tile in candidateTiles) {
            val owner = if (tile.index in state.boughtTiles) myColor else tile.owner
            val rent = if (owner == null || owner == myColor) {
                0
            } else {
                tile.effectiveRent(estimatePlayerRentMultiplier(snapshot, owner))
            }
            val postCash = state.cash - rent
            val sectorAfter = evaluateSectorAhead(snapshot, tile.index, myColor, postCash, settings)
            val sc = -rent * 1.15 + sectorAfter + if (rent == 0) 90.0 else 0.0
            val risk = if (postCash <= 0) RiskLevel.LETHAL else classifyCashRisk(postCash, dangerBuffer)
            val decision = UfoDecision(
                targetIndex = tile.index,
                score = if (postCash <= 0) sc - BANKRUPTCY_PENALTY else sc,
                cashDelta = -rent,
                nextState = state.copy(position = tile.index, cash = postCash),
                riskLevel = risk,
                shortAction = if (rent == 0) "своя (0)" else "дешёвая (-$rent)"
            )
            if (bestFallback == null || decision.score > bestFallback.score) {
                bestFallback = decision
            }
        }
        return bestFallback ?: UfoDecision(14, 50.0, 0, state, RiskLevel.SAFE, "безопасно")
    }

    private data class SwordsPlan(
        val score: Double,
        val headline: String,
        val advice: String,
        val reason: String
    )

    private fun chooseBestSwordsTarget(
        snapshot: BoardSnapshot,
        myColor: PlayerColor,
        extraBought: Set<Int>,
        settings: StrategySettings
    ): SwordsPlan {
        val opponents = snapshot.aliveOpponents()
        if (opponents.isEmpty()) {
            return SwordsPlan(80.0, "Мечи", "Мечи: нет соперников", "Мечи")
        }

        val myTotalRent = computeMyTotalRentPower(
            snapshot, myColor, extraBought, estimatePlayerRentMultiplier(snapshot, myColor)
        )
        val myPos = snapshot.player(myColor).position ?: 7

        var bestPlan: SwordsPlan? = null
        for (opp in opponents) {
            val oppTotalRent = computeOpponentTotalRent(snapshot, opp.color)

            // Positional threat: how much of my rent lies 1..12 steps ahead of opponent vs vice versa
            val oppPos = opp.position ?: 0
            val myRentAheadOfOpp = sumOwnerRentAhead(snapshot, oppPos, myColor, extraBought, 12)
            val oppRentAheadOfMe = sumOwnerRentAhead(snapshot, myPos, opp.color, emptySet(), 12)

            // Option A: DUEL (2x mutual rent) — great when we have stronger territories & opponent is approaching them
            val duelEv = 110.0 +
                (myTotalRent - oppTotalRent) * 0.16 +
                (myRentAheadOfOpp - oppRentAheadOfMe) * 0.25 +
                min(opp.money, 1500) * 0.04

            val duelPlan = SwordsPlan(
                score = duelEv,
                headline = "Мечи → Дуэль x2 с ${opp.color.shortEmoji}",
                advice = "Мечи: выбери ДУЭЛЬ (x2) против ${opp.color.displayName}",
                reason = "Дуэль x2 с ${opp.color.displayName}: ваши дома сильнее на его пути"
            )

            // Option B: ALLIANCE (50/50 split of profit & loss) — huge when opponent dominates the board!
            val allianceEv = 120.0 +
                (oppTotalRent - myTotalRent) * 0.22 +
                oppRentAheadOfMe * 0.18

            val alliancePlan = SwordsPlan(
                score = allianceEv,
                headline = "Мечи → Союз 50/50 с ${opp.color.shortEmoji}",
                advice = "Мечи: выбери СОЮЗ (50/50) с ${opp.color.displayName}",
                reason = "Союз 50/50 с ${opp.color.displayName}: делите его высокую аренду и снижаете риск"
            )

            val pick = if (alliancePlan.score >= duelPlan.score) alliancePlan else duelPlan
            if (bestPlan == null || pick.score > bestPlan.score) {
                bestPlan = pick
            }
        }
        return bestPlan!!
    }

    private fun computeHammerSynergyBonus(
        newHammer: Int,
        portfolioBase: Int,
        currentMult: Double,
        roundsLeft: Int
    ): Double {
        val roundScale = (roundsLeft.toDouble() / 15.0).coerceIn(0.10, 1.30)
        // Completing 4/4 boosts rent across ALL territories -> huge multiplier value!
        val fullUpgradeValue = portfolioBase * 0.48 * roundScale * (1.0 / currentMult.coerceAtLeast(1.0) + 0.5)
        return when {
            newHammer >= 4 -> fullUpgradeValue * 1.15
            newHammer == 3 -> fullUpgradeValue * 0.42
            newHammer == 2 -> fullUpgradeValue * 0.26
            else -> fullUpgradeValue * 0.18
        }
    }

    private fun computeSafeCashBuffer(snapshot: BoardSnapshot, myColor: PlayerColor): Int {
        val oppRents = snapshot.tiles
            .filter { it.type == TileType.PROPERTY && it.owner != null && it.owner != myColor }
            .map { it.effectiveRent(estimatePlayerRentMultiplier(snapshot, it.owner!!)) }
            .sortedDescending()
        if (oppRents.isEmpty()) return 250
        val topRent = oppRents.first()
        val avgTop3 = oppRents.take(3).average().toInt()
        return max(300, max(topRent, avgTop3))
    }

    private fun computeLiquidityPenalty(
        cashAfter: Int,
        dangerBuffer: Int,
        riskAversion: Double
    ): Double {
        if (cashAfter <= 0) return BANKRUPTCY_PENALTY
        if (cashAfter >= dangerBuffer) return 0.0
        val deficitRatio = (dangerBuffer - cashAfter).toDouble() / dangerBuffer.coerceAtLeast(1)
        // Smooth quadratic risk curve as cash approaches 0
        return deficitRatio * deficitRatio * dangerBuffer * 0.75 * riskAversion
    }

    private fun classifyCashRisk(cashAfter: Int, dangerBuffer: Int): RiskLevel = when {
        cashAfter <= 0 -> RiskLevel.LETHAL
        cashAfter < dangerBuffer * 0.45 -> RiskLevel.DANGEROUS
        cashAfter < dangerBuffer -> RiskLevel.MODERATE
        else -> RiskLevel.SAFE
    }

    private fun computeOpponentLandingHeat(snapshot: BoardSnapshot): DoubleArray {
        val heat = DoubleArray(BoardDefinition.TILE_COUNT)
        for (opp in snapshot.aliveOpponents()) {
            val pos = opp.position ?: continue
            for ((step, prob) in FORWARD_STEP_PROBS) {
                val target = BoardDefinition.step(pos, step).targetIndex
                heat[target] += prob
            }
            for ((step, prob) in BACKWARD_STEP_PROBS) {
                val target = BoardDefinition.step(pos, -step).targetIndex
                heat[target] += prob
            }
        }
        return heat
    }

    private fun evaluateSectorAhead(
        snapshot: BoardSnapshot,
        fromTile: Int,
        myColor: PlayerColor,
        myCash: Int,
        settings: StrategySettings
    ): Double {
        var expectedDanger = 0.0
        for ((step, prob) in FORWARD_STEP_PROBS) {
            val idx = BoardDefinition.step(fromTile, step).targetIndex
            val tile = snapshot.tiles[idx]
            if (tile.type == TileType.PROPERTY && tile.owner != null && tile.owner != myColor) {
                val rent = tile.effectiveRent(estimatePlayerRentMultiplier(snapshot, tile.owner))
                val lethalExtra = if (rent >= myCash) 450.0 else 0.0
                expectedDanger += prob * (rent + lethalExtra)
            }
        }
        return -expectedDanger * 0.22 * settings.riskAversion
    }

    private fun estimatePlayerRentMultiplier(snapshot: BoardSnapshot, color: PlayerColor): Double {
        val explicit = snapshot.player(color).rentMultiplier
        if (explicit > 1.05) return explicit
        val owned = snapshot.tiles.filter { it.type == TileType.PROPERTY && it.owner == color }
        if (owned.isEmpty()) return 1.0
        val ratios = owned
            .filter { !it.hasIncomeDebuff && it.basePrice > 0 && it.displayedRent >= it.basePrice }
            .map { it.displayedRent.toDouble() / it.basePrice.toDouble() }
        if (ratios.isNotEmpty()) {
            return ratios.sorted()[ratios.size / 2].coerceIn(1.0, 10.0)
        }
        // Fallback: every 4 owned properties implies ~+1x hammer tier
        return 1.0 + (owned.size / 4)
    }

    private fun computeMyPortfolioBase(
        snapshot: BoardSnapshot,
        myColor: PlayerColor,
        extraBought: Set<Int>
    ): Int {
        return snapshot.tiles
            .filter { it.type == TileType.PROPERTY && (it.owner == myColor || it.index in extraBought) }
            .sumOf { it.basePrice }
    }

    private fun countMyProperties(
        snapshot: BoardSnapshot,
        myColor: PlayerColor,
        extraBought: Set<Int>
    ): Int {
        return snapshot.tiles.count {
            it.type == TileType.PROPERTY && (it.owner == myColor || it.index in extraBought)
        }
    }

    private fun computeMyTotalRentPower(
        snapshot: BoardSnapshot,
        myColor: PlayerColor,
        extraBought: Set<Int>,
        mult: Double
    ): Int {
        return snapshot.tiles
            .filter { it.type == TileType.PROPERTY && (it.owner == myColor || it.index in extraBought) }
            .sumOf { tile ->
                if (tile.owner == myColor) tile.effectiveRent(mult)
                else (tile.basePrice * mult).toInt()
            }
    }

    private fun computeOpponentTotalRent(snapshot: BoardSnapshot, oppColor: PlayerColor): Int {
        val mult = estimatePlayerRentMultiplier(snapshot, oppColor)
        return snapshot.tiles
            .filter { it.type == TileType.PROPERTY && it.owner == oppColor }
            .sumOf { it.effectiveRent(mult) }
    }

    private fun sumOwnerRentAhead(
        snapshot: BoardSnapshot,
        fromPos: Int,
        owner: PlayerColor,
        extraBought: Set<Int>,
        horizon: Int
    ): Int {
        val mult = estimatePlayerRentMultiplier(snapshot, owner)
        var sum = 0
        for (d in 1..horizon) {
            val idx = BoardDefinition.step(fromPos, d).targetIndex
            val tile = snapshot.tiles[idx]
            if (tile.type == TileType.PROPERTY && (tile.owner == owner || tile.index in extraBought)) {
                sum += if (tile.owner == owner) tile.effectiveRent(mult) else (tile.basePrice * mult).toInt()
            }
        }
        return sum
    }

    private fun isOpponentLeading(snapshot: BoardSnapshot, oppColor: PlayerColor): Boolean {
        val myMoney = snapshot.player(snapshot.localPlayer).money
        val oppMoney = snapshot.player(oppColor).money
        val oppRent = computeOpponentTotalRent(snapshot, oppColor)
        return oppMoney >= myMoney || oppRent >= 1500
    }
}
