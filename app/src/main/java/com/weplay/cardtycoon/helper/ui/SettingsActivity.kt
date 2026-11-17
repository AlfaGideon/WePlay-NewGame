package com.weplay.cardtycoon.helper.ui

import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.weplay.cardtycoon.helper.databinding.ActivitySettingsBinding
import com.weplay.cardtycoon.helper.model.StrategySettings
import com.weplay.cardtycoon.helper.overlay.FloatingAdvisorService
import com.weplay.cardtycoon.helper.storage.SettingsRepository

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repo: SettingsRepository
    private var settings: StrategySettings = StrategySettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = SettingsRepository(this)
        settings = repo.load()

        bindValues()
        bindListeners()
    }

    private fun bindValues() {
        // Scan interval: 600..3000ms (step 100ms, 0..24)
        binding.seekInterval.max = 24
        binding.seekInterval.progress = ((settings.autoScanIntervalMs - 600L) / 100L).toInt().coerceIn(0, 24)

        // GO pass bonus: 100..1500 (step 50, 0..28)
        binding.seekGoBonus.max = 28
        binding.seekGoBonus.progress = ((settings.goPassBonus - 100) / 50).coerceIn(0, 28)

        // Pink wheel x1..x4 weight: 0.4..2.0 (step 0.1, 0..16)
        binding.seekPinkWheel.max = 16
        binding.seekPinkWheel.progress = ((settings.pinkWheelWeight - 0.4) * 10).toInt().coerceIn(0, 16)

        // UFO weight: 0.4..2.0 (step 0.1, 0..16)
        binding.seekUfo.max = 16
        binding.seekUfo.progress = ((settings.ufoWeight - 0.4) * 10).toInt().coerceIn(0, 16)

        // Wallet steal ratio: 10%..50% (step 5%, 0..8)
        binding.seekWallet.max = 8
        binding.seekWallet.progress = ((settings.walletStealRatio * 100 - 10) / 5).toInt().coerceIn(0, 8)

        // Swords weight: 0.4..2.0 (step 0.1, 0..16)
        binding.seekSwords.max = 16
        binding.seekSwords.progress = ((settings.swordsWeight - 0.4) * 10).toInt().coerceIn(0, 16)

        // Mystery bag value: -200..+300 (step 20, 0..25) — reflects trap risk!
        binding.seekBag.max = 25
        binding.seekBag.progress = ((settings.mysteryBagValue + 200) / 20).coerceIn(0, 25)

        // Bomb trap penalty (hospital 2 turns): 100..800 (step 20, 0..35)
        binding.seekBomb.max = 35
        binding.seekBomb.progress = ((settings.bombPenalty - 100) / 20).coerceIn(0, 35)

        // Poop trap penalty (-money): 50..600 (step 25, 0..22)
        binding.seekMud.max = 22
        binding.seekMud.progress = ((settings.mudPenalty - 50) / 25).coerceIn(0, 22)

        // Risk aversion: 0.5..2.5 (step 0.1, 0..20)
        binding.seekRisk.max = 20
        binding.seekRisk.progress = ((settings.riskAversion - 0.5) * 10).toInt().coerceIn(0, 20)

        refreshLabels()
    }

    private fun refreshLabels() {
        binding.tvIntervalVal.text = String.format("%.1f сек", settings.autoScanIntervalMs / 1000.0)
        binding.tvGoBonusVal.text = "+${settings.goPassBonus} (на GO: +${settings.goLandBonus})"
        binding.tvPinkWheelVal.text = String.format("x%.1f", settings.pinkWheelWeight)
        binding.tvUfoVal.text = String.format("x%.1f", settings.ufoWeight)
        binding.tvWalletVal.text = "${(settings.walletStealRatio * 100).toInt()}%"
        binding.tvSwordsVal.text = String.format("x%.1f", settings.swordsWeight)
        val bagSign = if (settings.mysteryBagValue >= 0) "+" else ""
        binding.tvBagVal.text = "${bagSign}${settings.mysteryBagValue}"
        binding.tvBombVal.text = "-${settings.bombPenalty}"
        binding.tvMudVal.text = "-${settings.mudPenalty}"
        binding.tvRiskVal.text = String.format("x%.1f", settings.riskAversion)
    }

    private fun bindListeners() {
        fun SeekBar.onChanged(block: (Int) -> Unit) {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        block(progress)
                        refreshLabels()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        binding.seekInterval.onChanged { p ->
            settings = settings.copy(autoScanIntervalMs = 600L + p * 100L)
        }
        binding.seekGoBonus.onChanged { p ->
            val pass = 100 + p * 50
            settings = settings.copy(goPassBonus = pass, goLandBonus = pass + 200)
        }
        binding.seekPinkWheel.onChanged { p ->
            settings = settings.copy(pinkWheelWeight = 0.4 + p * 0.1)
        }
        binding.seekUfo.onChanged { p ->
            settings = settings.copy(ufoWeight = 0.4 + p * 0.1)
        }
        binding.seekWallet.onChanged { p ->
            settings = settings.copy(walletStealRatio = (10 + p * 5) / 100.0)
        }
        binding.seekSwords.onChanged { p ->
            settings = settings.copy(swordsWeight = 0.4 + p * 0.1)
        }
        binding.seekBag.onChanged { p ->
            settings = settings.copy(mysteryBagValue = -200 + p * 20)
        }
        binding.seekBomb.onChanged { p ->
            settings = settings.copy(bombPenalty = 100 + p * 20)
        }
        binding.seekMud.onChanged { p ->
            settings = settings.copy(mudPenalty = 50 + p * 25)
        }
        binding.seekRisk.onChanged { p ->
            settings = settings.copy(riskAversion = 0.5 + p * 0.1)
        }

        binding.btnSaveSettings.setOnClickListener {
            repo.save(settings)
            if (FloatingAdvisorService.isRunning) {
                val intent = Intent(this, FloatingAdvisorService::class.java).apply {
                    action = FloatingAdvisorService.ACTION_RELOAD_SETTINGS
                }
                startService(intent)
            }
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnResetDefaults.setOnClickListener {
            val keepSeat = settings.preferredSeat
            settings = StrategySettings(preferredSeat = keepSeat)
            bindValues()
        }
    }
}
