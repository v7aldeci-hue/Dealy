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
 * Full playback and unlimited auto-sync UI snapshot pushed from [RadioPlaybackService] to [MainActivity].
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
    val syncStartElapsedMs: Long = 0L,
    val syncElapsedSec: Int = 0,
    val syncMicLevel: Float = 0f,
    val syncStatusText: String = "",
    val syncHasCandidate: Boolean = false,
    val syncCandidateSec: Double? = null,
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

        val prewarmBytes = (sampleRate / 4) * channelCount * 2
        replaceOutputBuffer(prewarmBytes).flip()

        return AudioProcessor.AudioFormat(newSr, newCh, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remainingBytes = inputBuffer.remaining()
        val frameSize = channelCount * 2
        if (remainingBytes < frameSize || frameSize <= 0) return

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
 * [AudioTrack.getMinBufferSize] and continuously measure output latency for [DelayAudioProcessor].
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
                val consumedFrames = consumedBytes / max(
