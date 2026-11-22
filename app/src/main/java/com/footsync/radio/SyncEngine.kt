package com.footsync.radio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
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
 * Provides historical and live undelayed radio PCM (timestamped to zero-delay speaker output time)
 * to [SyncEngine] strictly on demand while an auto-sync session is active.
 */
interface RadioPcmSource {
    fun readHistoryChunks(
        maxDurationMs: Long,
        chunkMs: Int,
        consumer: (pcm: ShortArray, frameCount: Int, channels: Int, sampleRate: Int, startTimeNs: Long) -> Unit
    )

    fun setLivePcmTap(
        tap: ((pcm: ShortArray, frameCount: Int, channels: Int, sampleRate: Int, startTimeNs: Long) -> Unit)?
    )
}

/**
 * Callbacks emitted on the main thread during an auto-sync session.
 */
interface SyncListener {
    fun onSyncStarted(timeoutSec: Int)
    fun onProgress(
        elapsedSec: Int,
        timeoutSec: Int,
        micLevel: Float,
        hasCandidate: Boolean,
        candidateLagSec: Double?,
        bestZ: Double,
        overlapSec: Double,
        statusText: String
    )

    fun onCandidateDetected(candidateLagSec: Double, peakZ: Double, marginZ: Double)
    fun onSyncConfirmed(measuredLagSec: Double, peakZ: Double, overlapSec: Double)
    fun onSyncTimeout(unconfirmedCandidateLagSec: Double?)
    fun onSyncCancelled()
    fun onSyncError(message: String)
}

/**
 * Evaluation snapshot produced by [StreamingSlotCorrelator].
 */
data class CorrelationResult(
    val valid: Boolean = false,
    val lagMs: Double = 0.0,
    val peakZ: Double = 0.0,
    val secondZ: Double = 0.0,
    val overlapSec: Double = 0.0
)

/**
 * 40-band spectral-change fingerprinter translated from H0 and adapted to 16 kHz mono:
 * - Box-average downsampling to 16 kHz mono
 * - 512-point Hann-windowed radix-2 FFT, hop = 320 samples (20 ms / 50 Hz)
 * - 40 contiguous bands (3 bins/band starting at bin 4 -> ~125 Hz to 3875 Hz)
 * - Log power + energy silence gate (> 1e-7)
 * - 4-frame temporal contrast + across-band mean removal + EMA RMS normalization + [-3, +3] clamp
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
            (lastEndTimeNs != Long.MIN_VALUE && abs(startTimeNs - lastEndTimeNs) > DISCONTINUITY_NS)
        ) {
            reset()
            lastSampleRate = sampleRate
        }

        val srLong = sampleRate.toLong()
        lastEndTimeNs = startTimeNs + (frameCount.toLong() * NANOS_PER_SEC) / srLong

        for (frame in 0 until frameCount) {
            val base = frame * channels
            val monoSample = when (channels) {
                1 -> samples[base].toDouble()
                2 -> (samples[base].toInt() + samples[base + 1].toInt()) * 0.5
                else -> {
                    var sumCh = 0.0
                    for (c in 0 until channels) {
                        sumCh += samples[base + c].toDouble()
                    }
                    sumCh / channels.toDouble()
                }
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
                ringWriteIdx = (ringWriteIdx + 1) and FFT_MASK
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
            val x = sampleRing[(ringWriteIdx + i) and FFT_MASK]
            energySum += (x * x).toDouble()
            val rev = BIT_REV[i]
            fftRe[rev] = x * HANN_WINDOW[i]
            fftIm[rev] = 0f
        }

        // Iterative in-place radix-2 Cooley-Tukey FFT
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

        // Sum bin power into 40 contiguous bands (3 bins per band starting at bin 4)
        for (b in 0 until NUM_BANDS) {
            val firstBin = START_BIN + b * BINS_PER_BAND
            var bandPower = 0.0
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
                val prevMean = (
                    history[0][b] +
                        history[1][b] +
                        history[2][b] +
                        history[3][b]
                    ) * 0.25f
                val diff = logBands[b] - prevMean
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
                features[b] = scaled.coerceIn(-CLAMP_LIMIT, CLAMP_LIMIT)
            }

            val centerTimeNs = lastSampleTimeNs - WINDOW_CENTER_OFFSET_NS
            onFrame(centerTimeNs, features)
        }

        System.arraycopy(logBands, 0, history[historyWriteIdx], 0, NUM_BANDS)
        historyWriteIdx = (historyWriteIdx + 1) and 3
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
        private const val FFT_MASK = FFT_SIZE - 1
        private const val HALF_FFT = FFT_SIZE / 2
        const val TARGET_SAMPLE_RATE = 16_000L
        const val HOP_SAMPLES = 320 // 20 ms at 16 kHz
        const val NUM_BANDS = 40
        private const val START_BIN = 4 // 4 * 31.25 Hz = 125 Hz -> 124 * 31.25 Hz = 3875 Hz
        private const val BINS_PER_BAND = 3
        private const val HISTORY_FRAMES = 4
        private const val NANOS_PER_SEC = 1_000_000_000L
        private const val WINDOW_CENTER_OFFSET_NS = 16_000_000L // 256 / 16000 s = 16 ms
        private const val DISCONTINUITY_NS = 30_000_000L
        private const val LOG_EPS = 1e-7
        private const val SILENCE_ENERGY_THRESHOLD = 1e-7
        private const val EMA_ALPHA = 0.005
        private const val RMS_EPS = 1e-6
        private const val CLAMP_LIMIT = 3.0f

        private val HANN_WINDOW = FloatArray(FFT_SIZE) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1).toDouble())).toFloat()
        }

        private val COS_TABLE = FloatArray(HALF_FFT) { i ->
            cos(2.0 * PI * i / FFT_SIZE.toDouble()).toFloat()
        }

        private val SIN_TABLE = FloatArray(HALF_FFT) { i ->
            (-sin(2.0 * PI * i / FFT_SIZE.toDouble())).toFloat()
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
 * Streaming slot correlator translated from G0 and configured for lags from -30s to +120s
 * with a ±1.0s (50-hop) secondary peak exclusion zone.
 *
 * Lag convention: lag = slot_mic - slot_radio
 * Positive lag means TV (mic) is behind radio, so radio must be delayed by +lag.
 */
internal class StreamingSlotCorrelator {

    private val radioVecs = FloatArray(RING_SIZE * NUM_BANDS)
    private val micVecs = FloatArray(RING_SIZE * NUM_BANDS)
    private val radioSlots = LongArray(RING_SIZE)
    private val micSlots = LongArray(RING_SIZE)

    private val sums = DoubleArray(LAG_COUNT)
    private val scores = DoubleArray(LAG_COUNT)
    private val scratch = DoubleArray(LAG_COUNT)
    private val counts = IntArray(LAG_COUNT)

    private var hasRadio = false
    private var minRadioSlot = Long.MAX_VALUE
    private var maxRadioSlot = Long.MIN_VALUE

    private var hasMic = false
    private var minMicSlot = Long.MAX_VALUE
    private var maxMicSlot = Long.MIN_VALUE

    init {
        resetAll()
    }

    @Synchronized
    fun addRadioFrame(timeNs: Long, vec: FloatArray) {
        val slot = quantizeSlot(timeNs)
        val ringIdx = (slot and RING_MASK.toLong()).toInt()
        if (radioSlots[ringIdx] == slot) return

        val ownOffset = ringIdx * NUM_BANDS
        System.arraycopy(vec, 0, radioVecs, ownOffset, NUM_BANDS)
        radioSlots[ringIdx] = slot

        if (!hasRadio) {
            hasRadio = true
            minRadioSlot = slot
            maxRadioSlot = slot
        } else {
            if (slot < minRadioSlot) minRadioSlot = slot
            if (slot > maxRadioSlot) maxRadioSlot = slot
            val oldestKeepable = maxRadioSlot - (RING_SIZE - 1)
            if (minRadioSlot < oldestKeepable) minRadioSlot = oldestKeepable
        }

        if (!hasMic) return

        // lag = micSlot - radioSlot in [MIN_LAG_HOPS, MAX_LAG_HOPS]
        // => micSlot in [slot + MIN_LAG_HOPS, slot + MAX_LAG_HOPS]
        val startMic = max(slot + MIN_LAG_HOPS, minMicSlot)
        val endMic = min(slot + MAX_LAG_HOPS, maxMicSlot)
        if (startMic > endMic) return

        var otherSlot = startMic
        while (otherSlot <= endMic) {
            val otherIdx = (otherSlot and RING_MASK.toLong()).toInt()
            if (micSlots[otherIdx] == otherSlot) {
                val dot = dot40(radioVecs, ownOffset, micVecs, otherIdx * NUM_BANDS)
                val lagIdx = (otherSlot - slot).toInt() - MIN_LAG_HOPS
                sums[lagIdx] += dot.toDouble()
                counts[lagIdx]++
            }
            otherSlot++
        }
    }

    @Synchronized
    fun addMicFrame(timeNs: Long, vec: FloatArray) {
        val slot = quantizeSlot(timeNs)
        val ringIdx = (slot and RING_MASK.toLong()).toInt()
        if (micSlots[ringIdx] == slot) return

        val ownOffset = ringIdx * NUM_BANDS
        System.arraycopy(vec, 0, micVecs, ownOffset, NUM_BANDS)
        micSlots[ringIdx] = slot

        if (!hasMic) {
            hasMic = true
            minMicSlot = slot
            maxMicSlot = slot
        } else {
            if (slot < minMicSlot) minMicSlot = slot
            if (slot > maxMicSlot) maxMicSlot = slot
            val oldestKeepable = maxMicSlot - (RING_SIZE - 1)
            if (minMicSlot < oldestKeepable) minMicSlot = oldestKeepable
        }

        if (!hasRadio) return

        // lag = micSlot - radioSlot in [MIN_LAG_HOPS, MAX_LAG_HOPS]
        // => radioSlot in [slot - MAX_LAG_HOPS, slot - MIN_LAG_HOPS]
        val startRadio = max(slot - MAX_LAG_HOPS, minRadioSlot)
        val endRadio = min(slot - MIN_LAG_HOPS, maxRadioSlot)
        if (startRadio > endRadio) return

        var otherSlot = startRadio
        while (otherSlot <= endRadio) {
            val otherIdx = (otherSlot and RING_MASK.toLong()).toInt()
            if (radioSlots[otherIdx] == otherSlot) {
                val dot = dot40(micVecs, ownOffset, radioVecs, otherIdx * NUM_BANDS)
                val lagIdx = (slot - otherSlot).toInt() - MIN_LAG_HOPS
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

        val startIdx = max(0, (minLagMs / HOP_MS) - MIN_LAG_HOPS)
        val endIdx = min(LAG_COUNT - 1, (maxLagMs / HOP_MS) - MIN_LAG_HOPS)

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

        // Parabolic sub-hop interpolation clamped to [-0.5, +0.5] hop
        var subHop = 0.0
        if (bestIdx > 0 && bestIdx < LAG_COUNT - 1) {
            val yL = scores[bestIdx - 1]
            val yC = scores[bestIdx]
            val yR = scores[bestIdx + 1]
            if (!yL.isNaN() && !yR.isNaN()) {
                val denom = yL - 2.0 * yC + yR
                if (denom < 0.0) {
                    subHop = (0.5 * (yL - yR) / denom).coerceIn(-0.5, 0.5)
                }
            }
        }

        val lagHops = (bestIdx + MIN_LAG_HOPS).toDouble() + subHop
        val lagMs = lagHops * HOP_MS.toDouble()
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

    /**
     * Zeroes sums and counts while preserving the spectral history rings so the second
     * verification pass must accumulate from completely fresh audio.
     */
    @Synchronized
    fun clearAccumulators() {
        Arrays.fill(sums, 0.0)
        Arrays.fill(counts, 0)
    }

    @Synchronized
    fun resetAll() {
        clearAccumulators()
        Arrays.fill(radioSlots, Long.MIN_VALUE)
        Arrays.fill(micSlots, Long.MIN_VALUE)
        hasRadio = false
        minRadioSlot = Long.MAX_VALUE
        maxRadioSlot = Long.MIN_VALUE
        hasMic = false
        minMicSlot = Long.MAX_VALUE
        maxMicSlot = Long.MIN_VALUE
    }

    private fun quantizeSlot(timeNs: Long): Long {
        return Math.floorDiv(timeNs + HALF_SLOT_NS, SLOT_NS)
    }

    private fun dot40(a: FloatArray, aOff: Int, b: FloatArray, bOff: Int): Float {
        var s0 = 0f
        var s1 = 0f
        var s2 = 0f
        var s3 = 0f
        var i = 0
        while (i < NUM_BANDS) {
            s0 += a[aOff + i] * b[bOff + i]
            s1 += a[aOff + i + 1] * b[bOff + i + 1]
            s2 += a[aOff + i + 2] * b[bOff + i + 2]
            s3 += a[aOff + i + 3] * b[bOff + i + 3]
            i += 4
        }
        return (s0 + s1) + (s2 + s3)
    }

    companion object {
        private const val NUM_BANDS = SpectralFingerprinter.NUM_BANDS
        const val HOP_MS = 20
        private const val SLOT_NS = 20_000_000L
        private const val HALF_SLOT_NS = 10_000_000L

        const val MIN_LAG_MS = -30_000
        const val MAX_LAG_MS = 120_000
        private const val MIN_LAG_HOPS = MIN_LAG_MS / HOP_MS // -1500
        private const val MAX_LAG_HOPS = MAX_LAG_MS / HOP_MS // +6000
        private const val LAG_COUNT = MAX_LAG_HOPS - MIN_LAG_HOPS + 1 // 7501

        // Power-of-two circular buffer covering >327 seconds of 20 ms slots
        private const val RING_SIZE = 16384
        private const val RING_MASK = RING_SIZE - 1

        private const val MIN_COUNT_HOPS = 100 // 2.0 s minimum overlap per lag
        private const val MIN_VALID_LAGS = 200
        private const val EXCLUSION_HOPS = 50 // ±1.0 s exclusion zone
        private const val MAD_SCALE = 1.4826
        private const val MAD_EPS = 1e-9
    }
}

/**
 * Double-verification anti-guessing gate translated from I0 and tuned for smartphone microphones:
 * - Requires overlap >= 4.0 s, peakZ >= 5.5, and peakZ - secondZ >= 1.8
 * - Clears correlator accumulators immediately on Candidate #1
 * - Confirms when Candidate #2 falls within ±150 ms of Candidate #1 (averaging both lags)
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

        // Immediately zero sums[] and counts[] while preserving spectral history rings
        correlator.clearAccumulators()

        return if (confirmCount >= 2) {
            Decision.Confirmed(candidateLagMs, result)
        } else {
            Decision.CandidateFirst(candidateLagMs, result)
        }
    }

    companion object {
        private const val MIN_OVERLAP_SEC = 4.0
        private const val MIN_PEAK_Z = 5.5
        private const val MIN_MARGIN_Z = 1.8
        private const val CONFIRM_TOLERANCE_MS = 150.0
    }
}

/**
 * On-demand 40-band spectral auto-sync engine for smartphone microphones.
 * Zero FFT or mic work runs outside an active sync session.
 */
class SyncEngine(
    private val radioSource: RadioPcmSource,
    private val listener: SyncListener
) {
    private data class PcmChunk(
        val pcm: ShortArray,
        val frameCount: Int,
        val channels: Int,
        val sampleRate: Int,
        val startTimeNs: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val sessionLock = Any()

    private var micThread: Thread? = null
    private var dspThread: Thread? = null
    @Volatile
    private var activeAudioRecord: AudioRecord? = null
    @Volatile
    private var latestMicRms: Float = 0f

    val isRunning: Boolean
        get() = running.get()

    fun start(timeoutSec: Int = DEFAULT_TIMEOUT_SEC) {
        val clampedTimeout = timeoutSec.coerceIn(15, 120)
        synchronized(sessionLock) {
            if (!running.compareAndSet(false, true)) return

            latestMicRms = 0f
            val radioQueue = ArrayBlockingQueue<PcmChunk>(RADIO_QUEUE_CAPACITY)
            val micQueue = ArrayBlockingQueue<PcmChunk>(MIC_QUEUE_CAPACITY)

            // Register live radio tap BEFORE reading history so no samples are missed at the boundary
            radioSource.setLivePcmTap { pcm, frameCount, channels, sampleRate, startTimeNs ->
                if (!running.get() || frameCount <= 0) return@setLivePcmTap
                val totalShorts = frameCount * channels
                val copy = ShortArray(totalShorts)
                System.arraycopy(pcm, 0, copy, 0, totalShorts)
                radioQueue.offer(PcmChunk(copy, frameCount, channels, sampleRate, startTimeNs))
            }

            mainHandler.post { listener.onSyncStarted(clampedTimeout) }

            val micWorker = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                runMicCaptureLoop(micQueue)
            }, "FootSync-MicCapture")

            val dspWorker = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
                runDspSyncLoop(clampedTimeout, radioQueue, micQueue)
            }, "FootSync-DspWorker")

            micThread = micWorker
            dspThread = dspWorker
            micWorker.start()
            dspWorker.start()
        }
    }

    fun cancel() {
        val wasRunning = running.getAndSet(false)
        if (!wasRunning) return

        radioSource.setLivePcmTap(null)
        stopAudioRecordSafely()

        synchronized(sessionLock) {
            micThread?.interrupt()
            dspThread?.interrupt()
            micThread = null
            dspThread = null
        }

        mainHandler.post { listener.onSyncCancelled() }
    }

    private fun stopInternal() {
        running.set(false)
        radioSource.setLivePcmTap(null)
        stopAudioRecordSafely()
        synchronized(sessionLock) {
            micThread?.interrupt()
            micThread = null
            dspThread = null
        }
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

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBufBytes = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes <= 0) return null
        val bufferBytes = max(minBufBytes * 4, MIC_READ_FRAMES * 2 * 8)

        val preferredSources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC
        )

        for (source in preferredSources) {
            var candidate: AudioRecord? = null
            try {
                candidate = AudioRecord(
                    source,
                    MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    candidate.startRecording()
                    if (candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        return candidate
                    }
                }
            } catch (_: Throwable) {
            }
            try {
                candidate?.release()
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun runMicCaptureLoop(micQueue: ArrayBlockingQueue<PcmChunk>) {
        val record = createAudioRecord()
        if (record == null) {
            if (running.get()) {
                stopInternal()
                mainHandler.post {
                    listener.onSyncError("تعذّر فتح ميكروفون الهاتف. تأكد من منح إذن الميكروفون.")
                }
            }
            return
        }

        activeAudioRecord = record
        val readBuffer = ShortArray(MIC_READ_FRAMES)
        val audioTimestamp = AudioTimestamp()
        var totalFramesRead = 0L
        var anchorTimeNs = Long.MIN_VALUE

        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = record.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) {
                    if (read < 0 && running.get()) {
                        Thread.sleep(10)
                    }
                    continue
                }

                val nowNs = System.nanoTime()
                val chunkDurationNs = (read.toLong() * NANOS_PER_SEC) / MIC_SAMPLE_RATE.toLong()
                val fallbackStartNs = nowNs - chunkDurationNs

                var measuredStartNs = fallbackStartNs
                val tsStatus = try {
                    record.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
                } catch (_: Throwable) {
                    AudioRecord.ERROR_INVALID_OPERATION
                }

                if (tsStatus == AudioRecord.SUCCESS &&
                    audioTimestamp.nanoTime > 0L &&
                    audioTimestamp.framePosition > 0L
                ) {
                    val deltaFrames = totalFramesRead - audioTimestamp.framePosition
                    val tsStartNs = audioTimestamp.nanoTime +
                        (deltaFrames * NANOS_PER_SEC) / MIC_SAMPLE_RATE.toLong()
                    if (abs(tsStartNs - fallbackStartNs) < 400_000_000L) {
                        measuredStartNs = tsStartNs
                    }
                }

                // Smooth monotonic clock anchor so consecutive mic frames never drift or jitter >30ms
                if (anchorTimeNs == Long.MIN_VALUE) {
                    anchorTimeNs = measuredStartNs -
                        (totalFramesRead * NANOS_PER_SEC) / MIC_SAMPLE_RATE.toLong()
                } else {
                    val predictedStartNs = anchorTimeNs +
                        (totalFramesRead * NANOS_PER_SEC) / MIC_SAMPLE_RATE.toLong()
                    val errorNs = measuredStartNs - predictedStartNs
                    anchorTimeNs += if (abs(errorNs) > 150_000_000L) {
                        errorNs
                    } else {
                        errorNs / 25L
                    }
                }

                val chunkStartNs = anchorTimeNs +
                    (totalFramesRead * NANOS_PER_SEC) / MIC_SAMPLE_RATE.toLong()
                totalFramesRead += read.toLong()

                // Compute normalized RMS level for live mic meter
                var sumSq = 0.0
                for (i in 0 until read) {
                    val v = readBuffer[i].toDouble() / 32768.0
                    sumSq += v * v
                }
                val rms = sqrt(sumSq / read.toDouble()).toFloat()
                latestMicRms = (latestMicRms * 0.65f + (rms * 4.5f).coerceIn(0f, 1f) * 0.35f)

                val copy = ShortArray(read)
                System.arraycopy(readBuffer, 0, copy, 0, read)
                if (!micQueue.offer(PcmChunk(copy, read, 1, MIC_SAMPLE_RATE, chunkStartNs))) {
                    micQueue.poll()
                    micQueue.offer(PcmChunk(copy, read, 1, MIC_SAMPLE_RATE, chunkStartNs))
                }
            }
        } catch (_: InterruptedException) {
            // Normal stop
        } catch (t: Throwable) {
            if (running.get()) {
                stopInternal()
                mainHandler.post {
                    listener.onSyncError("خطأ أثناء قراءة الميكروفون: ${t.message ?: "غير معروف"}")
                }
            }
        } finally {
            stopAudioRecordSafely()
        }
    }

    private fun runDspSyncLoop(
        timeoutSec: Int,
        radioQueue: ArrayBlockingQueue<PcmChunk>,
        micQueue: ArrayBlockingQueue<PcmChunk>
    ) {
        val correlator = StreamingSlotCorrelator()
        val gate = DoubleVerificationGate()

        val radioFingerprinter = SpectralFingerprinter { centerTimeNs, vec ->
            correlator.addRadioFrame(centerTimeNs, vec)
        }
        val micFingerprinter = SpectralFingerprinter { centerTimeNs, vec ->
            correlator.addMicFrame(centerTimeNs, vec)
        }

        val sessionStartNs = System.nanoTime()
        val timeoutNs = timeoutSec.toLong() * NANOS_PER_SEC

        // 1. Pre-populate radio spectral ring from delay buffer history (100 ms chunks)
        var lastHistoryEndNs = Long.MIN_VALUE
        try {
            radioSource.readHistoryChunks(
                maxDurationMs = HISTORY_PRELOAD_MS,
                chunkMs = HISTORY_CHUNK_MS
            ) { pcm, frameCount, channels, sampleRate, startTimeNs ->
                if (!running.get()) return@readHistoryChunks
                radioFingerprinter.feed(pcm, frameCount, channels, sampleRate, startTimeNs)
                val endNs = startTimeNs +
                    (frameCount.toLong() * NANOS_PER_SEC) / sampleRate.toLong()
                if (endNs > lastHistoryEndNs) {
                    lastHistoryEndNs = endNs
                }
            }
        } catch (_: Throwable) {
            // Continue with live stream even if history buffer was empty
        }

        var nextEvalNs = sessionStartNs + EVAL_INTERVAL_NS
        var lastResult = CorrelationResult()

        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                var didWork = false

                // Drain available live radio chunks (skipping overlap already loaded from history)
                while (running.get()) {
                    val rChunk = radioQueue.poll() ?: break
                    val endNs = rChunk.startTimeNs +
                        (rChunk.frameCount.toLong() * NANOS_PER_SEC) / rChunk.sampleRate.toLong()
                    if (lastHistoryEndNs != Long.MIN_VALUE && endNs <= lastHistoryEndNs) {
                        continue
                    }
                    lastHistoryEndNs = Long.MIN_VALUE
                    radioFingerprinter.feed(
                        rChunk.pcm,
                        rChunk.frameCount,
                        rChunk.channels,
                        rChunk.sampleRate,
                        rChunk.startTimeNs
                    )
                    didWork = true
                }

                // Drain available mic chunks
                while (running.get()) {
                    val mChunk = micQueue.poll() ?: break
                    micFingerprinter.feed(
                        mChunk.pcm,
                        mChunk.frameCount,
                        mChunk.channels,
                        mChunk.sampleRate,
                        mChunk.startTimeNs
                    )
                    didWork = true
                }

                val nowNs = System.nanoTime()
                val elapsedNs = nowNs - sessionStartNs

                if (nowNs >= nextEvalNs) {
                    nextEvalNs = nowNs + EVAL_INTERVAL_NS
                    val evalResult = correlator.evaluate(
                        minLagMs = StreamingSlotCorrelator.MIN_LAG_MS,
                        maxLagMs = StreamingSlotCorrelator.MAX_LAG_MS
                    )
                    lastResult = evalResult

                    when (val decision = gate.process(evalResult, correlator)) {
                        is DoubleVerificationGate.Decision.Confirmed -> {
                            val lagSec = decision.confirmedLagMs / 1000.0
                            stopInternal()
                            mainHandler.post {
                                listener.onSyncConfirmed(
                                    measuredLagSec = lagSec,
                                    peakZ = decision.result.peakZ,
                                    overlapSec = decision.result.overlapSec
                                )
                            }
                            return
                        }

                        is DoubleVerificationGate.Decision.CandidateFirst -> {
                            val candSec = decision.candidateLagMs / 1000.0
                            val marginZ = decision.result.peakZ - decision.result.secondZ
                            mainHandler.post {
                                listener.onCandidateDetected(
                                    candidateLagSec = candSec,
                                    peakZ = decision.result.peakZ,
                                    marginZ = marginZ
                                )
                            }
                        }

                        is DoubleVerificationGate.Decision.None -> {
                            // Continue listening
                        }
                    }

                    val elapsedSec = (elapsedNs / NANOS_PER_SEC).toInt().coerceAtMost(timeoutSec)
                    val hasCand = gate.confirmCount > 0 && !gate.candidateLagMs.isNaN()
                    val candSec = if (hasCand) gate.candidateLagMs / 1000.0 else null
                    val statusText = if (hasCand && candSec != null) {
                        String.format(
                            Locale.US,
                            "تم رصد مرشّح %.1f ث • يتأكد على صوت جديد… (%d/%d ث)",
                            candSec,
                            elapsedSec,
                            timeoutSec
                        )
                    } else if (latestMicRms < 0.03f && elapsedSec >= 3) {
                        "صوت التلفزيون منخفض جداً • قرّب الهاتف من التلفزيون ($elapsedSec/$timeoutSec ث)"
                    } else {
                        "جاري الاستماع لصوت التلفزيون والمطابقة… ($elapsedSec/$timeoutSec ث)"
                    }

                    mainHandler.post {
                        listener.onProgress(
                            elapsedSec = elapsedSec,
                            timeoutSec = timeoutSec,
                            micLevel = latestMicRms,
                            hasCandidate = hasCand,
                            candidateLagSec = candSec,
                            bestZ = if (lastResult.valid) lastResult.peakZ else 0.0,
                            overlapSec = if (lastResult.valid) lastResult.overlapSec else 0.0,
                            statusText = statusText
                        )
                    }
                }

                if (elapsedNs >= timeoutNs) {
                    val unconfirmedSec = if (gate.confirmCount == 1 && !gate.candidateLagMs.isNaN()) {
                        gate.candidateLagMs / 1000.0
                    } else {
                        null
                    }
                    stopInternal()
                    mainHandler.post {
                        listener.onSyncTimeout(unconfirmedSec)
                    }
                    return
                }

                if (!didWork) {
                    // Brief wait for next mic chunk without busy-spinning
                    val waited = micQueue.poll(15L, TimeUnit.MILLISECONDS)
                    if (waited != null && running.get()) {
                        micFingerprinter.feed(
                            waited.pcm,
                            waited.frameCount,
                            waited.channels,
                            waited.sampleRate,
                            waited.startTimeNs
                        )
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Cancelled cleanly
        } catch (t: Throwable) {
            if (running.get()) {
                stopInternal()
                mainHandler.post {
                    listener.onSyncError("توقفت المزامنة بسبب خطأ داخلي: ${t.message ?: "غير معروف"}")
                }
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 60
        private const val MIC_SAMPLE_RATE = 16_000
        private const val MIC_READ_FRAMES = 640 // 40 ms chunks at 16 kHz
        private const val HISTORY_PRELOAD_MS = 125_000L
        private const val HISTORY_CHUNK_MS = 100
        private const val EVAL_INTERVAL_NS = 1_000_000_000L
        private const val NANOS_PER_SEC = 1_000_000_000L
        private const val RADIO_QUEUE_CAPACITY = 256
        private const val MIC_QUEUE_CAPACITY = 128
    }
}
