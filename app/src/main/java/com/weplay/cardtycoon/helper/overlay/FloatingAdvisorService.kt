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
import android.util.DisplayMetrics
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
 * Foreground Service managing live MediaProjection screen capture and floating overlays.
 *
 * Uses a strict Turn-Lock + 2-Frame Consensus state machine:
 *  - During opponents' turns (!isMyTurn): observes board changes silently without jumping recommendations
 *    or drawing distracting overlays.
 *  - When YOUR turn begins (isMyTurn == true): confirms hand cards across 2 consecutive stable frames,
 *    computes the optimal multi-turn plan ONCE using ONLY real cards in hand, and LOCKS it for your turn!
 *  - Unlocks automatically once your turn finishes (ready for the new board state next round) or on manual refresh.
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

    private enum class TurnState {
        WAITING_OPPONENT,
        STABILIZING_MY_TURN,
        LOCKED_MY_TURN
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

    private var turnState: TurnState = TurnState.WAITING_OPPONENT
    private var pendingHandSignature: List<Int>? = null
    private var stabilizeAttempts: Int = 0
    private var missedTurnFrames: Int = 0
    private var lockedSnapshot: BoardSnapshot? = null
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
                recomputeLockedRecommendation()
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
                turnState = TurnState.WAITING_OPPONENT
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

    private fun readRealScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width().coerceAtLeast(360) to b.height().coerceAtLeast(640)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.coerceAtLeast(360) to dm.heightPixels.coerceAtLeast(640)
        }
    }

    private fun attachOverlays() {
        detachOverlays()

        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val (realW, realH) = readRealScreenSize()
        val hlParams = WindowManager.LayoutParams(
            realW,
            realH,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0 // Do NOT shift overlay down below status bar or cutout!
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val hlView = CardHighlightOverlay(this)
        windowManager.addView(hlView, hlParams)
        highlightView = hlView

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
            if (!isPaused) forceRecalculateNow()
        }

        view.findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            forceRecalculateNow()
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
            delay(450L)
            while (isActive) {
                if (!isPaused) {
                    processNextFrame(forceUnlock = false)
                }
                // Faster cadence while stabilizing your hand (280ms), relaxed polling otherwise
                val sleepMs = when (turnState) {
                    TurnState.STABILIZING_MY_TURN -> 280L
                    TurnState.LOCKED_MY_TURN -> settings.autoScanIntervalMs.coerceAtLeast(1000L)
                    TurnState.WAITING_OPPONENT -> settings.autoScanIntervalMs
                }
                delay(sleepMs)
            }
        }
    }

    private fun forceRecalculateNow() {
        manualCardOverrides = null
        pendingHandSignature = null
        stabilizeAttempts = 0
        turnState = TurnState.WAITING_OPPONENT
        serviceScope.launch {
            processNextFrame(forceUnlock = true)
        }
    }

    private suspend fun processNextFrame(forceUnlock: Boolean) {
        // On manual refresh, briefly hide our own overlay so MediaProjection captures a 100% clean screen!
        if (forceUnlock && highlightView?.visibility == View.VISIBLE) {
            highlightView?.visibility = View.INVISIBLE
            delay(85L)
        }
        val frame = withContext(Dispatchers.Default) { captureManager.captureFrame() }
        if (forceUnlock) {
            highlightView?.visibility = View.VISIBLE
        }
        if (frame == null) return
        val snapshot = try {
            screenAnalyzer.analyze(frame, settings)
        } finally {
            if (!frame.isRecycled) frame.recycle()
        }

        // Manual refresh forces immediate calculation if hand cards are visible (even dimmed)
        if (forceUnlock && snapshot.handCards.isNotEmpty()) {
            lockAndRenderForTurn(snapshot)
            return
        }

        when (turnState) {
            TurnState.WAITING_OPPONENT -> {
                if (snapshot.isMyTurn && snapshot.handCards.isNotEmpty()) {
                    // Your turn just started! Begin 2-frame consensus stabilization
                    turnState = TurnState.STABILIZING_MY_TURN
                    pendingHandSignature = snapshot.handCards.map { it.steps }
                    stabilizeAttempts = 1
                    missedTurnFrames = 0
                    renderWaitingStatus(snapshot, stabilizing = true)
                } else {
                    // Opponents are playing: NEVER change or jump recommendations; just show calm waiting status
                    renderWaitingStatus(snapshot, stabilizing = false)
                }
            }

            TurnState.STABILIZING_MY_TURN -> {
                if (!snapshot.isMyTurn || snapshot.handCards.isEmpty()) {
                    // False alarm or turn ended before stabilizing
                    turnState = TurnState.WAITING_OPPONENT
                    pendingHandSignature = null
                    stabilizeAttempts = 0
                    renderWaitingStatus(snapshot, stabilizing = false)
                    return
                }
                val currentSig = snapshot.handCards.map { it.steps }
                stabilizeAttempts++
                if (currentSig == pendingHandSignature || stabilizeAttempts >= 3) {
                    // 2 consecutive identical frames reached -> LOCK recommendation for this entire turn!
                    lockAndRenderForTurn(snapshot)
                } else {
                    pendingHandSignature = currentSig
                    renderWaitingStatus(snapshot, stabilizing = true)
                }
            }

            TurnState.LOCKED_MY_TURN -> {
                // Recommendation is FROZEN during your turn (ZERO jumps!).
                // Only unlock when your turn finishes (cards dim or disappear for 2 consecutive polls).
                if (!snapshot.isMyTurn || snapshot.handCards.isEmpty()) {
                    missedTurnFrames++
                    if (missedTurnFrames >= 2) {
                        turnState = TurnState.WAITING_OPPONENT
                        pendingHandSignature = null
                        stabilizeAttempts = 0
                        missedTurnFrames = 0
                        manualCardOverrides = null
                        lockedSnapshot = null
                        renderWaitingStatus(snapshot, stabilizing = false)
                    }
                } else {
                    missedTurnFrames = 0
                }
            }
        }
    }

    private suspend fun lockAndRenderForTurn(snapshot: BoardSnapshot) {
        turnState = TurnState.LOCKED_MY_TURN
        lockedSnapshot = snapshot
        pendingHandSignature = null
        stabilizeAttempts = 0
        missedTurnFrames = 0
        manualCardOverrides = null

        val rec = withContext(Dispatchers.Default) {
            strategyEngine.evaluate(snapshot, settings)
        }
        renderLockedRecommendation(rec)
    }

    private fun renderWaitingStatus(snap: BoardSnapshot, stabilizing: Boolean) {
        // Hide card & tile overlays during opponents' turns so screen stays clean and never jumps!
        highlightView?.update(null, settings)

        val me = snap.player(snap.localPlayer)
        updateSeatBadgeText(snap.localPlayer)

        if (stabilizing) {
            statusText?.text = "🔍 Читаю ваши карты..."
            bestCardBadge?.text = "…"
            bestCardBadge?.setBackgroundResource(R.drawable.bg_chip_normal)
            bestHeadline?.text = "Ваш ход! Проверка карт..."
            bestSubtext?.text = "Фиксирую стабильный расчёт"
            miniPillText?.text = "🔍 Ваш ход · расчёт..."
            return
        }

        statusText?.text = "⏳ Ход соперника · Р.${snap.round}/30 · 💰${me.money}"
        warningText?.visibility = View.GONE
        comboText?.visibility = View.GONE
        bestCardBadge?.text = "⏳"
        bestCardBadge?.setBackgroundResource(R.drawable.bg_chip_normal)
        bestHeadline?.text = if (me.isBankrupt) {
            "Вы обанкротились"
        } else {
            "Ждём ваш ход (соперники ходят)"
        }
        bestSubtext?.text = if (me.position != null) {
            "Позиция: кл.#${me.position} · обновлю совет на вашем ходу"
        } else {
            snap.statusNote.ifBlank { "Откройте «Карточный магнат»" }
        }
        miniPillText?.text = "⏳ Ход соперника · ${snap.localPlayer.shortEmoji} 💰${me.money}"
        movesContainer?.removeAllViews()
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

    private fun recomputeLockedRecommendation() {
        val snap = lockedSnapshot ?: return
        val adjusted = snap.copy(
            localPlayer = settings.preferredSeat ?: snap.localPlayer
        )
        val effective = applyManualOverrides(adjusted)
        serviceScope.launch {
            val rec = withContext(Dispatchers.Default) {
                strategyEngine.evaluate(effective, settings)
            }
            renderLockedRecommendation(rec)
        }
    }

    private fun renderLockedRecommendation(rec: RecommendationResult) {
        highlightView?.update(rec, settings)

        val snap = rec.snapshot
        val me = snap.player(snap.localPlayer)
        val best = rec.bestMove

        updateSeatBadgeText(snap.localPlayer)
        statusText?.text = "🔒 ВАШ ХОД · Р.${snap.round}/30 · 💰${me.money}"

        if (rec.summaryWarning != null) {
            warningText?.visibility = View.VISIBLE
            warningText?.text = "⚠ ${rec.summaryWarning}"
        } else {
            warningText?.visibility = View.GONE
        }

        if (best == null) {
            bestCardBadge?.text = "—"
            bestHeadline?.text = "Карты не распознаны"
            bestSubtext?.text = "Нажмите ↻ для повторного скана"
            comboText?.visibility = View.GONE
            miniPillText?.text = "★ Нажмите ↻ · ${snap.localPlayer.shortEmoji}"
            movesContainer?.removeAllViews()
            return
        }

        miniPillText?.text = "★ ${best.card.label} → ${best.actionHeadline}"

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

            tvCard.setOnClickListener {
                cycleCardStepOverride(snap, move)
            }
            tvCard.setOnLongClickListener {
                manualCardOverrides = null
                recomputeLockedRecommendation()
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
        recomputeLockedRecommendation()
    }

    private fun cyclePreferredSeat() {
        val order = listOf<PlayerColor?>(null, PlayerColor.BLUE, PlayerColor.ORANGE, PlayerColor.GREEN, PlayerColor.RED)
        val idx = order.indexOf(settings.preferredSeat)
        val nextSeat = order[(idx + 1) % order.size]
        settings = settings.copy(preferredSeat = nextSeat)
        settingsRepo.save(settings)
        updateSeatBadgeText(nextSeat)
        recomputeLockedRecommendation()
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
