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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.widget.ImageView
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
    private var lastPlayingLibIdForUi: String? = null
    private var lastLibrariesRenderKey: String = ""
    private var userDraggingSeekBar = false
    private var updatingLiveEdgeSwitchUi = false
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
    private lateinit var heroEmblemView: View
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
    private lateinit var swLiveEdgeRecovery: Switch

    // === Page 3 (Left — Unified Libraries & Persistent 2-Column Square Channels) UI references ===
    private lateinit var tvLibrariesSubtitle: TextView
    private lateinit var librariesGridScroll: ScrollView
    private lateinit var librariesGridContainer: LinearLayout
    private lateinit var channelsSectionContainer: LinearLayout
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
            libraryManager.ensureLoadedFromDisk()
            refreshLibrariesPageUi()
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
        libraryManager.ensureLoadedFromDisk()
        prefs = FootSyncPrefs(this)
        selectedLibraryId = resolveBestSelectedLibraryId(libraryManager.getLibraries())

        val rootView = buildRootPagerLayout()
        applyFontRecursively(rootView)
        setContentView(rootView)

        refreshLibrariesPageUi()
        requestNotificationPermissionIfNeeded()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pager.currentPage != PAGE_HOME) {
                    pager.scrollToPage(PAGE_HOME, animated = true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val svcIntent = Intent(this, RadioPlaybackService::class.java)
        bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        AppVersionGate.check(this)
    }

    override fun onStart() {
        super.onStart()
        if (!isServiceBound) {
            val svcIntent = Intent(this, RadioPlaybackService::class.java)
            bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        libraryManager.ensureLoadedFromDisk()
        refreshLibrariesPageUi()
        playbackService?.addStateListener(stateListener)
        pulseRingView.resumeAnimation()
    }

    override fun onResume() {
        super.onResume()
        libraryManager.ensureLoadedFromDisk()
        refreshLibrariesPageUi()
        AppVersionGate.check(this)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(syncElapsedTicker)
        wasSyncingUi = false
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
        ioExecutor.shutdown()
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
                if (pageIdx == PAGE_LIBRARIES) {
                    refreshLibrariesPageUi()
                }
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
            setOnClickListener { openSequentialChannelsQuickView() }
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

        val whiteLogoBitmap = loadWhiteLogoBitmap()
        heroEmblemView = if (whiteLogoBitmap != null) {
            ImageView(this).apply {
                setImageBitmap(whiteLogoBitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER)
            }
        } else {
            VectorIconView(this, IconType.FINGERPRINT, Color.WHITE).apply {
                layoutParams = FrameLayout.LayoutParams(dp(106), dp(106), Gravity.CENTER)
            }
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

        // Sync result banner (confirmation / cancellation / error message) in app blue theme with crisp white text
        tvHomeBanner = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = appTypefaceBold
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedDrawable(
                Color.parseColor("#112034"),
                Color.parseColor("#550088FF"),
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

    private fun loadWhiteLogoBitmap(): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(resources, R.drawable.ic_app_logo, boundsOpts)
            val maxDim = max(boundsOpts.outWidth, boundsOpts.outHeight)
            var sample = 1
            while (maxDim / sample > 512) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val src = BitmapFactory.decodeResource(resources, R.drawable.ic_app_logo, decodeOpts)
                ?: return null
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            src.recycle()

            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1

            for (y in 0 until h) {
                val rowOffset = y * w
                for (x in 0 until w) {
                    val idx = rowOffset + x
                    val p = pixels[idx]
                    val srcA = (p ushr 24) and 0xFF
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val lum = max(r, max(g, b))
                    val rawAlpha = if (lum <= 8) 0 else (((lum - 8) * 255) / 247).coerceIn(0, 255)
                    val alpha = (rawAlpha * srcA) / 255
                    pixels[idx] = (alpha shl 24) or 0x00FFFFFF
                    if (alpha > 12) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }

            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            full.setPixels(pixels, 0, w, 0, 0, w, h)

            if (maxX > minX && maxY > minY) {
                val pad = 2
                val cx = (minX - pad).coerceAtLeast(0)
                val cy = (minY - pad).coerceAtLeast(0)
                val cw = (maxX - cx + 1 + pad).coerceAtMost(w - cx)
                val ch = (maxY - cy + 1 + pad).coerceAtMost(h - cy)
                val cropped = Bitmap.createBitmap(full, cx, cy, cw, ch)
                if (cropped !== full) full.recycle()
                cropped
            } else {
                full
            }
        } catch (_: Throwable) {
            null
        }
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

        // 4. Live Edge Recovery & Sync Settings Card
        content.addView(makeSectionHeading("إعدادات المزامنة والبث المباشر").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        })

        val calCard = makePage2Card().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        }

        val liveEdgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap()
        }

        val liveEdgeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        liveEdgeCol.addView(TextView(this).apply {
            text = "الاستمرار بالبث المباشر"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = appTypefaceBold
        })
        liveEdgeCol.addView(TextView(this).apply {
            text = "القفز تلقائياً للحظة المباشرة عند تقطيع الإنترنت مع الحفاظ على المزامنة"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = appTypeface
        })

        swLiveEdgeRecovery = Switch(this).apply {
            isChecked = playbackService?.isLiveEdgeRecoveryEnabled() ?: loadLiveEdgeRecoveryPrefLocal()
            typeface = appTypeface
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(COLOR_ACCENT_BLUE, Color.parseColor("#444444"))
            )
            setOnCheckedChangeListener { _, isChecked ->
                if (updatingLiveEdgeSwitchUi) return@setOnCheckedChangeListener
                playbackService?.setLiveEdgeRecoveryEnabled(isChecked)
                saveLiveEdgeRecoveryPrefLocal(isChecked)
            }
        }

        liveEdgeRow.addView(liveEdgeCol)
        liveEdgeRow.addView(swLiveEdgeRecovery)

        calCard.addView(liveEdgeRow)
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

        // Unified ScrollView: Compact 2-Column Square Libraries on top + Persistent 2-Column Square Channels directly below
        librariesGridScroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val unifiedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(14), dp(2), dp(14), dp(28))
            layoutParams = matchWrap()
        }

        librariesGridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = matchWrap()
        }

        channelsSectionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            visibility = View.GONE
            layoutParams = matchWrap().apply { topMargin = dp(16) }
        }

        val sectionDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#262626"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply { bottomMargin = dp(14) }
        }

        channelsGridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = matchWrap()
        }

        channelsSectionContainer.addView(sectionDivider)
        channelsSectionContainer.addView(channelsGridContainer)

        unifiedContent.addView(librariesGridContainer)
        unifiedContent.addView(channelsSectionContainer)
        librariesGridScroll.addView(unifiedContent)

        page.addView(topBar)
        page.addView(tvLibrariesSubtitle)
        page.addView(librariesGridScroll)
        return page
    }

    private fun buildFeaturedAddLibraryCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = roundedDrawable(
                fillColor = COLOR_LIB_CARD_BG,
                strokeColor = Color.parseColor("#2E2E2E"),
                strokeWidthPx = dp(1),
                radiusPx = dp(16).toFloat()
            )
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
            setOnClickListener { showAddLibraryChooserDialog() }
        }
        applySpringTouch(card)

        // True 1:1 square upper area matching Shazam library cards
        val coverArea = SquareFrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#232323"))
                val r = dp(16).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }

        val coverContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }

        val musicIcon = VectorIconView(this, IconType.MUSIC_NOTE, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }

        val title = TextView(this).apply {
            text = "إضافة مكتبة صوتيات"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = appTypefaceBold
            gravity = Gravity.CENTER
            maxLines = 2
            layoutParams = matchWrap().apply { topMargin = dp(8) }
        }

        val desc = TextView(this).apply {
            text = "M3U / JSON / XML / TXT / GX"
            setTextColor(COLOR_GRAY_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = appTypeface
            gravity = Gravity.CENTER
            maxLines = 2
            layoutParams = matchWrap().apply { topMargin = dp(4) }
        }

        coverContent.addView(musicIcon)
        coverContent.addView(title)
        coverContent.addView(desc)
        coverArea.addView(coverContent)

        val footerArea = FrameLayout(this).apply {
            setPadding(dp(10), dp(14), dp(10), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(70)
            )
        }

        val ctaButton = TextView(this).apply {
            text = "إضافة مكتبة"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = appTypefaceBold
            gravity = Gravity.CENTER
            background = rippleButtonDrawable(
                fillColor = Color.WHITE,
                strokeColor = Color.TRANSPARENT,
                radiusPx = dp(10).toFloat()
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { showAddLibraryChooserDialog() }
        }
        applySpringTouch(ctaButton)
        footerArea.addView(ctaButton)

        card.addView(coverArea)
        card.addView(footerArea)
        return card
    }

    private fun buildShazamLibraryCard(lib: RadioLibrary): View {
        val isPlayingLib = latestState.libraryId == lib.id && latestState.channelIndex >= 0
        val isSelected = selectedLibraryId == lib.id

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = roundedDrawable(
                fillColor = COLOR_LIB_CARD_BG,
                strokeColor = if (isSelected || isPlayingLib) COLOR_ACCENT_BLUE else Color.parseColor("#262626"),
                strokeWidthPx = if (isSelected || isPlayingLib) dp(1.5f) else dp(1),
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
                refreshLibrariesPageUi()
                if (lib.isManualDefault && lib.channels.isEmpty()) {
                    showAddManualUrlDialog()
                } else if (lib.channels.isNotEmpty()) {
                    scrollToChannelsSection()
                }
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
            layoutParams = FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER)
            background = if (isPlayingLib || isSelected) {
                roundedDrawable(
                    fillColor = Color.parseColor("#220A84FF"),
                    strokeColor = COLOR_ACCENT_BLUE,
                    strokeWidthPx = dp(2),
                    radiusPx = dp(34).toFloat()
                )
            } else {
                null
            }
            addView(
                VectorIconView(
                    this@MainActivity,
                    IconType.MUSIC_NOTE,
                    if (isPlayingLib || isSelected) COLOR_ACCENT_BLUE else Color.parseColor("#545454")
                ),
                FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)
            )
        }
        coverArea.addView(iconCircle)

        if (lib.isManualDefault) {
            val addManualBtn = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(6)
                    marginEnd = dp(6)
                }
                background = rippleButtonDrawable(Color.parseColor("#55000000"), Color.TRANSPARENT, dp(17).toFloat())
                setOnClickListener { showAddManualUrlDialog() }
                addView(
                    VectorIconView(this@MainActivity, IconType.PLUS, Color.WHITE),
                    FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER)
                )
            }
            coverArea.addView(addManualBtn)
        } else {
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

        card.addView(coverArea)

        if (lib.isManualDefault) {
            val footerArea = FrameLayout(this).apply {
                setPadding(dp(10), dp(14), dp(10), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(70)
                )
            }

            val manualBoxButton = TextView(this).apply {
                text = "روابط يدوية"
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = appTypefaceBold
                gravity = Gravity.CENTER
                background = rippleButtonDrawable(
                    fillColor = Color.WHITE,
                    strokeColor = Color.TRANSPARENT,
                    radiusPx = dp(10).toFloat()
                )
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setOnClickListener { showAddManualUrlDialog() }
            }
            applySpringTouch(manualBoxButton)
            footerArea.addView(manualBoxButton)
            card.addView(footerArea)
        } else {
            val infoSection = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(12), dp(6), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(70)
                )
            }

            val nameView = TextView(this).apply {
                text = lib.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = appTypefaceBold
                maxLines = 1
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
                setTextColor(if (isPlayingLib || isSelected) COLOR_ACCENT_BLUE else COLOR_GRAY_SUBTITLE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = appTypeface
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = matchWrap().apply { topMargin = dp(2) }
            }

            infoSection.addView(nameView)
            infoSection.addView(countView)
            card.addView(infoSection)
        }
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
                String.format(Locale.US, "%s • %.1f KB/s", state.connectionStatus, state.speedKbps)
            } else {
                ""
            }
            tvHomeSyncSubtext.visibility =
                if (tvHomeSyncSubtext.text.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        }

        if (!state.isSyncing && state.lastSyncBannerText.isNotBlank()) {
            tvHomeBanner.text = state.lastSyncBannerText
            tvHomeBanner.visibility = View.VISIBLE
        } else {
            tvHomeBanner.visibility = View.GONE
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

        if (swLiveEdgeRecovery.isChecked != state.liveEdgeRecoveryEnabled) {
            updatingLiveEdgeSwitchUi = true
            swLiveEdgeRecovery.isChecked = state.liveEdgeRecoveryEnabled
            updatingLiveEdgeSwitchUi = false
        }

        if (!state.libraryId.isNullOrBlank() && state.libraryId != lastPlayingLibIdForUi) {
            lastPlayingLibIdForUi = state.libraryId
            selectedLibraryId = state.libraryId
        }

        // --- Page 3 (Left Libraries / Channels) highlight refresh only when active station changes ---
        val libRenderKey = "${state.libraryId}|${state.channelIndex}|${state.channelUrl}|${state.totalChannels}"
        if (libRenderKey != lastLibrariesRenderKey) {
            lastLibrariesRenderKey = libRenderKey
            refreshLibrariesPageUi()
        }
    }

    private fun openSequentialChannelsQuickView() {
        val libs = libraryManager.getLibraries()
        val targetLibId = latestState.libraryId
            ?.takeIf { id -> libs.any { it.id == id } }
            ?: selectedLibraryId.takeIf { id -> libs.any { it.id == id } }
            ?: libs.firstOrNull { it.channels.isNotEmpty() }?.id
            ?: libs.firstOrNull()?.id

        if (targetLibId != null) {
            selectedLibraryId = targetLibId
            libraryManager.setActiveLibrary(targetLibId)
        }
        refreshLibrariesPageUi()
        pager.scrollToPage(PAGE_LIBRARIES, animated = true)

        val activeLib = libs.firstOrNull { it.id == selectedLibraryId }
        if (activeLib != null && activeLib.channels.isNotEmpty()) {
            scrollToChannelsSection()
        }
    }

    private fun scrollToChannelsSection() {
        librariesGridScroll.post {
            val targetY = (channelsSectionContainer.top - dp(4)).coerceAtLeast(0)
            librariesGridScroll.smoothScrollTo(0, targetY)
        }
    }

    private fun resolveBestSelectedLibraryId(libs: List<RadioLibrary>): String {
        if (libs.isEmpty()) return LibraryManager.MANUAL_LIBRARY_ID
        val currentSelected = libs.firstOrNull { it.id == selectedLibraryId }
        if (currentSelected != null && currentSelected.channels.isNotEmpty()) {
            return currentSelected.id
        }
        val playingLib = latestState.libraryId?.let { id ->
            libs.firstOrNull { it.id == id && it.channels.isNotEmpty() }
        }
        if (playingLib != null) return playingLib.id

        val lastPlayedLib = prefs.getLastPlayingLibraryId()?.let { id ->
            libs.firstOrNull { it.id == id && it.channels.isNotEmpty() }
        }
        if (lastPlayedLib != null) return lastPlayedLib.id

        val activeSavedLib = prefs.getActiveLibraryId()?.let { id ->
            libs.firstOrNull { it.id == id && it.channels.isNotEmpty() }
        }
        if (activeSavedLib != null) return activeSavedLib.id

        val firstNonEmpty = libs.firstOrNull { it.channels.isNotEmpty() }
        if (firstNonEmpty != null) return firstNonEmpty.id

        return currentSelected?.id ?: libs.first().id
    }

    private fun refreshLibrariesPageUi() {
        val libs = libraryManager.getLibraries()
        selectedLibraryId = resolveBestSelectedLibraryId(libs)

        val activeLib = libs.firstOrNull { it.id == selectedLibraryId } ?: libs.first()
        tvLibrariesSubtitle.visibility = View.VISIBLE
        librariesGridScroll.visibility = View.VISIBLE
        renderLibrariesTwoColumnGrid(libs)
        renderInPageChannelGrid(activeLib)
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = dp(6)
            }
            row.addView(leftCard)

            if (i + 1 < cards.size) {
                val rightCard = cards[i + 1]
                rightCard.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
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
        applyFontRecursively(librariesGridContainer)
    }

    private fun renderInPageChannelGrid(lib: RadioLibrary) {
        channelsGridContainer.removeAllViews()
        if (lib.channels.isEmpty()) {
            channelsSectionContainer.visibility = View.GONE
            return
        }
        channelsSectionContainer.visibility = View.VISIBLE

        val total = lib.channels.size
        var i = 0
        while (i < total) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                layoutParams = matchWrap().apply {
                    if (i > 0) topMargin = dp(12)
                }
            }

            val firstCard = buildShazamChannelCard(lib, i, lib.channels[i]).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginEnd = dp(6)
                }
            }
            row.addView(firstCard)

            if (i + 1 < total) {
                val secondCard = buildShazamChannelCard(lib, i + 1, lib.channels[i + 1]).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginStart = dp(6)
                    }
                }
                row.addView(secondCard)
            } else {
                val emptySlot = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                        marginStart = dp(6)
                    }
                }
                row.addView(emptySlot)
            }

            channelsGridContainer.addView(row)
            i += 2
        }
        applyFontRecursively(channelsGridContainer)
    }

    private fun loadLiveEdgeRecoveryPrefLocal(): Boolean {
        return try {
            applicationContext
                .getSharedPreferences(LIVE_EDGE_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_LIVE_EDGE_RECOVERY, true)
        } catch (_: Throwable) {
            true
        }
    }

    private fun saveLiveEdgeRecoveryPrefLocal(enabled: Boolean) {
        try {
            applicationContext
                .getSharedPreferences(LIVE_EDGE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LIVE_EDGE_RECOVERY, enabled)
                .apply()
        } catch (_: Throwable) {
        }
    }

    // =========================================================================
    // BUSINESS ACTIONS & DIALOGS
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

    private fun showStyledDialog(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.window?.decorView?.let { applyFontRecursively(it) }
        }
        dialog.show()
    }

    private fun showAddLibraryChooserDialog() {
        val options = arrayOf(
            "استيراد ملف من الهاتف (M3U / JSON / XML / TXT / CFG)",
            "استيراد من رابط قائمة (URL)",
            "لصق نص القائمة مباشرة",
            "إضافة رابط بث مباشر يدوي"
        )
        showStyledDialog(
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
        )
    }

    private fun promptLibraryNameAndImportUri(uri: Uri) {
        val defaultName = libraryManager.queryDisplayName(uri)
        val input = makeDialogEditText("اسم المكتبة", defaultName)

        showStyledDialog(
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
        )
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

        showStyledDialog(
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
        )
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

        showStyledDialog(
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
        )
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

        showStyledDialog(
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
        )
    }

    private fun handleImportResult(res: ImportResult) {
        if (res.success && res.library != null) {
            selectedLibraryId = res.library.id
            refreshLibrariesPageUi()
            scrollToChannelsSection()
            toast("تم استيراد ${res.channelCount} قناة (${res.detectedFormat}) إلى \"${res.library.name}\"")
        } else {
            toast(res.errorMessage ?: "فشل استيراد المكتبة.")
        }
    }

    private fun showManageLibraryDialog(libraryId: String) {
        val lib = libraryManager.getLibraryById(libraryId) ?: return
        if (lib.isManualDefault) return

        val actions = arrayOf("إعادة تسمية المكتبة", "حذف المكتبة")
        showStyledDialog(
            AlertDialog.Builder(this)
                .setTitle(lib.name)
                .setItems(actions) { _, which ->
                    when (which) {
                        0 -> {
                            val input = makeDialogEditText("الاسم الجديد", lib.name)
                            showStyledDialog(
                                AlertDialog.Builder(this)
                                    .setTitle("إعادة تسمية المكتبة")
                                    .setView(wrapDialogView(input))
                                    .setPositiveButton("حفظ") { _, _ ->
                                        if (libraryManager.renameLibrary(lib.id, input.text.toString())) {
                                            refreshLibrariesPageUi()
                                        }
                                    }
                                    .setNegativeButton("إلغاء", null)
                            )
                        }

                        1 -> {
                            showStyledDialog(
                                AlertDialog.Builder(this)
                                    .setTitle("حذف المكتبة")
                                    .setMessage("هل تريد حذف مكتبة \"${lib.name}\" وجميع قنواتها (${lib.channels.size})؟")
                                    .setPositiveButton("حذف") { _, _ ->
                                        if (libraryManager.deleteLibrary(lib.id)) {
                                            selectedLibraryId = libraryManager.getActiveLibrary().id
                                            refreshLibrariesPageUi()
                                            toast("تم حذف المكتبة.")
                                        }
                                    }
                                    .setNegativeButton("إلغاء", null)
                            )
                        }
                    }
                }
                .setNegativeButton("إغلاق", null)
        )
    }

    private fun confirmDeleteChannel(lib: RadioLibrary, index: Int, channel: RadioChannel) {
        showStyledDialog(
            AlertDialog.Builder(this)
                .setTitle("حذف القناة")
                .setMessage("حذف \"${channel.name}\" من مكتبة \"${lib.name}\"؟")
                .setPositiveButton("حذف") { _, _ ->
                    if (libraryManager.deleteChannel(lib.id, index)) {
                        refreshLibrariesPageUi()
                    }
                }
                .setNegativeButton("إلغاء", null)
        )
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
    // UI STYLING, FONT PROPAGATION & ANIMATION HELPERS
    // =========================================================================

    private fun applyFontRecursively(view: View) {
        if (view is TextView) {
            val isBold = view.typeface?.isBold == true
            view.typeface = if (isBold) appTypefaceBold else appTypeface
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyFontRecursively(view.getChildAt(i))
            }
        }
    }

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
            typeface = appTypefaceBold
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
            typeface = appTypefaceBold
            gravity = Gravity.CENTER
            minHeight = dp(heightDp)
            background = rippleButtonDrawable(bgColor, strokeColor, dp(12).toFloat())
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
            typeface = appTypeface
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
    // CUSTOM VIEWS: SQUARE COVER FRAME, SNAP PAGER, PULSE RINGS, VECTOR ICONS
    // =========================================================================

    private class SquareFrameLayout(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }
    }

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
            // Suppress free-scrolling fling; snapping is performed cleanly on ACTION_UP
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
        FINGERPRINT,
        RADIO_WAVE,
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
                    canvas.drawCircle(s * 0.40f, s * 0.28f, s * 0.16f, fillPaint)
                    rect.set(s * 0.12f, s * 0.52f, s * 0.68f, s * 0.96f)
                    canvas.drawArc(rect, 180f, 180f, true, fillPaint)
                    strokePaint.strokeWidth = s * 0.08f
                    canvas.drawLine(s * 0.78f, s * 0.46f, s * 0.78f, s * 0.80f, strokePaint)
                    canvas.drawLine(s * 0.78f, s * 0.46f, s * 0.92f, s * 0.52f, strokePaint)
                    canvas.drawCircle(s * 0.72f, s * 0.82f, s * 0.09f, fillPaint)
                }

                IconType.DELAY -> {
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

                IconType.FINGERPRINT -> {
                    strokePaint.strokeWidth = s * 0.056f

                    // 1. Outermost top crest arch
                    rect.set(s * 0.12f, s * 0.08f, s * 0.88f, s * 0.84f)
                    canvas.drawArc(rect, -135f, 90f, false, strokePaint)

                    // 2. Second outer arch spanning upper shoulders
                    rect.set(s * 0.07f, s * 0.18f, s * 0.93f, s * 1.04f)
                    canvas.drawArc(rect, -152f, 124f, false, strokePaint)

                    // 3. Third ridge: left curve + top arch + right curve hooking inward at bottom-right
                    path.reset()
                    path.moveTo(s * 0.16f, s * 0.76f)
                    path.cubicTo(s * 0.10f, s * 0.55f, s * 0.14f, s * 0.30f, s * 0.50f, s * 0.30f)
                    path.cubicTo(s * 0.80f, s * 0.30f, s * 0.89f, s * 0.50f, s * 0.85f, s * 0.65f)
                    path.cubicTo(s * 0.82f, s * 0.76f, s * 0.66f, s * 0.78f, s * 0.61f, s * 0.65f)
                    path.cubicTo(s * 0.58f, s * 0.56f, s * 0.56f, s * 0.50f, s * 0.50f, s * 0.50f)
                    path.cubicTo(s * 0.40f, s * 0.50f, s * 0.36f, s * 0.61f, s * 0.40f, s * 0.74f)
                    path.cubicTo(s * 0.44f, s * 0.84f, s * 0.53f, s * 0.90f, s * 0.64f, s * 0.93f)
                    canvas.drawPath(path, strokePaint)

                    // 4. Fourth ridge: inner-left arch curling cleanly down to bottom-left
                    path.reset()
                    path.moveTo(s * 0.39f, s * 0.91f)
                    path.cubicTo(s * 0.25f, s * 0.78f, s * 0.22f, s * 0.56f, s * 0.31f, s * 0.45f)
                    path.cubicTo(s * 0.41f, s * 0.36f, s * 0.63f, s * 0.37f, s * 0.71f, s * 0.51f)
                    path.cubicTo(s * 0.74f, s * 0.57f, s * 0.74f, s * 0.62f, s * 0.73f, s * 0.66f)
                    canvas.drawPath(path, strokePaint)

                    // 5. Center core stem curving down and right toward bottom-right
                    path.reset()
                    path.moveTo(s * 0.50f, s * 0.61f)
                    path.cubicTo(s * 0.50f, s * 0.75f, s * 0.60f, s * 0.85f, s * 0.78f, s * 0.84f)
                    canvas.drawPath(path, strokePaint)
                }

                IconType.RADIO_WAVE -> {
                    canvas.drawCircle(s * 0.50f, s * 0.52f, s * 0.11f, fillPaint)
                    strokePaint.strokeWidth = s * 0.08f
                    rect.set(s * 0.28f, s * 0.30f, s * 0.72f, s * 0.74f)
                    canvas.drawArc(rect, -50f, 100f, false, strokePaint)
                    canvas.drawArc(rect, 130f, 100f, false, strokePaint)
                    rect.set(s * 0.12f, s * 0.14f, s * 0.88f, s * 0.90f)
                    canvas.drawArc(rect, -48f, 96f, false, strokePaint)
                    canvas.drawArc(rect, 132f, 96f, false, strokePaint)
                }

                IconType.PREV -> {
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
                    strokePaint.strokeWidth = s * 0.085f
                    rect.set(s * 0.12f, s * 0.18f, s * 0.88f, s * 0.82f)
                    canvas.drawRoundRect(rect, s * 0.10f, s * 0.10f, strokePaint)
                    rect.set(s * 0.46f, s * 0.46f, s * 0.80f, s * 0.74f)
                    canvas.drawRoundRect(rect, s * 0.06f, s * 0.06f, fillPaint)
                }

                IconType.GEAR -> {
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
        private const val APP_FONT_ASSET_PATH = "fonts/app_font.ttf"
        private const val LIVE_EDGE_PREFS_NAME = "footsync_live_edge_prefs"
        private const val KEY_LIVE_EDGE_RECOVERY = "live_edge_recovery_enabled"

        private const val PAGE_LIBRARIES = 0
        private const val PAGE_HOME = 1
        private const val PAGE_DELAY = 2

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
