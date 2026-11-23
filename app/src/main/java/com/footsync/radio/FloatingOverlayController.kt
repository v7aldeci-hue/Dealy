package com.footsync.radio

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

/**
 * Immutable UI snapshot pushed from [RadioPlaybackService] to [FloatingOverlayController].
 */
data class OverlayState(
    val stationName: String = "لا توجد قناة مشغّلة",
    val channelBadge: String = "",
    val delaySec: Double = 0.0,
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val isSyncing: Boolean = false,
    val syncElapsedSec: Int = 0,
    val syncStatusText: String = ""
)

/**
 * Touch actions dispatched by [FloatingOverlayController] back to [RadioPlaybackService].
 */
interface OverlayCallbacks {
    fun onOverlayPrevChannel()
    fun onOverlayNextChannel()
    fun onOverlayAdjustDelay(deltaSec: Double)
    fun onOverlayTogglePlayPause()
    fun onOverlayToggleMute()
    fun onOverlayToggleSync()
    fun onOverlayOpenApp()
    fun onOverlayHidden()
}

/**
 * Lightweight, touch-friendly floating overlay for Android smartphones using `fonts/app_font.ttf`.
 */
class FloatingOverlayController(
    context: Context,
    private val callbacks: OverlayCallbacks
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = FootSyncPrefs(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop

    private val appTypeface: Typeface = try {
        Typeface.createFromAsset(appContext.assets, APP_FONT_ASSET_PATH)
    } catch (_: Throwable) {
        Typeface.DEFAULT
    }
    private val appTypefaceBold: Typeface = try {
        Typeface.create(appTypeface, Typeface.BOLD)
    } catch (_: Throwable) {
        appTypeface
    }

    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var isMinimized = false
    private var currentState = OverlayState()

    // Expanded panel views
    private var expandedContainer: LinearLayout? = null
    private var tvStation: TextView? = null
    private var tvDelay: TextView? = null
    private var tvSyncStatus: TextView? = null
    private var btnPlayPause: TextView? = null
    private var btnMute: TextView? = null
    private var btnSync: TextView? = null

    // Minimized bubble views
    private var minimizedPill: LinearLayout? = null
    private var pillDot: View? = null
    private var tvPillDelay: TextView? = null

    val isVisible: Boolean
        get() = isShowing

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    fun show(startMinimized: Boolean = false): Boolean {
        if (!canDrawOverlays()) return false
        runOnMain {
            isMinimized = startMinimized
            if (isShowing) {
                applyModeVisibility()
                renderState(currentState)
                return@runOnMain
            }
            createAndAttachView()
        }
        return true
    }

    fun hide() {
        runOnMain {
            if (!isShowing) return@runOnMain
            val view = rootView
            if (view != null) {
                try {
                    windowManager.removeView(view)
                } catch (_: Throwable) {
                }
            }
            rootView = null
            layoutParams = null
            expandedContainer = null
            minimizedPill = null
            tvStation = null
            tvDelay = null
            tvSyncStatus = null
            btnPlayPause = null
            btnMute = null
            btnSync = null
            pillDot = null
            tvPillDelay = null
            isShowing = false
            callbacks.onOverlayHidden()
        }
    }

    fun updateState(state: OverlayState) {
        currentState = state
        runOnMain {
            if (isShowing) {
                renderState(state)
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun createAndAttachView() {
        val dm = appContext.resources.displayMetrics
        val defaultX = dp(12)
        val defaultY = (dm.heightPixels * 0.18f).toInt()

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getOverlayX(defaultX).coerceIn(0, (dm.widthPixels - dp(80)).coerceAtLeast(0))
            y = prefs.getOverlayY(defaultY).coerceIn(dp(24), (dm.heightPixels - dp(80)).coerceAtLeast(dp(24)))
        }

        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val expanded = buildExpandedPanel()
        val minimized = buildMinimizedPill()

        root.addView(expanded)
        root.addView(minimized)
        applyFontRecursively(root)

        rootView = root
        layoutParams = params
        expandedContainer = expanded
        minimizedPill = minimized

        try {
            windowManager.addView(root, params)
            isShowing = true
            applyModeVisibility()
            renderState(currentState)
        } catch (_: Throwable) {
            rootView = null
            layoutParams = null
            isShowing = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildExpandedPanel(): LinearLayout {
        val dm = appContext.resources.displayMetrics
        val panelWidth = (dm.widthPixels - dp(28)).coerceIn(dp(276), dp(340))

        val card = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = LinearLayout.LayoutParams(panelWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedDrawable(
                fillColor = COLOR_BG_PANEL,
                strokeColor = COLOR_BORDER,
                strokeWidthPx = dp(1),
                radiusPx = dp(18).toFloat()
            )
            elevation = dp(10).toFloat()
        }

        val headerRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val stationView = TextView(appContext).apply {
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = appTypefaceBold
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { callbacks.onOverlayOpenApp() }
        }
        tvStation = stationView

        val btnMin = makeHeaderChip("تصغير", COLOR_BTN_NEUTRAL, COLOR_TEXT_PRIMARY).apply {
            setOnClickListener {
                isMinimized = true
                applyModeVisibility()
            }
        }

        val btnClose = makeHeaderChip("إخفاء", COLOR_BTN_DANGER_SOFT, COLOR_DANGER).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
            setOnClickListener { hide() }
        }

        headerRow.addView(stationView)
        headerRow.addView(btnMin)
        headerRow.addView(btnClose)
        attachDragHandler(headerRow, onTap = null)

        val delayRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val btnPrev = makeActionButton("السابق", COLOR_BTN_NEUTRAL, COLOR_TEXT_PRIMARY, heightDp = 36).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            setOnClickListener { callbacks.onOverlayPrevChannel() }
        }

        val delayView = TextView(appContext).apply {
            setTextColor(COLOR_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = appTypefaceBold
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        }
        tvDelay = delayView

        val btnNext = makeActionButton("التالي", COLOR_BTN_NEUTRAL, COLOR_TEXT_PRIMARY, heightDp = 36).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            setOnClickListener { callbacks.onOverlayNextChannel() }
        }

        delayRow.addView(btnPrev)
        delayRow.addView(delayView)
        delayRow.addView(btnNext)

        val syncStatusView = TextView(appContext).apply {
            setTextColor(COLOR_WARNING)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = appTypeface
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }
        tvSyncStatus = syncStatusView

        val tuneRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val steps = listOf(
            "-1 ث" to -1.0,
            "-0.25" to -0.25,
            "+0.25" to 0.25,
            "+1 ث" to 1.0
        )
        steps.forEachIndexed { idx, (label, delta) ->
            val btn = makeActionButton(label, COLOR_BTN_SUBTLE, COLOR_TEXT_PRIMARY, heightDp = 36).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    if (idx > 0) marginStart = dp(5)
                }
                setOnClickListener { callbacks.onOverlayAdjustDelay(delta) }
            }
            tuneRow.addView(btn)
        }

        val controlRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val playBtn = makeActionButton("تشغيل", COLOR_BTN_NEUTRAL, COLOR_TEXT_PRIMARY, heightDp = 38).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            setOnClickListener { callbacks.onOverlayTogglePlayPause() }
        }
        btnPlayPause = playBtn

        val muteBtn = makeActionButton("كتم", COLOR_BTN_NEUTRAL, COLOR_TEXT_PRIMARY, heightDp = 38).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener { callbacks.onOverlayToggleMute() }
        }
        btnMute = muteBtn

        val syncBtn = makeActionButton("زامن", COLOR_ACCENT_BLUE, Color.WHITE, heightDp = 38).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1.15f).apply {
                marginStart = dp(6)
            }
            setOnClickListener { callbacks.onOverlayToggleSync() }
        }
        btnSync = syncBtn

        controlRow.addView(playBtn)
        controlRow.addView(muteBtn)
        controlRow.addView(syncBtn)

        card.addView(headerRow)
        card.addView(delayRow)
        card.addView(syncStatusView)
        card.addView(tuneRow)
        card.addView(controlRow)

        return card
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildMinimizedPill(): LinearLayout {
        val pill = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedDrawable(
                fillColor = COLOR_BG_PILL,
                strokeColor = COLOR_ACCENT_BLUE,
                strokeWidthPx = dp(1),
                radiusPx = dp(24).toFloat()
            )
            elevation = dp(10).toFloat()
        }

        val dot = View(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                marginEnd = dp(7)
            }
            background = roundedDrawable(
                fillColor = COLOR_ACCENT,
                strokeColor = Color.TRANSPARENT,
                strokeWidthPx = 0,
                radiusPx = dp(5).toFloat()
            )
        }
        pillDot = dot

        val delayText = TextView(appContext).apply {
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = appTypefaceBold
            text = "0.00 ث"
        }
        tvPillDelay = delayText

        pill.addView(dot)
        pill.addView(delayText)

        attachDragHandler(pill) {
            isMinimized = false
            applyModeVisibility()
        }

        return pill
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragHandler(targetView: View, onTap: (() -> Unit)?) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        targetView.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            val root = rootView ?: return@setOnTouchListener false
            val dm = appContext.resources.displayMetrics

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val maxX = (dm.widthPixels - root.width.coerceAtLeast(dp(64))).coerceAtLeast(0)
                        val maxY = (dm.heightPixels - root.height.coerceAtLeast(dp(48))).coerceAtLeast(dp(24))
                        params.x = (initialX + dx).coerceIn(0, maxX)
                        params.y = (initialY + dy).coerceIn(dp(20), maxY)
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        prefs.saveOverlayPosition(params.x, params.y)
                    } else {
                        onTap?.invoke()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun applyModeVisibility() {
        expandedContainer?.visibility = if (isMinimized) View.GONE else View.VISIBLE
        minimizedPill?.visibility = if (isMinimized) View.VISIBLE else View.GONE
        val root = rootView
        val params = layoutParams
        if (root != null && params != null && isShowing) {
            val dm = appContext.resources.displayMetrics
            if (!isMinimized) {
                val maxX = (dm.widthPixels - dp(290)).coerceAtLeast(0)
                params.x = params.x.coerceIn(0, maxX)
            }
            try {
                windowManager.updateViewLayout(root, params)
            } catch (_: Throwable) {
            }
        }
    }

    private fun renderState(state: OverlayState) {
        val delayLabel = String.format(Locale.US, "%.2f ث", state.delaySec)
        val titleText = if (state.channelBadge.isNotBlank()) {
            "${state.channelBadge} • ${state.stationName}"
        } else {
            state.stationName
        }

        tvStation?.text = titleText
        tvDelay?.text = delayLabel
        tvPillDelay?.text = if (state.isSyncing) {
            "مزامنة ${state.syncElapsedSec} ث"
        } else {
            delayLabel
        }

        btnPlayPause?.text = if (state.isPlaying) "إيقاف" else "تشغيل"
        btnMute?.text = if (state.isMuted) "إلغاء الكتم" else "كتم"
        btnMute?.background = rippleButtonDrawable(
            if (state.isMuted) COLOR_WARNING_SOFT else COLOR_BTN_NEUTRAL,
            if (state.isMuted) COLOR_WARNING else Color.TRANSPARENT,
            dp(10).toFloat()
        )
        btnMute?.setTextColor(if (state.isMuted) COLOR_WARNING else COLOR_TEXT_PRIMARY)

        btnSync?.text = if (state.isSyncing) "إلغاء (${state.syncElapsedSec} ث)" else "زامن"
        btnSync?.background = rippleButtonDrawable(
            if (state.isSyncing) COLOR_DANGER else COLOR_ACCENT_BLUE,
            Color.TRANSPARENT,
            dp(10).toFloat()
        )
        btnSync?.setTextColor(Color.WHITE)

        if (state.isSyncing && state.syncStatusText.isNotBlank()) {
            tvSyncStatus?.text = state.syncStatusText
            tvSyncStatus?.visibility = View.VISIBLE
        } else {
            tvSyncStatus?.visibility = View.GONE
        }

        val dotColor = when {
            state.isSyncing -> COLOR_ACCENT_BLUE
            state.isMuted -> COLOR_DANGER
            state.isPlaying -> COLOR_ACCENT
            else -> COLOR_TEXT_MUTED
        }
        pillDot?.background = roundedDrawable(
            fillColor = dotColor,
            strokeColor = Color.TRANSPARENT,
            strokeWidthPx = 0,
            radiusPx = dp(5).toFloat()
        )
    }

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

    private fun makeHeaderChip(text: String, bgColor: Int, textColor: Int): TextView {
        return TextView(appContext).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = appTypefaceBold
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(5), dp(9), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = rippleButtonDrawable(bgColor, Color.TRANSPARENT, dp(9).toFloat())
        }
    }

    private fun makeActionButton(
        text: String,
        bgColor: Int,
        textColor: Int,
        heightDp: Int
    ): TextView {
        return TextView(appContext).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = appTypefaceBold
            gravity = Gravity.CENTER
            minHeight = dp(heightDp)
            background = rippleButtonDrawable(bgColor, Color.TRANSPARENT, dp(10).toFloat())
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

    private fun dp(value: Int): Int {
        return (value * appContext.resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        private const val APP_FONT_ASSET_PATH = "fonts/app_font.ttf"

        private val COLOR_BG_PANEL = Color.parseColor("#EE121212")
        private val COLOR_BG_PILL = Color.parseColor("#F00A0D12")
        private val COLOR_BORDER = Color.parseColor("#33475569")
        private val COLOR_BTN_NEUTRAL = Color.parseColor("#1E1E1E")
        private val COLOR_BTN_SUBTLE = Color.parseColor("#2A2A2A")
        private val COLOR_ACCENT = Color.parseColor("#22C55E")
        private val COLOR_ACCENT_BLUE = Color.parseColor("#0A84FF")
        private val COLOR_WARNING = Color.parseColor("#FBBF24")
        private val COLOR_WARNING_SOFT = Color.parseColor("#33FBBF24")
        private val COLOR_DANGER = Color.parseColor("#EF4444")
        private val COLOR_BTN_DANGER_SOFT = Color.parseColor("#33EF4444")
        private val COLOR_TEXT_PRIMARY = Color.parseColor("#FFFFFF")
        private val COLOR_TEXT_MUTED = Color.parseColor("#9E9E9E")
    }
}
