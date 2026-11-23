package com.footsync.radio

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Full playback and auto-sync UI snapshot pushed from [RadioPlaybackService] to [MainActivity].
 */
data class ServiceUiState(
    val libraryId: String? = null,
    val libraryName: String = "",
    val channelIndex: Int = -1,
    val totalChannels: Int = 0,
    val channelName: String = "لا توجد قناة مشغّلة",
    val channelUrl: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isMuted: Boolean = false,
    val delaySec: Double = 0.0,
    val bufferedHistorySec: Double = 0.0,
    val connectionStatus: String = "متوقف",
    val speedKbps: Double = 0.0,
    val usingRelay: Boolean = false,
    val isOverlayVisible: Boolean = false,
    val isSyncing: Boolean = false,
    val syncElapsedSec: Int = 0,
    val syncTimeoutSec: Int = SyncEngine.DEFAULT_TIMEOUT_SEC,
    val syncMicLevel: Float = 0f,
    val syncStatusText: String = "",
    val syncHasCandidate: Boolean = false,
    val syncCandidateSec: Double? = null,
    val unconfirmedCandidateSec: Double? = null,
    val lastSyncBannerText: String = ""
)

/**
 * Circular PCM ring buffer supporting up to 180 seconds of live delay (185s capacity)
 * with zero-allocation hot path, strict frame alignment, direct pass-through at 0ms delay,
 * glitch-free cosine crossfade transitions, and monotonic speaker-aligned timestamping.
 */
@OptIn(UnstableApi::class)
class DelayAudioProcessor : BaseAudioProcessor(), RadioPcmSource {

    private val ringLock = Any()

    private var sampleRate = 48_000
    private var channelCount = 2
    private var capacityFrames = 48_000 * BUFFER_CAPACITY_SECONDS
    private var ringBuffer = ShortArray(0)

    // Pre-allocated working buffer (allocated once in onConfigure; zero allocations in queueInput)
    private var scratchIn = ShortArray(0)
    private var maxScratchFrames = 0

    private var totalFramesWritten = 0L
    private var anchorZeroDelayNs = Long.MIN_VALUE

    @Volatile
    private var requestedDelayMs = 0L
    private var activeDelayFrames = 0L
    private var previousDelayFrames = 0L
    private var crossfadeRemaining = 0
    private var crossfadeTotal = 0

    @Volatile
    private var sinkLatencyNs = DEFAULT_SINK_LATENCY_NS

    @Volatile
    private var liveTap: ((ShortArray, Int, Int, Int, Long) -> Unit)? = null

    fun setDelaySeconds(seconds: Double) {
        val clamped = seconds.coerceIn(0.0, MAX_DELAY_SECONDS)
        requestedDelayMs = (clamped * 1000.0).roundToLong()
    }

    fun getDelaySeconds(): Double = requestedDelayMs.toDouble() / 1000.0

    fun updateOutputLatencyNs(latencyNs: Long) {
        if (latencyNs in 0L..MAX_SINK_LATENCY_NS) {
            val prev = sinkLatencyNs
            sinkLatencyNs = if (prev == DEFAULT_SINK_LATENCY_NS) {
                latencyNs
            } else {
                prev + (latencyNs - prev) / 8L
            }
        }
    }

    fun getBufferedHistorySeconds(): Double = synchronized(ringLock) {
        if (sampleRate <= 0) return@synchronized 0.0
        val availableFrames = min(totalFramesWritten, capacityFrames.toLong())
        availableFrames.toDouble() / sampleRate.toDouble()
    }

    fun clearHistory() = synchronized(ringLock) {
        totalFramesWritten = 0L
        anchorZeroDelayNs = Long.MIN_VALUE
        crossfadeRemaining = 0
        activeDelayFrames = delayMsToFramesLocked(requestedDelayMs)
        previousDelayFrames = activeDelayFrames
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val newSr = inputAudioFormat.sampleRate
        val newCh = inputAudioFormat.channelCount
        if (newSr <= 0 || newCh <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        synchronized(ringLock) {
            val needRealloc = ringBuffer.isEmpty() ||
                newSr != sampleRate ||
                newCh != channelCount
            sampleRate = newSr
            channelCount = newCh
            capacityFrames = sampleRate * BUFFER_CAPACITY_SECONDS
            if (needRealloc) {
                ringBuffer = ShortArray(capacityFrames * channelCount)
                // Pre-allocate 1 full second of PCM scratch space so queueInput never allocates
                maxScratchFrames = sampleRate
                scratchIn = ShortArray(maxScratchFrames * channelCount)
            }
            totalFramesWritten = 0L
            anchorZeroDelayNs = Long.MIN_VALUE
            activeDelayFrames = delayMsToFramesLocked(requestedDelayMs)
            previousDelayFrames = activeDelayFrames
            crossfadeRemaining = 0
            crossfadeTotal = (sampleRate / 100).coerceIn(64, 1024)
        }

        // Pre-warm BaseAudioProcessor's internal output ByteBuffer to avoid runtime allocations
        val prewarmBytes = (sampleRate / 4) * channelCount * 2
        replaceOutputBuffer(prewarmBytes).flip()

        return AudioProcessor.AudioFormat(newSr, newCh, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remainingBytes = inputBuffer.remaining()
        val frameSize = channelCount * 2
        if (remainingBytes < frameSize || frameSize <= 0) return

        // Strictly align byte count to frameSize = channelCount * 2 and cap to pre-allocated scratch
        val maxFrames = if (maxScratchFrames > 0) maxScratchFrames else (remainingBytes / frameSize)
        val frameCount = min(remainingBytes / frameSize, maxFrames)
        if (frameCount <= 0) return

        val alignedBytes = frameCount * frameSize
        val totalShorts = frameCount * channelCount

        val srcOrder = inputBuffer.order()
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalShorts) {
            scratchIn[i] = inputBuffer.short
        }
        inputBuffer.order(srcOrder)

        val outBuf = replaceOutputBuffer(alignedBytes)
        outBuf.order(ByteOrder.LITTLE_ENDIAN)

        val nowNs = System.nanoTime()
        val chunkDurationNs = (frameCount.toLong() * NANOS_PER_SEC) / sampleRate.toLong()
        val measuredChunkStartNs = (nowNs - chunkDurationNs) + sinkLatencyNs

        var chunkStartTimeNs: Long
        var tapSnapshot: ((ShortArray, Int, Int, Int, Long) -> Unit)?
        val srSnapshot: Int
        val chSnapshot: Int

        synchronized(ringLock) {
            if (ringBuffer.isEmpty()) {
                for (i in 0 until totalShorts) {
                    outBuf.putShort(scratchIn[i])
                }
                outBuf.flip()
                return
            }

            srSnapshot = sampleRate
            chSnapshot = channelCount
            val cap = capacityFrames.toLong()

            // Maintain strictly monotonic zero-delay speaker clock anchor
            if (anchorZeroDelayNs == Long.MIN_VALUE) {
                anchorZeroDelayNs = measuredChunkStartNs -
                    (totalFramesWritten * NANOS_PER_SEC) / sampleRate.toLong()
            } else {
                val predictedStartNs = anchorZeroDelayNs +
                    (totalFramesWritten * NANOS_PER_SEC) / sampleRate.toLong()
                val errNs = measuredChunkStartNs - predictedStartNs
                anchorZeroDelayNs += if (abs(errNs) > 350_000_000L) {
                    errNs
                } else {
                    errNs / 40L
                }
            }

            chunkStartTimeNs = anchorZeroDelayNs +
                (totalFramesWritten * NANOS_PER_SEC) / sampleRate.toLong()

            val delayMs = requestedDelayMs
            val targetDelayFrames = delayMsToFramesLocked(delayMs)
            if (targetDelayFrames != activeDelayFrames) {
                previousDelayFrames = activeDelayFrames
                activeDelayFrames = targetDelayFrames
                crossfadeTotal = (sampleRate / 100).coerceIn(64, 1024)
                crossfadeRemaining = crossfadeTotal
            }

            // Fast, glitch-free pass-through when delayMs == 0L and no crossfade is active
            if (delayMs == 0L && activeDelayFrames == 0L && crossfadeRemaining == 0) {
                for (f in 0 until frameCount) {
                    val writeSlot = (totalFramesWritten % cap).toInt()
                    val writeBase = writeSlot * channelCount
                    val inBase = f * channelCount
                    for (c in 0 until channelCount) {
                        val s = scratchIn[inBase + c]
                        ringBuffer[writeBase + c] = s
                        outBuf.putShort(s)
                    }
                    totalFramesWritten++
                }
            } else {
                // Write incoming undelayed PCM first, then read delayed frame (read pointer <= write pointer)
                for (f in 0 until frameCount) {
                    val writeFrame = totalFramesWritten
                    val writeSlot = (writeFrame % cap).toInt()
                    val writeBase = writeSlot * channelCount
                    val inBase = f * channelCount
                    for (c in 0 until channelCount) {
                        ringBuffer[writeBase + c] = scratchIn[inBase + c]
                    }
                    totalFramesWritten++
                    val oldestAvailable = max(0L, totalFramesWritten - cap)

                    val newReadFrame = writeFrame - activeDelayFrames
                    if (crossfadeRemaining > 0) {
                        val oldReadFrame = writeFrame - previousDelayFrames
                        val progress = 1.0 - (crossfadeRemaining.toDouble() / crossfadeTotal.toDouble())
                        val fadeIn = (0.5 - 0.5 * cos(PI * progress)).toFloat()
                        val fadeOut = 1.0f - fadeIn
                        crossfadeRemaining--

                        for (c in 0 until channelCount) {
                            val sOld = readSampleLocked(oldReadFrame, oldestAvailable, c)
                            val sNew = readSampleLocked(newReadFrame, oldestAvailable, c)
                            val mixed = (sOld * fadeOut + sNew * fadeIn)
                                .toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                .toShort()
                            outBuf.putShort(mixed)
                        }
                    } else {
                        for (c in 0 until channelCount) {
                            outBuf.putShort(readSampleLocked(newReadFrame, oldestAvailable, c))
                        }
                    }
                }
            }

            tapSnapshot = liveTap
        }

        outBuf.flip()

        // Only deliver live PCM when SyncEngine is actively measuring
        tapSnapshot?.invoke(scratchIn, frameCount, chSnapshot, srSnapshot, chunkStartTimeNs)
    }

    private fun readSampleLocked(frameIndex: Long, oldestAvailable: Long, channel: Int): Short {
        if (frameIndex < 0L || frameIndex < oldestAvailable || frameIndex >= totalFramesWritten) {
            return 0
        }
        val slot = (frameIndex % capacityFrames.toLong()).toInt()
        return ringBuffer[slot * channelCount + channel]
    }

    private fun delayMsToFramesLocked(delayMs: Long): Long {
        if (delayMs <= 0L) return 0L
        val rawFrames = (delayMs * sampleRate.toLong()) / 1000L
        return rawFrames.coerceIn(0L, maxDelayFramesLocked())
    }

    private fun maxDelayFramesLocked(): Long {
        return min(
            (MAX_DELAY_SECONDS * sampleRate.toDouble()).roundToLong(),
            (capacityFrames - sampleRate).coerceAtLeast(0).toLong()
        )
    }

    override fun onFlush() {
        synchronized(ringLock) {
            crossfadeRemaining = 0
        }
    }

    override fun onReset() {
        synchronized(ringLock) {
            totalFramesWritten = 0L
            anchorZeroDelayNs = Long.MIN_VALUE
            crossfadeRemaining = 0
            ringBuffer = ShortArray(0)
            scratchIn = ShortArray(0)
            maxScratchFrames = 0
        }
    }

    override fun setLivePcmTap(
        tap: ((pcm: ShortArray, frameCount: Int, channels: Int, sampleRate: Int, startTimeNs: Long) -> Unit)?
    ) {
        liveTap = tap
    }

    override fun readHistoryChunks(
        maxDurationMs: Long,
        chunkMs: Int,
        consumer: (pcm: ShortArray, frameCount: Int, channels: Int, sampleRate: Int, startTimeNs: Long) -> Unit
    ) {
        val sr: Int
        val ch: Int
        val endFrame: Long
        val startFrame: Long
        val anchorNs: Long

        synchronized(ringLock) {
            if (ringBuffer.isEmpty() || totalFramesWritten <= 0L || anchorZeroDelayNs == Long.MIN_VALUE) {
                return
            }
            sr = sampleRate
            ch = channelCount
            endFrame = totalFramesWritten
            val cap = capacityFrames.toLong()
            val maxFrames = (maxDurationMs * sr.toLong()) / 1000L
            val oldestInRing = max(0L, endFrame - cap + (sr / 4).toLong())
            startFrame = max(oldestInRing, endFrame - maxFrames)
            anchorNs = anchorZeroDelayNs
        }

        if (startFrame >= endFrame) return

        val chunkFrames = max(1, (sr * chunkMs) / 1000)
        val chunkBuf = ShortArray(chunkFrames * ch)
        var cursor = startFrame

        while (cursor < endFrame) {
            val count = min(chunkFrames.toLong(), endFrame - cursor).toInt()
            var copied = 0
            synchronized(ringLock) {
                if (ringBuffer.isEmpty() || sr != sampleRate || ch != channelCount) return
                val cap = capacityFrames.toLong()
                val oldestNow = max(0L, totalFramesWritten - cap)
                if (cursor < oldestNow) {
                    cursor = oldestNow
                }
                val safeCount = min(count.toLong(), totalFramesWritten - cursor).toInt()
                if (safeCount > 0) {
                    for (f in 0 until safeCount) {
                        val slot = ((cursor + f) % cap).toInt()
                        val srcBase = slot * ch
                        val dstBase = f * ch
                        for (c in 0 until ch) {
                            chunkBuf[dstBase + c] = ringBuffer[srcBase + c]
                        }
                    }
                    copied = safeCount
                }
            }
            if (copied > 0) {
                val startTimeNs = anchorNs + (cursor * NANOS_PER_SEC) / sr.toLong()
                consumer(chunkBuf, copied, ch, sr, startTimeNs)
                cursor += copied.toLong()
            } else {
                break
            }
        }
    }

    companion object {
        const val MAX_DELAY_SECONDS = 180.0
        private const val BUFFER_CAPACITY_SECONDS = 185
        private const val NANOS_PER_SEC = 1_000_000_000L
        private const val DEFAULT_SINK_LATENCY_NS = 120_000_000L
        private const val MAX_SINK_LATENCY_NS = 2_500_000_000L
    }
}

/**
 * Wraps Media3 [DefaultAudioSink] to enforce an AudioTrack buffer size of at least 4x
 * [AudioTrack.getMinBufferSize] (preventing underruns/stuttering) and continuously measure
 * real AudioTrack / Bluetooth output latency for [DelayAudioProcessor].
 */
@OptIn(UnstableApi::class)
class SyncAudioSink(
    private val delegate: DefaultAudioSink,
    private val delayProcessor: DelayAudioProcessor
) : AudioSink by delegate {

    private var lastSampleRate = 48_000
    private var lastPcmFrameSize = 4

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        val sr = if (inputFormat.sampleRate > 0) inputFormat.sampleRate else 48_000
        val ch = if (inputFormat.channelCount > 0) inputFormat.channelCount else 2
        lastSampleRate = sr
        lastPcmFrameSize = ch * 2

        val channelConfig = when (ch) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        val minBuf = AudioTrack.getMinBufferSize(sr, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val fourXMinBuf = if (minBuf > 0) minBuf * 4 else (sr * lastPcmFrameSize) / 2
        val rawTarget = max(specifiedBufferSize, fourXMinBuf)
        val alignedBufferSize = ((rawTarget + lastPcmFrameSize - 1) / lastPcmFrameSize) * lastPcmFrameSize

        delegate.configure(inputFormat, alignedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        val posBefore = buffer.position()
        val handled = delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val consumedBytes = buffer.position() - posBefore

        if (consumedBytes > 0 && presentationTimeUs != C.TIME_UNSET) {
            val playedUs = delegate.getCurrentPositionUs(false)
            if (playedUs != AudioSink.CURRENT_POSITION_NOT_SET) {
                val consumedFrames = consumedBytes / max(1, lastPcmFrameSize)
                val consumedUs = (consumedFrames.toLong() * 1_000_000L) / max(1, lastSampleRate)
                val queuedEndUs = presentationTimeUs + consumedUs
                val latencyUs = queuedEndUs - playedUs
                if (latencyUs in 5_000L..2_500_000L) {
                    delayProcessor.updateOutputLatencyNs(latencyUs * 1000L)
                }
            }
        }
        return handled
    }
}

/**
 * Fallback raw-socket HTTP relay used ONLY when ExoPlayer fails on legacy ICY/invalid-HTTP-header
 * Shoutcast servers. Uses 16384-byte buffered streams to prevent socket stutter.
 */
class StreamRelay(
    private val onBytesTransferred: (Int) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var activeClientSocket: Socket? = null
    private var activeUpstreamSocket: Socket? = null

    @Volatile
    private var targetUrl: String = ""

    fun start(upstreamUrl: String): String {
        stop()
        targetUrl = upstreamUrl.trim()

        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        server.reuseAddress = true
        serverSocket = server
        val port = server.localPort
        running.set(true)

        val worker = Thread({
            while (running.get() && !server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                closeSocketQuietly(activeClientSocket)
                closeSocketQuietly(activeUpstreamSocket)
                activeClientSocket = client
                Thread({
                    handleClient(client, targetUrl)
                }, "FootSync-RelayPipe").start()
            }
        }, "FootSync-RelayAccept")
        acceptThread = worker
        worker.start()

        return "http://127.0.0.1:$port/stream"
    }

    fun stop() {
        running.set(false)
        closeSocketQuietly(activeUpstreamSocket)
        closeSocketQuietly(activeClientSocket)
        activeUpstreamSocket = null
        activeClientSocket = null
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun handleClient(client: Socket, initialUrl: String) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            client.sendBufferSize = RELAY_BUFFER_BYTES

            val clientIn = BufferedInputStream(client.getInputStream(), RELAY_BUFFER_BYTES)
            drainHttpRequest(clientIn)

            var currentUrl = initialUrl
            var redirects = 0
            var upstreamInput: BufferedInputStream? = null
            var statusLine = ""
            var headers = mutableMapOf<String, String>()

            while (redirects < 6) {
                val parsed = URL(currentUrl)
                val isHttps = parsed.protocol.equals("https", ignoreCase = true)
                val host = parsed.host
                val port = if (parsed.port > 0) parsed.port else if (isHttps) 443 else 80
                val pathAndQuery = buildString {
                    append(if (parsed.path.isNullOrEmpty()) "/" else parsed.path)
                    if (!parsed.query.isNullOrEmpty()) {
                        append('?').append(parsed.query)
                    }
                }

                val sock = openUpstreamSocket(host, port, isHttps)
                upstream = sock
                activeUpstreamSocket = sock

                val req = buildString {
                    append("GET ").append(pathAndQuery).append(" HTTP/1.1\r\n")
                    append("Host: ").append(host)
                    if ((isHttps && port != 443) || (!isHttps && port != 80)) {
                        append(':').append(port)
                    }
                    append("\r\n")
                    append("User-Agent: FootSyncRadio/1.0 (Android)\r\n")
                    append("Accept: */*\r\n")
                    append("Icy-MetaData: 0\r\n")
                    append("Connection: close\r\n\r\n")
                }

                val out = sock.getOutputStream()
                out.write(req.toByteArray(StandardCharsets.US_ASCII))
                out.flush()

                val uIn = BufferedInputStream(sock.getInputStream(), RELAY_BUFFER_BYTES)
                statusLine = readAsciiLine(uIn)
                headers = readHeaders(uIn)

                val upperStatus = statusLine.uppercase(Locale.US)
                if (upperStatus.contains(" 301") ||
                    upperStatus.contains(" 302") ||
                    upperStatus.contains(" 303") ||
                    upperStatus.contains(" 307") ||
                    upperStatus.contains(" 308")
                ) {
                    val loc = headers["location"]
                    closeSocketQuietly(sock)
                    upstream = null
                    if (loc.isNullOrBlank()) {
                        throw IOException("Empty redirect location")
                    }
                    currentUrl = URL(parsed, loc.trim()).toString()
                    redirects++
                    continue
                }

                upstreamInput = uIn
                break
            }

            val uIn = upstreamInput ?: throw IOException("Too many redirects")
            val upperStatus = statusLine.uppercase(Locale.US)
            val isOk = upperStatus.startsWith("ICY 200") ||
                upperStatus.contains(" 200") ||
                upperStatus.contains(" 206")

            if (!isOk) {
                writeErrorResponse(client.getOutputStream(), 502)
                return
            }

            val contentType = headers["content-type"]?.takeIf { it.isNotBlank() } ?: "audio/mpeg"
            val clientOut = BufferedOutputStream(client.getOutputStream(), RELAY_BUFFER_BYTES)
            val responseHeader = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: close\r\n\r\n")
            }
            clientOut.write(responseHeader.toByteArray(StandardCharsets.US_ASCII))
            clientOut.flush()

            val buf = ByteArray(RELAY_BUFFER_BYTES)
            while (running.get() && !client.isClosed) {
                val n = uIn.read(buf)
                if (n < 0) break
                if (n > 0) {
                    clientOut.write(buf, 0, n)
                    clientOut.flush()
                    onBytesTransferred(n)
                }
            }
            clientOut.flush()
        } catch (_: Throwable) {
        } finally {
            closeSocketQuietly(upstream)
            closeSocketQuietly(client)
        }
    }

    private fun openUpstreamSocket(host: String, port: Int, isHttps: Boolean): Socket {
        val raw = Socket()
        raw.receiveBufferSize = RELAY_BUFFER_BYTES * 2
        raw.connect(InetSocketAddress(host, port), 15_000)
        raw.soTimeout = 15_000
        raw.tcpNoDelay = true
        if (!isHttps) return raw

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
        ssl.soTimeout = 15_000
        ssl.startHandshake()
        return ssl
    }

    private fun drainHttpRequest(input: InputStream) {
        var lineCount = 0
        while (lineCount < 64) {
            val line = readAsciiLine(input)
            if (line.isEmpty()) break
            lineCount++
        }
    }

    private fun readHeaders(input: InputStream): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        var count = 0
        while (count < 64) {
            val line = readAsciiLine(input)
            if (line.isEmpty()) break
            val sep = line.indexOf(':')
            if (sep > 0) {
                val k = line.substring(0, sep).trim().lowercase(Locale.US)
                val v = line.substring(sep + 1).trim()
                map[k] = v
            }
            count++
        }
        return map
    }

    private fun readAsciiLine(input: InputStream): String {
        val baos = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                baos.write(b)
            }
            if (baos.size() > 4096) break
        }
        return baos.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun writeErrorResponse(out: OutputStream, code: Int) {
        try {
            val resp = "HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(StandardCharsets.US_ASCII))
            out.flush()
        } catch (_: Throwable) {
        }
    }

    private fun closeSocketQuietly(socket: Socket?) {
        if (socket != null) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val RELAY_BUFFER_BYTES = 16_384
    }
}

/**
 * Receives the AlarmManager restart intent if the Android OS kills [RadioPlaybackService]
 * during an active match playback session.
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val svcIntent = Intent(context, RadioPlaybackService::class.java).apply {
            action = RadioPlaybackService.ACTION_RESTORE_PLAYBACK
        }
        try {
            ContextCompat.startForegroundService(context, svcIntent)
        } catch (_: Throwable) {
        }
    }
}

/**
 * Dedicated background mediaPlayback Foreground Service hosting ExoPlayer (direct HTTP/HTTPS/HLS
 * first, ICY relay fallback only on invalid HTTP/ICY header errors), 180s PCM delay ring buffer,
 * auto-reconnect, on-demand SyncEngine, persistent Arabic notification, and FloatingOverlayController.
 */
@OptIn(UnstableApi::class)
class RadioPlaybackService : Service(), SyncListener, OverlayCallbacks {

    inner class LocalBinder : Binder() {
        fun getService(): RadioPlaybackService = this@RadioPlaybackService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(ServiceUiState) -> Unit>()

    lateinit var libraryManager: LibraryManager
        private set
    lateinit var prefs: FootSyncPrefs
        private set

    private lateinit var delayProcessor: DelayAudioProcessor
    private lateinit var streamRelay: StreamRelay
    private lateinit var syncEngine: SyncEngine
    private lateinit var overlayController: FloatingOverlayController
    private lateinit var notificationManager: NotificationManager

    private var player: ExoPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var currentTarget: PlaybackTarget? = null
    private var shouldStayAlive = false
    private var userStopped = false
    private var isForegroundStarted = false
    private var isMuted = false
    private var mutedBeforeSync = false
    private var autoMutedBySync = false
    private var useRelayForCurrentUrl = false
    private var reconnectBackoffMs = INITIAL_RECONNECT_MS
    private var playbackHealthySinceMs = 0L

    private val bytesInCurrentWindow = AtomicLong(0L)
    private var lastSpeedSampleTimeMs = 0L
    private var currentSpeedKbps = 0.0

    private var uiState = ServiceUiState()

    private val directTransferListener = object : TransferListener {
        override fun onTransferInitializing(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean
        ) = Unit

        override fun onTransferStart(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean
        ) = Unit

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int
        ) {
            if (isNetwork && bytesTransferred > 0) {
                bytesInCurrentWindow.addAndGet(bytesTransferred.toLong())
            }
        }

        override fun onTransferEnd(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean
        ) = Unit
    }

    private val reconnectRunnable = Runnable {
        val target = currentTarget ?: return@Runnable
        if (!shouldStayAlive || userStopped) return@Runnable
        startStreamForTarget(target, isReconnect = true)
    }

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            val nowMs = SystemClock.elapsedRealtime()
            val dtMs = (nowMs - lastSpeedSampleTimeMs).coerceAtLeast(1L)
            val bytes = bytesInCurrentWindow.getAndSet(0L)
            val instantKbps = (bytes.toDouble() / 1024.0) / (dtMs.toDouble() / 1000.0)
            currentSpeedKbps = if (bytes == 0L && currentSpeedKbps < 0.2) {
                0.0
            } else {
                currentSpeedKbps * 0.55 + instantKbps * 0.45
            }
            lastSpeedSampleTimeMs = nowMs

            if (playbackHealthySinceMs > 0L && (nowMs - playbackHealthySinceMs) > 4000L) {
                reconnectBackoffMs = INITIAL_RECONNECT_MS
            }

            val bufSec = delayProcessor.getBufferedHistorySeconds()
            updateState {
                it.copy(
                    speedKbps = if (it.isPlaying || it.isBuffering) currentSpeedKbps else 0.0,
                    bufferedHistorySec = bufSec
                )
            }
            if (isForegroundStarted) {
                updateNotification()
            }
            mainHandler.postDelayed(this, TELEMETRY_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        libraryManager = LibraryManager.getInstance(applicationContext)
        prefs = FootSyncPrefs(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        delayProcessor = DelayAudioProcessor()
        streamRelay = StreamRelay { bytes ->
            bytesInCurrentWindow.addAndGet(bytes.toLong())
        }
        syncEngine = SyncEngine(delayProcessor, this)
        overlayController = FloatingOverlayController(this, this)

        initLocks()
        initPlayer()

        lastSpeedSampleTimeMs = SystemClock.elapsedRealtime()
        mainHandler.postDelayed(telemetryRunnable, TELEMETRY_INTERVAL_MS)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> stepChannel(-1)
            ACTION_NEXT -> stepChannel(+1)
            ACTION_TOGGLE_MUTE -> toggleMute()
            ACTION_TOGGLE_PLAY_PAUSE -> togglePlayPause()
            ACTION_TOGGLE_OVERLAY -> toggleFloatingOverlay()
            ACTION_STOP -> stopPlaybackAndService()
            ACTION_RESTORE_PLAYBACK -> {
                if (!shouldStayAlive) {
                    val last = libraryManager.getLastOrFirstTarget()
                    if (last != null) {
                        playTarget(last)
                    } else {
                        stopSelf()
                    }
                }
            }
        }
        return if (shouldStayAlive && !userStopped) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (shouldStayAlive && !userStopped) {
            scheduleRestartAlarm()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(telemetryRunnable)

        if (syncEngine.isRunning) {
            syncEngine.cancel()
        }
        overlayController.hide()
        streamRelay.stop()
        releasePlayer()
        releaseLocks()

        if (shouldStayAlive && !userStopped) {
            scheduleRestartAlarm()
        }
        super.onDestroy()
    }

    fun addStateListener(listener: (ServiceUiState) -> Unit) {
        listeners.add(listener)
        mainHandler.post { listener(uiState) }
    }

    fun removeStateListener(listener: (ServiceUiState) -> Unit) {
        listeners.remove(listener)
    }

    fun getCurrentState(): ServiceUiState = uiState

    fun playChannel(libraryId: String, channelIndex: Int) {
        val target = libraryManager.getTarget(libraryId, channelIndex) ?: return
        playTarget(target)
    }

    fun playTarget(target: PlaybackTarget) {
        userStopped = false
        shouldStayAlive = true
        mainHandler.removeCallbacks(reconnectRunnable)

        if (syncEngine.isRunning) {
            syncEngine.cancel()
        }

        val isSameUrl = currentTarget?.channel?.url == target.channel.url
        currentTarget = target
        prefs.setActiveLibraryId(target.libraryId)
        prefs.saveLastPlayingPosition(target.libraryId, target.index)

        val savedDelay = prefs.getDelayForUrl(target.channel.url)
        delayProcessor.setDelaySeconds(savedDelay)
        if (!isSameUrl) {
            delayProcessor.clearHistory()
            // Always try direct ExoPlayer first on every new URL
            useRelayForCurrentUrl = false
        }

        reconnectBackoffMs = INITIAL_RECONNECT_MS
        bytesInCurrentWindow.set(0L)
        currentSpeedKbps = 0.0

        updateState {
            it.copy(
                libraryId = target.libraryId,
                libraryName = target.libraryName,
                channelIndex = target.index,
                totalChannels = target.totalCount,
                channelName = target.channel.name,
                channelUrl = target.channel.url,
                delaySec = savedDelay,
                isPlaying = true,
                isBuffering = true,
                connectionStatus = "جاري الاتصال…",
                unconfirmedCandidateSec = null,
                lastSyncBannerText = ""
            )
        }

        ensureForegroundStarted()
        acquireLocks()
        startStreamForTarget(target, isReconnect = false)
    }

    fun stepChannel(delta: Int): PlaybackTarget? {
        val baseLibId = currentTarget?.libraryId ?: prefs.getActiveLibraryId()
        val baseIndex = currentTarget?.index ?: if (delta > 0) -1 else 0
        val next = libraryManager.stepChannel(baseLibId, baseIndex, delta) ?: return null
        playTarget(next)
        return next
    }

    fun togglePlayPause() {
        val p = player ?: return
        val target = currentTarget
        if (target == null) {
            val fallback = libraryManager.getLastOrFirstTarget() ?: return
            playTarget(fallback)
            return
        }

        if (p.playWhenReady && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)) {
            shouldStayAlive = false
            mainHandler.removeCallbacks(reconnectRunnable)
            p.playWhenReady = false
            releaseLocks()
            updateState {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    connectionStatus = "متوقف مؤقتاً",
                    speedKbps = 0.0
                )
            }
            updateNotification()
        } else {
            userStopped = false
            shouldStayAlive = true
            ensureForegroundStarted()
            acquireLocks()
            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
                startStreamForTarget(target, isReconnect = false)
            } else {
                p.playWhenReady = true
                updateState {
                    it.copy(
                        isPlaying = true,
                        connectionStatus = "متصل"
                    )
                }
                updateNotification()
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        player?.volume = if (muted) 0f else 1f
        updateState { it.copy(isMuted = muted) }
        updateNotification()
    }

    fun toggleMute() {
        setMuted(!isMuted)
    }

    fun setDelaySeconds(seconds: Double, persistForUrl: Boolean = true) {
        val clamped = seconds.coerceIn(0.0, FootSyncPrefs.MAX_DELAY_SEC)
        delayProcessor.setDelaySeconds(clamped)
        if (persistForUrl) {
            currentTarget?.channel?.url?.let { url ->
                prefs.saveDelayForUrl(url, clamped)
            }
        }
        updateState { it.copy(delaySec = clamped) }
        updateNotification()
    }

    fun adjustDelaySeconds(deltaSec: Double) {
        setDelaySeconds(delayProcessor.getDelaySeconds() + deltaSec, persistForUrl = true)
    }

    fun resetDelay() {
        setDelaySeconds(0.0, persistForUrl = true)
    }

    fun startAutoSync(): Boolean {
        if (syncEngine.isRunning) return true
        val target = currentTarget
        if (target == null || !(uiState.isPlaying || uiState.isBuffering)) {
            updateState {
                it.copy(lastSyncBannerText = "شغّل محطة إذاعية أولاً قبل بدء المزامنة التلقائية.")
            }
            return false
        }

        val timeoutSec = prefs.getSyncTimeoutSec()
        val earphoneNoMute = prefs.isEarphoneNoMuteEnabled()
        mutedBeforeSync = isMuted
        autoMutedBySync = !earphoneNoMute && !isMuted
        if (autoMutedBySync) {
            setMuted(true)
        }

        updateState {
            it.copy(
                isSyncing = true,
                syncElapsedSec = 0,
                syncTimeoutSec = timeoutSec,
                syncMicLevel = 0f,
                syncHasCandidate = false,
                syncCandidateSec = null,
                unconfirmedCandidateSec = null,
                syncStatusText = "جاري تجهيز البصمة الصوتية والاستماع للتلفزيون…",
                lastSyncBannerText = ""
            )
        }
        syncEngine.start(timeoutSec)
        return true
    }

    fun cancelAutoSync() {
        if (syncEngine.isRunning) {
            syncEngine.cancel()
        }
    }

    fun toggleAutoSync(): Boolean {
        return if (syncEngine.isRunning) {
            cancelAutoSync()
            false
        } else {
            startAutoSync()
        }
    }

    fun applyUnconfirmedCandidate(lagSec: Double) {
        applyMeasuredSyncLag(lagSec, isManualCandidate = true)
    }

    fun showFloatingOverlay(startMinimized: Boolean = false): Boolean {
        val shown = overlayController.show(startMinimized)
        updateState { it.copy(isOverlayVisible = overlayController.isVisible) }
        if (shown) {
            overlayController.updateState(buildOverlayState(uiState))
            updateNotification()
        }
        return shown
    }

    fun hideFloatingOverlay() {
        overlayController.hide()
        updateState { it.copy(isOverlayVisible = false) }
        updateNotification()
    }

    fun toggleFloatingOverlay(): Boolean {
        return if (overlayController.isVisible) {
            hideFloatingOverlay()
            false
        } else {
            showFloatingOverlay(startMinimized = false)
        }
    }

    fun stopPlaybackAndService() {
        userStopped = true
        shouldStayAlive = false
        cancelRestartAlarm()
        mainHandler.removeCallbacks(reconnectRunnable)
        if (syncEngine.isRunning) {
            syncEngine.cancel()
        }
        overlayController.hide()
        streamRelay.stop()
        player?.stop()
        releaseLocks()

        updateState {
            it.copy(
                isPlaying = false,
                isBuffering = false,
                isOverlayVisible = false,
                connectionStatus = "متوقف",
                speedKbps = 0.0
            )
        }

        if (isForegroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForegroundStarted = false
        }
        stopSelf()
    }

    // === SyncListener implementation ===

    override fun onSyncStarted(timeoutSec: Int) {
        updateState {
            it.copy(
                isSyncing = true,
                syncElapsedSec = 0,
                syncTimeoutSec = timeoutSec,
                syncStatusText = "جاري الاستماع لصوت التلفزيون والمطابقة… (0/$timeoutSec ث)"
            )
        }
    }

    override fun onProgress(
        elapsedSec: Int,
        timeoutSec: Int,
        micLevel: Float,
        hasCandidate: Boolean,
        candidateLagSec: Double?,
        bestZ: Double,
        overlapSec: Double,
        statusText: String
    ) {
        updateState {
            it.copy(
                isSyncing = true,
                syncElapsedSec = elapsedSec,
                syncTimeoutSec = timeoutSec,
                syncMicLevel = micLevel,
                syncHasCandidate = hasCandidate,
                syncCandidateSec = candidateLagSec,
                syncStatusText = statusText
            )
        }
    }

    override fun onCandidateDetected(candidateLagSec: Double, peakZ: Double, marginZ: Double) {
        val msg = String.format(
            Locale.US,
            "تم رصد مرشّح %.1f ث • يتأكد على صوت جديد…",
            candidateLagSec
        )
        updateState {
            it.copy(
                syncHasCandidate = true,
                syncCandidateSec = candidateLagSec,
                syncStatusText = msg
            )
        }
    }

    override fun onSyncConfirmed(measuredLagSec: Double, peakZ: Double, overlapSec: Double) {
        restoreAudioAfterSync(forceUnmute = true)
        applyMeasuredSyncLag(measuredLagSec, isManualCandidate = false)
    }

    override fun onSyncTimeout(unconfirmedCandidateLagSec: Double?) {
        restoreAudioAfterSync(forceUnmute = false)
        val banner = if (unconfirmedCandidateLagSec != null) {
            String.format(
                Locale.US,
                "انتهى الوقت دون تأكيد مزدوج • تم رصد مرشّح غير مؤكد (%.1f ث). يمكنك تطبيقه يدوياً بالأسفل.",
                unconfirmedCandidateLagSec
            )
        } else {
            "انتهى وقت المزامنة ولم يتم التقاط تطابق مؤكد. تأكد من ارتفاع صوت التلفزيون وحاول مجدداً."
        }
        updateState {
            it.copy(
                isSyncing = false,
                syncMicLevel = 0f,
                syncStatusText = "",
                syncHasCandidate = false,
                syncCandidateSec = null,
                unconfirmedCandidateSec = unconfirmedCandidateLagSec,
                lastSyncBannerText = banner
            )
        }
    }

    override fun onSyncCancelled() {
        restoreAudioAfterSync(forceUnmute = false)
        updateState {
            it.copy(
                isSyncing = false,
                syncMicLevel = 0f,
                syncStatusText = "",
                syncHasCandidate = false,
                syncCandidateSec = null,
                lastSyncBannerText = "تم إلغاء المزامنة التلقائية واستعادة الصوت."
            )
        }
    }

    override fun onSyncError(message: String) {
        restoreAudioAfterSync(forceUnmute = false)
        updateState {
            it.copy(
                isSyncing = false,
                syncMicLevel = 0f,
                syncStatusText = "",
                syncHasCandidate = false,
                syncCandidateSec = null,
                lastSyncBannerText = message
            )
        }
    }

    // === OverlayCallbacks implementation ===

    override fun onOverlayPrevChannel() {
        stepChannel(-1)
    }

    override fun onOverlayNextChannel() {
        stepChannel(+1)
    }

    override fun onOverlayAdjustDelay(deltaSec: Double) {
        adjustDelaySeconds(deltaSec)
    }

    override fun onOverlayTogglePlayPause() {
        togglePlayPause()
    }

    override fun onOverlayToggleMute() {
        toggleMute()
    }

    override fun onOverlayToggleSync() {
        toggleAutoSync()
    }

    override fun onOverlayOpenApp() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(openIntent)
    }

    override fun onOverlayHidden() {
        updateState { it.copy(isOverlayVisible = false) }
        updateNotification()
    }

    // === Internal Engine Helpers ===

    private fun applyMeasuredSyncLag(measuredLagSec: Double, isManualCandidate: Boolean) {
        val trimSec = prefs.getCalibrationTrimSec()
        val combined = measuredLagSec + trimSec
        val banner: String
        val appliedDelay: Double

        if (combined < 0.0) {
            appliedDelay = 0.0
            banner = String.format(
                Locale.US,
                "البث الإذاعي متأخر أصلاً عن التلفزيون بـ %.2f ث، لذلك تم ضبط التأخير على 0.00 ث.",
                abs(combined)
            )
        } else {
            appliedDelay = combined.coerceIn(0.0, FootSyncPrefs.MAX_DELAY_SEC)
            banner = if (isManualCandidate) {
                String.format(
                    Locale.US,
                    "تم تطبيق المرشّح يدوياً: %.2f ث (معايرة %+.2f ث).",
                    appliedDelay,
                    trimSec
                )
            } else {
                String.format(
                    Locale.US,
                    "تمت المزامنة بنجاح ✓ التأخير المضبوط: %.2f ث (معايرة %+.2f ث).",
                    appliedDelay,
                    trimSec
                )
            }
        }

        setDelaySeconds(appliedDelay, persistForUrl = true)
        updateState {
            it.copy(
                isSyncing = false,
                syncMicLevel = 0f,
                syncStatusText = "",
                syncHasCandidate = false,
                syncCandidateSec = null,
                unconfirmedCandidateSec = null,
                lastSyncBannerText = banner
            )
        }
    }

    private fun restoreAudioAfterSync(forceUnmute: Boolean) {
        if (forceUnmute) {
            autoMutedBySync = false
            setMuted(false)
        } else if (autoMutedBySync) {
            autoMutedBySync = false
            setMuted(mutedBeforeSync)
        }
    }

    private fun initPlayer() {
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val baseSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(delayProcessor))
                    .build()
                return SyncAudioSink(baseSink, delayProcessor)
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("FootSyncRadio/1.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setTransferListener(directTransferListener)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                50_000, // maxBufferMs
                2_500,  // bufferForPlaybackMs
                5_000   // bufferForPlaybackAfterRebufferMs
            )
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        // handleAudioFocus = false so incoming phone notifications never pause or break the delay ring
        exo.setAudioAttributes(audioAttributes, false)
        exo.volume = if (isMuted) 0f else 1f

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        playbackHealthySinceMs = 0L
                        updateState {
                            it.copy(
                                isBuffering = true,
                                connectionStatus = if (useRelayForCurrentUrl) {
                                    "جاري التخزين (ICY)…"
                                } else {
                                    "جاري التخزين المؤقت…"
                                }
                            )
                        }
                        updateNotification()
                    }

                    Player.STATE_READY -> {
                        if (exo.playWhenReady) {
                            playbackHealthySinceMs = SystemClock.elapsedRealtime()
                            updateState {
                                it.copy(
                                    isPlaying = true,
                                    isBuffering = false,
                                    usingRelay = useRelayForCurrentUrl,
                                    connectionStatus = if (useRelayForCurrentUrl) {
                                        "متصل (ICY Relay)"
                                    } else {
                                        "متصل (مباشر)"
                                    }
                                )
                            }
                            updateNotification()
                        }
                    }

                    Player.STATE_ENDED -> {
                        if (shouldStayAlive && !userStopped) {
                            scheduleAutoReconnect("انقطع البث • إعادة الاتصال…")
                        }
                    }

                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!shouldStayAlive || userStopped) return
                playbackHealthySinceMs = 0L

                val target = currentTarget
                // ONLY fall back to StreamRelay if direct ExoPlayer failed with an ICY/invalid-HTTP-header error
                if (!useRelayForCurrentUrl && target != null && isIcyOrInvalidHttpHeaderError(error)) {
                    val lowerUrl = target.channel.url.trim().lowercase(Locale.US)
                    if ((lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) &&
                        !lowerUrl.contains(".m3u8")
                    ) {
                        useRelayForCurrentUrl = true
                        updateState {
                            it.copy(
                                isBuffering = true,
                                usingRelay = true,
                                connectionStatus = "التحويل إلى وضع ICY Relay…"
                            )
                        }
                        startStreamForTarget(target, isReconnect = true)
                        return
                    }
                }

                scheduleAutoReconnect("انقطاع في الاتصال • إعادة المحاولة…")
            }
        })

        player = exo
    }

    private fun isIcyOrInvalidHttpHeaderError(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 10) {
            if (cause is ProtocolException) return true
            val msg = cause.message?.lowercase(Locale.US) ?: ""
            if (msg.contains("icy") ||
                msg.contains("unexpected status line") ||
                msg.contains("invalid status line") ||
                msg.contains("malformed status") ||
                msg.contains("bad status") ||
                msg.contains("invalid http")
            ) {
                return true
            }
            if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode <= 0) {
                return true
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    private fun startStreamForTarget(target: PlaybackTarget, isReconnect: Boolean) {
        val exo = player ?: return
        val rawUrl = target.channel.url.trim()
        streamRelay.stop()

        val playableUrl = if (useRelayForCurrentUrl) {
            try {
                streamRelay.start(rawUrl)
            } catch (_: Throwable) {
                useRelayForCurrentUrl = false
                rawUrl
            }
        } else {
            // Direct ExoPlayer for all HTTP/HTTPS/HLS (.m3u8) streams
            rawUrl
        }

        updateState {
            it.copy(
                usingRelay = useRelayForCurrentUrl,
                isBuffering = true,
                connectionStatus = if (isReconnect) {
                    "إعادة الاتصال…"
                } else {
                    "جاري الاتصال…"
                }
            )
        }

        try {
            exo.stop()
            exo.setMediaItem(MediaItem.fromUri(playableUrl))
            exo.prepare()
            exo.playWhenReady = true
            exo.volume = if (isMuted) 0f else 1f
        } catch (_: Throwable) {
            scheduleAutoReconnect("فشل بدء التشغيل • إعادة المحاولة…")
        }
    }

    private fun scheduleAutoReconnect(reasonText: String) {
        mainHandler.removeCallbacks(reconnectRunnable)
        val waitMs = reconnectBackoffMs
        reconnectBackoffMs = min(MAX_RECONNECT_MS, reconnectBackoffMs * 2L)

        updateState {
            it.copy(
                isBuffering = true,
                speedKbps = 0.0,
                connectionStatus = "$reasonText (${waitMs / 1000.0} ث)"
            )
        }
        updateNotification()
        mainHandler.postDelayed(reconnectRunnable, waitMs)
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
    }

    private fun initLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FootSyncRadio::PlaybackWakeLock")
            .apply { setReferenceCounted(false) }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(lockMode, "FootSyncRadio::StreamWifiLock")
            .apply { setReferenceCounted(false) }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4L * 60L * 60L * 1000L)
            }
        } catch (_: Throwable) {
        }
        try {
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {
        }
    }

    private fun scheduleRestartAlarm() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = buildRestartPendingIntent()
            val triggerAt = SystemClock.elapsedRealtime() + 1_500L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (_: Throwable) {
        }
    }

    private fun cancelRestartAlarm() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(buildRestartPendingIntent())
        } catch (_: Throwable) {
        }
    }

    private fun buildRestartPendingIntent(): PendingIntent {
        val intent = Intent(this, ServiceRestartReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(this, REQUEST_RESTART_ALARM, intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "تشغيل إذاعة المباريات والمزامنة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار مستمر للتحكم ببث التعليق الإذاعي وتأخير الصوت"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun ensureForegroundStarted() {
        val notification = buildNotification()
        if (!isForegroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundStarted = true
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!isForegroundStarted) return
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(): Notification {
        val state = uiState
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val contentPi = PendingIntent.getActivity(this, 100, openAppIntent, piFlags)

        val prevPi = serviceActionPendingIntent(ACTION_PREV, 101)
        val nextPi = serviceActionPendingIntent(ACTION_NEXT, 102)
        val mutePi = serviceActionPendingIntent(ACTION_TOGGLE_MUTE, 103)
        val overlayPi = serviceActionPendingIntent(ACTION_TOGGLE_OVERLAY, 104)
        val stopPi = serviceActionPendingIntent(ACTION_STOP, 105)

        val title = if (state.channelIndex >= 0 && state.totalChannels > 0) {
            "${state.channelIndex + 1}/${state.totalChannels} • ${state.channelName}"
        } else {
            state.channelName
        }

        val speedPart = if (state.speedKbps >= 0.5) {
            String.format(Locale.US, " • %.1f KB/s", state.speedKbps)
        } else {
            ""
        }
        val subtitle = String.format(
            Locale.US,
            "التأخير: %.2f ث • %s%s",
            state.delaySec,
            state.connectionStatus,
            speedPart
        )

        val muteLabel = if (state.isMuted) "إلغاء الكتم" else "كتم"
        val muteIcon = if (state.isMuted) {
            android.R.drawable.ic_lock_silent_mode_off
        } else {
            android.R.drawable.ic_lock_silent_mode
        }
        val overlayLabel = if (state.isOverlayVisible) "إخفاء العائمة" else "إظهار العائمة"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "السابق", prevPi)
            .addAction(muteIcon, muteLabel, mutePi)
            .addAction(android.R.drawable.ic_media_next, "التالي", nextPi)
            .addAction(android.R.drawable.ic_menu_view, overlayLabel, overlayPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPi)
            .setStyle(
                MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RadioPlaybackService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    private fun updateState(transform: (ServiceUiState) -> ServiceUiState) {
        val next = transform(uiState)
        uiState = next
        if (overlayController.isVisible) {
            overlayController.updateState(buildOverlayState(next))
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (listener in listeners) {
                listener(next)
            }
        } else {
            mainHandler.post {
                for (listener in listeners) {
                    listener(next)
                }
            }
        }
    }

    private fun buildOverlayState(state: ServiceUiState): OverlayState {
        val badge = if (state.channelIndex >= 0 && state.totalChannels > 0) {
            "${state.channelIndex + 1}/${state.totalChannels}"
        } else {
            ""
        }
        return OverlayState(
            stationName = state.channelName,
            channelBadge = badge,
            delaySec = state.delaySec,
            isPlaying = state.isPlaying,
            isMuted = state.isMuted,
            isSyncing = state.isSyncing,
            syncStatusText = state.syncStatusText
        )
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "footsync_playback_channel"
        private const val NOTIFICATION_ID = 4101
        private const val REQUEST_RESTART_ALARM = 4102
        private const val TELEMETRY_INTERVAL_MS = 1_000L
        private const val INITIAL_RECONNECT_MS = 250L
        private const val MAX_RECONNECT_MS = 5_000L

        const val ACTION_PREV = "com.footsync.radio.ACTION_PREV"
        const val ACTION_NEXT = "com.footsync.radio.ACTION_NEXT"
        const val ACTION_TOGGLE_MUTE = "com.footsync.radio.ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.footsync.radio.ACTION_TOGGLE_PLAY_PAUSE"
        const val ACTION_TOGGLE_OVERLAY = "com.footsync.radio.ACTION_TOGGLE_OVERLAY"
        const val ACTION_STOP = "com.footsync.radio.ACTION_STOP"
        const val ACTION_RESTORE_PLAYBACK = "com.footsync.radio.ACTION_RESTORE_PLAYBACK"
    }
}
