package com.weplay.cardtycoon.helper.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.weplay.cardtycoon.helper.R
import com.weplay.cardtycoon.helper.board.BoardDefinition
import com.weplay.cardtycoon.helper.databinding.ActivityMainBinding
import com.weplay.cardtycoon.helper.model.BoardSnapshot
import com.weplay.cardtycoon.helper.model.HandCard
import com.weplay.cardtycoon.helper.model.PlayerColor
import com.weplay.cardtycoon.helper.model.PlayerState
import com.weplay.cardtycoon.helper.model.StrategySettings
import com.weplay.cardtycoon.helper.overlay.FloatingAdvisorService
import com.weplay.cardtycoon.helper.storage.SettingsRepository
import com.weplay.cardtycoon.helper.strategy.StrategyEngine

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepo: SettingsRepository
    private var settings: StrategySettings = StrategySettings()
    private val strategyEngine = StrategyEngine()

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshUiState() }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshUiState() }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, FloatingAdvisorService::class.java).apply {
                action = FloatingAdvisorService.ACTION_START
                putExtra(FloatingAdvisorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingAdvisorService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, getString(R.string.toast_widget_started), Toast.LENGTH_SHORT).show()
            binding.root.postDelayed({ refreshUiState() }, 300L)
        } else {
            Toast.makeText(this, getString(R.string.toast_capture_denied), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsRepo = SettingsRepository(this)
        settings = settingsRepo.load()

        setupControls()
        refreshUiState()
        runDemoSimulation()
    }

    override fun onResume() {
        super.onResume()
        settings = settingsRepo.load()
        syncSwitchesFromSettings()
        refreshUiState()
        runDemoSimulation()
    }

    private fun setupControls() {
        binding.btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }

        binding.btnGrantNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.btnToggleService.setOnClickListener {
            if (FloatingAdvisorService.isRunning) {
                val stopIntent = Intent(this, FloatingAdvisorService::class.java).apply {
                    action = FloatingAdvisorService.ACTION_STOP
                }
                startService(stopIntent)
                binding.root.postDelayed({ refreshUiState() }, 250L)
            } else {
                startAdvisorFlow()
            }
        }

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Seat preference buttons
        val seatButtons = mapOf(
            binding.btnSeatAuto to null,
            binding.btnSeatBlue to PlayerColor.BLUE,
            binding.btnSeatOrange to PlayerColor.ORANGE,
            binding.btnSeatGreen to PlayerColor.GREEN,
            binding.btnSeatRed to PlayerColor.RED
        )
        for ((btn, seat) in seatButtons) {
            btn.setOnClickListener {
                settings = settings.copy(preferredSeat = seat)
                settingsRepo.save(settings)
                notifyServiceSettingsChanged()
                updateSeatButtons()
            }
        }

        syncSwitchesFromSettings()

        binding.switchAutoScan.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(autoScanEnabled = isChecked)
            settingsRepo.save(settings)
            notifyServiceSettingsChanged()
        }

        binding.switchCardHighlight.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(showCardHighlightOverlay = isChecked)
            settingsRepo.save(settings)
            notifyServiceSettingsChanged()
        }

        binding.switchTileHighlight.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(showTargetTileHighlight = isChecked)
            settingsRepo.save(settings)
            notifyServiceSettingsChanged()
        }
    }

    private fun syncSwitchesFromSettings() {
        binding.switchAutoScan.isChecked = settings.autoScanEnabled
        binding.switchCardHighlight.isChecked = settings.showCardHighlightOverlay
        binding.switchTileHighlight.isChecked = settings.showTargetTileHighlight
        updateSeatButtons()
    }

    private fun updateSeatButtons() {
        val pairs = listOf(
            binding.btnSeatAuto to null,
            binding.btnSeatBlue to PlayerColor.BLUE,
            binding.btnSeatOrange to PlayerColor.ORANGE,
            binding.btnSeatGreen to PlayerColor.GREEN,
            binding.btnSeatRed to PlayerColor.RED
        )
        for ((btn, seat) in pairs) {
            val selected = settings.preferredSeat == seat
            btn.setBackgroundResource(
                if (selected) R.drawable.bg_seat_selected else R.drawable.bg_seat_unselected
            )
        }
    }

    private fun startAdvisorFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.toast_need_overlay), Toast.LENGTH_LONG).show()
            binding.btnGrantOverlay.performClick()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun refreshUiState() {
        val hasOverlay = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = if (hasOverlay) "✅ Разрешено" else "⚠ Не выдано"
        binding.btnGrantOverlay.isEnabled = !hasOverlay

        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        binding.tvNotifStatus.text = if (hasNotif) "✅ Разрешено" else "⚠ Нужно для фоновой работы"
        binding.btnGrantNotif.isEnabled = !hasNotif

        val running = FloatingAdvisorService.isRunning
        binding.btnToggleService.text = if (running) {
            getString(R.string.btn_stop_widget)
        } else {
            getString(R.string.btn_start_widget)
        }
        binding.tvServiceBadge.text = if (running) {
            "🟢 Виджет активен поверх экрана"
        } else {
            "⚪ Виджет остановлен"
        }
    }

    private fun notifyServiceSettingsChanged() {
        if (FloatingAdvisorService.isRunning) {
            val intent = Intent(this, FloatingAdvisorService::class.java).apply {
                action = FloatingAdvisorService.ACTION_RELOAD_SETTINGS
            }
            startService(intent)
        }
    }

    /**
     * Runs the live StrategyEngine on the exact Round 5 board state from the reference game
     * so the user can immediately inspect how multi-turn hand planning works.
     */
    private fun runDemoSimulation() {
        val tiles = BoardDefinition.defaultEmptyTiles().toMutableList()
        fun setOwned(idx: Int, owner: PlayerColor, rent: Int) {
            tiles[idx] = tiles[idx].copy(owner = owner, displayedRent = rent, houseLevel = 1)
        }
        // Round 5 board configuration
        setOwned(5, PlayerColor.GREEN, 200)
        setOwned(8, PlayerColor.BLUE, 400)
        setOwned(9, PlayerColor.BLUE, 300)
        setOwned(10, PlayerColor.GREEN, 200)
        tiles[12] = tiles[12].copy(hasMudTrap = true)
        setOwned(15, PlayerColor.BLUE, 400)
        setOwned(16, PlayerColor.GREEN, 600)
        setOwned(17, PlayerColor.ORANGE, 300)
        setOwned(19, PlayerColor.RED, 200)
        setOwned(23, PlayerColor.ORANGE, 200)
        setOwned(27, PlayerColor.GREEN, 600)

        val players = mapOf(
            PlayerColor.ORANGE to PlayerState(PlayerColor.ORANGE, money = 2415, position = 21),
            PlayerColor.GREEN to PlayerState(PlayerColor.GREEN, money = 1881, position = 16, rentMultiplier = 2.0),
            PlayerColor.BLUE to PlayerState(PlayerColor.BLUE, money = 975, position = 23, isLocal = true, hammerProgress = 3),
            PlayerColor.RED to PlayerState(PlayerColor.RED, money = 2670, position = 19)
        )
        val cards = listOf(
            HandCard(0, 5),
            HandCard(1, 7),
            HandCard(2, 7),
            HandCard(3, 2)
        )
        val snapshot = BoardSnapshot(
            round = 5,
            maxRounds = 30,
            tiles = tiles,
            players = players,
            localPlayer = PlayerColor.BLUE,
            handCards = cards,
            isMyTurn = true
        )
        val result = strategyEngine.evaluate(snapshot, settings)
        val sb = StringBuilder()
        sb.appendLine("Пример (Раунд 5/30, Синий на кл.#23, 💰975, молоток 3/4, карты: +5, +7, +7, +2):")
        result.rankedMoves.take(3).forEachIndexed { idx, m ->
            val medal = when (idx) {
                0 -> "★ 1."
                1 -> "  2."
                else -> "  3."
            }
            val combo = m.bestComboSteps.joinToString("→") { if (it >= 0) "+$it" else "$it" }
            sb.appendLine("$medal [${m.card.label}] ${m.targetTitle}: ${m.actionHeadline}")
            if (idx == 0) {
                sb.appendLine("      План руки: $combo (оценка ${m.totalScore.toInt()})")
                m.reasons.take(2).forEach { r -> sb.appendLine("      • $r") }
            }
        }
        binding.tvDemoOutput.text = sb.toString().trim()
    }
}
