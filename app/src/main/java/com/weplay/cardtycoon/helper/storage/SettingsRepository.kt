package com.weplay.cardtycoon.helper.storage

import android.content.Context
import com.weplay.cardtycoon.helper.model.PlayerColor
import com.weplay.cardtycoon.helper.model.StrategySettings

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("card_tycoon_helper_prefs", Context.MODE_PRIVATE)

    fun load(): StrategySettings {
        val seatName = prefs.getString("preferred_seat", null)
        val seat = seatName?.let { runCatching { PlayerColor.valueOf(it) }.getOrNull() }
        return StrategySettings(
            preferredSeat = seat,
            autoScanEnabled = prefs.getBoolean("auto_scan", true),
            autoScanIntervalMs = prefs.getLong("scan_interval_ms", 1000L).coerceIn(600L, 4000L),
            showCardHighlightOverlay = prefs.getBoolean("show_card_highlight", true),
            showTargetTileHighlight = prefs.getBoolean("show_tile_highlight", true),
            goPassBonus = prefs.getInt("go_pass_bonus", 500),
            goLandBonus = prefs.getInt("go_land_bonus", 700),
            pinkWheelWeight = prefs.getFloat("pink_wheel_weight", 1.0f).toDouble(),
            ufoWeight = prefs.getFloat("ufo_weight", 1.0f).toDouble(),
            walletStealRatio = prefs.getFloat("wallet_steal_ratio", 0.25f).toDouble(),
            swordsWeight = prefs.getFloat("swords_weight", 1.0f).toDouble(),
            chanceValue = prefs.getInt("chance_value", 150),
            mysteryBagValue = prefs.getInt("mystery_bag_value", 40),
            mudPenalty = prefs.getInt("mud_penalty", 250),
            bombPenalty = prefs.getInt("bomb_penalty", 420),
            riskAversion = prefs.getFloat("risk_aversion", 1.25f).toDouble()
        )
    }

    fun save(settings: StrategySettings) {
        prefs.edit()
            .putString("preferred_seat", settings.preferredSeat?.name)
            .putBoolean("auto_scan", settings.autoScanEnabled)
            .putLong("scan_interval_ms", settings.autoScanIntervalMs)
            .putBoolean("show_card_highlight", settings.showCardHighlightOverlay)
            .putBoolean("show_tile_highlight", settings.showTargetTileHighlight)
            .putInt("go_pass_bonus", settings.goPassBonus)
            .putInt("go_land_bonus", settings.goLandBonus)
            .putFloat("pink_wheel_weight", settings.pinkWheelWeight.toFloat())
            .putFloat("ufo_weight", settings.ufoWeight.toFloat())
            .putFloat("wallet_steal_ratio", settings.walletStealRatio.toFloat())
            .putFloat("swords_weight", settings.swordsWeight.toFloat())
            .putInt("chance_value", settings.chanceValue)
            .putInt("mystery_bag_value", settings.mysteryBagValue)
            .putInt("mud_penalty", settings.mudPenalty)
            .putInt("bomb_penalty", settings.bombPenalty)
            .putFloat("risk_aversion", settings.riskAversion.toFloat())
            .apply()
    }
}
