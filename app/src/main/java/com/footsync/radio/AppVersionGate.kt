package com.footsync.radio

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object AppVersionGate {

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/v7aldeci-hue/Shizam-v1/main/config.json"

    private const val TIMEOUT_MS = 5_000
    private const val APP_FONT_ASSET_PATH = "fonts/app_font.ttf"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val checking = AtomicBoolean(false)

    private var activeDialogRef: WeakReference<AlertDialog>? = null

    fun check(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        val existing = activeDialogRef?.get()
        if (existing != null && existing.isShowing) return

        if (!checking.compareAndSet(false, true)) return

        val activityRef = WeakReference(activity)
        val currentVersionCode = getCurrentVersionCode(activity)

        executor.execute {
            val config = fetchConfig()
            mainHandler.post {
                checking.set(false)
                val act = activityRef.get() ?: return@post
                if (act.isFinishing || act.isDestroyed) return@post

                if (config == null) {
                    showNetworkErrorDialog(act)
                } else if (config.forceStop || currentVersionCode < config.minVersionCode) {
                    stopPlaybackService(act)
                    showUpdateBlockedDialog(act, config)
                }
            }
        }
    }

    private data class RemoteConfig(
        val minVersionCode: Int,
        val forceStop: Boolean,
        val title: String,
        val message: String,
        val updateUrl: String
    )

    private fun fetchConfig(): RemoteConfig? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$CONFIG_URL?t=${System.currentTimeMillis()}")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                useCaches = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
            }

            if (conn.responseCode !in 200..299) return null

            val body = BufferedReader(
                InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)
            ).use { it.readText() }

            val json = JSONObject(body)
            RemoteConfig(
                minVersionCode = json.optInt("minVersionCode", 1),
                forceStop = json.optBoolean("forceStop", false),
                title = json.optString("title", "").ifBlank { "تحديث إجباري" },
                message = json.optString("message", "").ifBlank {
                    "انتهت صلاحية هذا الإصدار. يرجى تحميل التحديث الجديد من قناة تيليجرام."
                },
                updateUrl = json.optString("updateUrl", "").trim()
            )
        } catch (_: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun showUpdateBlockedDialog(activity: Activity, config: RemoteConfig) {
        dismissActiveDialog()

        val dialog = AlertDialog.Builder(activity)
            .setTitle(config.title)
            .setMessage(config.message)
            .setCancelable(false)
            .setPositiveButton("تحميل التحديث", null)
            .setNegativeButton("إغلاق التطبيق") { _, _ ->
                activity.finishAffinity()
            }
            .create()

        dialog.setOnShowListener {
            applyCustomFont(activity, dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                if (config.updateUrl.isNotEmpty()) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(config.updateUrl))
                        activity.startActivity(intent)
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        activeDialogRef = WeakReference(dialog)
        dialog.show()
    }

    private fun showNetworkErrorDialog(activity: Activity) {
        dismissActiveDialog()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("التحقق من الاتصال")
            .setMessage("يتطلب التطبيق اتصالاً بالإنترنت للتحقق من الإصدار.")
            .setCancelable(false)
            .setPositiveButton("إعادة المحاولة") { d, _ ->
                d.dismiss()
                activeDialogRef = null
                check(activity)
            }
            .setNegativeButton("إغلاق") { _, _ ->
                activity.finishAffinity()
            }
            .create()

        dialog.setOnShowListener {
            applyCustomFont(activity, dialog)
        }

        activeDialogRef = WeakReference(dialog)
        dialog.show()
    }

    private fun stopPlaybackService(activity: Activity) {
        try {
            activity.stopService(Intent(activity, RadioPlaybackService::class.java))
        } catch (_: Throwable) {
        }
    }

    private fun dismissActiveDialog() {
        try {
            activeDialogRef?.get()?.dismiss()
        } catch (_: Throwable) {
        }
        activeDialogRef = null
    }

    private fun getCurrentVersionCode(activity: Activity): Long {
        return try {
            val pm = activity.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(activity.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: Throwable) {
            1L
        }
    }

    private fun applyCustomFont(activity: Activity, dialog: AlertDialog) {
        val typeface = try {
            Typeface.createFromAsset(activity.assets, APP_FONT_ASSET_PATH)
        } catch (_: Throwable) {
            return
        }
        val boldTypeface = try {
            Typeface.create(typeface, Typeface.BOLD)
        } catch (_: Throwable) {
            typeface
        }
        dialog.window?.decorView?.let { applyFontRecursively(it, typeface, boldTypeface) }
    }

    private fun applyFontRecursively(view: View, regular: Typeface, bold: Typeface) {
        if (view is TextView) {
            view.typeface = if (view.typeface?.isBold == true) bold else regular
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyFontRecursively(view.getChildAt(i), regular, bold)
            }
        }
    }
}
