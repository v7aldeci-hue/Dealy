package com.footsync.radio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Portrait-first dark Arabic RTL smartphone interface for FootSync Radio.
 * Connects to [RadioPlaybackService] for persistent playback, 180s PCM delay control,
 * on-demand 40-band spectral auto-sync, 2-tap manual stopwatch sync, multi-format library
 * management, and optional touch floating overlay.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var libraryManager: LibraryManager
    private lateinit var prefs: FootSyncPrefs

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    private var playbackService: RadioPlaybackService? = null
    private var isServiceBound = false
    private var latestState = ServiceUiState()
    private var selectedLibraryId: String = LibraryManager.MANUAL_LIBRARY_ID
    private var userDraggingSeekBar = false
    private var pendingAutoSyncAfterPermission = false
    private var pendingOverlayAfterPermission = false

    // 2-Tap Manual Stopwatch state
    private var stopwatchActive = false
    private var stopwatchStartMs = 0L
    private var mutedBeforeStopwatch = false
    private val stopwatchTicker = object : Runnable {
        override fun run() {
            if (!stopwatchActive) return
            val elapsedSec = (SystemClock.elapsedRealtime() - stopwatchStartMs).toDouble() / 1000.0
            btnTwoTapSync.text = String.format(
                Locale.US,
                "اضغط عند مطابقة الصوت (%.2f ث)",
                elapsedSec
            )
            uiHandler.postDelayed(this, 33L)
        }
    }

    // UI References
    private lateinit var tvStationTitle: TextView
    private lateinit var tvStationMeta: TextView
    private lateinit var tvConnectionBadge: TextView
    private lateinit var tvSpeedAndBuffer: TextView
    private lateinit var btnFloatingOverlay: TextView

    private lateinit var tvDelayReadout: TextView
    private lateinit var seekDelay: SeekBar
    private lateinit var btnAutoSync: TextView
    private lateinit var btnTwoTapSync: TextView
    private lateinit var syncProgressContainer: LinearLayout
    private lateinit var pbMicLevel: ProgressBar
    private lateinit var tvSyncLiveStatus: TextView
    private lateinit var tvSyncBanner: TextView
    private lateinit var btnApplyCandidate: TextView

    private lateinit var btnTimeout45: TextView
    private lateinit var btnTimeout60: TextView
    private lateinit var btnTimeout80: TextView
    private lateinit var swEarphoneNoMute: Switch

    private lateinit var btnPrev: TextView
    private lateinit var btnPlayPause: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnMute: TextView
    private lateinit var btnResetDelay: TextView

    private lateinit var tvCalibrationValue: TextView

    private lateinit var libraryChipsContainer: LinearLayout
    private lateinit var tvLibraryHeaderCount: TextView
    private lateinit var btnManageActiveLibrary: TextView
    private lateinit var channelsListContainer: LinearLayout

    private val stateListener: (ServiceUiState) -> Unit = { state ->
        latestState = state
        renderServiceState(state)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? RadioPlaybackService.LocalBinder ?: return
            val svc = localBinder.getService()
            playbackService = svc
            isServiceBound = true
            svc.addStateListener(stateListener)
            if (pendingAutoSyncAfterPermission) {
                pendingAutoSyncAfterPermission = false
                svc.startAutoSync()
            }
            if (pendingOverlayAfterPermission && Settings.canDrawOverlays(this@MainActivity)) {
                pendingOverlayAfterPermission = false
                launchOverlayAndBackground()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
        }
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val svc = playbackService
            if (svc != null) {
                svc.startAutoSync()
            } else {
                pendingAutoSyncAfterPermission = true
            }
        } else {
            toast("يلزم إذن الميكروفون لتشغيل المزامنة التلقائية.")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Optional on Android 13+; service remains functional */ }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            launchOverlayAndBackground()
        } else {
            toast("لم يتم منح إذن الظهور فوق التطبيقات.")
        }
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            promptLibraryNameAndImportUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = COLOR_BG
        window.navigationBarColor = COLOR_BG

        libraryManager = LibraryManager(this)
        prefs = FootSyncPrefs(this)
        selectedLibraryId = libraryManager.getActiveLibrary().id

        setContentView(buildRootLayout())
        refreshLibraryUi()
        refreshTimeoutPills()
        refreshCalibrationReadout()
        requestNotificationPermissionIfNeeded()

        val svcIntent = Intent(this, RadioPlaybackService::class.java)
        bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        if (!isServiceBound) {
            val svcIntent = Intent(this, RadioPlaybackService::class.java)
            bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        playbackService?.addStateListener(stateListener)
    }

    override fun onStop() {
        playbackService?.removeStateListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(stopwatchTicker)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (isServiceBound) {
            playbackService?.removeStateListener(stateListener)
            try {
                unbindService(serviceConnection)
            } catch (_: Throwable) {
            }
            isServiceBound = false
        }
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    // === UI Construction ===

    private fun buildRootLayout(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_ALWAYS
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(14), dp(14), dp(28))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(buildHeaderStatusCard())
        root.addView(spaceVertical(12))
        root.addView(buildSyncAndDelayCard())
        root.addView(spaceVertical(12))
        root.addView(buildCalibrationCard())
        root.addView(spaceVertical(12))
        root.addView(buildLibrariesAndChannelsCard())

        scroll.addView(root)
        return scroll
    }

    private fun buildHeaderStatusCard(): LinearLayout {
        val card = makeCard()

        // Top Bar: Brand + Floating Overlay button
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap()
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val appLabel = TextView(this).apply {
            text = "FootSync Radio"
            setTextColor(COLOR_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
        }

        tvStationTitle = TextView(this).apply {
            text = "اختر محطة تعليق للبدء"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        tvStationMeta = TextView(this).apply {
            text = "جاهز للتشغيل والمزامنة عبر الميكروفون"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        titleCol.addView(appLabel)
        titleCol.addView(tvStationTitle)
        titleCol.addView(tvStationMeta)

        btnFloatingOverlay = makePillButton(
            text = "نافذة عائمة",
            bgColor = COLOR_SURFACE_ELEVATED,
            textColor = COLOR_SKY,
            strokeColor = COLOR_SKY_BORDER,
            heightDp = 40
        ).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(40)
            ).apply {
                marginStart = dp(8)
            }
            setOnClickListener { handleFloatingOverlayClick() }
        }

        topRow.addView(titleCol)
        topRow.addView(btnFloatingOverlay)

        // Status & Live Telemetry Row
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap().apply { topMargin = dp(10) }
        }

        tvConnectionBadge = TextView(this).apply {
            text = "متوقف"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedDrawable(COLOR_SURFACE_ELEVATED, Color.TRANSPARENT, 0, dp(20).toFloat())
        }

        tvSpeedAndBuffer = TextView(this).apply {
            text = "السرعة: 0.0 KB/s • الذاكرة: 0 ث"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }

        statusRow.addView(tvConnectionBadge)
        statusRow.addView(tvSpeedAndBuffer)

        card.addView(topRow)
        card.addView(statusRow)
        return card
    }

    private fun buildSyncAndDelayCard(): LinearLayout {
        val card = makeCard()

        val delayLabel = TextView(this).apply {
            text = "التأخير الصوتي الحالي"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            layoutParams = matchWrap()
        }

        tvDelayReadout = TextView(this).apply {
            text = "0.00 ث"
            setTextColor(COLOR_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = matchWrap().apply { topMargin = dp(2) }
        }

        // Primary Auto-Sync + 2-Tap Stopwatch buttons
        val syncButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = matchWrap().apply { topMargin = dp(12) }
        }

        btnAutoSync = makePillButton(
            text = "زامن",
            bgColor = COLOR_ACCENT_DARK,
            textColor = COLOR_TEXT_ON_ACCENT,
            heightDp = 50,
            textSizeSp = 16f
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1.1f)
            setOnClickListener { handleAutoSyncClick() }
        }

        btnTwoTapSync = makePillButton(
            text = "مزامنة بضغطتين",
            bgColor = COLOR_SURFACE_ELEVATED,
            textColor = COLOR_TEXT_PRIMARY,
            strokeColor = COLOR_BORDER,
            heightDp = 50,
            textSizeSp = 13.5f
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener { handleTwoTapStopwatchClick() }
        }

        syncButtonsRow.addView(btnAutoSync)
        syncButtonsRow.addView(btnTwoTapSync)

        // Live Auto-Sync Progress Box (shown only while measuring)
        syncProgressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedDrawable(COLOR_SYNC_BOX_BG, COLOR_WARNING, dp(1), dp(12).toFloat())
            layoutParams = matchWrap().apply { topMargin = dp(10) }
        }

        tvSyncLiveStatus = TextView(this).apply {
            setTextColor(COLOR_WARNING)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = matchWrap()
        }

        val micRowLabel = TextView(this).apply {
            text = "مستوى صوت التلفزيون بالميكروفون"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = matchWrap().apply { topMargin = dp(6) }
        }

        pbMicLevel = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(COLOR_SURFACE_ELEVATED)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply { topMargin = dp(4) }
        }

        syncProgressContainer.addView(tvSyncLiveStatus)
        syncProgressContainer.addView(micRowLabel)
        syncProgressContainer.addView(pbMicLevel)

        // Sync result/info banner + optional unconfirmed candidate apply button
        tvSyncBanner = TextView(this).apply {
            visibility = View.GONE
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedDrawable(COLOR_SURFACE_ELEVATED, COLOR_BORDER, dp(1), dp(10).toFloat())
            layoutParams = matchWrap().apply { topMargin = dp(10) }
        }

        btnApplyCandidate = makePillButton(
            text = "تطبيق المرشّح",
            bgColor = COLOR_WARNING_SOFT,
            textColor = COLOR_WARNING,
            strokeColor = COLOR_WARNING,
            heightDp = 42
        ).apply {
            visibility = View.GONE
            layoutParams = matchWrap().apply { topMargin = dp(8) }
            setOnClickListener {
                val cand = latestState.unconfirmedCandidateSec ?: return@setOnClickListener
                playbackService?.applyUnconfirmedCandidate(cand)
            }
        }

        // Sync Options Row: Timeout Selector (45 / 60 / 80s) + Earphone No-Mute Toggle
        val syncOptionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap().apply { topMargin = dp(12) }
        }

        val timeoutLabel = TextView(this).apply {
            text = "مهلة البحث:"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }

        btnTimeout45 = makeSmallOptionChip("45 ث") {
            prefs.setSyncTimeoutSec(45)
            refreshTimeoutPills()
        }
        btnTimeout60 = makeSmallOptionChip("60 ث") {
            prefs.setSyncTimeoutSec(60)
            refreshTimeoutPills()
        }
        btnTimeout80 = makeSmallOptionChip("80 ث") {
            prefs.setSyncTimeoutSec(80)
            refreshTimeoutPills()
        }

        syncOptionsRow.addView(timeoutLabel)
        syncOptionsRow.addView(btnTimeout45)
        syncOptionsRow.addView(spaceHorizontal(5))
        syncOptionsRow.addView(btnTimeout60)
        syncOptionsRow.addView(spaceHorizontal(5))
        syncOptionsRow.addView(btnTimeout80)

        val earphoneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap().apply { topMargin = dp(8) }
        }

        val earphoneLabel = TextView(this).apply {
            text = "سماعة أذن (بدون كتم أثناء المزامنة)"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        swEarphoneNoMute = Switch(this).apply {
            isChecked = prefs.isEarphoneNoMuteEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                prefs.setEarphoneNoMuteEnabled(isChecked)
            }
        }

        earphoneRow.addView(earphoneLabel)
        earphoneRow.addView(swEarphoneNoMute)

        // Delay SeekBar (0..180s in 0.1s increments)
        val seekHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = matchWrap().apply { topMargin = dp(14) }
        }
        seekHeader.addView(TextView(this).apply {
            text = "0 ث"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        seekHeader.addView(TextView(this).apply {
            text = "180 ث (3 دقائق)"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        seekDelay = SeekBar(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            max = 1800 // 0.0s .. 180.0s (0.1s resolution)
            progress = 0
            thumbTintList = ColorStateList.valueOf(COLOR_ACCENT)
            progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(COLOR_SURFACE_ELEVATED)
            layoutParams = matchWrap().apply { topMargin = dp(4) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val sec = progress.toDouble() / 10.0
                        tvDelayReadout.text = String.format(Locale.US, "%.2f ث", sec)
                        playbackService?.setDelaySeconds(sec, persistForUrl = false)
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    userDraggingSeekBar = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userDraggingSeekBar = false
                    val sec = (sb?.progress ?: 0).toDouble() / 10.0
                    playbackService?.setDelaySeconds(sec, persistForUrl = true)
                }
            })
        }

        // Fine-tuning pills: -10s, -1s, -0.25s, +0.25s, +1s, +10s
        val tuneRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = matchWrap().apply { topMargin = dp(10) }
        }
        listOf("-10 ث" to -10.0, "-1 ث" to -1.0, "-0.25 ث" to -0.25).forEachIndexed { idx, (lbl, delta) ->
            tuneRow1.addView(
                makePillButton(lbl, COLOR_SURFACE_ELEVATED, COLOR_TEXT_PRIMARY, heightDp = 40).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                        if (idx > 0) marginStart = dp(6)
                    }
                    setOnClickListener { playbackService?.adjustDelaySeconds(delta) }
                }
            )
        }

        val tuneRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = matchWrap().apply { topMargin = dp(6) }
        }
        listOf("+0.25 ث" to 0.25, "+1 ث" to 1.0, "+10 ث" to 10.0).forEachIndexed { idx, (lbl, delta) ->
            tuneRow2.addView(
                makePillButton(lbl, COLOR_SURFACE_ELEVATED, COLOR_TEXT_PRIMARY, heightDp = 40).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                        if (idx > 0) marginStart = dp(6)
                    }
                    setOnClickListener { playbackService?.adjustDelaySeconds(delta) }
                }
            )
        }

        // Playback Navigation & Audio Action Row
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = matchWrap().apply { topMargin = dp(12) }
        }

        btnPrev = makePillButton("السابق", COLOR_SURFACE_ELEVATED, COLOR_TEXT_PRIMARY, heightDp = 44).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener {
                ensureServiceStarted()
                val stepped = playbackService?.stepChannel(-1)
                if (stepped == null) {
                    toast("لا توجد قنوات في المكتبة النشطة.")
                }
            }
        }

        btnPlayPause = makePillButton("تشغيل", COLOR_SKY_DARK, Color.WHITE, heightDp = 44).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1.25f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
            setOnClickListener {
                ensureServiceStarted()
                playbackService?.togglePlayPause()
            }
        }

        btnNext = makePillButton("التالي", COLOR_SURFACE_ELEVATED, COLOR_TEXT_PRIMARY, heightDp = 44).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener {
                ensureServiceStarted()
                val stepped = playbackService?.stepChannel(+1)
                if (stepped == null) {
                    toast("لا توجد قنوات في المكتبة النشطة.")
                }
            }
        }

        navRow.addView(btnPrev)
        navRow.addView(btnPlayPause)
        navRow.addView(btnNext)

        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = matchWrap().apply { topMargin = dp(8) }
        }

        btnMute = makePillButton("كتم الصوت", COLOR_SURFACE_ELEVATED, COLOR_TEXT_PRIMARY, heightDp = 42).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            setOnClickListener { playbackService?.toggleMute() }
        }

        btnResetDelay = makePillButton("تصفير (0.00 ث)", COLOR_SURFACE_ELEVATED, COLOR_TEXT_SECONDARY, heightDp = 42).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener { playbackService?.resetDelay() }
        }

        utilityRow.addView(btnMute)
        utilityRow.addView(btnResetDelay)

        card.addView(delayLabel)
        card.addView(tvDelayReadout)
        card.addView(syncButtonsRow)
        card.addView(syncProgressContainer)
        card.addView(tvSyncBanner)
        card.addView(btnApplyCandidate)
        card.addView(syncOptionsRow)
        card.addView(earphoneRow)
        card.addView(seekHeader)
        card.addView(seekDelay)
        card.addView(tuneRow1)
        card.addView(tuneRow2)
        card.addView(navRow)
        card.addView(utilityRow)
        return card
    }

    private fun buildCalibrationCard(): LinearLayout {
        val card = makeCard()

        val title = TextView(this).apply {
            text = "معايرة السماعة / البلوتوث"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val subtitle = TextView(this).apply {
            text = "تصحيح ثابت يُضاف تلقائياً بعد كل مزامنة (-2.00 إلى +2.00 ث)"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = matchWrap().apply { topMargin = dp(2) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = matchWrap().apply { topMargin = dp(10) }
        }

        val btnMinus = makePillButton("-0.05 ث", COLOR_SURFACE_ELEVATED, COLOR_TEXT_PRIMARY, heightDp = 40).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            setOnClickListener { adjustCalibration(-0.05) }
        }

        tvCalibrationValue = TextView(this).apply {
            setTextColor(COLOR_SKY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f)
            setOnClickListener {
                prefs.setCalibrationTrimSec(0.0)
                refreshCalibrationReadout()
                toast("تم تصفير المعايرة.")
            }
        }

        val btnPlus = makePillButton("+0.05 ث", COLOR_SURFACE_ELEVATED, COLOR_TEXT_PRIMARY, heightDp = 40).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            setOnClickListener { adjustCalibration(+0.05) }
        }

        row.addView(btnMinus)
        row.addView(tvCalibrationValue)
        row.addView(btnPlus)

        card.addView(title)
        card.addView(subtitle)
        card.addView(row)
        return card
    }

    private fun buildLibrariesAndChannelsCard(): LinearLayout {
        val card = makeCard()

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap()
        }

        val headerTitle = TextView(this).apply {
            text = "المكتبات الصوتية والقنوات"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnAddManual = makePillButton(
            text = "+ رابط",
            bgColor = COLOR_SURFACE_ELEVATED,
            textColor = COLOR_TEXT_PRIMARY,
            heightDp = 36,
            textSizeSp = 12.5f
        ).apply {
            setPadding(dp(11), dp(6), dp(11), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply { marginEnd = dp(6) }
            setOnClickListener { showAddManualUrlDialog() }
        }

        val btnAddLibrary = makePillButton(
            text = "إضافة مكتبة",
            bgColor = COLOR_ACCENT_DARK,
            textColor = COLOR_TEXT_ON_ACCENT,
            heightDp = 36,
            textSizeSp = 12.5f
        ).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            )
            setOnClickListener { showAddLibraryChooserDialog() }
        }

        headerRow.addView(headerTitle)
        headerRow.addView(btnAddManual)
        headerRow.addView(btnAddLibrary)

        // Horizontal selectable library chips
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = matchWrap().apply { topMargin = dp(10) }
        }

        libraryChipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        chipsScroll.addView(libraryChipsContainer)

        // Active library subheader + rename/delete action
        val activeLibRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap().apply { topMargin = dp(10) }
        }

        tvLibraryHeaderCount = TextView(this).apply {
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnManageActiveLibrary = TextView(this).apply {
            text = "إدارة المكتبة"
            setTextColor(COLOR_SKY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { showManageLibraryDialog(selectedLibraryId) }
        }

        activeLibRow.addView(tvLibraryHeaderCount)
        activeLibRow.addView(btnManageActiveLibrary)

        // Sequential numbered channel rows
        channelsListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap().apply { topMargin = dp(8) }
        }

        card.addView(headerRow)
        card.addView(chipsScroll)
        card.addView(activeLibRow)
        card.addView(channelsListContainer)
        return card
    }

    // === State Rendering ===

    private fun renderServiceState(state: ServiceUiState) {
        tvStationTitle.text = state.channelName
        tvStationMeta.text = if (state.channelIndex >= 0 && state.totalChannels > 0) {
            "${state.libraryName} • قناة ${state.channelIndex + 1} من ${state.totalChannels}"
        } else {
            "اختر قناة من المكتبات أو أضف رابط بث مباشر"
        }

        tvConnectionBadge.text = state.connectionStatus
        val badgeColor = when {
            state.isPlaying && !state.isBuffering -> COLOR_ACCENT
            state.isBuffering -> COLOR_WARNING
            else -> COLOR_TEXT_SECONDARY
        }
        tvConnectionBadge.setTextColor(badgeColor)

        tvSpeedAndBuffer.text = String.format(
            Locale.US,
            "السرعة: %.1f KB/s • الذاكرة: %d ث",
            state.speedKbps,
            state.bufferedHistorySec.toInt()
        )

        if (!userDraggingSeekBar) {
            tvDelayReadout.text = String.format(Locale.US, "%.2f ث", state.delaySec)
            seekDelay.progress = (state.delaySec * 10.0).roundToInt().coerceIn(0, seekDelay.max)
        }

        btnPlayPause.text = if (state.isPlaying || state.isBuffering) "إيقاف مؤقت" else "تشغيل"

        btnMute.text = if (state.isMuted) "إلغاء الكتم" else "كتم الصوت"
        btnMute.background = rippleButtonDrawable(
            if (state.isMuted) COLOR_WARNING_SOFT else COLOR_SURFACE_ELEVATED,
            if (state.isMuted) COLOR_WARNING else Color.TRANSPARENT,
            dp(12).toFloat()
        )
        btnMute.setTextColor(if (state.isMuted) COLOR_WARNING else COLOR_TEXT_PRIMARY)

        btnFloatingOverlay.text = if (state.isOverlayVisible) "إخفاء العائمة" else "نافذة عائمة"

        // Keep screen awake ONLY while auto-sync is actively measuring
        if (state.isSyncing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (state.isSyncing) {
            btnAutoSync.text = "إلغاء المزامنة"
            btnAutoSync.background = rippleButtonDrawable(COLOR_DANGER, Color.TRANSPARENT, dp(12).toFloat())
            btnAutoSync.setTextColor(Color.WHITE)
            syncProgressContainer.visibility = View.VISIBLE
            tvSyncLiveStatus.text = state.syncStatusText.ifBlank { "جاري الاستماع والمطابقة…" }
            pbMicLevel.progress = (state.syncMicLevel * 100f).roundToInt().coerceIn(0, 100)
        } else {
            btnAutoSync.text = "زامن"
            btnAutoSync.background = rippleButtonDrawable(COLOR_ACCENT_DARK, Color.TRANSPARENT, dp(12).toFloat())
            btnAutoSync.setTextColor(COLOR_TEXT_ON_ACCENT)
            syncProgressContainer.visibility = View.GONE
        }

        if (state.lastSyncBannerText.isNotBlank()) {
            tvSyncBanner.text = state.lastSyncBannerText
            tvSyncBanner.visibility = View.VISIBLE
        } else {
            tvSyncBanner.visibility = View.GONE
        }

        val unconfirmed = state.unconfirmedCandidateSec
        if (!state.isSyncing && unconfirmed != null) {
            btnApplyCandidate.text = String.format(Locale.US, "تطبيق المرشّح %.1f ث", unconfirmed)
            btnApplyCandidate.visibility = View.VISIBLE
        } else {
            btnApplyCandidate.visibility = View.GONE
        }

        // Keep active library channel highlight synchronized with service
        renderChannelsListOnly()
    }

    private fun refreshLibraryUi() {
        val libs = libraryManager.getLibraries()
        if (libs.none { it.id == selectedLibraryId }) {
            selectedLibraryId = libs.first().id
        }

        libraryChipsContainer.removeAllViews()
        libs.forEachIndexed { index, lib ->
            val isSelected = lib.id == selectedLibraryId
            val chip = TextView(this).apply {
                text = "${lib.name} (${lib.channels.size})"
                setTextColor(if (isSelected) COLOR_TEXT_ON_ACCENT else COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = rippleButtonDrawable(
                    fillColor = if (isSelected) COLOR_ACCENT else COLOR_SURFACE_ELEVATED,
                    strokeColor = if (isSelected) COLOR_ACCENT else COLOR_BORDER,
                    radiusPx = dp(20).toFloat()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) marginStart = dp(8)
                }
                setOnClickListener {
                    selectedLibraryId = lib.id
                    libraryManager.setActiveLibrary(lib.id)
                    refreshLibraryUi()
                }
                setOnLongClickListener {
                    if (!lib.isManualDefault) {
                        showManageLibraryDialog(lib.id)
                        true
                    } else {
                        false
                    }
                }
            }
            libraryChipsContainer.addView(chip)
        }

        renderChannelsListOnly()
    }

    private fun renderChannelsListOnly() {
        val activeLib = libraryManager.getLibraryById(selectedLibraryId) ?: libraryManager.getActiveLibrary()
        selectedLibraryId = activeLib.id

        tvLibraryHeaderCount.text = "المكتبة النشطة: ${activeLib.name} • ${activeLib.channels.size} قناة بالترتيب"
        btnManageActiveLibrary.visibility = if (activeLib.isManualDefault) View.GONE else View.VISIBLE

        channelsListContainer.removeAllViews()
        if (activeLib.channels.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = if (activeLib.isManualDefault) {
                    "لا توجد روابط يدوية بعد. اضغط \"+ رابط\" لإضافة بث مباشر أو \"إضافة مكتبة\" لاستيراد ملف M3U / JSON / XML / TXT."
                } else {
                    "هذه المكتبة فارغة."
                }
                setTextColor(COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(20), dp(12), dp(20))
            }
            channelsListContainer.addView(emptyView)
            return
        }

        activeLib.channels.forEachIndexed { idx, channel ->
            val isCurrentlyPlaying = latestState.libraryId == activeLib.id &&
                latestState.channelIndex == idx &&
                latestState.channelUrl == channel.url

            val savedDelay = prefs.getDelayForUrl(channel.url)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rippleButtonDrawable(
                    fillColor = if (isCurrentlyPlaying) COLOR_ACTIVE_ROW_BG else COLOR_SURFACE_ELEVATED,
                    strokeColor = if (isCurrentlyPlaying) COLOR_ACCENT else COLOR_BORDER,
                    radiusPx = dp(12).toFloat()
                )
                layoutParams = matchWrap().apply {
                    if (idx > 0) topMargin = dp(7)
                }
                setOnClickListener {
                    ensureServiceStarted()
                    playbackService?.playChannel(activeLib.id, idx)
                }
                setOnLongClickListener {
                    confirmDeleteChannel(activeLib, idx, channel)
                    true
                }
            }

            // Numbered sequential badge (1, 2, 3...)
            val numBadge = TextView(this).apply {
                text = "${idx + 1}"
                setTextColor(if (isCurrentlyPlaying) COLOR_TEXT_ON_ACCENT else COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = roundedDrawable(
                    fillColor = if (isCurrentlyPlaying) COLOR_ACCENT else COLOR_BG,
                    strokeColor = Color.TRANSPARENT,
                    strokeWidthPx = 0,
                    radiusPx = dp(8).toFloat()
                )
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                    marginEnd = dp(10)
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(this).apply {
                text = channel.name
                setTextColor(if (isCurrentlyPlaying) COLOR_ACCENT else COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = if (isCurrentlyPlaying) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val subView = TextView(this).apply {
                text = if (isCurrentlyPlaying) "قيد التشغيل الآن" else channel.url
                setTextColor(if (isCurrentlyPlaying) COLOR_SKY else COLOR_TEXT_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            textCol.addView(nameView)
            textCol.addView(subView)

            row.addView(numBadge)
            row.addView(textCol)

            if (savedDelay > 0.005) {
                val delayTag = TextView(this).apply {
                    text = String.format(Locale.US, "%.2f ث", savedDelay)
                    setTextColor(COLOR_WARNING)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = roundedDrawable(COLOR_WARNING_SOFT, Color.TRANSPARENT, 0, dp(8).toFloat())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8) }
                }
                row.addView(delayTag)
            }

            channelsListContainer.addView(row)
        }
    }

    private fun refreshTimeoutPills() {
        val current = prefs.getSyncTimeoutSec()
        val pairs = listOf(45 to btnTimeout45, 60 to btnTimeout60, 80 to btnTimeout80)
        for ((sec, view) in pairs) {
            val active = sec == current
            view.setTextColor(if (active) COLOR_TEXT_ON_ACCENT else COLOR_TEXT_SECONDARY)
            view.background = rippleButtonDrawable(
                fillColor = if (active) COLOR_ACCENT else COLOR_SURFACE_ELEVATED,
                strokeColor = if (active) COLOR_ACCENT else COLOR_BORDER,
                radiusPx = dp(10).toFloat()
            )
        }
    }

    private fun adjustCalibration(deltaSec: Double) {
        val updated = ((prefs.getCalibrationTrimSec() + deltaSec) * 20.0).roundToInt() / 20.0
        prefs.setCalibrationTrimSec(updated.coerceIn(-2.0, 2.0))
        refreshCalibrationReadout()
    }

    private fun refreshCalibrationReadout() {
        val v = prefs.getCalibrationTrimSec()
        tvCalibrationValue.text = if (abs(v) < 0.001) {
            "0.00 ث"
        } else {
            String.format(Locale.US, "%+.2f ث", v)
        }
    }

    // === Actions & Dialogs ===

    private fun handleAutoSyncClick() {
        val svc = playbackService
        if (svc == null) {
            toast("جاري تهيئة مشغّل الصوت…")
            return
        }
        if (latestState.isSyncing) {
            svc.cancelAutoSync()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        svc.startAutoSync()
    }

    private fun handleTwoTapStopwatchClick() {
        val svc = playbackService ?: return
        if (!stopwatchActive) {
            if (!latestState.isPlaying && !latestState.isBuffering) {
                toast("شغّل قناة إذاعية أولاً قبل المزامنة اليدوية.")
                return
            }
            stopwatchActive = true
            stopwatchStartMs = SystemClock.elapsedRealtime()
            mutedBeforeStopwatch = latestState.isMuted
            svc.setMuted(true)
            btnTwoTapSync.background = rippleButtonDrawable(
                COLOR_WARNING_SOFT,
                COLOR_WARNING,
                dp(12).toFloat()
            )
            btnTwoTapSync.setTextColor(COLOR_WARNING)
            uiHandler.post(stopwatchTicker)
        } else {
            stopwatchActive = false
            uiHandler.removeCallbacks(stopwatchTicker)
            val elapsedSec = (SystemClock.elapsedRealtime() - stopwatchStartMs).toDouble() / 1000.0
            val newDelay = (latestState.delaySec + elapsedSec).coerceIn(0.0, FootSyncPrefs.MAX_DELAY_SEC)
            svc.setDelaySeconds(newDelay, persistForUrl = true)
            svc.setMuted(false)
            btnTwoTapSync.text = "مزامنة بضغطتين"
            btnTwoTapSync.background = rippleButtonDrawable(
                COLOR_SURFACE_ELEVATED,
                COLOR_BORDER,
                dp(12).toFloat()
            )
            btnTwoTapSync.setTextColor(COLOR_TEXT_PRIMARY)
            toast(String.format(Locale.US, "تمت إضافة %.2f ث • التأخير الآن %.2f ث", elapsedSec, newDelay))
        }
    }

    private fun handleFloatingOverlayClick() {
        val svc = playbackService ?: return
        if (latestState.isOverlayVisible) {
            svc.hideFloatingOverlay()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            pendingOverlayAfterPermission = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        launchOverlayAndBackground()
    }

    private fun launchOverlayAndBackground() {
        ensureServiceStarted()
        val shown = playbackService?.showFloatingOverlay(startMinimized = false) == true
        if (shown) {
            moveTaskToBack(true)
        } else {
            toast("تعذّر إظهار النافذة العائمة.")
        }
    }

    private fun showAddLibraryChooserDialog() {
        val options = arrayOf(
            "استيراد ملف من الهاتف (M3U / JSON / XML / TXT / CFG)",
            "استيراد من رابط قائمة (URL)",
            "لصق نص القائمة مباشرة"
        )
        AlertDialog.Builder(this)
            .setTitle("إضافة مكتبة صوتية")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openDocumentLauncher.launch(arrayOf("*/*"))
                    1 -> showImportUrlDialog()
                    2 -> showImportRawTextDialog()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun promptLibraryNameAndImportUri(uri: Uri) {
        val defaultName = libraryManager.queryDisplayName(uri)
        val input = makeDialogEditText("اسم المكتبة", defaultName)

        AlertDialog.Builder(this)
            .setTitle("استيراد مكتبة من ملف")
            .setView(wrapDialogView(input))
            .setPositiveButton("استيراد") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { defaultName }
                toast("جاري قراءة وتحليل الملف…")
                ioExecutor.execute {
                    val res = libraryManager.importFromUri(uri, name)
                    runOnUiThread { handleImportResult(res) }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showImportUrlDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val etName = makeDialogEditText("اسم المكتبة (اختياري)", "")
        val etUrl = makeDialogEditText("رابط القائمة (http:// أو https://)", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(etName)
        container.addView(spaceVertical(8))
        container.addView(etUrl)

        AlertDialog.Builder(this)
            .setTitle("استيراد مكتبة من رابط")
            .setView(container)
            .setPositiveButton("تحميل واستيراد") { _, _ ->
                val url = etUrl.text.toString().trim()
                val name = etName.text.toString().trim().ifEmpty { null }
                if (url.isEmpty()) {
                    toast("أدخل رابط القائمة أولاً.")
                    return@setPositiveButton
                }
                toast("جاري تحميل القائمة…")
                ioExecutor.execute {
                    val res = libraryManager.importFromUrl(url, name)
                    runOnUiThread { handleImportResult(res) }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showImportRawTextDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val etName = makeDialogEditText("اسم المكتبة", "مكتبة جديدة")
        val etText = makeDialogEditText("الصق محتوى M3U أو JSON أو XML أو روابط TXT هنا", "").apply {
            minLines = 5
            maxLines = 9
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        container.addView(etName)
        container.addView(spaceVertical(8))
        container.addView(etText)

        AlertDialog.Builder(this)
            .setTitle("لصق نص القائمة")
            .setView(container)
            .setPositiveButton("حفظ المكتبة") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { "مكتبة ملصوقة" }
                val raw = etText.text.toString()
                ioExecutor.execute {
                    val res = libraryManager.importFromRawText(name, raw)
                    runOnUiThread { handleImportResult(res) }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showAddManualUrlDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val etName = makeDialogEditText("اسم المحطة (اختياري)", "")
        val etUrl = makeDialogEditText("رابط البث المباشر (http:// / https://)", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(etName)
        container.addView(spaceVertical(8))
        container.addView(etUrl)

        AlertDialog.Builder(this)
            .setTitle("إضافة رابط بث يدوي")
            .setView(container)
            .setPositiveButton("تشغيل وحفظ") { _, _ ->
                val url = etUrl.text.toString().trim()
                val name = etName.text.toString().trim().ifEmpty { null }
                if (!url.startsWith("http://", ignoreCase = true) &&
                    !url.startsWith("https://", ignoreCase = true) &&
                    !url.startsWith("rtsp://", ignoreCase = true) &&
                    !url.startsWith("rtmp://", ignoreCase = true)
                ) {
                    toast("أدخل رابط بث صالح يبدأ بـ http:// أو https://")
                    return@setPositiveButton
                }
                val target = libraryManager.addManualChannel(url, name)
                selectedLibraryId = target.libraryId
                refreshLibraryUi()
                ensureServiceStarted()
                playbackService?.playTarget(target)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun handleImportResult(res: ImportResult) {
        if (res.success && res.library != null) {
            selectedLibraryId = res.library.id
            refreshLibraryUi()
            toast("تم استيراد ${res.channelCount} قناة (${res.detectedFormat}) إلى \"${res.library.name}\"")
        } else {
            toast(res.errorMessage ?: "فشل استيراد المكتبة.")
        }
    }

    private fun showManageLibraryDialog(libraryId: String) {
        val lib = libraryManager.getLibraryById(libraryId) ?: return
        if (lib.isManualDefault) return

        val actions = arrayOf("إعادة تسمية المكتبة", "حذف المكتبة")
        AlertDialog.Builder(this)
            .setTitle(lib.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        val input = makeDialogEditText("الاسم الجديد", lib.name)
                        AlertDialog.Builder(this)
                            .setTitle("إعادة تسمية المكتبة")
                            .setView(wrapDialogView(input))
                            .setPositiveButton("حفظ") { _, _ ->
                                if (libraryManager.renameLibrary(lib.id, input.text.toString())) {
                                    refreshLibraryUi()
                                }
                            }
                            .setNegativeButton("إلغاء", null)
                            .show()
                    }

                    1 -> {
                        AlertDialog.Builder(this)
                            .setTitle("حذف المكتبة")
                            .setMessage("هل تريد حذف مكتبة \"${lib.name}\" وجميع قنواتها (${lib.channels.size})؟")
                            .setPositiveButton("حذف") { _, _ ->
                                if (libraryManager.deleteLibrary(lib.id)) {
                                    selectedLibraryId = libraryManager.getActiveLibrary().id
                                    refreshLibraryUi()
                                    toast("تم حذف المكتبة.")
                                }
                            }
                            .setNegativeButton("إلغاء", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun confirmDeleteChannel(lib: RadioLibrary, index: Int, channel: RadioChannel) {
        AlertDialog.Builder(this)
            .setTitle("حذف القناة")
            .setMessage("حذف \"${channel.name}\" من مكتبة \"${lib.name}\"؟")
            .setPositiveButton("حذف") { _, _ ->
                if (libraryManager.deleteChannel(lib.id, index)) {
                    refreshLibraryUi()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun ensureServiceStarted() {
        val intent = Intent(this, RadioPlaybackService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (_: Throwable) {
            try {
                startService(intent)
            } catch (_: Throwable) {
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // === Styling Helpers ===

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedDrawable(COLOR_CARD, COLOR_BORDER, dp(1), dp(18).toFloat())
            layoutParams = matchWrap()
        }
    }

    private fun makePillButton(
        text: String,
        bgColor: Int,
        textColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        heightDp: Int = 44,
        textSizeSp: Float = 13.5f
    ): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(heightDp)
            background = rippleButtonDrawable(bgColor, strokeColor, dp(12).toFloat())
        }
    }

    private fun makeSmallOptionChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun makeDialogEditText(hintText: String, initialValue: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setText(initialValue)
            setSingleLine(true)
            setSelection(text.length)
        }
    }

    private fun wrapDialogView(view: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(view)
        }
    }

    private fun roundedDrawable(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Int,
        radiusPx: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fillColor)
            if (strokeWidthPx > 0 && strokeColor != Color.TRANSPARENT) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    private fun rippleButtonDrawable(
        fillColor: Int,
        strokeColor: Int,
        radiusPx: Float
    ): RippleDrawable {
        val content = roundedDrawable(
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidthPx = if (strokeColor != Color.TRANSPARENT) dp(1) else 0,
            radiusPx = radiusPx
        )
        return RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#30FFFFFF")),
            content,
            null
        )
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun spaceVertical(dpVal: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(dpVal))
        }
    }

    private fun spaceHorizontal(dpVal: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(dpVal), 1)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val COLOR_BG = Color.parseColor("#0B1120")
        private val COLOR_CARD = Color.parseColor("#111827")
        private val COLOR_SURFACE_ELEVATED = Color.parseColor("#1E293B")
        private val COLOR_BORDER = Color.parseColor("#2A374C")
        private val COLOR_ACTIVE_ROW_BG = Color.parseColor("#132A23")
        private val COLOR_SYNC_BOX_BG = Color.parseColor("#241E12")
        private val COLOR_ACCENT = Color.parseColor("#22C55E")
        private val COLOR_ACCENT_DARK = Color.parseColor("#16A34A")
        private val COLOR_TEXT_ON_ACCENT = Color.parseColor("#04150A")
        private val COLOR_SKY = Color.parseColor("#38BDF8")
        private val COLOR_SKY_DARK = Color.parseColor("#0284C7")
        private val COLOR_SKY_BORDER = Color.parseColor("#4438BDF8")
        private val COLOR_WARNING = Color.parseColor("#FBBF24")
        private val COLOR_WARNING_SOFT = Color.parseColor("#2EFBBF24")
        private val COLOR_DANGER = Color.parseColor("#EF4444")
        private val COLOR_TEXT_PRIMARY = Color.parseColor("#F8FAFC")
        private val COLOR_TEXT_SECONDARY = Color.parseColor("#94A3B8")
        private val COLOR_TEXT_MUTED = Color.parseColor("#64748B")
    }
}
