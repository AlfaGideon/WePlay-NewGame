package com.weplay.cardtycoon.helper.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.weplay.cardtycoon.helper.R
import com.weplay.cardtycoon.helper.capture.ScreenCaptureManager
import com.weplay.cardtycoon.helper.model.BoardSnapshot
import com.weplay.cardtycoon.helper.model.HandCard
import com.weplay.cardtycoon.helper.model.MoveEvaluation
import com.weplay.cardtycoon.helper.model.PlayerColor
import com.weplay.cardtycoon.helper.model.RecommendationResult
import com.weplay.cardtycoon.helper.model.RiskLevel
import com.weplay.cardtycoon.helper.model.StrategySettings
import com.weplay.cardtycoon.helper.storage.SettingsRepository
import com.weplay.cardtycoon.helper.strategy.StrategyEngine
import com.weplay.cardtycoon.helper.ui.MainActivity
import com.weplay.cardtycoon.helper.vision.ScreenAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Foreground Service managing:
 *  1) Live MediaProjection screen capture loop,
 *  2) Floating draggable advisor widget (compact pill <-> expanded panel),
 *  3) Non-touchable pass-through highlight overlay directly over the game's 4 cards & board tiles.
 */
class FloatingAdvisorService : Service() {

    companion object {
        const val ACTION_START = "com.weplay.cardtycoon.helper.START"
        const val ACTION_STOP = "com.weplay.cardtycoon.helper.STOP"
        const val ACTION_RELOAD_SETTINGS = "com.weplay.cardtycoon.helper.RELOAD_SETTINGS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "card_tycoon_advisor_channel"
        private const val NOTIF_ID = 1042

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var settingsRepo: SettingsRepository
    private val strategyEngine = StrategyEngine()
    private val screenAnalyzer = ScreenAnalyzer()
    private lateinit var captureManager: ScreenCaptureManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scanJob: Job? = null

    private var advisorView: View? = null
    private var advisorParams: WindowManager.LayoutParams? = null
    private var highlightView: CardHighlightOverlay? = null

    private var settings: StrategySettings = StrategySettings()
    private var isPaused: Boolean = false
    private var isMinimized: Boolean = false
    private var lastSnapshot: BoardSnapshot? = null
    private var manualCardOverrides: MutableList<Int>? = null

    // UI references
    private var miniPillContainer: View? = null
    private var miniPillText: TextView? = null
    private var expandedContainer: View? = null
    private var statusText: TextView? = null
    private var seatBadge: TextView? = null
    private var pauseBtn: ImageButton? = null
    private var bestCardBadge: TextView? = null
    private var bestHeadline: TextView? = null
    private var bestSubtext: TextView? = null
    private var warningText: TextView? = null
    private var movesContainer: LinearLayout? = null
    private var comboText: TextView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsRepo = SettingsRepository(this)
        settings = settingsRepo.load()
        captureManager = ScreenCaptureManager(this) {
            stopSelf()
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_SETTINGS -> {
                settings = settingsRepo.load()
                recomputeFromLastSnapshot()
                return START_STICKY
            }
            ACTION_START -> {
                startForegroundCompat()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultData == null || !captureManager.start(resultCode, resultData)) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                isRunning = true
                settings = settingsRepo.load()
                isPaused = !settings.autoScanEnabled
                attachOverlays()
                startScanLoop()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        scanJob?.cancel()
        serviceScope.cancel()
        captureManager.stop()
        screenAnalyzer.close()
        detachOverlays()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingAdvisorService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_advisor)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_running_text))
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.btn_stop), stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(channel)
    }

    private fun attachOverlays() {
        detachOverlays()

        // 1. Pass-through full-screen card & target-tile highlight overlay
        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val hlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        val hlView = CardHighlightOverlay(this)
        windowManager.addView(hlView, hlParams)
        highlightView = hlView

        // 2. Draggable floating advisor widget (parked near top so board & bottom hand stay clear!)
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_advisor, null)
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).toInt()
            y = (56 * density).toInt()
        }

        bindOverlayViews(view, params)
        windowManager.addView(view, params)
        advisorView = view
        advisorParams = params
    }

    private fun bindOverlayViews(view: View, params: WindowManager.LayoutParams) {
        miniPillContainer = view.findViewById(R.id.miniPillContainer)
        miniPillText = view.findViewById(R.id.miniPillText)
        expandedContainer = view.findViewById(R.id.expandedContainer)
        statusText = view.findViewById(R.id.tvStatus)
        seatBadge = view.findViewById(R.id.tvSeatBadge)
        pauseBtn = view.findViewById(R.id.btnPause)
        bestCardBadge = view.findViewById(R.id.tvBestCard)
        bestHeadline = view.findViewById(R.id.tvBestHeadline)
        bestSubtext = view.findViewById(R.id.tvBestSubtext)
        warningText = view.findViewById(R.id.tvWarning)
        movesContainer = view.findViewById(R.id.movesListContainer)
        comboText = view.findViewById(R.id.tvCombo)

        val dragHeader: View = view.findViewById(R.id.dragHeader)
        setupDragListener(dragHeader, view, params, onClick = null)
        setupDragListener(miniPillContainer!!, view, params, onClick = {
            isMinimized = false
            updateVisibilityMode()
        })

        seatBadge?.setOnClickListener {
            cyclePreferredSeat()
        }

        pauseBtn?.setOnClickListener {
            isPaused = !isPaused
            updateStatusHeader()
            if (!isPaused) triggerSingleScan()
        }

        view.findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            manualCardOverrides = null
            triggerSingleScan()
        }

        view.findViewById<ImageButton>(R.id.btnMinimize).setOnClickListener {
            isMinimized = true
            updateVisibilityMode()
        }

        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            stopSelf()
        }

        updateSeatBadgeText()
        updateStatusHeader()
        updateVisibilityMode()
    }

    private fun setupDragListener(
        target: View,
        rootView: View,
        params: WindowManager.LayoutParams,
        onClick: (() -> Unit)?
    ) {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        target.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = initX + dx
                    params.y = initY + dy
                    try {
                        windowManager.updateViewLayout(rootView, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onClick?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private fun detachOverlays() {
        advisorView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        advisorView = null
        highlightView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        highlightView = null
    }

    private fun startScanLoop() {
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            delay(500L)
            while (isActive) {
                if (!isPaused) {
                    runSingleCaptureAndEvaluate()
                }
                delay(settings.autoScanIntervalMs)
            }
        }
    }

    private fun triggerSingleScan() {
        serviceScope.launch {
            runSingleCaptureAndEvaluate()
        }
    }

    private suspend fun runSingleCaptureAndEvaluate() {
        val frame = withContext(Dispatchers.Default) { captureManager.captureFrame() } ?: return
        try {
            val snapshot = screenAnalyzer.analyze(frame, settings)
            // Clear manual overrides if the real hand card count or steps clearly changed
            lastSnapshot = snapshot
            val effectiveSnapshot = applyManualOverrides(snapshot)
            val recommendation = withContext(Dispatchers.Default) {
                strategyEngine.evaluate(effectiveSnapshot, settings)
            }
            renderRecommendation(recommendation)
        } finally {
            if (!frame.isRecycled) frame.recycle()
        }
    }

    private fun applyManualOverrides(snapshot: BoardSnapshot): BoardSnapshot {
        val overrides = manualCardOverrides ?: return snapshot
        val updatedCards = overrides.mapIndexed { idx, steps ->
            val origBox = snapshot.handCards.getOrNull(idx)?.screenBox
            HandCard(
                slotIndex = idx,
                steps = steps,
                screenBox = origBox ?: android.graphics.RectF(),
                confidence = 1.0f,
                isActive = true
            )
        }
        return snapshot.copy(handCards = updatedCards)
    }

    private fun recomputeFromLastSnapshot() {
        val snap = lastSnapshot ?: return
        val adjusted = snap.copy(
            localPlayer = settings.preferredSeat ?: snap.localPlayer
        )
        val effective = applyManualOverrides(adjusted)
        serviceScope.launch {
            val rec = withContext(Dispatchers.Default) {
                strategyEngine.evaluate(effective, settings)
            }
            renderRecommendation(rec)
        }
    }

    private fun renderRecommendation(rec: RecommendationResult) {
        highlightView?.update(rec, settings)

        val snap = rec.snapshot
        val me = snap.player(snap.localPlayer)
        val best = rec.bestMove

        updateSeatBadgeText(snap.localPlayer)

        val turnLabel = if (snap.isMyTurn) "ВАШ ХОД" else "Р.${snap.round}/30"
        statusText?.text = if (isPaused) "⏸ Пауза · $turnLabel" else "🟢 $turnLabel · 💰${me.money}"

        if (rec.summaryWarning != null) {
            warningText?.visibility = View.VISIBLE
            warningText?.text = "⚠ ${rec.summaryWarning}"
        } else {
            warningText?.visibility = View.GONE
        }

        if (best == null) {
            bestCardBadge?.text = "—"
            bestHeadline?.text = "Ожидание карт..."
            bestSubtext?.text = snap.statusNote.ifBlank { "Откройте игру «Карточный магнат»" }
            comboText?.visibility = View.GONE
            miniPillText?.text = "★ Жду ход · ${snap.localPlayer.shortEmoji}"
            movesContainer?.removeAllViews()
            return
        }

        // Compact pill summary
        miniPillText?.text = "★ ${best.card.label} → ${best.actionHeadline}"

        // Hero best move card
        bestCardBadge?.text = best.card.label
        bestCardBadge?.setBackgroundResource(
            if (best.riskLevel == RiskLevel.LETHAL) R.drawable.bg_chip_danger else R.drawable.bg_chip_best
        )
        bestHeadline?.text = "${best.targetTitle} · ${best.actionHeadline}"
        val adviceLine = best.specialAdvice ?: best.reasons.firstOrNull().orEmpty()
        bestSubtext?.text = adviceLine

        if (best.bestComboSteps.size >= 2) {
            comboText?.visibility = View.VISIBLE
            val comboStr = best.bestComboSteps.joinToString(" → ") { if (it >= 0) "+$it" else "$it" }
            comboText?.text = "План руки: $comboStr"
        } else {
            comboText?.visibility = View.GONE
        }

        // Ranked list of all cards (tap any row to adjust card steps +/-1 if OCR misread!)
        val container = movesContainer ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for ((rank, move) in rec.rankedMoves.withIndex()) {
            val row = inflater.inflate(R.layout.item_move_row, container, false)
            val tvCard = row.findViewById<TextView>(R.id.tvRowCard)
            val tvAction = row.findViewById<TextView>(R.id.tvRowAction)
            val tvScore = row.findViewById<TextView>(R.id.tvRowScore)

            tvCard.text = move.card.label
            tvCard.setBackgroundResource(
                when {
                    move.riskLevel == RiskLevel.LETHAL -> R.drawable.bg_chip_danger
                    rank == 0 -> R.drawable.bg_chip_best
                    else -> R.drawable.bg_chip_normal
                }
            )
            tvAction.text = "${move.targetTitle}: ${move.actionHeadline}"
            val sign = if (move.totalScore >= 0) "+" else ""
            tvScore.text = "${sign}${move.totalScore.toInt()}"

            // Quick 1-tap correction: tap card chip to increment step (+1, wraps +10 -> -6), long-press resets overrides
            tvCard.setOnClickListener {
                cycleCardStepOverride(snap, move)
            }
            tvCard.setOnLongClickListener {
                manualCardOverrides = null
                recomputeFromLastSnapshot()
                true
            }
            container.addView(row)
        }
    }

    private fun cycleCardStepOverride(snap: BoardSnapshot, move: MoveEvaluation) {
        val currentList = (manualCardOverrides ?: snap.handCards.map { it.steps }).toMutableList()
        val slot = move.card.slotIndex
        if (slot !in currentList.indices) return
        val cur = currentList[slot]
        val next = when {
            cur >= 10 -> -6
            cur == -1 -> 1
            else -> cur + 1
        }
        currentList[slot] = next
        manualCardOverrides = currentList
        recomputeFromLastSnapshot()
    }

    private fun cyclePreferredSeat() {
        val order = listOf<PlayerColor?>(null, PlayerColor.BLUE, PlayerColor.ORANGE, PlayerColor.GREEN, PlayerColor.RED)
        val idx = order.indexOf(settings.preferredSeat)
        val nextSeat = order[(idx + 1) % order.size]
        settings = settings.copy(preferredSeat = nextSeat)
        settingsRepo.save(settings)
        updateSeatBadgeText(nextSeat)
        recomputeFromLastSnapshot()
    }

    private fun updateSeatBadgeText(activeColor: PlayerColor? = settings.preferredSeat) {
        seatBadge?.text = when (settings.preferredSeat) {
            null -> "Авто(${activeColor?.shortEmoji ?: "🔵"})"
            else -> settings.preferredSeat!!.shortEmoji
        }
    }

    private fun updateStatusHeader() {
        pauseBtn?.setImageResource(
            if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        )
        if (isPaused) {
            statusText?.text = "⏸ Пауза (нажмите ↻)"
        }
    }

    private fun updateVisibilityMode() {
        miniPillContainer?.visibility = if (isMinimized) View.VISIBLE else View.GONE
        expandedContainer?.visibility = if (isMinimized) View.GONE else View.VISIBLE
    }
}
