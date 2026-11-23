package com.footsync.radio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cursor-based access to the undelayed radio PCM circular buffer (exact mirror of Lu0/k0 + Lu0/L0).
 */
interface RadioPcmSource {
    /**
     * Returns [anchorWriteTimeNs, sampleRate, channelCount, generationId], or null if no audio is active.
     */
    fun getStreamAnchor(): LongArray?
    fun getOldestFrameIndex(): Long
    fun getCurrentWriteFrameIndex(): Long
    fun getOutputLatencyNs(): Long
    fun readFramesAt(startFrame: Long, dest: ShortArray, frameCount: Int): Int
}

/**
 * Callbacks emitted on the main thread during a phone-mode auto-sync session (exact Lu0/h0 flow).
 */
interface SyncListener {
    fun onSyncStarted()
    fun onProgress(
        elapsedSec: Int,
        micLevel: Float,
        hasCandidate: Boolean,
        candidateLagSec: Double?,
        bestZ: Double,
        overlapSec: Double,
        statusText: String
    )

    fun onCandidateDetected(candidateLagSec: Double, peakZ: Double, marginZ: Double)

    /**
     * [effectiveLagMs] = outputLatencyMs + rawLagMs (exact Lu0/h0 + LG/l sign convention):
     * - effectiveLagMs <= 20.0 -> TV is behind radio; radio should be delayed by max(0, round(-effectiveLagMs)) ms.
     * - effectiveLagMs > 20.0  -> Radio is behind TV by effectiveLagMs ms.
     */
    fun onSyncConfirmed(effectiveLagMs: Double, peakZ: Double, overlapSec: Double)
    fun onSyncCancelled()
    fun onSyncError(message: String)
}

/**
 * Exact 1:1 translation of Lu0/F0.
 */
data class CorrelationResult(
    val valid: Boolean = false,
    val lagMs: Double = 0.0,
    val peakZ: Double = 0.0,
    val secondZ: Double = 0.0,
    val overlapSec: Double = 0.0
)

/**
 * Exact 1:1 copy of Lu0/H0:
 * - Box-average downsampling to 8,000 Hz mono (0x1f40)
 * - 512-point Hann-windowed radix-2 FFT, hop = 160 samples (20 ms / 50 Hz)
 * - 40 contiguous bands, 3 bins/band starting at bin 6 (~94 Hz to ~1969 Hz)
 * - Log power + energy silence gate (> 1.0E-7)
 * - 4-frame temporal contrast + across-band mean removal + EMA RMS normalization (alpha = 0.005) + [-3, +3] clamp
 * - Center timestamp offset = 32,000,000 ns (0x1e84800)
 */
internal class SpectralFingerprinter(
    private val onFrame: (centerTimeNs: Long, features: FloatArray) -> Unit
) {
    private val sampleRing = FloatArray(FFT_SIZE)
    private val fftRe = FloatArray(FFT_SIZE)
    private val fftIm = FloatArray(FFT_SIZE)
    private val history = Array(HISTORY_FRAMES) { FloatArray(NUM_BANDS) }
    private val logBands = FloatArray(NUM_BANDS)
    private val features = FloatArray(NUM_BANDS)

    private var ringFilled = 0
    private var ringWriteIdx = 0
    private var hopCounter = 0
    private var historyCount = 0
    private var historyWriteIdx = 0
    private var lastSampleRate = 0

    private var phaseAcc = 0L
    private var downsampleCount = 0L
    private var lastEndTimeNs = Long.MIN_VALUE
    private var lastSampleTimeNs = 0L

    private var downsampleSum = 0.0
    private var emaVar = 0.0

    @Synchronized
    fun reset() {
        historyWriteIdx = 0
        historyCount = 0
        hopCounter = 0
        ringWriteIdx = 0
        ringFilled = 0
        downsampleCount = 0L
        phaseAcc = 0L
        downsampleSum = 0.0
        emaVar = 0.0
        lastEndTimeNs = Long.MIN_VALUE
    }

    @Synchronized
    fun feed(
        samples: ShortArray,
        frameCount: Int,
        channels: Int,
        sampleRate: Int,
        startTimeNs: Long
    ) {
        if (frameCount <= 0 || sampleRate <= 0 || channels <= 0) return

        if (sampleRate != lastSampleRate ||
            lastEndTimeNs == Long.MIN_VALUE ||
            abs(startTimeNs - lastEndTimeNs) > DISCONTINUITY_NS
        ) {
            reset()
            lastSampleRate = sampleRate
        }

        val srLong = sampleRate.toLong()
        lastEndTimeNs = startTimeNs + (frameCount.toLong() * NANOS_PER_SEC) / srLong

        for (frame in 0 until frameCount) {
            val base = frame * channels
            val monoSample = if (channels == 1) {
                samples[base].toDouble()
            } else {
                (samples[base].toInt() + samples[base + 1].toInt()).toDouble() * 0.5
            }

            downsampleSum += monoSample
            downsampleCount++
            phaseAcc += TARGET_SAMPLE_RATE

            if (phaseAcc >= srLong) {
                phaseAcc -= srLong
                val normalized = ((downsampleSum / downsampleCount.toDouble()) / 32768.0).toFloat()
                downsampleSum = 0.0
                downsampleCount = 0L

                sampleRing[ringWriteIdx] = normalized
                ringWriteIdx = (ringWriteIdx + 1) % FFT_SIZE
                if (ringFilled < FFT_SIZE) {
                    ringFilled++
                }

                lastSampleTimeNs = startTimeNs + ((frame + 1).toLong() * NANOS_PER_SEC) / srLong

                if (ringFilled == FFT_SIZE) {
                    hopCounter++
                    if (hopCounter >= HOP_SAMPLES) {
                        hopCounter = 0
                        processFftFrame()
                    }
                }
            }
        }
    }

    private fun processFftFrame() {
        var energySum = 0.0
        for (i in 0 until FFT_SIZE) {
            val idx = (ringWriteIdx + i) % FFT_SIZE
            val x = sampleRing[idx]
            energySum += (x * x).toDouble()
            val rev = BIT_REV[i]
            fftRe[rev] = x * HANN_WINDOW[i]
            fftIm[rev] = 0f
        }

        var size = 2
        while (size <= FFT_SIZE) {
            val halfSize = size ushr 1
            val tableStep = FFT_SIZE / size
            var start = 0
            while (start < FFT_SIZE) {
                var k = 0
                while (k < halfSize) {
                    val twIdx = k * tableStep
                    val wr = COS_TABLE[twIdx]
                    val wi = SIN_TABLE[twIdx]
                    val i1 = start + k
                    val i2 = i1 + halfSize
                    val r2 = fftRe[i2]
                    val im2 = fftIm[i2]
                    val tr = r2 * wr - im2 * wi
                    val ti = r2 * wi + im2 * wr
                    val r1 = fftRe[i1]
                    val im1 = fftIm[i1]
                    fftRe[i2] = r1 - tr
                    fftIm[i2] = im1 - ti
                    fftRe[i1] = r1 + tr
                    fftIm[i1] = im1 + ti
                    k++
                }
                start += size
            }
            size = size shl 1
        }

        for (b in 0 until NUM_BANDS) {
            var bandPower = 0.0
            val firstBin = b * BINS_PER_BAND + START_BIN
            for (j in 0 until BINS_PER_BAND) {
                val bin = firstBin + j
                val re = fftRe[bin]
                val im = fftIm[bin]
                bandPower += (re * re + im * im).toDouble()
            }
            logBands[b] = ln(bandPower + LOG_EPS).toFloat()
        }

        val isNonSilent = (energySum / FFT_SIZE.toDouble()) > SILENCE_ENERGY_THRESHOLD

        if (historyCount == HISTORY_FRAMES && isNonSilent) {
            var acrossBandSum = 0.0
            for (b in 0 until NUM_BANDS) {
                var histSum = 0f
                for (h in 0 until HISTORY_FRAMES) {
                    histSum += history[h][b]
                }
                val diff = logBands[b] - (histSum / 4.0f)
                features[b] = diff
                acrossBandSum += diff.toDouble()
            }

            val bandMean = acrossBandSum / NUM_BANDS.toDouble()
            var varSum = 0.0
            for (b in 0 until NUM_BANDS) {
                val centered = (features[b].toDouble() - bandMean).toFloat()
                features[b] = centered
                varSum += (centered * centered).toDouble()
            }

            val frameVar = varSum / NUM_BANDS.toDouble()
            emaVar = if (emaVar == 0.0) {
                frameVar
            } else {
                emaVar + EMA_ALPHA * (frameVar - emaVar)
            }

            val scale = (1.0 / sqrt(emaVar + RMS_EPS)).toFloat()
            for (b in 0 until NUM_BANDS) {
                val scaled = features[b] * scale
                features[b] = max(-CLAMP_LIMIT, min(CLAMP_LIMIT, scaled))
            }

            onFrame(lastSampleTimeNs - WINDOW_CENTER_OFFSET_NS, features)
        }

        System.arraycopy(logBands, 0, history[historyWriteIdx], 0, NUM_BANDS)
        historyWriteIdx = (historyWriteIdx + 1) % HISTORY_FRAMES
        if (isNonSilent) {
            if (historyCount < HISTORY_FRAMES) {
                historyCount++
            }
        } else {
            historyCount = 0
        }
    }

    companion object {
        const val FFT_SIZE = 512
        private const val HALF_FFT = 256
        const val TARGET_SAMPLE_RATE = 8_000L
        const val HOP_SAMPLES = 160
        const val NUM_BANDS = 40
        private const val START_BIN = 6
        private const val BINS_PER_BAND = 3
        private const val HISTORY_FRAMES = 4
        private const val NANOS_PER_SEC = 1_000_000_000L
        private const val WINDOW_CENTER_OFFSET_NS = 32_000_000L
        private const val DISCONTINUITY_NS = 30_000_000L
        private const val LOG_EPS = 1.0e-7
        private const val SILENCE_ENERGY_THRESHOLD = 1.0e-7
        private const val EMA_ALPHA = 0.005
        private const val RMS_EPS = 1.0e-6
        private const val CLAMP_LIMIT = 3.0f

        private val HANN_WINDOW = FloatArray(FFT_SIZE) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i.toDouble() / 511.0)).toFloat()
        }

        private val COS_TABLE = FloatArray(HALF_FFT) { i ->
            cos(2.0 * PI * i.toDouble() / 512.0).toFloat()
        }

        private val SIN_TABLE = FloatArray(HALF_FFT) { i ->
            (-sin(2.0 * PI * i.toDouble() / 512.0)).toFloat()
        }

        private val BIT_REV = IntArray(FFT_SIZE).apply {
            val bits = Integer.numberOfTrailingZeros(FFT_SIZE)
            for (i in 0 until FFT_SIZE) {
                this[i] = Integer.reverse(i) ushr (32 - bits)
            }
        }
    }
}

/**
 * Exact 1:1 copy of Lu0/G0:
 * - Symmetric lag window ±3100 hops (±62,000 ms)
 * - Ring size = 8192 slots (mask = 8191)
 * - Secondary peak exclusion = 6 hops (±120 ms)
 * - Lag convention: lag = radioSlot - micSlot
 */
internal class StreamingSlotCorrelator {

    private val micVecs = FloatArray(RING_SIZE * NUM_BANDS)
    private val radioVecs = FloatArray(RING_SIZE * NUM_BANDS)
    private val micSlots = LongArray(RING_SIZE)
    private val radioSlots = LongArray(RING_SIZE)

    private val sums = DoubleArray(LAG_COUNT)
    private val scores = DoubleArray(LAG_COUNT)
    private val scratch = DoubleArray(LAG_COUNT)
    private val counts = IntArray(LAG_COUNT)

    init {
        resetAll()
    }

    @Synchronized
    fun addMicFrame(timeNs: Long, vec: FloatArray) {
        addFrame(timeNs, vec, micVecs, micSlots, radioVecs, radioSlots, isMic = true)
    }

    @Synchronized
    fun addRadioFrame(timeNs: Long, vec: FloatArray) {
        addFrame(timeNs, vec, radioVecs, radioSlots, micVecs, micSlots, isMic = false)
    }

    private fun addFrame(
        timeNs: Long,
        vec: FloatArray,
        ownVecs: FloatArray,
        ownSlots: LongArray,
        otherVecs: FloatArray,
        otherSlots: LongArray,
        isMic: Boolean
    ) {
        val slot = floorDiv(timeNs + HALF_SLOT_NS, SLOT_NS)
        val mask = RING_MASK.toLong()
        val ringIdx = (slot and mask).toInt()
        if (ownSlots[ringIdx] == slot) return

        val ownOffset = ringIdx * NUM_BANDS
        System.arraycopy(vec, 0, ownVecs, ownOffset, NUM_BANDS)
        ownSlots[ringIdx] = slot

        val maxLag = MAX_LAG_HOPS.toLong()
        var otherSlot = slot - maxLag
        val endSlot = slot + maxLag
        while (otherSlot <= endSlot) {
            val otherIdx = (otherSlot and mask).toInt()
            if (otherSlots[otherIdx] == otherSlot) {
                val otherOffset = otherIdx * NUM_BANDS
                var dot = 0f
                var b = 0
                while (b < NUM_BANDS) {
                    dot += ownVecs[ownOffset + b] * otherVecs[otherOffset + b]
                    b++
                }
                val lagHops = if (isMic) {
                    (otherSlot - slot).toInt()
                } else {
                    (slot - otherSlot).toInt()
                }
                val lagIdx = lagHops + MAX_LAG_HOPS
                sums[lagIdx] += dot.toDouble()
                counts[lagIdx]++
            }
            otherSlot++
        }
    }

    @Synchronized
    fun evaluate(minLagMs: Int = MIN_LAG_MS, maxLagMs: Int = MAX_LAG_MS): CorrelationResult {
        var validCount = 0
        for (i in 0 until LAG_COUNT) {
            val c = counts[i]
            if (c >= MIN_COUNT_HOPS) {
                val s = sums[i] / sqrt(c.toDouble())
                scores[i] = s
                scratch[validCount++] = s
            } else {
                scores[i] = Double.NaN
            }
        }

        if (validCount < MIN_VALID_LAGS) {
            return CorrelationResult(valid = false)
        }

        Arrays.sort(scratch, 0, validCount)
        val med = scratch[validCount / 2]

        var devCount = 0
        for (i in 0 until LAG_COUNT) {
            val s = scores[i]
            if (!s.isNaN()) {
                scratch[devCount++] = abs(s - med)
            }
        }

        Arrays.sort(scratch, 0, validCount)
        val mad = MAD_SCALE * scratch[validCount / 2] + MAD_EPS

        val startIdx = max(0, (minLagMs / HOP_MS) + MAX_LAG_HOPS)
        val endIdx = min(LAG_COUNT - 1, (maxLagMs / HOP_MS) + MAX_LAG_HOPS)

        var bestIdx = -1
        for (i in startIdx..endIdx) {
            val s = scores[i]
            if (!s.isNaN() && (bestIdx < 0 || s > scores[bestIdx])) {
                bestIdx = i
            }
        }

        if (bestIdx < 0) {
            return CorrelationResult(valid = false)
        }

        var secondBestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until LAG_COUNT) {
            if (abs(i - bestIdx) > EXCLUSION_HOPS) {
                val s = scores[i]
                if (!s.isNaN() && s > secondBestScore) {
                    secondBestScore = s
                }
            }
        }

        var subHop = 0.0
        if (bestIdx > 0 && bestIdx < LAG_COUNT - 1) {
            val yL = scores[bestIdx - 1]
            val yC = scores[bestIdx]
            val yR = scores[bestIdx + 1]
            if (!yL.isNaN() && !yR.isNaN()) {
                val denom = yL - 2.0 * yC + yR
                if (denom < 0.0) {
                    subHop = max(-0.5, min(0.5, 0.5 * (yL - yR) / denom))
                }
            }
        }

        val lagMs = ((bestIdx - MAX_LAG_HOPS).toDouble() + subHop) * 20.0
        val peakZ = (scores[bestIdx] - med) / mad
        val secondZ = if (secondBestScore == Double.NEGATIVE_INFINITY) {
            0.0
        } else {
            (secondBestScore - med) / mad
        }
        val overlapSec = (counts[bestIdx] * HOP_MS).toDouble() / 1000.0

        return CorrelationResult(
            valid = true,
            lagMs = lagMs,
            peakZ = peakZ,
            secondZ = secondZ,
            overlapSec = overlapSec
        )
    }

    @Synchronized
    fun clearAccumulators() {
        Arrays.fill(sums, 0.0)
        Arrays.fill(counts, 0)
    }

    @Synchronized
    fun resetAll() {
        clearAccumulators()
        Arrays.fill(micSlots, Long.MIN_VALUE)
        Arrays.fill(radioSlots, Long.MIN_VALUE)
    }

    private fun floorDiv(x: Long, y: Long): Long {
        var q = x / y
        if ((x xor y) < 0L && q * y != x) {
            q--
        }
        return q
    }

    companion object {
        private const val NUM_BANDS = SpectralFingerprinter.NUM_BANDS
        const val HOP_MS = 20
        private const val SLOT_NS = 20_000_000L
        private const val HALF_SLOT_NS = 10_000_000L

        const val MAX_LAG_HOPS = 3100
        const val MIN_LAG_MS = -62_000
        const val MAX_LAG_MS = 62_000
        private const val LAG_COUNT = MAX_LAG_HOPS * 2 + 1

        private const val RING_SIZE = 8192
        private const val RING_MASK = RING_SIZE - 1

        private const val MIN_COUNT_HOPS = 100
        private const val MIN_VALID_LAGS = 200
        private const val EXCLUSION_HOPS = 6
        private const val MAD_SCALE = 1.4826
        private const val MAD_EPS = 1.0e-9
    }
}

/**
 * Exact 1:1 copy of Lu0/I0:
 * - peakZ >= 7.0
 * - peakZ - secondZ >= 2.5
 * - overlapSec >= 4.0
 * - 2 consecutive detections within 60.0 ms
 * - Calls correlator.clearAccumulators() after each qualifying detection
 */
internal class DoubleVerificationGate {
    sealed class Decision {
        data object None : Decision()
        data class CandidateFirst(val candidateLagMs: Double, val result: CorrelationResult) : Decision()
        data class Confirmed(val confirmedLagMs: Double, val result: CorrelationResult) : Decision()
    }

    var candidateLagMs: Double = Double.NaN
        private set
    var confirmCount: Int = 0
        private set

    fun reset() {
        candidateLagMs = Double.NaN
        confirmCount = 0
    }

    fun process(result: CorrelationResult, correlator: StreamingSlotCorrelator): Decision {
        if (!result.valid ||
            result.peakZ < MIN_PEAK_Z ||
            (result.peakZ - result.secondZ) < MIN_MARGIN_Z ||
            result.overlapSec < MIN_OVERLAP_SEC
        ) {
            return Decision.None
        }

        if (confirmCount > 0 && abs(result.lagMs - candidateLagMs) <= CONFIRM_TOLERANCE_MS) {
            confirmCount++
            candidateLagMs += (result.lagMs - candidateLagMs) / confirmCount.toDouble()
        } else {
            candidateLagMs = result.lagMs
            confirmCount = 1
        }

        correlator.clearAccumulators()

        return if (confirmCount >= 2) {
            Decision.Confirmed(candidateLagMs, result)
        } else {
            Decision.CandidateFirst(candidateLagMs, result)
        }
    }

    companion object {
        private const val MIN_PEAK_Z = 7.0
        private const val MIN_MARGIN_Z = 2.5
        private const val MIN_OVERLAP_SEC = 4.0
        private const val CONFIRM_TOLERANCE_MS = 60.0
    }
}

/**
 * Exact 1:1 copy of the phone-mode sync engine (Lu0/h0 + LG/l case 0xf + Lu0/d0):
 * - 48,000 Hz mono AudioRecord using UNPROCESSED (only when supported) else VOICE_RECOGNITION
 * - Single-thread mic read + radio circular-buffer pull loop (zero TV loopback code)
 * - Direct hardware AudioTimestamp (TIMEBASE_MONOTONIC) with min-anchor fallback
 * - Exact 300-sample-peak silence detector (3s window)
 * - Evaluates every 1.0s over [-62s, +62s] with exact 60-second timeout (0xdf8475800L ns)
 */
class SyncEngine(
    private val appContext: Context,
    private val radioSource: RadioPcmSource,
    private val listener: SyncListener
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val sessionLock = Any()

    private var workerThread: Thread? = null
    @Volatile
    private var activeAudioRecord: AudioRecord? = null

    val isRunning: Boolean
        get() = running.get()

    fun start(muteDrainWaitMs: Long = 0L) {
        synchronized(sessionLock) {
            if (!running.compareAndSet(false, true)) return

            mainHandler.post { listener.onSyncStarted() }

            val thread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                runSyncSession(muteDrainWaitMs.coerceIn(0L, 600L))
            }, "FootSync-MicSync")
            workerThread = thread
            thread.start()
        }
    }

    fun cancel() {
        val wasRunning = running.getAndSet(false)
        if (!wasRunning) return

        stopAudioRecordSafely()
        synchronized(sessionLock) {
            workerThread?.interrupt()
            workerThread = null
        }
        mainHandler.post { listener.onSyncCancelled() }
    }

    private fun stopAudioRecordSafely() {
        val record = activeAudioRecord
        activeAudioRecord = null
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: Throwable) {
            }
            try {
                record.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Exact copy of Lu0/h0.d():
     * Uses UNPROCESSED (9) ONLY when SUPPORT_AUDIO_SOURCE_UNPROCESSED == "true", else VOICE_RECOGNITION (6),
     * at 48,000 Hz mono PCM16, buffer = max(minBuf, 19200).
     */
    @SuppressLint("MissingPermission")
    private fun openAudioRecord(): AudioRecord {
        var audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val prop = am?.getProperty("android.media.property.SUPPORT_AUDIO_SOURCE_UNPROCESSED")
            if ("true" == prop) {
                audioSource = MediaRecorder.AudioSource.UNPROCESSED
            }
        }

        val minBuf = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            throw SyncException("المايك ما يدعم القياس على هذا الجهاز")
        }

        val bufSize = max(minBuf, MIN_RECORD_BUFFER_BYTES)
        val record = try {
            AudioRecord(
                audioSource,
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (_: SecurityException) {
            throw SyncException("اسمح للتطبيق باستخدام المايك ثم اضغط مرة ثانية")
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw SyncException("تعذر فتح المايك")
        }
        return record
    }

    private fun runSyncSession(muteDrainWaitMs: Long) {
        var record: AudioRecord? = null
        try {
            val anchorInfo = radioSource.getStreamAnchor()
                ?: throw SyncException("شغّل الصوتية أولاً ثم اضغط قياس")

            val anchorWriteTimeNs = anchorInfo[0]
            val radioSampleRate = anchorInfo[1].toInt()
            val radioChannels = anchorInfo[2].toInt()
            val streamGeneration = anchorInfo[3]

            if (radioSampleRate <= 0 || radioChannels <= 0) {
                throw SyncException("شغّل الصوتية أولاً ثم اضغط قياس")
            }

            if (muteDrainWaitMs > 0L) {
                Thread.sleep(muteDrainWaitMs)
            }
            if (!running.get() || Thread.currentThread().isInterrupted) {
                return
            }

            record = openAudioRecord()
            activeAudioRecord = record

            val correlator = StreamingSlotCorrelator()
            val gate = DoubleVerificationGate()
            val micFingerprinter = SpectralFingerprinter { centerTimeNs, vec ->
                correlator.addMicFrame(centerTimeNs, vec)
            }
            val radioFingerprinter = SpectralFingerprinter { centerTimeNs, vec ->
                correlator.addRadioFrame(centerTimeNs, vec)
            }

            // Start 64 seconds back in the radio circular buffer (exact LG/l line 133: 64.0 * sampleRate)
            val oldestFrame = radioSource.getOldestFrameIndex()
            val currentWriteFrame = radioSource.getCurrentWriteFrameIndex()
            val historyBackFrames = (radioSampleRate.toDouble() * HISTORY_LOOKBACK_SEC).toLong()
            var radioCursor = max(oldestFrame, currentWriteFrame - historyBackFrames)

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw SyncException("المايك مشغول بتطبيق ثاني")
            }

            val micBuf = ShortArray(MIC_READ_SAMPLES)
            val radioChunkFrames = max(1, radioSampleRate / 10)
            var radioReadBuf = ShortArray(radioChunkFrames * radioChannels)
            val audioTimestamp = AudioTimestamp()

            val sessionStartNs = System.nanoTime()
            var lastEvalNs = sessionStartNs
            var lastLoudTimeNs = 0L
            var totalMicFrames = 0L
            var fallbackMinAnchorNs = Long.MAX_VALUE
            var smoothedMicLevel = 0f

            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = record.read(micBuf, 0, MIC_READ_SAMPLES)
                if (read <= 0) {
                    if (!running.get()) return
                    throw SyncException("المايك توقف ($read)")
                }

                val nowNs = System.nanoTime()

                // Verify the radio stream did not stop or switch mid-sync (exact Lu0/h0.c lines 60-72)
                val liveAnchor = radioSource.getStreamAnchor()
                if (liveAnchor == null || liveAnchor[3] != streamGeneration) {
                    throw SyncException("توقفت الصوتية أو تغيّرت أثناء القياس")
                }

                // Exact Lu0/h0.c mic timestamp calculation
                val micChunkStartNs: Long
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    record.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS &&
                    audioTimestamp.nanoTime > 0L
                ) {
                    micChunkStartNs = audioTimestamp.nanoTime +
                        ((totalMicFrames - audioTimestamp.framePosition) * NANOS_PER_SEC) / MIC_SAMPLE_RATE.toLong()
                } else {
                    val candAnchor = nowNs -
                        (((totalMicFrames + read.toLong()) * NANOS_PER_SEC) / MIC_SAMPLE_RATE.toLong())
                    if (fallbackMinAnchorNs == Long.MAX_VALUE || candAnchor < fallbackMinAnchorNs) {
                        fallbackMinAnchorNs = candAnchor
                    }
                    micChunkStartNs = fallbackMinAnchorNs +
                        (totalMicFrames * NANOS_PER_SEC) / MIC_SAMPLE_RATE.toLong()
                }

                totalMicFrames += read.toLong()

                // Exact Lu0/h0.c peak detector (> 300 -> loud)
                var peakAbs = 0
                for (i in 0 until read) {
                    val a = abs(micBuf[i].toInt())
                    if (a > peakAbs) peakAbs = a
                }
                if (peakAbs > LOUD_PEAK_THRESHOLD) {
                    lastLoudTimeNs = nowNs
                }
                val normPeak = (peakAbs.toFloat() / 32768.0f).coerceIn(0f, 1f)
                smoothedMicLevel = smoothedMicLevel * 0.65f + min(1f, normPeak * 2.5f) * 0.35f

                // Feed 48 kHz mono mic chunk into H0 (downsamples internally to 8 kHz)
                micFingerprinter.feed(micBuf, read, 1, MIC_SAMPLE_RATE, micChunkStartNs)

                // Pull available 100 ms chunks from radio ring buffer (exact Lu0/h0.c lines 220-318)
                val writeHead = radioSource.getCurrentWriteFrameIndex()
                val oldestNow = radioSource.getOldestFrameIndex()
                if (radioCursor < oldestNow) {
                    radioCursor = oldestNow
                }
                val requiredShorts = radioChunkFrames * radioChannels
                if (radioReadBuf.size != requiredShorts) {
                    radioReadBuf = ShortArray(requiredShorts)
                }

                var chunksRead = 0
                while (radioCursor + radioChunkFrames.toLong() <= writeHead &&
                    chunksRead < MAX_RADIO_CHUNKS_PER_LOOP &&
                    running.get()
                ) {
                    val copied = radioSource.readFramesAt(radioCursor, radioReadBuf, radioChunkFrames)
                    if (copied <= 0) break
                    val radioChunkStartNs = anchorWriteTimeNs +
                        (radioCursor * NANOS_PER_SEC) / radioSampleRate.toLong()
                    radioFingerprinter.feed(
                        radioReadBuf,
                        copied,
                        radioChannels,
                        radioSampleRate,
                        radioChunkStartNs
                    )
                    radioCursor += copied.toLong()
                    chunksRead++
                }

                // Evaluate once per second (exact Lu0/h0.c line 321)
                if (nowNs - lastEvalNs >= EVAL_INTERVAL_NS) {
                    val evalResult = correlator.evaluate(
                        minLagMs = StreamingSlotCorrelator.MIN_LAG_MS,
                        maxLagMs = StreamingSlotCorrelator.MAX_LAG_MS
                    )

                    when (val decision = gate.process(evalResult, correlator)) {
                        is DoubleVerificationGate.Decision.Confirmed -> {
                            val outputLatencyMs = max(0L, radioSource.getOutputLatencyNs() / 1_000_000L)
                            val effectiveLagMs = outputLatencyMs.toDouble() + decision.confirmedLagMs
                            running.set(false)
                            mainHandler.post {
                                listener.onSyncConfirmed(
                                    effectiveLagMs = effectiveLagMs,
                                    peakZ = decision.result.peakZ,
                                    overlapSec = decision.result.overlapSec
                                )
                            }
                            return
                        }

                        is DoubleVerificationGate.Decision.CandidateFirst -> {
                            val candSec = abs(decision.candidateLagMs) / 1000.0
                            val marginZ = decision.result.peakZ - decision.result.secondZ
                            mainHandler.post {
                                listener.onCandidateDetected(
                                    candidateLagSec = candSec,
                                    peakZ = decision.result.peakZ,
                                    marginZ = marginZ
                                )
                            }
                        }

                        is DoubleVerificationGate.Decision.None -> Unit
                    }

                    val elapsedNs = nowNs - sessionStartNs
                    val elapsedSec = (elapsedNs / NANOS_PER_SEC).toInt().coerceAtLeast(0)
                    val hasCand = gate.confirmCount > 0 && !gate.candidateLagMs.isNaN()
                    val candSec = if (hasCand) abs(gate.candidateLagMs) / 1000.0 else null

                    val statusText = when {
                        nowNs - lastLoudTimeNs > SILENCE_WARN_NS ->
                            "المايك ما يسمع التلفزيون • علّي صوت التلفزيون أو قرّب الموبايل"

                        hasCand && candSec != null ->
                            String.format(Locale.US, "مرشح %.1f ث • يتأكد…", candSec)

                        else ->
                            "يسمع التلفزيون… $elapsedSec ث"
                    }

                    mainHandler.post {
                        listener.onProgress(
                            elapsedSec = elapsedSec,
                            micLevel = smoothedMicLevel,
                            hasCandidate = hasCand,
                            candidateLagSec = candSec,
                            bestZ = if (evalResult.valid) evalResult.peakZ else 0.0,
                            overlapSec = if (evalResult.valid) evalResult.overlapSec else 0.0,
                            statusText = statusText
                        )
                    }

                    // Exact Lu0/h0.c lines 453-473: 60-second timeout (0xdf8475800L ns)
                    if (elapsedNs > SYNC_TIMEOUT_NS) {
                        throw SyncException("ما لكيت تطابق • تأكد أنها نفس المباراة وصوت الملعب مسموع من التلفزيون")
                    }

                    lastEvalNs = nowNs
                }
            }
        } catch (_: InterruptedException) {
            // Cancelled cleanly
        } catch (e: SyncException) {
            if (running.getAndSet(false)) {
                val msg = e.message ?: "تعذر القياس"
                mainHandler.post { listener.onSyncError(msg) }
            }
        } catch (t: Throwable) {
            if (running.getAndSet(false)) {
                val msg = "تعذر القياس: ${t.javaClass.simpleName}"
                mainHandler.post { listener.onSyncError(msg) }
            }
        } finally {
            stopAudioRecordSafely()
            if (record != null) {
                try {
                    record.release()
                } catch (_: Throwable) {
                }
            }
            synchronized(sessionLock) {
                if (workerThread == Thread.currentThread()) {
                    workerThread = null
                }
            }
        }
    }

    private class SyncException(message: String) : Exception(message)

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 60
        private const val MIC_SAMPLE_RATE = 48_000
        private const val MIC_READ_SAMPLES = 960
        private const val MIN_RECORD_BUFFER_BYTES = 19_200
        private const val HISTORY_LOOKBACK_SEC = 64.0
        private const val LOUD_PEAK_THRESHOLD = 300
        private const val MAX_RADIO_CHUNKS_PER_LOOP = 300
        private const val EVAL_INTERVAL_NS = 1_000_000_000L
        private const val SILENCE_WARN_NS = 3_000_000_000L
        private const val SYNC_TIMEOUT_NS = 60_000_000_000L
        private const val NANOS_PER_SEC = 1_000_000_000L
    }
}
