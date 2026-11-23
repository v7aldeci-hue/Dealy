package com.footsync.radio

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 3-page dark Shazam-styled smartphone UI for FootSync Radio:
 * - Page 0 (Left, Marked "3"): Libraries 2-column card grid + in-page sequential channel browser.
 * - Page 1 (Center, Marked "1", Default Home): Hero pulsing circular Auto-Sync screen + quick controls.
 * - Page 2 (Right, Marked "2"): Delay & Controls (dashed direct numeric input, seekbar, step grid,
 *   2-tap stopwatch, earphone toggle, timeout selector, speaker/Bluetooth calibration trim).
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
    private var browsingLibraryId: String? = null
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
            tvStopwatchDisplay.text = String.format(Locale.US, "%.2f ث", elapsedSec)
            btnTwoTapSync.text = "اضغط عند مطابقة الصوت"
            uiHandler.postDelayed(this, 33L)
        }
    }

    // Outer 3-page horizontal snap pager (index 0 = Left Page 3, index 1 = Center Page 1, index 2 = Right Page 2)
    private lateinit var pager: SnapHorizontalPager

    // === Page 1 (Center — Home) UI references ===
    private lateinit var tvHomePrompt: TextView
    private lateinit var tvHomeStationPill: TextView
    private lateinit var tvHomeDelayPill: TextView
    private lateinit var homeStatusDot: View
    private lateinit var pulseRingView: PulseRingView
    private lateinit var heroCircleButton: FrameLayout
    private lateinit var heroEmblemView: VectorIconView
    private lateinit var tvHomeSyncSubtext: TextView
    private lateinit var btnHomePlayPause: FrameLayout
    private lateinit var icHomePlayPause: VectorIconView
    private lateinit var lblHomePlayPause: TextView
    private lateinit var btnHomeMute: FrameLayout
    private lateinit var icHomeMute: VectorIconView
    private lateinit var lblHomeMute: TextView
    private lateinit var btnHomeOverlay: FrameLayout
    private lateinit var icHomeOverlay: VectorIconView
    private lateinit var tvHomeBanner: TextView
    private lateinit var btnHomeApplyCandidate: TextView

    // === Page 2 (Right — Delay & Controls) UI references ===
    private lateinit var tvDelayReadout: TextView
    private lateinit var tvDelayBufferInfo: TextView
    private lateinit var etDirectDelay: EditText
    private lateinit var seekDelay: SeekBar
    private lateinit var tvStopwatchDisplay: TextView
    private lateinit var btnTwoTapSync: TextView
    private lateinit var swEarphoneNoMute: Switch
    private lateinit var btnTimeout45: TextView
    private lateinit var btnTimeout60: TextView
    private lateinit var btnTimeout80: TextView
    private lateinit var tvCalibrationValue: TextView

    // === Page 3 (Left — Libraries & In-Page Channels) UI references ===
    private lateinit var tvLibrariesSubtitle: TextView
    private lateinit var librariesGridScroll: ScrollView
    private lateinit var librariesGridContainer: LinearLayout
    private lateinit var channelBrowserContainer: LinearLayout
    private lateinit var tvChannelBrowserTitle: TextView
    private lateinit var tvChannelBrowserCount: TextView
    private lateinit var btnChannelBrowserManage: TextView
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

        window.statusBarColor = COLOR_HOME_BG
        window.navigationBarColor = COLOR_HOME_BG

        libraryManager = LibraryManager(this)
        prefs = FootSyncPrefs(this)
        selectedLibraryId = libraryManager.getActiveLibrary().id

        setContentView(buildRootPagerLayout())

        refreshLibrariesPageUi()
        refreshTimeoutPills()
        refreshCalibrationReadout()
        requestNotificationPermissionIfNeeded()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    pager.currentPage == PAGE_LIBRARIES && browsingLibraryId != null -> {
                        browsingLibraryId = null
                        refreshLibrariesPageUi()
                    }

                    pager.currentPage != PAGE_HOME -> {
                        pager.scrollToPage(PAGE_HOME, animated = true)
                    }

                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

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
        pulseRingView.resumeAnimation()
    }

    override fun onStop() {
        pulseRingView.pauseAnimation()
        playbackService?.removeStateListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(stopwatchTicker)
        pulseRingView.pauseAnimation()
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

    // =========================================================================
    // 3-PAGE HORIZONTAL SNAP PAGER ROOT (LTR outer order: Left=3, Center=1, Right=2)
    // =========================================================================

    private fun buildRootPagerLayout(): View {
        val pageLeftLibraries = buildPage3Libraries()
        val pageCenterHome = buildPage1CenterHome()
        val pageRightDelay = buildPage2DelayControls()

        pager = SnapHorizontalPager(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPages(
                listOf(pageLeftLibraries, pageCenterHome, pageRightDelay),
                initialPage = PAGE_HOME
            )
            onPageChanged = { pageIdx ->
                val barColor = if (pageIdx == PAGE_HOME) COLOR_HOME_BG else COLOR_DARK_SURFACE_BG
                window.statusBarColor = barColor
                window.navigationBarColor = barColor
                if (pageIdx != PAGE_DELAY) {
                    hideKeyboard()
                }
            }
        }
        return pager
    }

    // =========================================================================
    // PAGE 1 (CENTER — Default Home Screen, Marked "1")
    // =========================================================================

    private fun buildPage1CenterHome(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(COLOR_HOME_BG)
            setPadding(dp(18), dp(14), dp(18), dp(22))
        }

        // Top Navigation Bar (LTR): Left = المكتبات, Center = • • •, Right = التأخير
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val leftNavBtn = buildTopNavTab(IconType.LIBRARY, "المكتبات") {
            pager.scrollToPage(PAGE_LIBRARIES, animated = true)
        }

        val centerDotsHolder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(
                buildPaginationDots(activeIndex = 1),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        val rightNavBtn = buildTopNavTab(IconType.DELAY, "التأخير") {
            pager.scrollToPage(PAGE_DELAY, animated = true)
        }

        topBar.addView(leftNavBtn)
        topBar.addView(centerDotsHolder)
        topBar.addView(rightNavBtn)

        // Center Hero Column (Prompt + Active Station Pill + 184dp Pulsing Sync Circle)
        val centerArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        centerArea.addView(spaceWeight(0.42f))

        // Header Prompt: Headphone vector icon + bold white "اضغط للمزامنة"
        val promptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            layoutParams = matchWrap()
        }

        val headphoneIcon = VectorIconView(this, IconType.HEADPHONES, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                marginEnd = dp(8)
            }
        }

        tvHomePrompt = TextView(this).apply {
            text = "اضغط للمزامنة"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        promptRow.addView(headphoneIcon)
        promptRow.addView(tvHomePrompt)
        centerArea.addView(promptRow)

        // Subtle Pill showing Active Station Name + Live Delay
        val stationPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = roundedDrawable(
                fillColor = Color.parseColor("#18202C"),
                strokeColor = Color.parseColor("#2B3545"),
                strokeWidthPx = dp(1),
                radiusPx = dp(20).toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            setOnClickListener { pager.scrollToPage(PAGE_LIBRARIES, animated = true) }
        }
        applySpringTouch(stationPill)

        homeStatusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
            }
            background = roundedDrawable(COLOR_TEXT_MUTED, Color.TRANSPARENT, 0, dp(4).toFloat())
        }

        tvHomeStationPill = TextView(this).apply {
            text = "اختر محطة تعليق"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(175)
        }

        val pillDivider = TextView(this).apply {
            text = "  •  "
            setTextColor(COLOR_TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }

        tvHomeDelayPill = TextView(this).apply {
            text = "0.00 ث"
            setTextColor(COLOR_SHAZAM_BLUE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        stationPill.addView(homeStatusDot)
        stationPill.addView(tvHomeStationPill)
        stationPill.addView(pillDivider)
        stationPill.addView(tvHomeDelayPill)
        centerArea.addView(stationPill)

        // Hero Circular Sync Button (184dp x 184dp) centered over Shazam PulseRingView
        val heroStage = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(dp(300), dp(300)).apply {
                topMargin = dp(8)
            }
        }

        pulseRingView = PulseRingView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }

        heroCircleButton = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(184), dp(184), Gravity.CENTER)
            background = buildHeroCircleDrawable(isListening = false)
            elevation = dp(12).toFloat()
            setOnClickListener { handleAutoSyncClick() }
        }
        applySpringTouch(heroCircleButton)

        heroEmblemView = VectorIconView(this, IconType.SHAZAM_SYNC, Color.WHITE).apply {
            layoutParams = FrameLayout.LayoutParams(dp(102), dp(102), Gravity.CENTER)
        }
        heroCircleButton.addView(heroEmblemView)

        pulseRingView.attachTargetCircle(heroCircleButton)

        heroStage.addView(pulseRingView)
        heroStage.addView(heroCircleButton)
        centerArea.addView(heroStage)

        // Live subtle sync feedback line under the circle
        tvHomeSyncSubtext = TextView(this).apply {
            text = ""
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = matchWrap().apply { topMargin = dp(2) }
        }
        centerArea.addView(tvHomeSyncSubtext)

        // Sync result banner + Unconfirmed Candidate Manual Apply Button (when timeout occurs)
        tvHomeBanner = TextView(this).apply {
            visibility = View.GONE
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedDrawable(
                Color.parseColor("#182130"),
                Color.parseColor("#2B3B52"),
                dp(1),
                dp(12).toFloat()
            )
            layoutParams = matchWrap().apply { topMargin = dp(6) }
        }
        centerArea.addView(tvHomeBanner)

        btnHomeApplyCandidate = TextView(this).apply {
            visibility = View.GONE
            text = "تطبيق المرشّح"
            setTextColor(COLOR_WARNING)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rippleButtonDrawable(
                COLOR_WARNING_SOFT,
                COLOR_WARNING,
                dp(22).toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
            setOnClickListener {
                val cand = latestState.unconfirmedCandidateSec ?: return@setOnClickListener
                playbackService?.applyUnconfirmedCandidate(cand)
            }
        }
        applySpringTouch(btnHomeApplyCandidate)
        centerArea.addView(btnHomeApplyCandidate)

        centerArea.addView(spaceWeight(0.58f))

        // Bottom Quick Controls Row (subtle dark circular buttons #232830 matching screenshot #1 bottom)
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            layoutParams = matchWrap().apply { bottomMargin = dp(4) }
        }

        val (prevCol, _, _) = buildHomeCircleAction(IconType.PREV, "السابق", sizeDp = 48) {
            ensureServiceStarted()
            val stepped = playbackService?.stepChannel(-1)
            if (stepped == null) toast("لا توجد قنوات في المكتبة النشطة.")
        }

        val (playCol, playCircle, playIcon, playLabel) = buildHomeCircleActionWithRefs(
            IconType.PLAY,
            "تشغيل",
            sizeDp = 56
        ) {
            ensureServiceStarted()
            playbackService?.togglePlayPause()
        }
        btnHomePlayPause = playCircle
        icHomePlayPause = playIcon
        lblHomePlayPause = playLabel

        val (nextCol, _, _) = buildHomeCircleAction(IconType.NEXT, "التالي", sizeDp = 48) {
            ensureServiceStarted()
            val stepped = playbackService?.stepChannel(+1)
            if (stepped == null) toast("لا توجد قنوات في المكتبة النشطة.")
        }

        val (muteCol, muteCircle, muteIcon, muteLabel) = buildHomeCircleActionWithRefs(
            IconType.UNMUTE,
            "كتم",
            sizeDp = 48
        ) {
            playbackService?.toggleMute()
        }
        btnHomeMute = muteCircle
        icHomeMute = muteIcon
        lblHomeMute = muteLabel

        val (overlayCol, overlayCircle, overlayIcon, _) = buildHomeCircleActionWithRefs(
            IconType.OVERLAY,
            "عائمة",
            sizeDp = 48
        ) {
            handleFloatingOverlayClick()
        }
        btnHomeOverlay = overlayCircle
        icHomeOverlay = overlayIcon

        bottomRow.addView(prevCol)
        bottomRow.addView(playCol)
        bottomRow.addView(nextCol)
        bottomRow.addView(muteCol)
        bottomRow.addView(overlayCol)

        page.addView(topBar)
        page.addView(centerArea)
        page.addView(bottomRow)
        return page
    }

    private fun buildTopNavTab(iconType: IconType, label: String, onClick: () -> Unit): LinearLayout {
        val tab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
        }
        applySpringTouch(tab)

        val icon = VectorIconView(this, iconType, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        val text = TextView(this).apply {
            this.text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        tab.addView(icon)
        tab.addView(text)
        return tab
    }

    private data class CircleActionBundle(
        val column: LinearLayout,
        val circle: FrameLayout,
        val icon: VectorIconView,
        val label: TextView
    )

    private fun buildHomeCircleAction(
        iconType: IconType,
        label: String,
        sizeDp: Int,
        onClick: () -> Unit
    ): Triple<LinearLayout, FrameLayout, VectorIconView> {
        val bundle = buildHomeCircleActionWithRefs(iconType, label, sizeDp, onClick)
        return Triple(bundle.column, bundle.circle, bundle.icon)
    }

    private fun buildHomeCircleActionWithRefs(
        iconType: IconType,
        label: String,
        sizeDp: Int,
        onClick: () -> Unit
    ): CircleActionBundle {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val circle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            background = rippleButtonDrawable(
                fillColor = COLOR_HOME_CIRCLE_BTN,
                strokeColor = Color.parseColor("#2E3540"),
                radiusPx = dp(sizeDp / 2).toFloat()
            )
            setOnClickListener { onClick() }
        }
        applySpringTouch(circle)

        val icon = VectorIconView(this, iconType, Color.WHITE).apply {
            val iconPx = dp((sizeDp * 0.44f).roundToInt())
            layoutParams = FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER)
        }
        circle.addView(icon)

        val lbl = TextView(this).apply {
            text = label
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        col.addView(circle)
        col.addView(lbl)
        return CircleActionBundle(col, circle, icon, lbl)
    }

    private fun buildHeroCircleDrawable(isListening: Boolean): RippleDrawable {
        val fill = if (isListening) COLOR_SHAZAM_BLUE else COLOR_HERO_IDLE
        val stroke = if (isListening) Color.parseColor("#66C2E8FF") else Color.parseColor("#22FFFFFF")
        return rippleButtonDrawable(fill, stroke, dp(92).toFloat())
    }

    // =========================================================================
    // PAGE 2 (RIGHT — Delay & Controls Screen, Marked "2")
    // =========================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPage2DelayControls(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(COLOR_DARK_SURFACE_BG)
        }

        // Top Bar (LTR): Left = Back arrow to Center, Center = Title + 3 dots (3rd active), Right = Reset 0.00
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            layoutParams = matchWrap()
        }

        val btnBack = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = rippleButtonDrawable(Color.TRANSPARENT, Color.TRANSPARENT, dp(20).toFloat())
            setOnClickListener { pager.scrollToPage(PAGE_HOME, animated = true) }
            addView(
                VectorIconView(this@MainActivity, IconType.BACK_LEFT, Color.WHITE),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            )
        }
        applySpringTouch(btnBack)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(this).apply {
            text = "التأخير والضبط"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val dots = buildPaginationDots(activeIndex = 2).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        titleCol.addView(tvTitle)
        titleCol.addView(dots)

        val btnTopReset = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = rippleButtonDrawable(Color.TRANSPARENT, Color.TRANSPARENT, dp(20).toFloat())
            setOnClickListener {
                playbackService?.resetDelay()
                toast("تم تصفير التأخير إلى 0.00 ث")
            }
            addView(
                VectorIconView(this@MainActivity, IconType.RESET, Color.WHITE),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            )
        }
        applySpringTouch(btnTopReset)

        topBar.addView(btnBack)
        topBar.addView(titleCol)
        topBar.addView(btnTopReset)

        // Scrollable RTL Body
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_ALWAYS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(6), dp(16), dp(28))
            layoutParams = matchWrap()
        }

        // Section 1 Heading
        content.addView(makeSectionHeading("التأخير المباشر"))

        // 1. Dashed-Border Delay Display & Direct Numeric Input Card (matching screenshot #2 dashed box)
        val dashedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#181818"))
                setStroke(
                    dp(1.2f),
                    Color.parseColor("#474747"),
                    dp(7).toFloat(),
                    dp(5).toFloat()
                )
            }
            layoutParams = matchWrap().apply { topMargin = dp(10) }
        }

        tvDelayReadout = TextView(this).apply {
            text = "0.00 ث"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = matchWrap()
        }

        tvDelayBufferInfo = TextView(this).apply {
            text = "الذاكرة الصوتية المتاحة: 0 ث • السرعة: 0.0 KB/s"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            layoutParams = matchWrap().apply { topMargin = dp(4) }
        }

        val directInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap().apply { topMargin = dp(14) }
        }

        etDirectDelay = EditText(this).apply {
            hint = "أدخل التأخير بالثواني (مثال 12.5)"
            setHintTextColor(Color.parseColor("#757575"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedDrawable(
                Color.parseColor("#252525"),
                Color.parseColor("#383838"),
                dp(1),
                dp(12).toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1.4f)
        }

        val btnApplyDirect = makePillButton(
            text = "تطبيق",
            bgColor = COLOR_ACCENT_BLUE,
            textColor = Color.WHITE,
            heightDp = 44,
            textSizeSp = 13.5f
        ).apply {
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(44)
            ).apply { marginStart = dp(8) }
            setOnClickListener { applyDirectDelayInput() }
        }

        val btnResetDirect = makePillButton(
            text = "تصفير",
            bgColor = Color.parseColor("#2A2A2A"),
            textColor = COLOR_GRAY_SUBTITLE,
            heightDp = 44,
            textSizeSp = 13f
        ).apply {
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(44)
            ).apply { marginStart = dp(6) }
            setOnClickListener {
                etDirectDelay.setText("")
                playbackService?.resetDelay()
                hideKeyboard()
            }
        }

        directInputRow.addView(etDirectDelay)
        directInputRow.addView(btnApplyDirect)
        directInputRow.addView(btnResetDirect)

        dashedCard.addView(tvDelayReadout)
        dashedCard.addView(tvDelayBufferInfo)
        dashedCard.addView(directInputRow)
        content.addView(dashedCard)

        // 2. Delay SeekBar Card (0..180s) + Advance / Delay Step Grid
        content.addView(makeSectionHeading("شريط التأخير والضبط الدقيق").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        })

        val seekCard = makePage2Card().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        }

        val seekBoundsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = matchWrap()
        }
        seekBoundsRow.addView(TextView(this).apply {
            text = "0 ث"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        seekBoundsRow.addView(TextView(this).apply {
            text = "180 ث (3 دقائق)"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        seekDelay = SeekBar(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            max = 1800 // 0.0s .. 180.0s (0.1s resolution)
            progress = 0
            thumbTintList = ColorStateList.valueOf(COLOR_ACCENT_BLUE)
            progressTintList = ColorStateList.valueOf(COLOR_ACCENT_BLUE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#333333"))
            layoutParams = matchWrap().apply { topMargin = dp(6) }
            // Prevent horizontal pager from stealing SeekBar drag gestures
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val sec = progress.toDouble() / 10.0
                        tvDelayReadout.text = String.format(Locale.US, "%.2f ث", sec)
                        tvHomeDelayPill.text = String.format(Locale.US, "%.2f ث", sec)
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

        // Step Grid Row 1: -10s, -1s, -0.25s
        val stepRowMinus = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = matchWrap().apply { topMargin = dp(14) }
        }
        listOf("-10 ث" to -10.0, "-1 ث" to -1.0, "-0.25 ث" to -0.25).forEachIndexed { i, (label, delta) ->
            stepRowMinus.addView(
                makePillButton(label, Color.parseColor("#2B2B2B"), Color.WHITE, heightDp = 42).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (i > 0) marginStart = dp(8)
                    }
                    setOnClickListener { playbackService?.adjustDelaySeconds(delta) }
                }
            )
        }

        // Step Grid Row 2: +0.25s, +1s, +10s
        val stepRowPlus = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = matchWrap().apply { topMargin = dp(8) }
        }
        listOf("+0.25 ث" to 0.25, "+1 ث" to 1.0, "+10 ث" to 10.0).forEachIndexed { i, (label, delta) ->
            stepRowPlus.addView(
                makePillButton(label, Color.parseColor("#2B2B2B"), Color.WHITE, heightDp = 42).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (i > 0) marginStart = dp(8)
                    }
                    setOnClickListener { playbackService?.adjustDelaySeconds(delta) }
                }
            )
        }

        seekCard.addView(seekBoundsRow)
        seekCard.addView(seekDelay)
        seekCard.addView(stepRowMinus)
        seekCard.addView(stepRowPlus)
        content.addView(seekCard)

        // 3. 2-Tap Manual Sync Stopwatch Card
        content.addView(makeSectionHeading("المزامنة اليدوية بضغطتين").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        })

        val stopwatchCard = makePage2Card().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        }

        val swDesc = TextView(this).apply {
            text = "اضغط عند سماع الحدث في الراديو (يُكتم الصوت ويبدأ العد)، واضغط ثانيةً عند رؤيته على التلفاز لإضافة الفارق فوراً."
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = matchWrap()
        }

        val swRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap().apply { topMargin = dp(12) }
        }

        tvStopwatchDisplay = TextView(this).apply {
            text = "0.00 ث"
            setTextColor(COLOR_ACCENT_BLUE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
        }

        btnTwoTapSync = makePillButton(
            text = "مزامنة بضغطتين",
            bgColor = Color.parseColor("#2B2B2B"),
            textColor = Color.WHITE,
            strokeColor = Color.parseColor("#3E3E3E"),
            heightDp = 46,
            textSizeSp = 14f
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
            setOnClickListener { handleTwoTapStopwatchClick() }
        }

        swRow.addView(tvStopwatchDisplay)
        swRow.addView(btnTwoTapSync)

        stopwatchCard.addView(swDesc)
        stopwatchCard.addView(swRow)
        content.addView(stopwatchCard)

        // 4. Sync & Audio Calibration Card
        content.addView(makeSectionHeading("إعدادات المزامنة والمعايرة").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        })

        val calCard = makePage2Card().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        }

        // Earphone No-Mute Toggle
        val earphoneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap()
        }

        val earphoneCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        earphoneCol.addView(TextView(this).apply {
            text = "سماعة أذن (بدون كتم)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        })
        earphoneCol.addView(TextView(this).apply {
            text = "إبقاء صوت الراديو يعمل أثناء استماع الميكروفون للتلفاز"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        })

        swEarphoneNoMute = Switch(this).apply {
            isChecked = prefs.isEarphoneNoMuteEnabled()
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(COLOR_ACCENT_BLUE, Color.parseColor("#444444"))
            )
            setOnCheckedChangeListener { _, isChecked ->
                prefs.setEarphoneNoMuteEnabled(isChecked)
            }
        }

        earphoneRow.addView(earphoneCol)
        earphoneRow.addView(swEarphoneNoMute)

        // Divider
        val div1 = makeSubtleDivider()

        // Sync Timeout Selector (45 / 60 / 80s)
        val timeoutLabel = TextView(this).apply {
            text = "أقصى مدة بحث للمزامنة التلقائية"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = matchWrap()
        }

        val timeoutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = matchWrap().apply { topMargin = dp(8) }
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

        timeoutRow.addView(btnTimeout45)
        timeoutRow.addView(spaceHorizontal(8))
        timeoutRow.addView(btnTimeout60)
        timeoutRow.addView(spaceHorizontal(8))
        timeoutRow.addView(btnTimeout80)

        // Divider
        val div2 = makeSubtleDivider()

        // Speaker / Bluetooth Calibration Trim (-2.00s .. +2.00s in 0.05s steps)
        val calTitle = TextView(this).apply {
            text = "معايرة السماعة / البلوتوث"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val calSub = TextView(this).apply {
            text = "تصحيح ثابت يُضاف تلقائياً بعد المزامنة (-2.00 إلى +2.00 ث • اضغط الرقم للتصفير)"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = matchWrap().apply { topMargin = dp(2) }
        }

        val calRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap().apply { topMargin = dp(10) }
        }

        val btnCalMinus = makePillButton("-0.05 ث", Color.parseColor("#2B2B2B"), Color.WHITE, heightDp = 40).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            setOnClickListener { adjustCalibration(-0.05) }
        }

        tvCalibrationValue = TextView(this).apply {
            setTextColor(COLOR_ACCENT_BLUE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f)
            setOnClickListener {
                prefs.setCalibrationTrimSec(0.0)
                refreshCalibrationReadout()
                toast("تم تصفير معايرة السماعة.")
            }
        }

        val btnCalPlus = makePillButton("+0.05 ث", Color.parseColor("#2B2B2B"), Color.WHITE, heightDp = 40).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            setOnClickListener { adjustCalibration(+0.05) }
        }

        calRow.addView(btnCalMinus)
        calRow.addView(tvCalibrationValue)
        calRow.addView(btnCalPlus)

        calCard.addView(earphoneRow)
        calCard.addView(div1)
        calCard.addView(timeoutLabel)
        calCard.addView(timeoutRow)
        calCard.addView(div2)
        calCard.addView(calTitle)
        calCard.addView(calSub)
        calCard.addView(calRow)
        content.addView(calCard)

        scroll.addView(content)
        page.addView(topBar)
        page.addView(scroll)
        return page
    }

    // =========================================================================
    // PAGE 3 (LEFT — Libraries 2-Column Grid + In-Page Channels, Marked "3")
    // =========================================================================

    private fun buildPage3Libraries(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(COLOR_DARK_SURFACE_BG)
        }

        // Top Bar (LTR): Left = Gear/Overlay icon (matching screenshot #3), Center = "المكتبات" + dots (1st white), Right = "+ رابط" & arrow to center
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(8))
            layoutParams = matchWrap()
        }

        val btnGearOverlay = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = rippleButtonDrawable(Color.TRANSPARENT, Color.TRANSPARENT, dp(20).toFloat())
            setOnClickListener { handleFloatingOverlayClick() }
            addView(
                VectorIconView(this@MainActivity, IconType.GEAR, Color.WHITE),
                FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER)
            )
        }
        applySpringTouch(btnGearOverlay)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(this).apply {
            text = "المكتبات"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val dots = buildPaginationDots(activeIndex = 0).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        titleCol.addView(tvTitle)
        titleCol.addView(dots)

        val rightActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnQuickManual = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            background = rippleButtonDrawable(Color.TRANSPARENT, Color.TRANSPARENT, dp(18).toFloat())
            setOnClickListener { showAddManualUrlDialog() }
            addView(
                VectorIconView(this@MainActivity, IconType.PLUS, Color.WHITE),
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            )
        }
        applySpringTouch(btnQuickManual)

        val btnToCenter = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(36))
            background = rippleButtonDrawable(Color.TRANSPARENT, Color.TRANSPARENT, dp(18).toFloat())
            setOnClickListener { pager.scrollToPage(PAGE_HOME, animated = true) }
            addView(
                VectorIconView(this@MainActivity, IconType.FORWARD_RIGHT, Color.WHITE),
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            )
        }
        applySpringTouch(btnToCenter)

        rightActions.addView(btnQuickManual)
        rightActions.addView(btnToCenter)

        topBar.addView(btnGearOverlay)
        topBar.addView(titleCol)
        topBar.addView(rightActions)

        // Helper subtitle (matching screenshot #3 centered muted gray text)
        tvLibrariesSubtitle = TextView(this).apply {
            text = "أضف مكتبات الصوتيات أو اختر مكتبة للتشغيل التسلسلي"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22), dp(8), dp(22), dp(14))
            layoutParams = matchWrap()
        }

        // Mode A: 2-Column Shazam-Style Library Card Grid
        librariesGridScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_ALWAYS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        librariesGridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(14), dp(2), dp(14), dp(28))
            layoutParams = matchWrap()
        }
        librariesGridScroll.addView(librariesGridContainer)

        // Mode B: In-Page Sequential Channel Browser (shown when a library card is tapped)
        channelBrowserContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val browserHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(10))
            layoutParams = matchWrap()
        }

        val btnBackToLibs = makePillButton(
            text = "← رجوع للمكتبات",
            bgColor = Color.parseColor("#232323"),
            textColor = Color.WHITE,
            heightDp = 36,
            textSizeSp = 12.5f
        ).apply {
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            )
            setOnClickListener {
                browsingLibraryId = null
                refreshLibrariesPageUi()
            }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val btnAddManualInBrowser = makePillButton(
            text = "+ رابط يدوي",
            bgColor = Color.parseColor("#232323"),
            textColor = COLOR_ACCENT_BLUE,
            heightDp = 36,
            textSizeSp = 12.5f
        ).apply {
            setPadding(dp(11), 0, dp(11), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            )
            setOnClickListener { showAddManualUrlDialog() }
        }

        btnChannelBrowserManage = makePillButton(
            text = "إدارة",
            bgColor = Color.parseColor("#232323"),
            textColor = COLOR_GRAY_SUBTITLE,
            heightDp = 36,
            textSizeSp = 12.5f
        ).apply {
            setPadding(dp(11), 0, dp(11), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply { marginStart = dp(6) }
            setOnClickListener {
                val id = browsingLibraryId ?: return@setOnClickListener
                showManageLibraryDialog(id)
            }
        }

        browserHeader.addView(btnBackToLibs)
        browserHeader.addView(spacer)
        browserHeader.addView(btnAddManualInBrowser)
        browserHeader.addView(btnChannelBrowserManage)

        val browserInfoRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(10))
            layoutParams = matchWrap()
        }

        tvChannelBrowserTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        }
        tvChannelBrowserCount = TextView(this).apply {
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = matchWrap().apply { topMargin = dp(2) }
        }
        browserInfoRow.addView(tvChannelBrowserTitle)
        browserInfoRow.addView(tvChannelBrowserCount)

        val channelsScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_ALWAYS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        channelsListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(2), dp(14), dp(28))
            layoutParams = matchWrap()
        }
        channelsScroll.addView(channelsListContainer)

        channelBrowserContainer.addView(browserHeader)
        channelBrowserContainer.addView(browserInfoRow)
        channelBrowserContainer.addView(channelsScroll)

        page.addView(topBar)
        page.addView(tvLibrariesSubtitle)
        page.addView(librariesGridScroll)
        page.addView(channelBrowserContainer)
        return page
    }

    private fun buildFeaturedAddLibraryCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(18), dp(14), dp(14))
            background = roundedDrawable(
                fillColor = COLOR_LIB_CARD_BG,
                strokeColor = Color.parseColor("#2E2E2E"),
                strokeWidthPx = dp(1),
                radiusPx = dp(16).toFloat()
            )
            minimumHeight = dp(246)
            setOnClickListener { showAddLibraryChooserDialog() }
        }
        applySpringTouch(card)

        val musicIcon = VectorIconView(this, IconType.MUSIC_NOTE, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                topMargin = dp(4)
            }
        }

        val title = TextView(this).apply {
            text = "إضافة مكتبة صوتيات"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = matchWrap().apply { topMargin = dp(14) }
        }

        val desc = TextView(this).apply {
            text = "M3U / M3U8 / JSON / XML / TXT / GX / Sunplus / ALI"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            layoutParams = matchWrap().apply { topMargin = dp(8) }
        }

        val flexSpace = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        }

        // High-contrast white pill button (#FFFFFF bg, black bold text) matching screenshot #3
        val ctaButton = TextView(this).apply {
            text = "إضافة مكتبة"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(40)
            background = rippleButtonDrawable(
                fillColor = Color.WHITE,
                strokeColor = Color.TRANSPARENT,
                radiusPx = dp(10).toFloat()
            )
            layoutParams = matchWrap().apply { topMargin = dp(14) }
            setOnClickListener { showAddLibraryChooserDialog() }
        }
        applySpringTouch(ctaButton)

        card.addView(musicIcon)
        card.addView(title)
        card.addView(desc)
        card.addView(flexSpace)
        card.addView(ctaButton)
        return card
    }

    private fun buildShazamLibraryCard(lib: RadioLibrary): View {
        val isPlayingLib = latestState.libraryId == lib.id && latestState.channelIndex >= 0
        val isSelected = selectedLibraryId == lib.id

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            minimumHeight = dp(246)
            background = roundedDrawable(
                fillColor = COLOR_LIB_CARD_BG,
                strokeColor = if (isPlayingLib) COLOR_ACCENT_BLUE else Color.parseColor("#262626"),
                strokeWidthPx = if (isPlayingLib) dp(1.5f) else dp(1),
                radiusPx = dp(16).toFloat()
            )
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
            setOnClickListener {
                selectedLibraryId = lib.id
                libraryManager.setActiveLibrary(lib.id)
                browsingLibraryId = lib.id
                refreshLibrariesPageUi()
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
        applySpringTouch(card)

        // Upper square #2C2C2C cover artwork area matching screenshot #3
        val coverArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(156)
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_LIB_COVER_BG)
                val r = dp(16).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }

        // Center music/radio vector icon with optional #0A84FF ring if active
        val iconCircle = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER)
            background = if (isPlayingLib || isSelected) {
                roundedDrawable(
                    fillColor = Color.parseColor("#220A84FF"),
                    strokeColor = COLOR_ACCENT_BLUE,
                    strokeWidthPx = dp(2),
                    radiusPx = dp(37).toFloat()
                )
            } else {
                null
            }
            addView(
                VectorIconView(
                    this@MainActivity,
                    IconType.MUSIC_NOTE,
                    if (isPlayingLib) COLOR_ACCENT_BLUE else Color.parseColor("#545454")
                ),
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
            )
        }
        coverArea.addView(iconCircle)

        // Compact 3-dot manage menu button on upper corner for imported libraries
        if (!lib.isManualDefault) {
            val menuBtn = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(4)
                    marginEnd = dp(4)
                }
                background = rippleButtonDrawable(Color.parseColor("#44000000"), Color.TRANSPARENT, dp(17).toFloat())
                setOnClickListener { showManageLibraryDialog(lib.id) }
                addView(
                    VectorIconView(this@MainActivity, IconType.MORE_VERT, Color.WHITE),
                    FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                )
            }
            coverArea.addView(menuBtn)
        }

        // Lower #1A1A1A section showing Library Name (bold white) + Channel Count (gray)
        val infoSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val nameView = TextView(this).apply {
            text = lib.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        val countText = when (lib.channels.size) {
            0 -> "فارغة"
            1 -> "قناة واحدة"
            2 -> "قناتان"
            else -> "${lib.channels.size} قناة"
        }

        val countView = TextView(this).apply {
            text = if (isPlayingLib) "$countText • قيد التشغيل" else countText
            setTextColor(if (isPlayingLib) COLOR_ACCENT_BLUE else COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = matchWrap().apply { topMargin = dp(4) }
        }

        infoSection.addView(nameView)
        infoSection.addView(countView)

        card.addView(coverArea)
        card.addView(infoSection)
        return card
    }

    // =========================================================================
    // STATE RENDERING
    // =========================================================================

    private fun renderServiceState(state: ServiceUiState) {
        // Keep screen awake ONLY while auto-sync is actively measuring
        if (state.isSyncing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val delayText = String.format(Locale.US, "%.2f ث", state.delaySec)

        // --- Page 1 (Center Home) updates ---
        tvHomeStationPill.text = if (state.channelIndex >= 0 && state.totalChannels > 0) {
            "${state.channelIndex + 1}/${state.totalChannels} • ${state.channelName}"
        } else {
            state.channelName
        }
        tvHomeDelayPill.text = delayText

        val dotColor = when {
            state.isSyncing -> COLOR_SHAZAM_BLUE
            state.isPlaying && !state.isBuffering -> COLOR_ACCENT_GREEN
            state.isBuffering -> COLOR_WARNING
            else -> COLOR_TEXT_MUTED
        }
        homeStatusDot.background = roundedDrawable(dotColor, Color.TRANSPARENT, 0, dp(4).toFloat())

        pulseRingView.setListening(state.isSyncing)
        pulseRingView.setMicLevel(state.syncMicLevel)
        heroCircleButton.background = buildHeroCircleDrawable(isListening = state.isSyncing)

        if (state.isSyncing) {
            tvHomePrompt.text = when {
                state.syncHasCandidate && state.syncCandidateSec != null ->
                    String.format(Locale.US, "تم رصد مرشّح %.1f ث • يتأكد…", state.syncCandidateSec)

                else -> "جاري الاستماع لصوت التلفاز…"
            }
            tvHomeSyncSubtext.text = state.syncStatusText.ifBlank {
                "جاري تحليل البصمة الصوتية (${state.syncElapsedSec}/${state.syncTimeoutSec} ث)"
            }
            tvHomeSyncSubtext.visibility = View.VISIBLE
        } else {
            tvHomePrompt.text = "اضغط للمزامنة"
            tvHomeSyncSubtext.text = if (state.isPlaying || state.isBuffering) {
                String.format(Locale.US, "%s • %.1f KB/s", state.connectionStatus, state.speedKbps)
            } else {
                ""
            }
            tvHomeSyncSubtext.visibility = if (tvHomeSyncSubtext.text.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        }

        if (!state.isSyncing && state.lastSyncBannerText.isNotBlank()) {
            tvHomeBanner.text = state.lastSyncBannerText
            tvHomeBanner.visibility = View.VISIBLE
        } else {
            tvHomeBanner.visibility = View.GONE
        }

        val unconfirmed = state.unconfirmedCandidateSec
        if (!state.isSyncing && unconfirmed != null) {
            btnHomeApplyCandidate.text = String.format(Locale.US, "تطبيق المرشّح %.1f ث", unconfirmed)
            btnHomeApplyCandidate.visibility = View.VISIBLE
        } else {
            btnHomeApplyCandidate.visibility = View.GONE
        }

        // Bottom quick buttons state
        val activePlayback = state.isPlaying || state.isBuffering
        icHomePlayPause.setIcon(if (activePlayback) IconType.PAUSE else IconType.PLAY, Color.WHITE)
        lblHomePlayPause.text = if (activePlayback) "إيقاف" else "تشغيل"
        btnHomePlayPause.background = rippleButtonDrawable(
            fillColor = if (activePlayback) COLOR_ACCENT_BLUE else COLOR_HOME_CIRCLE_BTN,
            strokeColor = if (activePlayback) COLOR_ACCENT_BLUE else Color.parseColor("#2E3540"),
            radiusPx = dp(28).toFloat()
        )

        icHomeMute.setIcon(
            if (state.isMuted) IconType.MUTE else IconType.UNMUTE,
            if (state.isMuted) COLOR_WARNING else Color.WHITE
        )
        lblHomeMute.text = if (state.isMuted) "مكتوم" else "كتم"
        btnHomeMute.background = rippleButtonDrawable(
            fillColor = if (state.isMuted) COLOR_WARNING_SOFT else COLOR_HOME_CIRCLE_BTN,
            strokeColor = if (state.isMuted) COLOR_WARNING else Color.parseColor("#2E3540"),
            radiusPx = dp(24).toFloat()
        )

        icHomeOverlay.setIcon(
            IconType.OVERLAY,
            if (state.isOverlayVisible) COLOR_ACCENT_BLUE else Color.WHITE
        )
        btnHomeOverlay.background = rippleButtonDrawable(
            fillColor = if (state.isOverlayVisible) Color.parseColor("#220A84FF") else COLOR_HOME_CIRCLE_BTN,
            strokeColor = if (state.isOverlayVisible) COLOR_ACCENT_BLUE else Color.parseColor("#2E3540"),
            radiusPx = dp(24).toFloat()
        )

        // --- Page 2 (Right Delay Controls) updates ---
        if (!userDraggingSeekBar) {
            tvDelayReadout.text = delayText
            seekDelay.progress = (state.delaySec * 10.0).roundToInt().coerceIn(0, seekDelay.max)
        }
        tvDelayBufferInfo.text = String.format(
            Locale.US,
            "الذاكرة الصوتية: %d ث • السرعة: %.1f KB/s • %s",
            state.bufferedHistorySec.toInt(),
            state.speedKbps,
            state.connectionStatus
        )

        // --- Page 3 (Left Libraries / Channels) highlight refresh ---
        refreshLibrariesPageUi()
    }

    private fun refreshLibrariesPageUi() {
        val libs = libraryManager.getLibraries()
        if (libs.none { it.id == selectedLibraryId }) {
            selectedLibraryId = libs.first().id
        }

        val openLibId = browsingLibraryId
        val openLib = if (openLibId != null) libs.firstOrNull { it.id == openLibId } else null

        if (openLib == null) {
            browsingLibraryId = null
            tvLibrariesSubtitle.visibility = View.VISIBLE
            librariesGridScroll.visibility = View.VISIBLE
            channelBrowserContainer.visibility = View.GONE
            renderLibrariesTwoColumnGrid(libs)
        } else {
            tvLibrariesSubtitle.visibility = View.GONE
            librariesGridScroll.visibility = View.GONE
            channelBrowserContainer.visibility = View.VISIBLE
            renderInPageChannelList(openLib)
        }
    }

    private fun renderLibrariesTwoColumnGrid(libs: List<RadioLibrary>) {
        librariesGridContainer.removeAllViews()

        val cards = ArrayList<View>(libs.size + 1)
        cards.add(buildFeaturedAddLibraryCard())
        for (lib in libs) {
            cards.add(buildShazamLibraryCard(lib))
        }

        var i = 0
        while (i < cards.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                layoutParams = matchWrap().apply {
                    if (i > 0) topMargin = dp(12)
                }
            }

            val leftCard = cards[i]
            leftCard.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            ).apply {
                marginEnd = dp(6)
            }
            row.addView(leftCard)

            if (i + 1 < cards.size) {
                val rightCard = cards[i + 1]
                rightCard.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    marginStart = dp(6)
                }
                row.addView(rightCard)
            } else {
                val emptySlot = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                        marginStart = dp(6)
                    }
                }
                row.addView(emptySlot)
            }

            librariesGridContainer.addView(row)
            i += 2
        }
    }

    private fun renderInPageChannelList(lib: RadioLibrary) {
        tvChannelBrowserTitle.text = lib.name
        tvChannelBrowserCount.text = "${lib.channels.size} قناة بالترتيب التسلسلي • اضغط للتشغيل أو اضغط مطولاً للحذف"
        btnChannelBrowserManage.visibility = if (lib.isManualDefault) View.GONE else View.VISIBLE

        channelsListContainer.removeAllViews()
        if (lib.channels.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = if (lib.isManualDefault) {
                    "لا توجد روابط يدوية بعد.\nاضغط \"+ رابط يدوي\" لإضافة رابط بث مباشر."
                } else {
                    "هذه المكتبة فارغة."
                }
                setTextColor(COLOR_GRAY_SUBTITLE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(36), dp(16), dp(36))
            }
            channelsListContainer.addView(emptyView)
            return
        }

        lib.channels.forEachIndexed { idx, channel ->
            val isCurrentlyPlaying = latestState.libraryId == lib.id &&
                latestState.channelIndex == idx &&
                latestState.channelUrl == channel.url

            val savedDelay = prefs.getDelayForUrl(channel.url)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rippleButtonDrawable(
                    fillColor = if (isCurrentlyPlaying) Color.parseColor("#13233A") else COLOR_PAGE2_CARD,
                    strokeColor = if (isCurrentlyPlaying) COLOR_ACCENT_BLUE else Color.parseColor("#2A2A2A"),
                    radiusPx = dp(14).toFloat()
                )
                layoutParams = matchWrap().apply {
                    if (idx > 0) topMargin = dp(8)
                }
                setOnClickListener {
                    ensureServiceStarted()
                    playbackService?.playChannel(lib.id, idx)
                }
                setOnLongClickListener {
                    confirmDeleteChannel(lib, idx, channel)
                    true
                }
            }
            applySpringTouch(row)

            val numBadge = TextView(this).apply {
                text = "${idx + 1}"
                setTextColor(if (isCurrentlyPlaying) Color.WHITE else COLOR_GRAY_SUBTITLE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = roundedDrawable(
                    fillColor = if (isCurrentlyPlaying) COLOR_ACCENT_BLUE else Color.parseColor("#2A2A2A"),
                    strokeColor = Color.TRANSPARENT,
                    strokeWidthPx = 0,
                    radiusPx = dp(9).toFloat()
                )
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    marginEnd = dp(10)
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(this).apply {
                text = channel.name
                setTextColor(if (isCurrentlyPlaying) COLOR_ACCENT_BLUE else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = if (isCurrentlyPlaying) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val subView = TextView(this).apply {
                text = if (isCurrentlyPlaying) "قيد التشغيل الآن" else channel.url
                setTextColor(if (isCurrentlyPlaying) Color.parseColor("#93C5FD") else Color.parseColor("#757575"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
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
            view.setTextColor(if (active) Color.WHITE else COLOR_GRAY_SUBTITLE)
            view.background = rippleButtonDrawable(
                fillColor = if (active) COLOR_ACCENT_BLUE else Color.parseColor("#2B2B2B"),
                strokeColor = if (active) COLOR_ACCENT_BLUE else Color.parseColor("#3B3B3B"),
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

    // =========================================================================
    // BUSINESS ACTIONS & DIALOGS (UNCHANGED CORE LOGIC)
    // =========================================================================

    private fun applyDirectDelayInput() {
        val raw = etDirectDelay.text.toString().trim()
        if (raw.isEmpty()) {
            toast("أدخل قيمة التأخير بالثواني أولاً.")
            return
        }
        val normalized = normalizeNumericInput(raw)
        val parsed = normalized.toDoubleOrNull()
        if (parsed == null || parsed < 0.0) {
            toast("أدخل رقماً صالحاً بين 0 و 180 ثانية.")
            return
        }
        val clamped = parsed.coerceIn(0.0, FootSyncPrefs.MAX_DELAY_SEC)
        playbackService?.setDelaySeconds(clamped, persistForUrl = true)
        etDirectDelay.setText(String.format(Locale.US, "%.2f", clamped))
        etDirectDelay.clearFocus()
        hideKeyboard()
        toast(String.format(Locale.US, "تم ضبط التأخير على %.2f ث", clamped))
    }

    private fun normalizeNumericInput(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '٠', '۰' -> sb.append('0')
                '١', '۱' -> sb.append('1')
                '٢', '۲' -> sb.append('2')
                '٣', '۳' -> sb.append('3')
                '٤', '۴' -> sb.append('4')
                '٥', '۵' -> sb.append('5')
                '٦', '۶' -> sb.append('6')
                '٧', '۷' -> sb.append('7')
                '٨', '۸' -> sb.append('8')
                '٩', '۹' -> sb.append('9')
                ',', '٫', '،' -> sb.append('.')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

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
            tvStopwatchDisplay.text = "0.00 ث"
            btnTwoTapSync.text = "مزامنة بضغطتين"
            btnTwoTapSync.background = rippleButtonDrawable(
                Color.parseColor("#2B2B2B"),
                Color.parseColor("#3E3E3E"),
                dp(12).toFloat()
            )
            btnTwoTapSync.setTextColor(Color.WHITE)
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
            "لصق نص القائمة مباشرة",
            "إضافة رابط بث مباشر يدوي"
        )
        AlertDialog.Builder(this)
            .setTitle("إضافة مكتبة صوتيات")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openDocumentLauncher.launch(arrayOf("*/*"))
                    1 -> showImportUrlDialog()
                    2 -> showImportRawTextDialog()
                    3 -> showAddManualUrlDialog()
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
            layoutDirection = View.LAYOUT_DIRECTION_RTL
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
            layoutDirection = View.LAYOUT_DIRECTION_RTL
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
            layoutDirection = View.LAYOUT_DIRECTION_RTL
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
                refreshLibrariesPageUi()
                ensureServiceStarted()
                playbackService?.playTarget(target)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun handleImportResult(res: ImportResult) {
        if (res.success && res.library != null) {
            selectedLibraryId = res.library.id
            browsingLibraryId = res.library.id
            refreshLibrariesPageUi()
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
                                    refreshLibrariesPageUi()
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
                                    if (browsingLibraryId == lib.id) {
                                        browsingLibraryId = null
                                    }
                                    selectedLibraryId = libraryManager.getActiveLibrary().id
                                    refreshLibrariesPageUi()
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
                    refreshLibrariesPageUi()
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

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // =========================================================================
    // UI STYLING & ANIMATION HELPERS
    // =========================================================================

    private fun buildPaginationDots(activeIndex: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER
            for (i in 0 until 3) {
                val dot = View(this@MainActivity).apply {
                    val size = dp(6)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        if (i > 0) marginStart = dp(7)
                    }
                    background = roundedDrawable(
                        fillColor = if (i == activeIndex) Color.WHITE else Color.parseColor("#55FFFFFF"),
                        strokeColor = Color.TRANSPARENT,
                        strokeWidthPx = 0,
                        radiusPx = dp(3).toFloat()
                    )
                }
                addView(dot)
            }
        }
    }

    private fun makeSectionHeading(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = matchWrap()
        }
    }

    private fun makePage2Card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundedDrawable(
                fillColor = COLOR_PAGE2_CARD,
                strokeColor = Color.parseColor("#2A2A2A"),
                strokeWidthPx = dp(1),
                radiusPx = dp(16).toFloat()
            )
            layoutParams = matchWrap()
        }
    }

    private fun makeSubtleDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#2B2B2B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
            }
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
            applySpringTouch(this)
        }
    }

    private fun makeSmallOptionChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(38)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            setOnClickListener { onClick() }
            applySpringTouch(this)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun applySpringTouch(target: View) {
        target.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.94f)
                        .scaleY(0.94f)
                        .setDuration(80L)
                        .setInterpolator(LinearInterpolator())
                        .start()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(170L)
                        .setInterpolator(OvershootInterpolator(1.6f))
                        .start()
                }
            }
            false
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
            layoutDirection = View.LAYOUT_DIRECTION_RTL
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
            ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
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

    private fun spaceWeight(weight: Float): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, weight)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // CUSTOM VIEWS: SNAP HORIZONTAL PAGER, PULSE RING ANIMATOR, VECTOR ICONS
    // =========================================================================

    /**
     * Zero-dependency 3-page snapping HorizontalScrollView.
     * Snaps cleanly to Page 0, 1, or 2 based on fling velocity and drag distance,
     * while letting vertical ScrollViews inside each page scroll smoothly.
     */
    private class SnapHorizontalPager(context: Context) : HorizontalScrollView(context) {

        private val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        }

        private val pages = mutableListOf<View>()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var velocityTracker: VelocityTracker? = null
        private var downX = 0f
        private var downY = 0f
        private var initialPositioned = false
        private var pendingInitialPage = 1

        var currentPage: Int = 1
            private set

        var onPageChanged: ((Int) -> Unit)? = null

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(container)
        }

        fun setPages(pageViews: List<View>, initialPage: Int) {
            container.removeAllViews()
            pages.clear()
            pages.addAll(pageViews)
            pendingInitialPage = initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            currentPage = pendingInitialPage
            initialPositioned = false

            val w = if (width > 0) width else resources.displayMetrics.widthPixels
            for (p in pages) {
                container.addView(p, LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.MATCH_PARENT))
            }
            requestLayout()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && pages.isNotEmpty()) {
                for (p in pages) {
                    val lp = p.layoutParams
                    if (lp.width != w) {
                        lp.width = w
                        p.layoutParams = lp
                    }
                }
                val targetPage = if (!initialPositioned) pendingInitialPage else currentPage
                initialPositioned = true
                post {
                    scrollTo(targetPage * w, 0)
                    currentPage = targetPage
                    onPageChanged?.invoke(currentPage)
                }
            }
        }

        fun scrollToPage(index: Int, animated: Boolean) {
            if (pages.isEmpty()) return
            val clamped = index.coerceIn(0, pages.lastIndex)
            val pageW = if (width > 0) width else resources.displayMetrics.widthPixels
            val targetX = clamped * pageW
            if (animated) {
                smoothScrollTo(targetX, 0)
            } else {
                scrollTo(targetX, 0)
            }
            if (currentPage != clamped) {
                currentPage = clamped
                onPageChanged?.invoke(currentPage)
            }
        }

        override fun fling(velocityX: Int) {
            // Suppress default free-scrolling fling; snapping is handled on ACTION_UP
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
                    return super.onInterceptTouchEvent(ev)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    // Let vertical ScrollViews win when vertical movement dominates
                    if (dy > touchSlop && dy >= dx) {
                        return false
                    }
                    if (dx > touchSlop && dx > dy * 1.15f) {
                        return super.onInterceptTouchEvent(ev)
                    }
                    return false
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain()
            }
            velocityTracker?.addMovement(ev)

            val handled = super.onTouchEvent(ev)

            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                val pageW = if (width > 0) width else resources.displayMetrics.widthPixels
                val dragDelta = ev.x - downX
                val targetPage = when {
                    vx < -420f -> currentPage + 1
                    vx > 420f -> currentPage - 1
                    abs(dragDelta) > pageW * 0.24f -> {
                        if (dragDelta < 0f) currentPage + 1 else currentPage - 1
                    }

                    else -> ((scrollX + pageW * 0.5f) / pageW.toFloat()).toInt()
                }
                scrollToPage(targetPage, animated = true)
            }
            return handled
        }
    }

    /**
     * Shazam-style concentric ripple rings, ambient radial listening glow, and gentle breathing
     * scale animation bound to the center 184dp circular sync button.
     */
    private class PulseRingView(context: Context) : View(context) {

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private var targetCircle: View? = null
        private var isListening = false
        private var micLevel = 0f
        private var phase = 0f
        private var lastGlowRadius = 0f
        private var lastGlowListening = false

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                phase = anim.animatedValue as Float
                updateCircleBreathingScale()
                invalidate()
            }
        }

        fun attachTargetCircle(view: View) {
            targetCircle = view
        }

        fun setListening(listening: Boolean) {
            if (isListening == listening) return
            isListening = listening
            val desiredDuration = if (listening) 1450L else 3200L
            if (animator.duration != desiredDuration) {
                animator.duration = desiredDuration
            }
            invalidate()
        }

        fun setMicLevel(level: Float) {
            micLevel = level.coerceIn(0f, 1f)
        }

        fun resumeAnimation() {
            if (!animator.isStarted) {
                animator.start()
            } else if (animator.isPaused) {
                animator.resume()
            }
        }

        fun pauseAnimation() {
            if (animator.isRunning) {
                animator.pause()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            resumeAnimation()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        private fun updateCircleBreathingScale() {
            val target = targetCircle ?: return
            // Only apply breathing scale when user isn't pressing the circle down
            if (target.isPressed) return
            val wave = sin(phase * 2.0 * PI).toFloat()
            val scale = if (isListening) {
                1.015f + wave * (0.042f + micLevel * 0.028f)
            } else {
                1.008f + wave * 0.022f
            }
            target.scaleX = scale
            target.scaleY = scale
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width * 0.5f
            val cy = height * 0.5f
            val maxR = min(cx, cy) * 0.98f
            val baseR = maxR * 0.59f

            // Ambient radial glow behind the center circle
            if (lastGlowRadius != maxR || lastGlowListening != isListening) {
                lastGlowRadius = maxR
                lastGlowListening = isListening
                val innerColor = if (isListening) {
                    Color.parseColor("#660088FF")
                } else {
                    Color.parseColor("#1E3A5F99")
                }
                val midColor = if (isListening) {
                    Color.parseColor("#240088FF")
                } else {
                    Color.parseColor("#0A1D2F4D")
                }
                glowPaint.shader = RadialGradient(
                    cx,
                    cy,
                    maxR,
                    intArrayOf(innerColor, midColor, Color.TRANSPARENT),
                    floatArrayOf(0.45f, 0.78f, 1.0f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx, cy, maxR, glowPaint)

            // Concentric expanding sonar rings
            val ringCount = if (isListening) 3 else 2
            val density = resources.displayMetrics.density
            val baseStroke = (if (isListening) 2.6f else 1.5f) * density
            val expandSpan = (maxR - baseR) * (if (isListening) (0.95f + micLevel * 0.12f) else 0.78f)

            for (i in 0 until ringCount) {
                val p = (phase + i.toFloat() / ringCount.toFloat()) % 1f
                val eased = 1f - (1f - p) * (1f - p)
                val radius = baseR + expandSpan * eased
                val fade = (1f - p) * (1f - p)

                if (isListening) {
                    val alpha = (fade * (165f + micLevel * 65f)).roundToInt().coerceIn(0, 235)
                    ringPaint.color = Color.argb(alpha, 0, 136, 255)
                } else {
                    val alpha = (fade * 52f).roundToInt().coerceIn(0, 60)
                    ringPaint.color = Color.argb(alpha, 255, 255, 255)
                }
                ringPaint.strokeWidth = baseStroke * (1f - eased * 0.45f)
                canvas.drawCircle(cx, cy, radius, ringPaint)
            }
        }
    }

    private enum class IconType {
        LIBRARY,
        DELAY,
        HEADPHONES,
        SHAZAM_SYNC,
        PREV,
        NEXT,
        PLAY,
        PAUSE,
        MUTE,
        UNMUTE,
        OVERLAY,
        GEAR,
        MUSIC_NOTE,
        BACK_LEFT,
        FORWARD_RIGHT,
        RESET,
        PLUS,
        MORE_VERT
    }

    /**
     * Self-contained crisp Canvas/Path vector icons (zero external drawable dependencies).
     */
    private class VectorIconView(
        context: Context,
        private var iconType: IconType,
        tintColor: Int
    ) : View(context) {

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tintColor
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tintColor
            style = Paint.Style.FILL
        }

        private val path = Path()
        private val rect = RectF()

        fun setIcon(type: IconType, tintColor: Int) {
            iconType = type
            strokePaint.color = tintColor
            fillPaint.color = tintColor
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val s = min(w, h)
            if (s <= 0f) return

            val ox = (w - s) * 0.5f
            val oy = (h - s) * 0.5f
            canvas.save()
            canvas.translate(ox, oy)
            path.reset()

            when (iconType) {
                IconType.LIBRARY -> {
                    // Person silhouette + small eighth note (matching top-left of screenshot #1)
                    canvas.drawCircle(s * 0.40f, s * 0.28f, s * 0.16f, fillPaint)
                    rect.set(s * 0.12f, s * 0.52f, s * 0.68f, s * 0.96f)
                    canvas.drawArc(rect, 180f, 180f, true, fillPaint)
                    strokePaint.strokeWidth = s * 0.08f
                    canvas.drawLine(s * 0.78f, s * 0.46f, s * 0.78f, s * 0.80f, strokePaint)
                    canvas.drawLine(s * 0.78f, s * 0.46f, s * 0.92f, s * 0.52f, strokePaint)
                    canvas.drawCircle(s * 0.72f, s * 0.82f, s * 0.09f, fillPaint)
                }

                IconType.DELAY -> {
                    // Clean clock / time-shift badge (matching top-right of screenshot #1)
                    strokePaint.strokeWidth = s * 0.09f
                    canvas.drawCircle(s * 0.50f, s * 0.50f, s * 0.38f, strokePaint)
                    canvas.drawLine(s * 0.50f, s * 0.50f, s * 0.50f, s * 0.28f, strokePaint)
                    canvas.drawLine(s * 0.50f, s * 0.50f, s * 0.69f, s * 0.60f, strokePaint)
                }

                IconType.HEADPHONES -> {
                    strokePaint.strokeWidth = s * 0.10f
                    rect.set(s * 0.16f, s * 0.16f, s * 0.84f, s * 0.84f)
                    canvas.drawArc(rect, 180f, 180f, false, strokePaint)
                    rect.set(s * 0.11f, s * 0.50f, s * 0.29f, s * 0.84f)
                    canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, fillPaint)
                    rect.set(s * 0.71f, s * 0.50f, s * 0.89f, s * 0.84f)
                    canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, fillPaint)
                }

                IconType.SHAZAM_SYNC -> {
                    // Bold interlocked Shazam-style dual S-sync arcs
                    strokePaint.strokeWidth = s * 0.155f
                    // Upper-left hook curving into center
                    path.moveTo(s * 0.45f, s * 0.17f)
                    path.lineTo(s * 0.26f, s * 0.36f)
                    rect.set(s * 0.14f, s * 0.28f, s * 0.60f, s * 0.74f)
                    path.arcTo(rect, 135f, -180f, false)
                    canvas.drawPath(path, strokePaint)

                    // Lower-right hook curving symmetrically into center
                    path.reset()
                    path.moveTo(s * 0.55f, s * 0.83f)
                    path.lineTo(s * 0.74f, s * 0.64f)
                    rect.set(s * 0.40f, s * 0.26f, s * 0.86f, s * 0.72f)
                    path.arcTo(rect, -45f, -180f, false)
                    canvas.drawPath(path, strokePaint)
                }

                IconType.PREV -> {
                    // Left-pointing skip previous (bar + triangle)
                    rect.set(s * 0.18f, s * 0.22f, s * 0.29f, s * 0.78f)
                    canvas.drawRoundRect(rect, s * 0.04f, s * 0.04f, fillPaint)
                    path.moveTo(s * 0.80f, s * 0.22f)
                    path.lineTo(s * 0.34f, s * 0.50f)
                    path.lineTo(s * 0.80f, s * 0.78f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                }

                IconType.NEXT -> {
                    path.moveTo(s * 0.20f, s * 0.22f)
                    path.lineTo(s * 0.66f, s * 0.50f)
                    path.lineTo(s * 0.20f, s * 0.78f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                    rect.set(s * 0.71f, s * 0.22f, s * 0.82f, s * 0.78f)
                    canvas.drawRoundRect(rect, s * 0.04f, s * 0.04f, fillPaint)
                }

                IconType.PLAY -> {
                    path.moveTo(s * 0.28f, s * 0.18f)
                    path.lineTo(s * 0.82f, s * 0.50f)
                    path.lineTo(s * 0.28f, s * 0.82f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                }

                IconType.PAUSE -> {
                    rect.set(s * 0.24f, s * 0.20f, s * 0.42f, s * 0.80f)
                    canvas.drawRoundRect(rect, s * 0.06f, s * 0.06f, fillPaint)
                    rect.set(s * 0.58f, s * 0.20f, s * 0.76f, s * 0.80f)
                    canvas.drawRoundRect(rect, s * 0.06f, s * 0.06f, fillPaint)
                }

                IconType.UNMUTE -> {
                    path.moveTo(s * 0.16f, s * 0.38f)
                    path.lineTo(s * 0.34f, s * 0.38f)
                    path.lineTo(s * 0.54f, s * 0.20f)
                    path.lineTo(s * 0.54f, s * 0.80f)
                    path.lineTo(s * 0.34f, s * 0.62f)
                    path.lineTo(s * 0.16f, s * 0.62f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                    strokePaint.strokeWidth = s * 0.08f
                    rect.set(s * 0.50f, s * 0.32f, s * 0.74f, s * 0.68f)
                    canvas.drawArc(rect, -55f, 110f, false, strokePaint)
                    rect.set(s * 0.52f, s * 0.20f, s * 0.88f, s * 0.80f)
                    canvas.drawArc(rect, -55f, 110f, false, strokePaint)
                }

                IconType.MUTE -> {
                    path.moveTo(s * 0.14f, s * 0.38f)
                    path.lineTo(s * 0.32f, s * 0.38f)
                    path.lineTo(s * 0.52f, s * 0.20f)
                    path.lineTo(s * 0.52f, s * 0.80f)
                    path.lineTo(s * 0.32f, s * 0.62f)
                    path.lineTo(s * 0.14f, s * 0.62f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                    strokePaint.strokeWidth = s * 0.09f
                    canvas.drawLine(s * 0.64f, s * 0.36f, s * 0.86f, s * 0.64f, strokePaint)
                    canvas.drawLine(s * 0.86f, s * 0.36f, s * 0.64f, s * 0.64f, strokePaint)
                }

                IconType.OVERLAY -> {
                    // Picture-in-Picture / Floating window icon
                    strokePaint.strokeWidth = s * 0.085f
                    rect.set(s * 0.12f, s * 0.18f, s * 0.88f, s * 0.82f)
                    canvas.drawRoundRect(rect, s * 0.10f, s * 0.10f, strokePaint)
                    rect.set(s * 0.46f, s * 0.46f, s * 0.80f, s * 0.74f)
                    canvas.drawRoundRect(rect, s * 0.06f, s * 0.06f, fillPaint)
                }

                IconType.GEAR -> {
                    // Crisp 8-tooth gear matching top-left of screenshot #3
                    val cx = s * 0.50f
                    val cy = s * 0.50f
                    strokePaint.strokeWidth = s * 0.13f
                    for (i in 0 until 8) {
                        val angle = i * PI / 4.0
                        val x1 = cx + (s * 0.27f * cos(angle)).toFloat()
                        val y1 = cy + (s * 0.27f * sin(angle)).toFloat()
                        val x2 = cx + (s * 0.40f * cos(angle)).toFloat()
                        val y2 = cy + (s * 0.40f * sin(angle)).toFloat()
                        canvas.drawLine(x1, y1, x2, y2, strokePaint)
                    }
                    strokePaint.strokeWidth = s * 0.14f
                    canvas.drawCircle(cx, cy, s * 0.24f, strokePaint)
                }

                IconType.MUSIC_NOTE -> {
                    // Beamed double eighth-note matching screenshot #3 cards
                    strokePaint.strokeWidth = s * 0.10f
                    canvas.drawLine(s * 0.36f, s * 0.72f, s * 0.36f, s * 0.22f, strokePaint)
                    canvas.drawLine(s * 0.76f, s * 0.64f, s * 0.76f, s * 0.14f, strokePaint)
                    path.moveTo(s * 0.32f, s * 0.20f)
                    path.lineTo(s * 0.81f, s * 0.10f)
                    path.lineTo(s * 0.81f, s * 0.26f)
                    path.lineTo(s * 0.32f, s * 0.36f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                    canvas.drawCircle(s * 0.26f, s * 0.76f, s * 0.13f, fillPaint)
                    canvas.drawCircle(s * 0.66f, s * 0.68f, s * 0.13f, fillPaint)
                }

                IconType.BACK_LEFT -> {
                    strokePaint.strokeWidth = s * 0.10f
                    path.moveTo(s * 0.62f, s * 0.20f)
                    path.lineTo(s * 0.32f, s * 0.50f)
                    path.lineTo(s * 0.62f, s * 0.80f)
                    canvas.drawPath(path, strokePaint)
                }

                IconType.FORWARD_RIGHT -> {
                    strokePaint.strokeWidth = s * 0.10f
                    path.moveTo(s * 0.38f, s * 0.20f)
                    path.lineTo(s * 0.68f, s * 0.50f)
                    path.lineTo(s * 0.38f, s * 0.80f)
                    canvas.drawPath(path, strokePaint)
                }

                IconType.RESET -> {
                    strokePaint.strokeWidth = s * 0.095f
                    rect.set(s * 0.18f, s * 0.18f, s * 0.82f, s * 0.82f)
                    canvas.drawArc(rect, -45f, 290f, false, strokePaint)
                    path.moveTo(s * 0.22f, s * 0.18f)
                    path.lineTo(s * 0.22f, s * 0.40f)
                    path.lineTo(s * 0.44f, s * 0.40f)
                    canvas.drawPath(path, strokePaint)
                }

                IconType.PLUS -> {
                    strokePaint.strokeWidth = s * 0.10f
                    canvas.drawLine(s * 0.50f, s * 0.20f, s * 0.50f, s * 0.80f, strokePaint)
                    canvas.drawLine(s * 0.20f, s * 0.50f, s * 0.80f, s * 0.50f, strokePaint)
                }

                IconType.MORE_VERT -> {
                    val r = s * 0.085f
                    canvas.drawCircle(s * 0.50f, s * 0.24f, r, fillPaint)
                    canvas.drawCircle(s * 0.50f, s * 0.50f, r, fillPaint)
                    canvas.drawCircle(s * 0.50f, s * 0.76f, r, fillPaint)
                }
            }
            canvas.restore()
        }
    }

    companion object {
        private const val PAGE_LIBRARIES = 0 // Left (Screenshot #3)
        private const val PAGE_HOME = 1      // Center (Screenshot #1)
        private const val PAGE_DELAY = 2     // Right (Screenshot #2)

        private val COLOR_HOME_BG = Color.parseColor("#0A0D12")
        private val COLOR_HERO_IDLE = Color.parseColor("#3A3F47")
        private val COLOR_SHAZAM_BLUE = Color.parseColor("#0088FF")
        private val COLOR_HOME_CIRCLE_BTN = Color.parseColor("#232830")

        private val COLOR_DARK_SURFACE_BG = Color.parseColor("#121212")
        private val COLOR_PAGE2_CARD = Color.parseColor("#1E1E1E")
        private val COLOR_LIB_CARD_BG = Color.parseColor("#1A1A1A")
        private val COLOR_LIB_COVER_BG = Color.parseColor("#2C2C2C")

        private val COLOR_ACCENT_BLUE = Color.parseColor("#0A84FF")
        private val COLOR_ACCENT_GREEN = Color.parseColor("#22C55E")
        private val COLOR_WARNING = Color.parseColor("#FBBF24")
        private val COLOR_WARNING_SOFT = Color.parseColor("#2EFBBF24")

        private val COLOR_TEXT_PRIMARY = Color.parseColor("#FFFFFF")
        private val COLOR_TEXT_SECONDARY = Color.parseColor("#9CA3AF")
        private val COLOR_GRAY_SUBTITLE = Color.parseColor("#9E9E9E")
        private val COLOR_TEXT_MUTED = Color.parseColor("#6B7280")
    }
}
