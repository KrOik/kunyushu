package tv.kyshu.utils

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object GaitAnalyzer {
    private const val MIN_FREQ_HZ = 0.6
    private const val MAX_FREQ_HZ = 3.5
    private const val REPLAY_HUMANIZE_INTENSITY = 0.04f
    private const val EPS = 1e-9

    data class SensorSample(
        val timestamp: Long,
        val x: Float,
        val y: Float,
        val z: Float,
    )

    data class GaitAnalysisParams(
        val frequency: Float,
        val duration: Float,
        val gravity: FloatArray,
        val noiseSigma: Float,
        val noiseSigmaAccel: Float,
        val noiseSigmaGyro: Float,
        val noiseSigmaGravity: Float,
        val humanize: Boolean = true,
        val humanizeIntensity: Float = REPLAY_HUMANIZE_INTENSITY,
        val dcAccel: FloatArray = FloatArray(3),
        val dcGyro: FloatArray = FloatArray(3),
        val fourierAccel: List<List<Float>> = listOf(emptyList(), emptyList(), emptyList()),
        val fourierGyro: List<List<Float>> = listOf(emptyList(), emptyList(), emptyList()),
        val fourierGravity: List<List<Float>> = listOf(emptyList(), emptyList(), emptyList()),
        val pulseAccel: List<List<Float>> = listOf(emptyList(), emptyList(), emptyList()),
        val pulseGyro: List<List<Float>> = listOf(emptyList(), emptyList(), emptyList()),
        val pulseGravity: List<List<Float>> = listOf(emptyList(), emptyList(), emptyList()),
        val freqMulAccel: FloatArray = floatArrayOf(1f, 1f, 1f),
        val freqMulGyro: FloatArray = floatArrayOf(1f, 1f, 1f),
        val freqMulGravity: FloatArray = floatArrayOf(1f, 1f, 1f),
        val scoreAccel: FloatArray = FloatArray(3),
        val scoreGyro: FloatArray = FloatArray(3),
        val scoreGravity: FloatArray = FloatArray(3),
    ) {
        companion object {
            fun emptyAxes(): List<List<Float>> = listOf(emptyList(), emptyList(), emptyList())
        }
    }

    data class VerificationSummary(
        val linear: Float,
        val gyro: Float,
        val gravity: Float,
    ) {
        override fun toString(): String {
            return "拟合相似度:\nLinear: %.1f%%\nGyro: %.1f%%\nGravity: %.1f%%"
                .format(linear, gyro, gravity)
        }
    }

    private data class Series(val t: DoubleArray, val axes: Array<DoubleArray>, val fs: Double)
    private data class FourierFit(val coeffs: List<Float>, val recon: DoubleArray)
    private data class SensorFit(
        val fourier: List<List<Float>>,
        val pulses: List<List<Float>>,
        val freqMul: FloatArray,
        val dc: DoubleArray,
        val scores: FloatArray,
        val residualSigma: Float,
    )

    fun analyze(
        linearData: List<SensorSample>,
        gyroData: List<SensorSample>,
        gravityData: List<SensorSample>,
    ): GaitAnalysisParams {
        if (linearData.size < 8) return defaultParams()

        val linear = toSeries(linearData) ?: return defaultParams()
        val gyro = toSeries(gyroData)
        val gravity = toSeries(gravityData)

        val maxAmp = (0..2).maxOf { axisAmplitude(linear.axes[it]) }
        if (maxAmp < 0.3) return defaultParams()

        val startFreq = estimateStartFrequency(linear, gyro).coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)
        val candidateFreq = pickFrequencyCandidate(linear, gyro, startFreq)
        val optimizedFreq = optimizeFrequency(linear, gyro, candidateFreq).coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)

        val gravityMean = gravity?.let { meanAxes(it.axes).toFloatArray() }
            ?: floatArrayOf(0f, -9.81f, 0f)

        val linearFit = fitSensor(
            series = linear,
            freqHz = optimizedFreq,
            maxHarmonics = 16,
            axisCandidates = arrayOf(
                doubleArrayOf(1.0, 0.5, 2.0),
                doubleArrayOf(1.0, 0.5, 2.0),
                doubleArrayOf(1.0, 0.5, 2.0),
            ),
            pulseSigmaPhi = 0.30,
            dcOverride = null,
        )
        val gyroFit = gyro?.let {
            fitSensor(
                series = it,
                freqHz = optimizedFreq,
                maxHarmonics = 16,
                axisCandidates = arrayOf(
                    doubleArrayOf(1.0, 0.5, 2.0),
                    doubleArrayOf(0.5, 1.0, 1.5, 2.0),
                    doubleArrayOf(1.0, 0.5, 2.0),
                ),
                pulseSigmaPhi = 0.35,
                dcOverride = null,
            )
        }
        val gravityFit = gravity?.let {
            fitSensor(
                series = it,
                freqHz = optimizedFreq,
                maxHarmonics = 8,
                axisCandidates = arrayOf(
                    doubleArrayOf(1.0, 0.5, 2.0),
                    doubleArrayOf(1.0, 0.5, 2.0),
                    doubleArrayOf(1.0, 0.5, 2.0),
                ),
                pulseSigmaPhi = 0.35,
                dcOverride = gravityMean.toDoubleArray(),
            )
        }

        val sigmas = listOfNotNull(
            linearFit.residualSigma.takeIf { it > 0f },
            gyroFit?.residualSigma?.takeIf { it > 0f },
            gravityFit?.residualSigma?.takeIf { it > 0f },
        )
        val noiseSigma = if (sigmas.isNotEmpty()) median(sigmas.map { it.toDouble() }.toDoubleArray()).toFloat() else 0.1f

        return GaitAnalysisParams(
            frequency = optimizedFreq.toFloat(),
            duration = ((linearData.last().timestamp - linearData.first().timestamp) / 1_000_000_000.0).toFloat(),
            gravity = gravityFit?.dc?.toFloatArray() ?: gravityMean,
            noiseSigma = noiseSigma,
            noiseSigmaAccel = linearFit.residualSigma,
            noiseSigmaGyro = gyroFit?.residualSigma ?: 0f,
            noiseSigmaGravity = gravityFit?.residualSigma ?: 0f,
            dcAccel = linearFit.dc.toFloatArray(),
            dcGyro = gyroFit?.dc?.toFloatArray() ?: FloatArray(3),
            fourierAccel = linearFit.fourier,
            fourierGyro = gyroFit?.fourier ?: GaitAnalysisParams.emptyAxes(),
            fourierGravity = gravityFit?.fourier ?: GaitAnalysisParams.emptyAxes(),
            pulseAccel = linearFit.pulses,
            pulseGyro = gyroFit?.pulses ?: GaitAnalysisParams.emptyAxes(),
            pulseGravity = gravityFit?.pulses ?: GaitAnalysisParams.emptyAxes(),
            freqMulAccel = linearFit.freqMul,
            freqMulGyro = gyroFit?.freqMul ?: floatArrayOf(1f, 1f, 1f),
            freqMulGravity = gravityFit?.freqMul ?: floatArrayOf(1f, 1f, 1f),
            scoreAccel = linearFit.scores,
            scoreGyro = gyroFit?.scores ?: FloatArray(3),
            scoreGravity = gravityFit?.scores ?: FloatArray(3),
        )
    }

    fun verify(
        linearData: List<SensorSample>,
        gyroData: List<SensorSample>,
        gravityData: List<SensorSample>,
        params: GaitAnalysisParams,
    ): VerificationSummary {
        val linear = toSeries(linearData)
        val gyro = toSeries(gyroData)
        val gravity = toSeries(gravityData)
        return VerificationSummary(
            linear = linear?.let {
                averageScore(it, params.frequency.toDouble(), params.fourierAccel, params.pulseAccel, params.freqMulAccel, params.dcAccel)
            } ?: 0f,
            gyro = gyro?.let {
                averageScore(it, params.frequency.toDouble(), params.fourierGyro, params.pulseGyro, params.freqMulGyro, params.dcGyro)
            } ?: 0f,
            gravity = gravity?.let {
                averageScore(it, params.frequency.toDouble(), params.fourierGravity, params.pulseGravity, params.freqMulGravity, params.gravity)
            } ?: 0f,
        )
    }

    private fun defaultParams(): GaitAnalysisParams {
        return GaitAnalysisParams(
            frequency = 2.8f,
            duration = 0f,
            gravity = floatArrayOf(0f, -9.81f, 0f),
            noiseSigma = 0.5f,
            noiseSigmaAccel = 0.5f,
            noiseSigmaGyro = 0.1f,
            noiseSigmaGravity = 0.02f,
            humanizeIntensity = REPLAY_HUMANIZE_INTENSITY,
            fourierAccel = listOf(
                listOf(2.0f, 0f, 0.8f, 0f),
                listOf(8.0f, (PI / 2.0).toFloat(), 3.2f, 0f),
                listOf(4.0f, 0f, 1.6f, 0f),
            ),
        )
    }

    private fun toSeries(samples: List<SensorSample>): Series? {
        if (samples.size < 2) return null
        val sorted = samples.sortedBy { it.timestamp }
        val start = sorted.first().timestamp
        val durationSec = (sorted.last().timestamp - start) / 1_000_000_000.0
        if (durationSec <= 0.0) return null
        val n = sorted.size
        val t = DoubleArray(n)
        val axes = Array(3) { DoubleArray(n) }
        sorted.forEachIndexed { index, sample ->
            t[index] = (sample.timestamp - start) / 1_000_000_000.0
            axes[0][index] = sample.x.toDouble()
            axes[1][index] = sample.y.toDouble()
            axes[2][index] = sample.z.toDouble()
        }
        return Series(t = t, axes = axes, fs = (n - 1) / durationSec)
    }

    private fun estimateStartFrequency(linear: Series, gyro: Series?): Double {
        val magnitude = DoubleArray(linear.t.size) { i ->
            sqrt(linear.axes[0][i] * linear.axes[0][i] + linear.axes[1][i] * linear.axes[1][i] + linear.axes[2][i] * linear.axes[2][i])
        }
        val candidates = ArrayList<Double>()
        autocorrPeakFrequency(magnitude, linear.fs)?.let { candidates.add(it) }
        periodogramPeakFrequency(linear.t, magnitude)?.let { candidates.add(it) }
        for (axis in 0..2) {
            val centered = center(linear.axes[axis]).first
            if (axisAmplitude(centered) > 0.05) {
                periodogramPeakFrequency(linear.t, centered)?.let { candidates.add(it) }
            }
        }
        if (gyro != null) {
            for (axis in 0..2) {
                periodogramPeakFrequency(gyro.t, center(gyro.axes[axis]).first)?.let { candidates.add(it) }
            }
        }
        return if (candidates.isEmpty()) 1.8 else median(candidates.toDoubleArray())
    }

    private fun periodogramPeakFrequency(t: DoubleArray, y: DoubleArray): Double? {
        if (t.size < 16) return null
        val centered = center(y).first
        var bestFreq = 0.0
        var bestPower = 0.0
        var f = MIN_FREQ_HZ
        while (f <= MAX_FREQ_HZ + 1e-9) {
            val omega = 2.0 * PI * f
            var s = 0.0
            var c = 0.0
            for (i in centered.indices) {
                val angle = omega * t[i]
                s += centered[i] * sin(angle)
                c += centered[i] * cos(angle)
            }
            val power = s * s + c * c
            if (power > bestPower) {
                bestPower = power
                bestFreq = f
            }
            f += 0.02
        }
        return bestFreq.takeIf { it > 0.0 }
    }

    private fun autocorrPeakFrequency(y: DoubleArray, fs: Double): Double? {
        if (y.size < 16 || fs <= 0.0) return null
        val centered = center(y).first
        val minLag = (fs / MAX_FREQ_HZ).toInt().coerceAtLeast(1)
        val maxLag = (fs / MIN_FREQ_HZ).toInt().coerceAtMost(y.size - 1)
        if (minLag >= maxLag) return null
        var bestLag = -1
        var best = -Double.MAX_VALUE
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until centered.size - lag) {
                sum += centered[i] * centered[i + lag]
            }
            val score = sum / (centered.size - lag)
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        return if (bestLag > 0) fs / bestLag else null
    }

    private fun pickFrequencyCandidate(linear: Series, gyro: Series?, startFreq: Double): Double {
        val datasets = ArrayList<Triple<DoubleArray, DoubleArray, Double>>()
        for (axis in 0..2) {
            datasets.add(Triple(linear.t, detrend(linear.t, center(linear.axes[axis]).first), linear.fs))
        }
        if (gyro != null) {
            for (axis in 0..2) {
                datasets.add(Triple(gyro.t, detrend(gyro.t, center(gyro.axes[axis]).first), gyro.fs))
            }
        }
        val candidates = listOf(startFreq, startFreq / 2.0, startFreq * 2.0)
            .filter { it in MIN_FREQ_HZ..MAX_FREQ_HZ }
            .distinctBy { "%.3f".format(it) }
        var best = startFreq
        var bestErr = Double.MAX_VALUE
        for (freq in candidates) {
            val err = frequencyFitError(datasets, freq, 8)
            val tolerance = frequencyImprovementTolerance(bestErr)
            if (bestErr == Double.MAX_VALUE ||
                err < bestErr - tolerance ||
                (abs(err - bestErr) <= tolerance && abs(freq - startFreq) < abs(best - startFreq))
            ) {
                bestErr = err
                best = freq
            }
        }
        return best
    }

    private fun optimizeFrequency(linear: Series, gyro: Series?, startFreq: Double): Double {
        val datasets = ArrayList<Triple<DoubleArray, DoubleArray, Double>>(6)
        for (axis in 0..2) {
            datasets.add(Triple(linear.t, detrend(linear.t, center(linear.axes[axis]).first), linear.fs))
        }
        if (gyro != null) {
            for (axis in 0..2) {
                datasets.add(Triple(gyro.t, detrend(gyro.t, center(gyro.axes[axis]).first), gyro.fs))
            }
        }
        var best = startFreq
        var bestErr = frequencyFitError(datasets, startFreq, 10)
        fun search(from: Double, to: Double, steps: Int) {
            val lo = max(MIN_FREQ_HZ, from)
            val hi = min(MAX_FREQ_HZ, to)
            if (hi <= lo) return
            for (i in 0 until steps) {
                val f = lo + (hi - lo) * i / max(1, steps - 1)
                val err = frequencyFitError(datasets, f, 10)
                val tolerance = frequencyImprovementTolerance(bestErr)
                if (err < bestErr - tolerance ||
                    (abs(err - bestErr) <= tolerance && abs(f - startFreq) < abs(best - startFreq))
                ) {
                    bestErr = err
                    best = f
                }
            }
        }
        search(startFreq - 0.4, startFreq + 0.4, 64)
        search(best - 0.05, best + 0.05, 64)
        return best
    }

    private fun frequencyFitError(datasets: List<Triple<DoubleArray, DoubleArray, Double>>, freq: Double, harmonics: Int): Double {
        var total = 0.0
        datasets.forEach { (t, y, fs) ->
            val h = clampHarmonics(freq, fs, harmonics)
            val fit = fitFourierRidge(t, y, freq, h, alpha = 0.01)
            val std = stddev(y).takeIf { it > EPS } ?: 1.0
            var err = 0.0
            for (i in y.indices) {
                val d = y[i] - fit.recon[i]
                err += d * d
            }
            total += err / (std * std)
        }
        return total
    }

    private fun frequencyImprovementTolerance(bestErr: Double): Double {
        if (bestErr == Double.MAX_VALUE) return 0.0
        return max(1e-3, abs(bestErr) * 0.05)
    }

    private fun fitSensor(
        series: Series,
        freqHz: Double,
        maxHarmonics: Int,
        axisCandidates: Array<DoubleArray>,
        pulseSigmaPhi: Double,
        dcOverride: DoubleArray?,
    ): SensorFit {
        val fourier = ArrayList<List<Float>>(3)
        val pulses = ArrayList<List<Float>>(3)
        val freqMul = FloatArray(3) { 1f }
        val dc = dcOverride ?: meanAxes(series.axes)
        val scores = FloatArray(3)
        val residualSigmas = ArrayList<Double>()
        for (axis in 0..2) {
            val centered = DoubleArray(series.t.size) { i -> series.axes[axis][i] - dc[axis] }
            val y = detrend(series.t, centered)
            val candidates = axisCandidates.getOrNull(axis) ?: doubleArrayOf(1.0)
            var bestScore = -Double.MAX_VALUE
            var bestMul = 1.0
            var bestCoeff = emptyList<Float>()
            var bestRecon = DoubleArray(y.size)
            for (mul in candidates) {
                val f = freqHz * mul
                if (f !in MIN_FREQ_HZ / 2.0..MAX_FREQ_HZ * 2.0) continue
                val h = clampHarmonics(f, series.fs, maxHarmonics)
                val alphaCandidates = doubleArrayOf(0.0, 0.001, 0.01, 0.05)
                for (alpha in alphaCandidates) {
                    val fit = fitFourierRidge(series.t, y, f, h, alpha)
                    val score = score(y, fit.recon)
                    if (score > bestScore) {
                        bestScore = score
                        bestMul = mul
                        bestCoeff = fit.coeffs
                        bestRecon = fit.recon
                    }
                }
            }
            val pulse = fitPulses(series.t, y, bestRecon, freqHz * bestMul, pulseSigmaPhi, maxPulses = 3)
            val reconWithPulse = addPulseRecon(series.t, bestRecon, freqHz * bestMul, pulse)
            fourier.add(bestCoeff)
            pulses.add(pulse)
            freqMul[axis] = bestMul.toFloat()
            scores[axis] = score(y, reconWithPulse).toFloat()
            residualSigmas.add(robustMad(DoubleArray(y.size) { i -> y[i] - reconWithPulse[i] }))
        }
        return SensorFit(
            fourier = fourier,
            pulses = pulses,
            freqMul = freqMul,
            dc = dc,
            scores = scores,
            residualSigma = median(residualSigmas.toDoubleArray()).toFloat(),
        )
    }

    private fun fitFourierRidge(t: DoubleArray, y: DoubleArray, freqHz: Double, harmonics: Int, alpha: Double): FourierFit {
        val h = harmonics.coerceAtLeast(1)
        val p = 2 * h
        val xtx = DoubleArray(p * p)
        val xty = DoubleArray(p)
        val omega = 2.0 * PI * freqHz
        for (i in y.indices) {
            for (k in 1..h) {
                val angle = omega * k * t[i]
                val s = sin(angle)
                val c = cos(angle)
                val idxS = 2 * (k - 1)
                val idxC = idxS + 1
                xty[idxS] += s * y[i]
                xty[idxC] += c * y[i]
                for (kk in 1..h) {
                    val angle2 = omega * kk * t[i]
                    val s2 = sin(angle2)
                    val c2 = cos(angle2)
                    val jS = 2 * (kk - 1)
                    val jC = jS + 1
                    xtx[idxS * p + jS] += s * s2
                    xtx[idxS * p + jC] += s * c2
                    xtx[idxC * p + jS] += c * s2
                    xtx[idxC * p + jC] += c * c2
                }
            }
        }
        for (d in 0 until p) {
            xtx[d * p + d] += alpha
        }
        val beta = solveSymmetricPositive(xtx, p, xty)
        val coeffs = ArrayList<Float>(p)
        for (k in 1..h) {
            val a = beta[2 * (k - 1)]
            val b = beta[2 * (k - 1) + 1]
            coeffs.add(sqrt(a * a + b * b).toFloat())
            coeffs.add(atan2(b, a).toFloat())
        }
        val recon = DoubleArray(y.size)
        for (i in y.indices) {
            var value = 0.0
            for (k in 1..h) {
                val angle = omega * k * t[i]
                value += beta[2 * (k - 1)] * sin(angle) + beta[2 * (k - 1) + 1] * cos(angle)
            }
            recon[i] = value
        }
        return FourierFit(coeffs, recon)
    }

    private fun solveSymmetricPositive(a: DoubleArray, n: Int, b: DoubleArray): DoubleArray {
        val l = DoubleArray(n * n)
        for (i in 0 until n) {
            for (j in 0..i) {
                var sum = a[i * n + j]
                for (k in 0 until j) {
                    sum -= l[i * n + k] * l[j * n + k]
                }
                l[i * n + j] = if (i == j) sqrt(max(sum, 1e-12)) else sum / l[j * n + j]
            }
        }
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var sum = b[i]
            for (k in 0 until i) sum -= l[i * n + k] * y[k]
            y[i] = sum / l[i * n + i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = y[i]
            for (k in i + 1 until n) sum -= l[k * n + i] * x[k]
            x[i] = sum / l[i * n + i]
        }
        return x
    }

    private fun fitPulses(
        t: DoubleArray,
        y: DoubleArray,
        recon: DoubleArray,
        freqHz: Double,
        sigmaPhi: Double,
        maxPulses: Int,
    ): List<Float> {
        if (y.isEmpty() || freqHz <= 0.0) return emptyList()
        val residual = DoubleArray(y.size) { i -> y[i] - recon[i] }
        val std = robustMad(residual)
        if (std <= 1e-6) return emptyList()
        val bins = 256
        val sums = DoubleArray(bins)
        val counts = IntArray(bins)
        val omega = 2.0 * PI * freqHz
        for (i in residual.indices) {
            val phase = wrapPositive(omega * t[i])
            val idx = ((phase / (2.0 * PI)) * bins).toInt().coerceIn(0, bins - 1)
            sums[idx] += residual[i]
            counts[idx] += 1
        }
        val avg = DoubleArray(bins) { if (counts[it] > 0) sums[it] / counts[it] else 0.0 }
        val smooth = circularSmooth(avg, 5)
        val order = (0 until bins).filter {
            val prev = abs(smooth[(it - 1 + bins) % bins])
            val cur = abs(smooth[it])
            val next = abs(smooth[(it + 1) % bins])
            cur > prev && cur >= next
        }.sortedByDescending { abs(smooth[it]) }
        val picked = ArrayList<Int>()
        for (idx in order) {
            if (picked.size >= maxPulses) break
            val separated = picked.all { min(abs(idx - it), bins - abs(idx - it)) >= 12 }
            if (separated && abs(smooth[idx]) >= 0.35 * std) picked.add(idx)
        }
        val out = ArrayList<Float>(picked.size * 3)
        for (idx in picked.sorted()) {
            out.add((2.0 * PI * (idx + 0.5) / bins).toFloat())
            out.add(sigmaPhi.toFloat())
            out.add(smooth[idx].toFloat())
        }
        return out
    }

    private fun addPulseRecon(t: DoubleArray, base: DoubleArray, freqHz: Double, flatPulses: List<Float>): DoubleArray {
        if (flatPulses.size < 3) return base
        val out = base.copyOf()
        val omega = 2.0 * PI * freqHz
        for (i in out.indices) {
            val phase = omega * t[i]
            var pulse = 0.0
            var j = 0
            while (j + 2 < flatPulses.size) {
                val mu = flatPulses[j].toDouble()
                val sigma = flatPulses[j + 1].toDouble()
                val amp = flatPulses[j + 2].toDouble()
                if (sigma > 0.0) {
                    val d = wrapSigned(phase - mu)
                    pulse += amp * kotlin.math.exp(-0.5 * (d / sigma) * (d / sigma))
                }
                j += 3
            }
            out[i] += pulse
        }
        return out
    }

    private fun averageScore(
        series: Series,
        freqHz: Double,
        fourier: List<List<Float>>,
        pulses: List<List<Float>>,
        freqMul: FloatArray,
        dc: FloatArray,
    ): Float {
        var sum = 0.0
        var count = 0
        for (axis in 0..2) {
            val raw = DoubleArray(series.t.size) { i -> series.axes[axis][i] - dc.getOrElse(axis) { 0f } }
            val recon = synthAxis(series.t, freqHz, freqMul.getOrElse(axis) { 1f }.toDouble(), fourier.getOrNull(axis), pulses.getOrNull(axis))
            sum += score(raw, recon)
            count++
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun synthAxis(
        t: DoubleArray,
        freqHz: Double,
        freqMul: Double,
        coeffs: List<Float>?,
        pulses: List<Float>?,
    ): DoubleArray {
        val out = DoubleArray(t.size)
        val omega = 2.0 * PI * freqHz
        for (i in t.indices) {
            val phase = omega * t[i] * freqMul
            var value = 0.0
            val c = coeffs.orEmpty()
            var j = 0
            while (j + 1 < c.size) {
                val k = (j / 2) + 1
                value += c[j] * sin(k * phase + c[j + 1])
                j += 2
            }
            val p = pulses.orEmpty()
            j = 0
            while (j + 2 < p.size) {
                val sigma = p[j + 1].toDouble()
                if (sigma > 0.0) {
                    val d = wrapSigned(phase - p[j].toDouble())
                    value += p[j + 2] * kotlin.math.exp(-0.5 * (d / sigma) * (d / sigma))
                }
                j += 3
            }
            out[i] = value
        }
        return out
    }

    private fun clampHarmonics(freq: Double, fs: Double, requested: Int): Int {
        if (freq <= 0.0 || fs <= 0.0) return 1
        val nyquist = floor((fs * 0.5) / freq).toInt().coerceAtLeast(1)
        return min(requested.coerceAtLeast(1), nyquist)
    }

    private fun center(y: DoubleArray): Pair<DoubleArray, Double> {
        val mean = y.average()
        return Pair(DoubleArray(y.size) { y[it] - mean }, mean)
    }

    private fun meanAxes(axes: Array<DoubleArray>): DoubleArray {
        return DoubleArray(3) { axis -> axes[axis].average() }
    }

    private fun detrend(t: DoubleArray, y: DoubleArray): DoubleArray {
        if (y.size < 3) return y
        val meanT = t.average()
        val meanY = y.average()
        var num = 0.0
        var den = 0.0
        for (i in y.indices) {
            val dt = t[i] - meanT
            num += dt * (y[i] - meanY)
            den += dt * dt
        }
        if (den <= EPS) return y
        val slope = num / den
        val intercept = meanY - slope * meanT
        return DoubleArray(y.size) { y[it] - (slope * t[it] + intercept) }
    }

    private fun score(original: DoubleArray, reconstructed: DoubleArray): Double {
        var err = 0.0
        var mag = 0.0
        for (i in original.indices) {
            err += abs(original[i] - reconstructed[i])
            mag += abs(original[i])
        }
        if (mag <= EPS) return if (err <= EPS) 100.0 else 0.0
        return ((1.0 - err / mag) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun axisAmplitude(y: DoubleArray): Double {
        if (y.isEmpty()) return 0.0
        val sorted = y.sorted()
        val lo = sorted[(sorted.size * 0.05).toInt().coerceIn(0, sorted.lastIndex)]
        val hi = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.lastIndex)]
        return 0.5 * (hi - lo)
    }

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun robustMad(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val med = median(values)
        return 1.4826 * median(DoubleArray(values.size) { abs(values[it] - med) })
    }

    private fun stddev(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun circularSmooth(values: DoubleArray, window: Int): DoubleArray {
        if (values.isEmpty() || window <= 1) return values
        val half = window / 2
        return DoubleArray(values.size) { i ->
            var sum = 0.0
            var count = 0
            for (k in -half..half) {
                sum += values[(i + k + values.size) % values.size]
                count++
            }
            sum / count
        }
    }

    private fun wrapPositive(x: Double): Double {
        var value = x % (2.0 * PI)
        if (value < 0.0) value += 2.0 * PI
        return value
    }

    private fun wrapSigned(x: Double): Double {
        var value = (x + PI) % (2.0 * PI)
        if (value < 0.0) value += 2.0 * PI
        return value - PI
    }

    private fun FloatArray.toDoubleArray(): DoubleArray = DoubleArray(size) { this[it].toDouble() }
    private fun DoubleArray.toFloatArray(): FloatArray = FloatArray(size) { this[it].toFloat() }
}
