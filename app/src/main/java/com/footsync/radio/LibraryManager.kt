package com.footsync.radio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

/**
 * Single playable radio/commentary station inside a library.
 */
data class RadioChannel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String
)

/**
 * Named sequential playlist library.
 */
data class RadioLibrary(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isManualDefault: Boolean = false,
    val channels: MutableList<RadioChannel> = mutableListOf()
)

/**
 * Active playback target referencing a specific library and 0-based sequential channel index.
 */
data class PlaybackTarget(
    val libraryId: String,
    val libraryName: String,
    val index: Int,
    val totalCount: Int,
    val channel: RadioChannel
)

/**
 * Result of importing a playlist from storage, URL, or raw text.
 */
data class ImportResult(
    val success: Boolean,
    val library: RadioLibrary? = null,
    val channelCount: Int = 0,
    val detectedFormat: String = "",
    val errorMessage: String? = null
)

/**
 * Persistent preferences for per-URL delay memory, calibration trim, sync timeout, and UI state.
 */
class FootSyncPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDelayForUrl(url: String): Double {
        if (url.isBlank()) return 0.0
        val key = DELAY_PREFIX + url.trim()
        if (!prefs.contains(key)) return 0.0
        return Double.fromBits(prefs.getLong(key, 0L)).coerceIn(0.0, MAX_DELAY_SEC)
    }

    fun saveDelayForUrl(url: String, delaySec: Double) {
        if (url.isBlank()) return
        val clamped = delaySec.coerceIn(0.0, MAX_DELAY_SEC)
        prefs.edit().putLong(DELAY_PREFIX + url.trim(), clamped.toBits()).apply()
    }

    fun getCalibrationTrimSec(): Double {
        if (!prefs.contains(KEY_CALIBRATION_TRIM)) return 0.0
        return Double.fromBits(prefs.getLong(KEY_CALIBRATION_TRIM, 0L)).coerceIn(-2.0, 2.0)
    }

    fun setCalibrationTrimSec(trimSec: Double) {
        val clamped = trimSec.coerceIn(-2.0, 2.0)
        prefs.edit().putLong(KEY_CALIBRATION_TRIM, clamped.toBits()).apply()
    }

    fun getSyncTimeoutSec(): Int {
        val v = prefs.getInt(KEY_SYNC_TIMEOUT_SEC, SyncEngine.DEFAULT_TIMEOUT_SEC)
        return when (v) {
            45, 60, 80 -> v
            else -> SyncEngine.DEFAULT_TIMEOUT_SEC
        }
    }

    fun setSyncTimeoutSec(sec: Int) {
        val valid = when (sec) {
            45, 60, 80 -> sec
            else -> SyncEngine.DEFAULT_TIMEOUT_SEC
        }
        prefs.edit().putInt(KEY_SYNC_TIMEOUT_SEC, valid).apply()
    }

    fun isEarphoneNoMuteEnabled(): Boolean = prefs.getBoolean(KEY_EARPHONE_NO_MUTE, false)

    fun setEarphoneNoMuteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EARPHONE_NO_MUTE, enabled).apply()
    }

    fun getActiveLibraryId(): String? = prefs.getString(KEY_ACTIVE_LIBRARY_ID, null)

    fun setActiveLibraryId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_LIBRARY_ID, id).apply()
    }

    fun getLastPlayingLibraryId(): String? = prefs.getString(KEY_LAST_PLAYING_LIB_ID, null)

    fun getLastPlayingChannelIndex(): Int = prefs.getInt(KEY_LAST_PLAYING_CH_INDEX, 0)

    fun saveLastPlayingPosition(libraryId: String, index: Int) {
        prefs.edit()
            .putString(KEY_LAST_PLAYING_LIB_ID, libraryId)
            .putInt(KEY_LAST_PLAYING_CH_INDEX, index.coerceAtLeast(0))
            .apply()
    }

    fun getOverlayX(defaultX: Int): Int = prefs.getInt(KEY_OVERLAY_X, defaultX)

    fun getOverlayY(defaultY: Int): Int = prefs.getInt(KEY_OVERLAY_Y, defaultY)

    fun saveOverlayPosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_OVERLAY_X, x)
            .putInt(KEY_OVERLAY_Y, y)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "footsync_radio_prefs"
        private const val DELAY_PREFIX = "delay_url_"
        private const val KEY_CALIBRATION_TRIM = "calibration_trim_sec"
        private const val KEY_SYNC_TIMEOUT_SEC = "sync_timeout_sec"
        private const val KEY_EARPHONE_NO_MUTE = "earphone_no_mute"
        private const val KEY_ACTIVE_LIBRARY_ID = "active_library_id"
        private const val KEY_LAST_PLAYING_LIB_ID = "last_playing_lib_id"
        private const val KEY_LAST_PLAYING_CH_INDEX = "last_playing_ch_index"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        const val MAX_DELAY_SEC = 180.0
    }
}

/**
 * Universal playlist parser supporting M3U/M3U8, JSON, XML (IPTV & receiver lists),
 * and TXT/CFG/LST receiver formats (GX, Sunplus, ALI) while strictly preserving sequential order.
 */
object UniversalPlaylistParser {

    data class ParseOutput(
        val formatName: String,
        val channels: List<RadioChannel>
    )

    private val STREAM_URL_START = Pattern.compile("(?i)(https?|rtmp|rtsp)://")
    private val BARE_AMPERSAND = Pattern.compile("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)")
    private val RECEIVER_PREFIX_ONLY = Pattern.compile("(?i)^(?:[ic]|url|link|stream|iptv|radio|ch|channel)\\s*[:=\\-]?\\s*$")
    private val TVG_NAME_REGEX = Pattern.compile("(?i)tvg-name\\s*=\\s*\"([^\"]+)\"")

    private val JSON_ARRAY_KEYS = listOf(
        "channels", "stations", "items", "data", "list", "radio",
        "radios", "streams", "playlist", "entries", "results", "programs"
    )
    private val JSON_NAME_KEYS = listOf(
        "name", "title", "label", "channel", "station",
        "channelName", "channel_name", "stationName", "station_name",
        "tvg-name", "tvg_name", "text", "displayName"
    )
    private val JSON_URL_KEYS = listOf(
        "url", "stream", "link", "src", "uri",
        "stream_url", "streamUrl", "href", "audio", "play", "playUrl", "file"
    )

    private val XML_CONTAINER_TAGS = setOf(
        "channel", "item", "station", "iptv", "radio", "program", "entry", "stream", "track", "link"
    )
    private val XML_NAME_TAGS = setOf(
        "name", "title", "label", "channel_name", "channelname", "station_name", "stationname", "text"
    )
    private val XML_URL_TAGS = setOf(
        "url", "src", "link", "stream", "stream_url", "streamurl", "uri", "href", "location", "play"
    )

    fun parse(rawContent: String, sourceUrl: String? = null, fallbackTitle: String? = null): ParseOutput {
        val cleaned = stripBom(rawContent)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        if (cleaned.isEmpty()) {
            return ParseOutput("فارغ", emptyList())
        }

        // Handle direct HLS media/master playlist imported by URL (avoid importing .ts media segments)
        if (!sourceUrl.isNullOrBlank() && isDirectHlsStreamPlaylist(cleaned)) {
            val title = fallbackTitle?.takeIf { it.isNotBlank() }
                ?: extractNameFromUrl(sourceUrl)
                ?: "بث HLS مباشر"
            return ParseOutput(
                "HLS",
                listOf(RadioChannel(name = title, url = sourceUrl.trim()))
            )
        }

        // 1. JSON detection
        if (cleaned.startsWith("{") || cleaned.startsWith("[")) {
            val jsonChannels = parseJson(cleaned)
            if (jsonChannels.isNotEmpty()) {
                return ParseOutput("JSON", jsonChannels)
            }
        }

        // 2. XML detection
        if (looksLikeXml(cleaned)) {
            val xmlChannels = parseXml(cleaned)
            if (xmlChannels.isNotEmpty()) {
                return ParseOutput("XML", xmlChannels)
            }
        }

        // 3. M3U / M3U8 detection
        if (looksLikeM3u(cleaned)) {
            val m3uChannels = parseM3u(cleaned)
            if (m3uChannels.isNotEmpty()) {
                return ParseOutput("M3U", m3uChannels)
            }
        }

        // 4. Smart TXT / Receiver (GX, Sunplus, ALI) line-by-line parser
        val txtChannels = parseTextAndReceiverFormats(cleaned)
        return ParseOutput("TXT/Receiver", txtChannels)
    }

    fun stripBom(text: String): String {
        return if (text.startsWith("\uFEFF")) text.substring(1) else text
    }

    private fun isDirectHlsStreamPlaylist(content: String): Boolean {
        val upper = content.uppercase(Locale.US)
        if (!upper.contains("#EXTM3U")) return false
        val hasHlsTags = upper.contains("#EXT-X-TARGETDURATION") ||
            upper.contains("#EXT-X-MEDIA-SEQUENCE") ||
            upper.contains("#EXT-X-STREAM-INF")
        if (!hasHlsTags) return false
        // If it contains standard IPTV #EXTINF:-1 entries with full http(s) URLs, parse as IPTV M3U
        val hasIptvExtinf = upper.contains("#EXTINF:-1") || upper.contains("TVG-NAME=")
        return !hasIptvExtinf
    }

    private fun looksLikeM3u(content: String): Boolean {
        val upper = content.uppercase(Locale.US)
        return upper.contains("#EXTM3U") || upper.contains("#EXTINF")
    }

    private fun looksLikeXml(content: String): Boolean {
        val lower = content.lowercase(Locale.US)
        return lower.startsWith("<?xml") ||
            lower.startsWith("<") && (
                lower.contains("<channel") ||
                    lower.contains("<item") ||
                    lower.contains("<station") ||
                    lower.contains("<iptv") ||
                    lower.contains("<radio") ||
                    lower.contains("<program") ||
                    lower.contains("<entry") ||
                    lower.contains("<stream") ||
                    lower.contains("<playlist")
                )
    }

    private fun parseM3u(content: String): List<RadioChannel> {
        val result = ArrayList<RadioChannel>()
        val lines = content.split('\n')
        var pendingTitle: String? = null
        var seq
