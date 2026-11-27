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
import java.io.FileOutputStream
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
        prefs.edit().putString(KEY_ACTIVE_LIBRARY_ID, id).commit()
    }

    fun getLastPlayingLibraryId(): String? = prefs.getString(KEY_LAST_PLAYING_LIB_ID, null)

    fun getLastPlayingChannelIndex(): Int = prefs.getInt(KEY_LAST_PLAYING_CH_INDEX, 0)

    fun saveLastPlayingPosition(libraryId: String, index: Int) {
        prefs.edit()
            .putString(KEY_LAST_PLAYING_LIB_ID, libraryId)
            .putInt(KEY_LAST_PLAYING_CH_INDEX, index.coerceAtLeast(0))
            .commit()
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
        var seq = 1

        for (rawLine in lines) {
            val line = stripBom(rawLine).trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                pendingTitle = extractExtinfTitle(line)
                continue
            }

            if (line.startsWith("#")) {
                continue
            }

            val urlMatcher = STREAM_URL_START.matcher(line)
            if (urlMatcher.find()) {
                val url = extractCleanUrl(line.substring(urlMatcher.start()))
                if (url.isNotEmpty()) {
                    val name = pendingTitle?.takeIf { it.isNotBlank() } ?: "قناة $seq"
                    result.add(RadioChannel(name = name, url = url))
                    seq++
                    pendingTitle = null
                }
            }
        }
        return result
    }

    private fun extractExtinfTitle(extinfLine: String): String? {
        var inQuotes = false
        var commaIndex = -1
        for (i in extinfLine.indices) {
            val ch = extinfLine[i]
            if (ch == '"') {
                inQuotes = !inQuotes
            } else if (ch == ',' && !inQuotes) {
                commaIndex = i
                break
            }
        }

        if (commaIndex >= 0 && commaIndex + 1 < extinfLine.length) {
            val titleAfterComma = extinfLine.substring(commaIndex + 1).trim()
            if (titleAfterComma.isNotEmpty()) {
                return titleAfterComma
            }
        }

        val tvgMatcher = TVG_NAME_REGEX.matcher(extinfLine)
        if (tvgMatcher.find()) {
            val tvgName = tvgMatcher.group(1)?.trim()
            if (!tvgName.isNullOrEmpty()) return tvgName
        }

        return null
    }

    private fun parseJson(content: String): List<RadioChannel> {
        val result = ArrayList<RadioChannel>()
        try {
            val trimmed = content.trim()
            val array = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                findChannelsJsonArray(JSONObject(trimmed))
            } ?: return emptyList()

            var seq = 1
            for (i in 0 until array.length()) {
                val item = array.opt(i) ?: continue
                when (item) {
                    is JSONObject -> {
                        val rawUrl = findFirstStringByKeys(item, JSON_URL_KEYS)
                        val url = rawUrl?.let { extractUrlFromField(it) }
                        if (!url.isNullOrEmpty()) {
                            val name = findFirstStringByKeys(item, JSON_NAME_KEYS)
                                ?.takeIf { it.isNotBlank() }
                                ?: "قناة $seq"
                            result.add(RadioChannel(name = name, url = url))
                            seq++
                        }
                    }

                    is String -> {
                        val url = extractUrlFromField(item)
                        if (!url.isNullOrEmpty()) {
                            result.add(RadioChannel(name = "قناة $seq", url = url))
                            seq++
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        return result
    }

    private fun findChannelsJsonArray(root: JSONObject): JSONArray? {
        for (key in JSON_ARRAY_KEYS) {
            val direct = root.optJSONArray(key)
            if (direct != null) return direct
        }
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = root.optJSONArray(k)
            if (arr != null && arr.length() > 0) {
                return arr
            }
        }
        val nestedKeys = root.keys()
        while (nestedKeys.hasNext()) {
            val k = nestedKeys.next()
            val childObj = root.optJSONObject(k)
            if (childObj != null) {
                val found = findChannelsJsonArray(childObj)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findFirstStringByKeys(obj: JSONObject, candidateKeys: List<String>): String? {
        for (key in candidateKeys) {
            if (obj.has(key) && !obj.isNull(key)) {
                val value = obj.optString(key, "").trim()
                if (value.isNotEmpty()) return value
            }
        }
        val lowerSet = candidateKeys.map { it.lowercase(Locale.US) }.toSet()
        val iter = obj.keys()
        while (iter.hasNext()) {
            val k = iter.next()
            if (k.lowercase(Locale.US) in lowerSet && !obj.isNull(k)) {
                val value = obj.optString(k, "").trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun parseXml(content: String): List<RadioChannel> {
        val result = ArrayList<RadioChannel>()
        try {
            val sanitized = BARE_AMPERSAND.matcher(content).replaceAll("&amp;")
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(sanitized))

            var inContainer = false
            var containerTagName = ""
            var currentName: String? = null
            var currentUrl: String? = null
            var activeChildTag: String? = null
            var seq = 1

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name?.lowercase(Locale.US) ?: ""
                        if (!inContainer && tag in XML_CONTAINER_TAGS) {
                            inContainer = true
                            containerTagName = tag
                            currentName = getXmlAttribute(
                                parser,
                                listOf("name", "title", "label", "channel", "station")
                            )
                            currentUrl = getXmlAttribute(
                                parser,
                                listOf("url", "src", "link", "href", "stream", "uri")
                            )
                            activeChildTag = null
                        } else if (inContainer) {
                            activeChildTag = tag
                            if (currentName.isNullOrBlank()) {
                                currentName = getXmlAttribute(parser, listOf("name", "title", "label"))
                            }
                            if (currentUrl.isNullOrBlank()) {
                                currentUrl = getXmlAttribute(parser, listOf("url", "src", "link", "href"))
                            }
                        }
                    }

                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (inContainer) {
                            val text = parser.text?.trim() ?: ""
                            if (text.isNotEmpty()) {
                                val child = activeChildTag
                                if (child != null && child in XML_NAME_TAGS && currentName.isNullOrBlank()) {
                                    currentName = text
                                } else if (child != null && child in XML_URL_TAGS && currentUrl.isNullOrBlank()) {
                                    currentUrl = text
                                } else if (child == null && currentUrl.isNullOrBlank() &&
                                    STREAM_URL_START.matcher(text).find()
                                ) {
                                    currentUrl = text
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val tag = parser.name?.lowercase(Locale.US) ?: ""
                        if (inContainer && tag == containerTagName) {
                            val validUrl = currentUrl?.let { extractUrlFromField(it) }
                            if (!validUrl.isNullOrEmpty()) {
                                val name = currentName?.takeIf { it.isNotBlank() } ?: "قناة $seq"
                                result.add(RadioChannel(name = name, url = validUrl))
                                seq++
                            }
                            inContainer = false
                            containerTagName = ""
                            currentName = null
                            currentUrl = null
                            activeChildTag = null
                        } else if (inContainer && tag == activeChildTag) {
                            activeChildTag = null
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        return result
    }

    private fun getXmlAttribute(parser: XmlPullParser, names: List<String>): String? {
        val count = parser.attributeCount
        for (target in names) {
            for (i in 0 until count) {
                val attrName = parser.getAttributeName(i)?.lowercase(Locale.US) ?: continue
                if (attrName == target) {
                    val v = parser.getAttributeValue(i)?.trim()
                    if (!v.isNullOrEmpty()) return v
                }
            }
        }
        return null
    }

    private fun parseTextAndReceiverFormats(content: String): List<RadioChannel> {
        val result = ArrayList<RadioChannel>()
        val lines = content.split('\n')
        var seq = 1

        for (rawLine in lines) {
            val line = stripBom(rawLine).trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") && !STREAM_URL_START.matcher(line).find()) continue

            val matcher = STREAM_URL_START.matcher(line)
            if (!matcher.find()) continue

            val urlStart = matcher.start()
            val prefixRaw = line.substring(0, urlStart).trim()
            val restFromUrl = line.substring(urlStart).trim()

            val isDirectivePrefix = prefixRaw.isEmpty() ||
                RECEIVER_PREFIX_ONLY.matcher(prefixRaw).matches()

            val (extractedUrl, trailingAfterUrl) = splitUrlAndTrailing(restFromUrl)
            if (extractedUrl.isEmpty()) continue

            val channelName = if (!isDirectivePrefix) {
                cleanLabel(prefixRaw)
            } else {
                cleanLabel(trailingAfterUrl)
            }.ifBlank { "قناة $seq" }

            result.add(RadioChannel(name = channelName, url = extractedUrl))
            seq++
        }
        return result
    }

    private fun splitUrlAndTrailing(textStartingWithUrl: String): Pair<String, String> {
        var end = textStartingWithUrl.length
        for (i in textStartingWithUrl.indices) {
            val ch = textStartingWithUrl[i]
            if (ch.isWhitespace() || ch == '|' || ch == '"' || ch == '\'' || ch == '<' || ch == '>') {
                end = i
                break
            }
            if (ch == ',') {
                end = i
                break
            }
            if (ch == ';') {
                val prev = if (i > 0) textStartingWithUrl[i - 1] else ' '
                val after = textStartingWithUrl.substring(i + 1)
                val looksLikeShoutcastSuffix = prev == '/' &&
                    (after.isEmpty() || after.startsWith("stream", ignoreCase = true))
                if (!looksLikeShoutcastSuffix) {
                    end = i
                    break
                }
            }
        }

        val url = trimTrailingUrlPunctuation(textStartingWithUrl.substring(0, end))
        val trailing = if (end < textStartingWithUrl.length) {
            textStartingWithUrl.substring(end).trim()
        } else {
            ""
        }
        return url to trailing
    }

    private fun extractCleanUrl(textStartingWithUrl: String): String {
        return splitUrlAndTrailing(textStartingWithUrl).first
    }

    private fun extractUrlFromField(field: String): String? {
        val matcher = STREAM_URL_START.matcher(field)
        if (!matcher.find()) return null
        val url = extractCleanUrl(field.substring(matcher.start()))
        return url.takeIf { it.isNotEmpty() }
    }

    private fun trimTrailingUrlPunctuation(rawUrl: String): String {
        var u = rawUrl.trim()
        while (u.isNotEmpty() && (u.endsWith("\"") || u.endsWith("'") || u.endsWith(")") || u.endsWith("]"))) {
            u = u.dropLast(1)
        }
        return u
    }

    private fun cleanLabel(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("(?i)^[ic]\\s*:\\s*"), "")
        while (s.isNotEmpty() && s.first() in charArrayOf(',', ';', '|', '=', ':', '-', '\t', '"', '\'')) {
            s = s.drop(1).trim()
        }
        while (s.isNotEmpty() && s.last() in charArrayOf(',', ';', '|', '=', ':', '-', '\t', '"', '\'')) {
            s = s.dropLast(1).trim()
        }
        return s.trim()
    }

    private fun extractNameFromUrl(url: String): String? {
        return try {
            val parsed = URL(url)
            val seg = parsed.path.trimEnd('/').substringAfterLast('/')
                .substringBeforeLast('.')
                .trim()
            seg.takeIf { it.isNotEmpty() } ?: parsed.host
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Thread-safe Singleton managing persistence, importing, renaming, deleting, and sequential
 * playback of multiple audio libraries stored atomically in `filesDir/libraries.json`.
 *
 * Both `LibraryManager.getInstance(context)` and `LibraryManager(context)` resolve to the same
 * shared Singleton instance so MainActivity and RadioPlaybackService never overwrite each other.
 */
class LibraryManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val storageFile = File(appContext.filesDir, STORAGE_FILE_NAME)
    private val mirrorPrefs = appContext.getSharedPreferences(MIRROR_PREFS_NAME, Context.MODE_PRIVATE)
    private val prefs = FootSyncPrefs(appContext)
    private val lock = Any()

    private val libraries = mutableListOf<RadioLibrary>()

    init {
        loadFromDisk()
    }

    /**
     * Re-checks disk and SharedPreferences mirror if in-memory libraries currently contain
     * no channels or imported libraries, ensuring saved libraries always reappear reliably.
     */
    fun ensureLoadedFromDisk() = synchronized(lock) {
        val hasRealDataInRam = libraries.size > 1 || libraries.any { it.channels.isNotEmpty() }
        if (!hasRealDataInRam) {
            val recovered = readBestPersistedLibrariesLocked()
            if (recovered != null && (recovered.size > 1 || recovered.any { it.channels.isNotEmpty() })) {
                libraries.clear()
                libraries.addAll(recovered)
                ensureManualLibraryLocked()
            }
        }
    }

    fun getLibraries(): List<RadioLibrary> = synchronized(lock) {
        ensureLoadedFromDisk()
        libraries.map { lib ->
            lib.copy(channels = lib.channels.toMutableList())
        }
    }

    fun getLibraryById(libraryId: String?): RadioLibrary? = synchronized(lock) {
        if (libraryId == null) return@synchronized null
        ensureLoadedFromDisk()
        libraries.firstOrNull { it.id == libraryId }
            ?.let { it.copy(channels = it.channels.toMutableList()) }
    }

    fun getActiveLibrary(): RadioLibrary = synchronized(lock) {
        ensureLoadedFromDisk()
        val activeId = prefs.getActiveLibraryId()
        val found = libraries.firstOrNull { it.id == activeId }
            ?: libraries.firstOrNull { it.channels.isNotEmpty() }
            ?: libraries.first()
        found.copy(channels = found.channels.toMutableList())
    }

    fun setActiveLibrary(libraryId: String): RadioLibrary = synchronized(lock) {
        val found = libraries.firstOrNull { it.id == libraryId } ?: libraries.first()
        prefs.setActiveLibraryId(found.id)
        found.copy(channels = found.channels.toMutableList())
    }

    fun getManualLibrary(): RadioLibrary = synchronized(lock) {
        ensureManualLibraryLocked()
        libraries.first { it.isManualDefault }.let { it.copy(channels = it.channels.toMutableList()) }
    }

    fun addManualChannel(url: String, customName: String? = null): PlaybackTarget = synchronized(lock) {
        val cleanUrl = url.trim()
        val manual = ensureManualLibraryLocked()
        val existingIdx = manual.channels.indexOfFirst { it.url.equals(cleanUrl, ignoreCase = true) }
        val index = if (existingIdx >= 0) {
            if (!customName.isNullOrBlank()) {
                manual.channels[existingIdx] = manual.channels[existingIdx].copy(name = customName.trim())
                saveToDiskLocked()
            }
            existingIdx
        } else {
            val name = customName?.trim()?.takeIf { it.isNotEmpty() }
                ?: "رابط يدوي ${manual.channels.size + 1}"
            manual.channels.add(RadioChannel(name = name, url = cleanUrl))
            saveToDiskLocked()
            manual.channels.lastIndex
        }

        prefs.setActiveLibraryId(manual.id)
        prefs.saveLastPlayingPosition(manual.id, index)
        PlaybackTarget(
            libraryId = manual.id,
            libraryName = manual.name,
            index = index,
            totalCount = manual.channels.size,
            channel = manual.channels[index]
        )
    }

    fun deleteChannel(libraryId: String, channelIndex: Int): Boolean = synchronized(lock) {
        val lib = libraries.firstOrNull { it.id == libraryId } ?: return@synchronized false
        if (channelIndex !in lib.channels.indices) return@synchronized false
        lib.channels.removeAt(channelIndex)
        saveToDiskLocked()
        true
    }

    fun renameLibrary(libraryId: String, newName: String): Boolean = synchronized(lock) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@synchronized false
        val lib = libraries.firstOrNull { it.id == libraryId } ?: return@synchronized false
        if (lib.isManualDefault) return@synchronized false
        val idx = libraries.indexOf(lib)
        libraries[idx] = lib.copy(name = trimmed)
        saveToDiskLocked()
        true
    }

    fun deleteLibrary(libraryId: String): Boolean = synchronized(lock) {
        val lib = libraries.firstOrNull { it.id == libraryId } ?: return@synchronized false
        if (lib.isManualDefault) return@synchronized false
        libraries.remove(lib)
        if (prefs.getActiveLibraryId() == libraryId) {
            prefs.setActiveLibraryId(libraries.first().id)
        }
        saveToDiskLocked()
        true
    }

    fun queryDisplayName(uri: Uri): String {
        try {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val rawName = cursor.getString(idx)?.trim()
                        if (!rawName.isNullOrEmpty()) {
                            return rawName.substringBeforeLast('.').ifBlank { rawName }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "مكتبة مستوردة"
    }

    fun readTextFromUri(uri: Uri): String {
        val sb = StringBuilder()
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    sb.append(buffer, 0, read)
                    if (sb.length > MAX_PLAYLIST_CHARS) break
                }
            }
        } ?: throw IllegalStateException("تعذّر فتح الملف المحدد.")
        return sb.toString()
    }

    fun importFromUri(uri: Uri, customLibraryName: String? = null): ImportResult {
        return try {
            val content = readTextFromUri(uri)
            val libName = customLibraryName?.trim()?.takeIf { it.isNotEmpty() }
                ?: queryDisplayName(uri)
            importFromRawText(libName, content, sourceUrl = null)
        } catch (t: Throwable) {
            ImportResult(
                success = false,
                errorMessage = "فشل قراءة الملف: ${t.message ?: "خطأ غير معروف"}"
            )
        }
    }

    fun importFromUrl(playlistUrl: String, customLibraryName: String? = null): ImportResult {
        val cleanUrl = playlistUrl.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return ImportResult(
                success = false,
                errorMessage = "الرابط يجب أن يبدأ بـ http:// أو https://"
            )
        }

        return try {
            val text = downloadText(cleanUrl)
            val defaultName = customLibraryName?.trim()?.takeIf { it.isNotEmpty() }
                ?: deriveNameFromUrl(cleanUrl)
            importFromRawText(defaultName, text, sourceUrl = cleanUrl)
        } catch (t: Throwable) {
            ImportResult(
                success = false,
                errorMessage = "تعذّر تحميل القائمة من الرابط: ${t.message ?: "خطأ في الاتصال"}"
            )
        }
    }

    fun importFromRawText(
        libraryName: String,
        rawText: String,
        sourceUrl: String? = null
    ): ImportResult {
        val cleanName = libraryName.trim().ifEmpty { "مكتبة صوتية" }
        val parsed = UniversalPlaylistParser.parse(
            rawContent = rawText,
            sourceUrl = sourceUrl,
            fallbackTitle = cleanName
        )

        if (parsed.channels.isEmpty()) {
            return ImportResult(
                success = false,
                errorMessage = "لم يتم العثور على أي روابط بث صالحة داخل المحتوى."
            )
        }

        val newLibrary = RadioLibrary(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            isManualDefault = false,
            channels = parsed.channels.toMutableList()
        )

        synchronized(lock) {
            libraries.add(newLibrary)
            prefs.setActiveLibraryId(newLibrary.id)
            saveToDiskLocked()
        }

        return ImportResult(
            success = true,
            library = newLibrary.copy(channels = newLibrary.channels.toMutableList()),
            channelCount = newLibrary.channels.size,
            detectedFormat = parsed.formatName
        )
    }

    fun getTarget(libraryId: String, index: Int): PlaybackTarget? = synchronized(lock) {
        ensureLoadedFromDisk()
        val lib = libraries.firstOrNull { it.id == libraryId } ?: return@synchronized null
        if (lib.channels.isEmpty()) return@synchronized null
        val safeIdx = index.coerceIn(0, lib.channels.lastIndex)
        PlaybackTarget(
            libraryId = lib.id,
            libraryName = lib.name,
            index = safeIdx,
            totalCount = lib.channels.size,
            channel = lib.channels[safeIdx]
        )
    }

    /**
     * Resolves the next sequential channel in [libraryId] (wraps cleanly from last to first).
     */
    fun stepChannel(libraryId: String?, currentIndex: Int, delta: Int): PlaybackTarget? =
        synchronized(lock) {
            ensureLoadedFromDisk()
            val lib = libraries.firstOrNull { it.id == libraryId }
                ?: libraries.firstOrNull { it.id == prefs.getActiveLibraryId() }
                ?: libraries.firstOrNull { it.channels.isNotEmpty() }
                ?: return@synchronized null

            if (lib.channels.isEmpty()) return@synchronized null
            val size = lib.channels.size
            val nextIdx = ((currentIndex + delta) % size + size) % size
            prefs.setActiveLibraryId(lib.id)
            prefs.saveLastPlayingPosition(lib.id, nextIdx)
            PlaybackTarget(
                libraryId = lib.id,
                libraryName = lib.name,
                index = nextIdx,
                totalCount = size,
                channel = lib.channels[nextIdx]
            )
        }

    fun getLastOrFirstTarget(): PlaybackTarget? = synchronized(lock) {
        ensureLoadedFromDisk()
        val lastLibId = prefs.getLastPlayingLibraryId()
        val lastIdx = prefs.getLastPlayingChannelIndex()
        if (lastLibId != null) {
            val lib = libraries.firstOrNull { it.id == lastLibId && it.channels.isNotEmpty() }
            if (lib != null) {
                val safeIdx = lastIdx.coerceIn(0, lib.channels.lastIndex)
                return@synchronized PlaybackTarget(
                    libraryId = lib.id,
                    libraryName = lib.name,
                    index = safeIdx,
                    totalCount = lib.channels.size,
                    channel = lib.channels[safeIdx]
                )
            }
        }
        val firstNonEmpty = libraries.firstOrNull { it.channels.isNotEmpty() } ?: return@synchronized null
        PlaybackTarget(
            libraryId = firstNonEmpty.id,
            libraryName = firstNonEmpty.name,
            index = 0,
            totalCount = firstNonEmpty.channels.size,
            channel = firstNonEmpty.channels[0]
        )
    }

    private fun downloadText(urlString: String): String {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "FootSyncRadio/1.0 (Android)")
                setRequestProperty("Accept", "*/*")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("إعادة توجيه بدون عنوان")
                    currentUrl = URL(URL(currentUrl), location).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
                val contentType = conn.contentType?.lowercase(Locale.US) ?: ""
                if (contentType.startsWith("audio/") && !contentType.contains("mpegurl")) {
                    return currentUrl
                }
                val sb = StringBuilder()
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                        if (sb.length > MAX_PLAYLIST_CHARS) break
                    }
                }
                return sb.toString()
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("عدد إعادة التوجيهات تجاوز الحد المسموح")
    }

    private fun deriveNameFromUrl(url: String): String {
        return try {
            val parsed = URL(url)
            val file = parsed.path.trimEnd('/').substringAfterLast('/')
                .substringBeforeLast('.')
                .trim()
            if (file.isNotEmpty()) file else (parsed.host ?: "مكتبة رابط")
        } catch (_: Throwable) {
            "مكتبة رابط"
        }
    }

    private fun loadFromDisk() = synchronized(lock) {
        libraries.clear()
        val fileLibs = readLibrariesFromFileLocked()
        val mirrorLibs = readLibrariesFromMirrorLocked()

        val chosen = pickRicherLibraries(fileLibs, mirrorLibs)
        if (chosen != null) {
            libraries.addAll(chosen)
        }

        ensureManualLibraryLocked()

        val activeId = prefs.getActiveLibraryId()
        if (activeId.isNullOrEmpty() || libraries.none { it.id == activeId }) {
            val preferred = libraries.firstOrNull { it.channels.isNotEmpty() } ?: libraries.first()
            prefs.setActiveLibraryId(preferred.id)
        }

        // Repair either storage copy if one was missing or stale
        val fileMissingOrStale = fileLibs == null || scoreLibraries(chosen) > scoreLibraries(fileLibs)
        val mirrorMissingOrStale = mirrorLibs == null || scoreLibraries(chosen) > scoreLibraries(mirrorLibs)
        if (chosen == null || fileMissingOrStale || mirrorMissingOrStale) {
            saveToDiskLocked()
        }
    }

    private fun readBestPersistedLibrariesLocked(): List<RadioLibrary>? {
        return pickRicherLibraries(readLibrariesFromFileLocked(), readLibrariesFromMirrorLocked())
    }

    private fun readLibrariesFromFileLocked(): List<RadioLibrary>? {
        if (!storageFile.exists() || storageFile.length() <= 0L) return null
        return try {
            parseLibrariesJson(storageFile.readText(StandardCharsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    private fun readLibrariesFromMirrorLocked(): List<RadioLibrary>? {
        val raw = try {
            mirrorPrefs.getString(KEY_MIRROR_JSON, null)
        } catch (_: Throwable) {
            null
        }
        if (raw.isNullOrBlank()) return null
        return try {
            parseLibrariesJson(raw)
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseLibrariesJson(raw: String): List<RadioLibrary> {
        val parsed = mutableListOf<RadioLibrary>()
        val root = JSONObject(raw)
        val arr = root.optJSONArray("libraries") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", UUID.randomUUID().toString())
            val name = obj.optString("name", "مكتبة")
            val isManual = obj.optBoolean("isManualDefault", id == MANUAL_LIBRARY_ID)
            val chArr = obj.optJSONArray("channels") ?: JSONArray()
            val channels = mutableListOf<RadioChannel>()
            for (j in 0 until chArr.length()) {
                val cObj = chArr.optJSONObject(j) ?: continue
                val cUrl = cObj.optString("url", "").trim()
                if (cUrl.isEmpty()) continue
                val cId = cObj.optString("id", UUID.randomUUID().toString())
                val cName = cObj.optString("name", "قناة ${j + 1}")
                channels.add(RadioChannel(id = cId, name = cName, url = cUrl))
            }
            parsed.add(
                RadioLibrary(
                    id = id,
                    name = name,
                    isManualDefault = isManual,
                    channels = channels
                )
            )
        }
        return parsed
    }

    private fun scoreLibraries(list: List<RadioLibrary>?): Int {
        if (list == null) return -1
        val totalChannels = list.sumOf { it.channels.size }
        val nonManualCount = list.count { !it.isManualDefault }
        return totalChannels * 10 + nonManualCount * 5 + list.size
    }

    private fun pickRicherLibraries(
        primary: List<RadioLibrary>?,
        backup: List<RadioLibrary>?
    ): List<RadioLibrary>? {
        if (primary == null) return backup
        if (backup == null) return primary
        return if (scoreLibraries(backup) > scoreLibraries(primary)) backup else primary
    }

    private fun ensureManualLibraryLocked(): RadioLibrary {
        val existing = libraries.firstOrNull { it.isManualDefault || it.id == MANUAL_LIBRARY_ID }
        if (existing != null) return existing
        val manual = RadioLibrary(
            id = MANUAL_LIBRARY_ID,
            name = MANUAL_LIBRARY_NAME,
            isManualDefault = true,
            channels = mutableListOf()
        )
        libraries.add(0, manual)
        return manual
    }

    private fun serializeLibrariesJsonLocked(): String {
        val root = JSONObject()
        val arr = JSONArray()
        for (lib in libraries) {
            val obj = JSONObject()
            obj.put("id", lib.id)
            obj.put("name", lib.name)
            obj.put("isManualDefault", lib.isManualDefault)
            val chArr = JSONArray()
            for (ch in lib.channels) {
                val cObj = JSONObject()
                cObj.put("id", ch.id)
                cObj.put("name", ch.name)
                cObj.put("url", ch.url)
                chArr.put(cObj)
            }
            obj.put("channels", chArr)
            arr.put(obj)
        }
        root.put("libraries", arr)
        return root.toString()
    }

    private fun saveToDiskLocked() {
        val jsonStr = try {
            serializeLibrariesJsonLocked()
        } catch (_: Throwable) {
            return
        }

        // 1. Synchronous backup in SharedPreferences (survives instant swipe-kill reliably)
        try {
            mirrorPrefs.edit().putString(KEY_MIRROR_JSON, jsonStr).commit()
        } catch (_: Throwable) {
        }

        // 2. Atomic file write to filesDir/libraries.json with safe fallback
        try {
            val parent = storageFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val jsonBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
            val tmp = File(appContext.filesDir, "$STORAGE_FILE_NAME.tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(jsonBytes)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Throwable) {
                }
            }

            val renamed = tmp.renameTo(storageFile)
            if (!renamed) {
                if (storageFile.exists()) {
                    storageFile.delete()
                }
                if (!tmp.renameTo(storageFile)) {
                    FileOutputStream(storageFile).use { fos ->
                        fos.write(jsonBytes)
                        fos.flush()
                        try {
                            fos.fd.sync()
                        } catch (_: Throwable) {
                        }
                    }
                    tmp.delete()
                }
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val STORAGE_FILE_NAME = "libraries.json"
        private const val MIRROR_PREFS_NAME = "footsync_libraries_mirror"
        private const val KEY_MIRROR_JSON = "libraries_json_v1"
        const val MANUAL_LIBRARY_ID = "manual_default_library"
        const val MANUAL_LIBRARY_NAME = "روابط يدوية"
        private const val MAX_PLAYLIST_CHARS = 4 * 1024 * 1024

        @Volatile
        private var instance: LibraryManager? = null

        fun getInstance(context: Context): LibraryManager {
            return instance ?: synchronized(this) {
                instance ?: LibraryManager(context.applicationContext).also { instance = it }
            }
        }

        operator fun invoke(context: Context): LibraryManager = getInstance(context)
    }
}
