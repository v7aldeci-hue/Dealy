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
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var libraryManager: LibraryManager
    private lateinit var prefs: FootSyncPrefs

    private lateinit var appTypeface: Typeface
    private lateinit var appTypefaceBold: Typeface

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

    // Live elapsed seconds ticker during continuous no-timeout auto-sync
    private var syncUiStartedMs = 0L
    private var wasSyncingUi = false
    private val syncElapsedTicker = object : Runnable {
        override fun run() {
            if (!latestState.isSyncing) return
            updateLiveElapsedCounter()
            uiHandler.postDelayed(this, 250L)
        }
    }

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

    // Outer 3-page horizontal snap pager (0 = Left Libraries, 1 = Center Home, 2 = Right Delay)
    private lateinit var pager: SnapHorizontalPager

    // === Page 1 (Center — Home) UI references ===
    private lateinit var tvHomePrompt: TextView
    private lateinit var tvHomeStationPill: TextView
    private lateinit var tvHomeDelayPill: TextView
    private lateinit var homeStatusDot: View
    private lateinit var pulseRingView: PulseRingView
    private lateinit var heroCircleButton: FrameLayout
    private lateinit var heroEmblemView: VectorIconView
    private lateinit var tvHomeElapsedPill: TextView
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

    // === Page 2 (Right — Delay & Controls) UI references ===
    private lateinit var tvDelayReadout: TextView
    private lateinit var tvDelayBufferInfo: TextView
    private lateinit var etDirectDelay: EditText
    private lateinit var seekDelay: SeekBar
    private lateinit var tvStopwatchDisplay: TextView
    private lateinit var btnTwoTapSync: TextView
    private lateinit var swEarphoneNoMute: Switch
    private lateinit var tvCalibrationValue: TextView

    // === Page 3 (Left — Libraries & In-Page 2-Column Square Channels) UI references ===
    private lateinit var tvLibrariesSubtitle: TextView
    private lateinit var librariesGridScroll: ScrollView
    private lateinit var librariesGridContainer: LinearLayout
    private lateinit var channelBrowserContainer: LinearLayout
    private lateinit var tvChannelBrowserTitle: TextView
    private lateinit var tvChannelBrowserCount: TextView
    private lateinit var btnChannelBrowserManage: TextView
    private lateinit var channelsGridContainer: LinearLayout

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

        loadAppFont()

        window.statusBarColor = COLOR_HOME_BG
        window.navigationBarColor = COLOR_HOME_BG

        libraryManager = LibraryManager.getInstance(applicationContext)
        prefs = FootSyncPrefs(this)
        selectedLibraryId = libraryManager.getActiveLibrary().id

        val rootView = buildRootPagerLayout()
        applyFontRecursively(rootView)
        setContentView(rootView)

        refreshLibrariesPageUi()
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
        uiHandler.removeCallbacks(syncElapsedTicker)
        pulseRingView.pauseAnimation()
        playbackService?.removeStateListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(syncElapsedTicker)
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

    private fun loadAppFont() {
        appTypeface = try {
            Typeface.createFromAsset(assets, APP_FONT_ASSET_PATH)
        } catch (_: Throwable) {
            Typeface.DEFAULT
        }
        appTypefaceBold = try {
            Typeface.create(appTypeface, Typeface.BOLD)
        } catch (_: Throwable) {
            appTypeface
        }
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
    // PAGE 1 (CENTER — Default Home Screen)
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

        // Center Hero Column (Prompt + Active Station Pill + 184dp Pulsing Fingerprint Circle)
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
            typeface = appTypefaceBold
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
            typeface = appTypeface
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(175)
        }

        val pillDivider = TextView(this).apply {
            text = "  •  "
            setTextColor(COLOR_TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = appTypeface
        }

        tvHomeDelayPill = TextView(this).apply {
            text = "0.00 ث"
            setTextColor(COLOR_SHAZAM_BLUE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = appTypefaceBold
        }

        stationPill.addView(homeStatusDot)
        stationPill.addView(tvHomeStationPill)
        stationPill.addView(pillDivider)
        stationPill.addView(tvHomeDelayPill)
        centerArea.addView(stationPill)

        // Hero Circular Sync Button (184dp x 184dp) with Fingerprint Vector Emblem + PulseRingView
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

        heroEmblemView = VectorIconView(this, IconType.FINGERPRINT, Color.WHITE).apply {
            layoutParams = FrameLayout.LayoutParams(dp(106), dp(106), Gravity.CENTER)
        }
        heroCircleButton.addView(heroEmblemView)

        pulseRingView.attachTargetCircle(heroCircleButton)

        heroStage.addView(pulseRingView)
        heroStage.addView(heroCircleButton)
        centerArea.addView(heroStage)

        // Live Elapsed Seconds Counter Pill (No Timeout — runs continuously until verified or cancelled)
        tvHomeElapsedPill = TextView(this).apply {
            text = "الوقت المنقضي: 0 ث  •  00:00"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = appTypefaceBold
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            visibility = View.GONE
            setPadding(dp(18), dp(7), dp(18), dp(7))
            background = roundedDrawable(
                fillColor = Color.parseColor("#13253D"),
                strokeColor = Color.parseColor("#550088FF"),
                strokeWidthPx = dp(1),
                radiusPx = dp(20).toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
            }
        }
        centerArea.addView(tvHomeElapsedPill)

        // Live subtle sync / stream feedback line
        tvHomeSyncSubtext = TextView(this).apply {
            text = ""
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = appTypeface
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = matchWrap().apply { topMargin = dp(6) }
        }
        centerArea.addView(tvHomeSyncSubtext)

        // Sync result banner (confirmation / cancellation / error message)
        tvHomeBanner = TextView(this).apply {
            visibility = View.GONE
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = appTypeface
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

        centerArea.addView(spaceWeight(0.58f))

        // Bottom Quick Controls Row (subtle dark circular buttons #232830)
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
            typeface = appTypefaceBold
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
            typeface = appTypeface
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
    // PAGE 2 (RIGHT — Delay & Controls Screen)
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
            typeface = appTypefaceBold
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

        content.addView(makeSectionHeading("التأخير المباشر"))

        // 1. Dashed-Border Delay Display & Direct Numeric Input Card
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
            typeface = appTypefaceBold
            gravity = Gravity.CENTER
            layoutParams = matchWrap()
        }

        tvDelayBufferInfo = TextView(this).apply {
            text = "الذاكرة الصوتية المتاحة: 0 ث • السرعة: 0.0 KB/s"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = appTypeface
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
            typeface = appTypeface
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
            typeface = appTypeface
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        seekBoundsRow.addView(TextView(this).apply {
            text = "180 ث (3 دقائق)"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = appTypeface
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        seekDelay = SeekBar(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            max = 1800
            progress = 0
            thumbTintList = ColorStateList.valueOf(COLOR_ACCENT_BLUE)
            progressTintList = ColorStateList.valueOf(COLOR_ACCENT_BLUE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#333333"))
            layoutParams = matchWrap().apply { topMargin = dp(6) }
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
            typeface = appTypeface
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
            typeface = appTypefaceBold
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

        // 4. Sync & Audio Calibration Card (Earphone Toggle + Continuous Sync Note + Calibration Trim)
        content.addView(makeSectionHeading("إعدادات المزامنة والمعايرة").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        })

        val calCard = makePage2Card().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        }

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
            typeface = appTypefaceBold
        })
        earphoneCol.addView(TextView(this).apply {
            text = "إبقاء صوت الراديو يعمل أثناء استماع الميكروفون للتلفاز"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = appTypeface
        })

        swEarphoneNoMute = Switch(this).apply {
            isChecked = prefs.isEarphoneNoMuteEnabled()
            typeface = appTypeface
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

        val div1 = makeSubtleDivider()

        val continuousNote = TextView(this).apply {
            text = "المزامنة التلقائية تعمل بشكل مستمر دون مهلة زمنية حتى التحقق المزدوج أو الإلغاء يدوياً."
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = appTypeface
            layoutParams = matchWrap()
        }

        val div2 = makeSubtleDivider()

        val calTitle = TextView(this).apply {
            text = "معايرة السماعة / البلوتوث"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = appTypefaceBold
        }
        val calSub = TextView(this).apply {
            text = "تصحيح ثابت يُضاف تلقائياً بعد المزامنة (-2.00 إلى +2.00 ث • اضغط الرقم للتصفير)"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = appTypeface
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
            typeface = appTypefaceBold
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
        calCard.addView(continuousNote)
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
    // PAGE 3 (LEFT — Libraries 2-Column Square Grid + 2-Column Square Channels)
    // =========================================================================

    private fun buildPage3Libraries(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(COLOR_DARK_SURFACE_BG)
        }

        // Top Bar (LTR): Left = Gear/Overlay icon, Center = "المكتبات" + dots (1st white), Right = "+" & arrow to center
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
            typeface = appTypefaceBold
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

        tvLibrariesSubtitle = TextView(this).apply {
            text = "أضف مكتبات الصوتيات أو اختر مكتبة للتشغيل التسلسلي"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = appTypeface
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

        // Mode B: In-Page Shazam 2-Column Square Channel Cards Grid
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
            typeface = appTypefaceBold
        }
        tvChannelBrowserCount = TextView(this).apply {
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = appTypeface
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

        channelsGridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(2), dp(14), dp(28))
            layoutParams = matchWrap()
        }
        channelsScroll.addView(channelsGridContainer)

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
            typeface = appTypefaceBold
            gravity = Gravity.CENTER
            layoutParams = matchWrap().apply { topMargin = dp(14) }
        }

        val desc = TextView(this).apply {
            text = "M3U / M3U8 / JSON / XML / TXT / GX / Sunplus / ALI"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = appTypeface
            gravity = Gravity.CENTER
            layoutParams = matchWrap().apply { topMargin = dp(8) }
        }

        val flexSpace = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        }

        val ctaButton = TextView(this).apply {
            text = "إضافة مكتبة"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = appTypefaceBold
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

        // True 1:1 square upper #2C2C2C cover artwork area
        val coverArea = SquareFrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_LIB_COVER_BG)
                val r = dp(16).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }

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

        if (!lib.isManualDefault) {
            val menuBtn = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(6)
                    marginEnd = dp(6)
                }
                background = rippleButtonDrawable(Color.parseColor("#55000000"), Color.TRANSPARENT, dp(17).toFloat())
                setOnClickListener { showManageLibraryDialog(lib.id) }
                addView(
                    VectorIconView(this@MainActivity, IconType.MORE_VERT, Color.WHITE),
                    FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                )
            }
            coverArea.addView(menuBtn)
        }

        val infoSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(11), dp(12), dp(12))
            layoutParams = matchWrap()
        }

        val nameView = TextView(this).apply {
            text = lib.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = appTypefaceBold
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
            typeface = appTypeface
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

    /**
     * Shazam-style 1:1 square artwork card for each channel inside the selected library.
     */
    private fun buildShazamChannelCard(
        lib: RadioLibrary,
        idx: Int,
        channel: RadioChannel
    ): View {
        val isCurrentlyPlaying = latestState.libraryId == lib.id &&
            latestState.channelIndex == idx &&
            latestState.channelUrl == channel.url
        val savedDelay = prefs.getDelayForUrl(channel.url)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = rippleButtonDrawable(
                fillColor = if (isCurrentlyPlaying) Color.parseColor("#142136") else COLOR_LIB_CARD_BG,
                strokeColor = if (isCurrentlyPlaying) COLOR_ACCENT_BLUE else Color.parseColor("#262626"),
                radiusPx = dp(16).toFloat()
            )
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
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
        applySpringTouch(card)

        // True 1:1 square upper #2C2C2C cover artwork area
        val coverArea = SquareFrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (isCurrentlyPlaying) Color.parseColor("#1D3354") else COLOR_LIB_COVER_BG)
                val r = dp(16).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }

        val emblemRing = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER)
            background = if (isCurrentlyPlaying) {
                roundedDrawable(
                    fillColor = Color.parseColor("#2A0A84FF"),
                    strokeColor = COLOR_ACCENT_BLUE,
                    strokeWidthPx = dp(2),
                    radiusPx = dp(34).toFloat()
                )
            } else {
                roundedDrawable(
                    fillColor = Color.parseColor("#16FFFFFF"),
                    strokeColor = Color.TRANSPARENT,
                    strokeWidthPx = 0,
                    radiusPx = dp(34).toFloat()
                )
            }
            addView(
                VectorIconView(
                    this@MainActivity,
                    if (isCurrentlyPlaying) IconType.RADIO_WAVE else IconType.MUSIC_NOTE,
                    if (isCurrentlyPlaying) COLOR_ACCENT_BLUE else Color.parseColor("#6E6E6E")
                ),
                FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
            )
        }
        coverArea.addView(emblemRing)

        // Sequential index badge (1, 2, 3...)
        val numBadge = TextView(this).apply {
            text = "${idx + 1}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = appTypefaceBold
            gravity = Gravity.CENTER
            minWidth = dp(28)
            minHeight = dp(26)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = roundedDrawable(
                fillColor = if (isCurrentlyPlaying) COLOR_ACCENT_BLUE else Color.parseColor("#88000000"),
                strokeColor = Color.TRANSPARENT,
                strokeWidthPx = 0,
                radiusPx = dp(10).toFloat()
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = dp(8)
                marginStart = dp(8)
            }
        }
        coverArea.addView(numBadge)

        if (savedDelay > 0.005) {
            val delayBadge = TextView(this).apply {
                text = String.format(Locale.US, "%.2f ث", savedDelay)
                setTextColor(COLOR_WARNING)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = appTypefaceBold
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(3), dp(7), dp(3))
                background = roundedDrawable(
                    fillColor = Color.parseColor("#AA121212"),
                    strokeColor = COLOR_WARNING,
                    strokeWidthPx = dp(0.8f),
                    radiusPx = dp(9).toFloat()
                )
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = dp(8)
                    marginEnd = dp(8)
                }
            }
            coverArea.addView(delayBadge)
        }

        if (isCurrentlyPlaying) {
            val playingTag = TextView(this).apply {
                text = "قيد التشغيل"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = appTypefaceBold
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = roundedDrawable(
                    fillColor = COLOR_ACCENT_BLUE,
                    strokeColor = Color.TRANSPARENT,
                    strokeWidthPx = 0,
                    radiusPx = dp(10).toFloat()
                )
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = dp(8)
                }
            }
            coverArea.addView(playingTag)
        }

        // Lower #1A1A1A channel title & subtitle section
        val infoSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(11), dp(10), dp(11), dp(12))
            layoutParams = matchWrap()
        }

        val nameView = TextView(this).apply {
            text = channel.name
            setTextColor(if (isCurrentlyPlaying) COLOR_ACCENT_BLUE else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = appTypefaceBold
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            minLines = 2
        }

        val subView = TextView(this).apply {
            text = if (isCurrentlyPlaying) "بث مباشر نشط" else extractShortHost(channel.url)
            setTextColor(if (isCurrentlyPlaying) Color.parseColor("#93C5FD") else COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = appTypeface
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = matchWrap().apply { topMargin = dp(4) }
        }

        infoSection.addView(nameView)
        infoSection.addView(subView)

        card.addView(coverArea)
        card.addView(infoSection)
        return card
    }

    private fun extractShortHost(url: String): String {
        return try {
            val cleaned = url.substringAfter("://", url)
            cleaned.substringBefore('/').substringBefore(':').ifBlank { "بث صوتي" }
        } catch (_: Throwable) {
            "بث صوتي"
        }
    }

    // =========================================================================
    // STATE RENDERING & LIVE ELAPSED COUNTER
    // =========================================================================

    private fun updateLiveElapsedCounter() {
        val localElapsed = if (syncUiStartedMs > 0L) {
            ((SystemClock.elapsedRealtime() - syncUiStartedMs) / 1000L).toInt().coerceAtLeast(0)
        } else {
            0
        }
        val elapsedSec = max(latestState.syncElapsedSec, localElapsed)
        val mins = elapsedSec / 60
        val secs = elapsedSec % 60
        val clockPart = String.format(Locale.US, "%02d:%02d", mins, secs)
        tvHomeElapsedPill.text = "الوقت المنقضي: $elapsedSec ث  •  $clockPart"
    }

    private fun renderServiceState(state: ServiceUiState) {
        // Keep screen awake ONLY while auto-sync is actively measuring
        if (state.isSyncing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Manage live elapsed seconds ticker (no timeout)
        if (state.isSyncing && !wasSyncingUi) {
            wasSyncingUi = true
            syncUiStartedMs = SystemClock.elapsedRealtime() - (state.syncElapsedSec * 1000L)
            uiHandler.removeCallbacks(syncElapsedTicker)
            uiHandler.post(syncElapsedTicker)
        } else if (!state.isSyncing && wasSyncingUi) {
            wasSyncingUi = false
            syncUiStartedMs = 0L
            uiHandler.removeCallbacks(syncElapsedTicker)
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
            tvHomeElapsedPill.visibility = View.VISIBLE
            updateLiveElapsedCounter()
            tvHomeSyncSubtext.text = state.syncStatusText.ifBlank {
                "جاري تحليل البصمة الصوتية • اضغط البصمة للإلغاء"
            }
            tvHomeSyncSubtext.visibility = View.VISIBLE
        } else {
            tvHomePrompt.text = "اضغط للمزامنة"
            tvHomeElapsedPill.visibility = View.GONE
            tvHomeSyncSubtext.text = if (state.isPlaying || state.isBuffering) {
                String.format(Locale.US, "%s • %.1f
