/*
 * Copyright (C) 2025 The KunYuShu Project
 *
 * GaitSimulator — 基于 mockWalk 录制数据解算的步态仿真器。
 * 从 gait_analysis.json 读取 Fourier 谐波 + 高斯脉冲参数，
 * 重构三轴 accel/gyro/gravity/linear 信号，并叠加 humanization：
 *   - 随机游走偏置 (bias drift)
 *   - 相位抖动 (phase jitter)
 *   - 增益抖动 (gain jitter)
 *   - 频率漂移 (frequency drift)
 *   - 生理震颤 (physiological tremor)
 *   - jerk 冲击 (jerk transient)
 *   - 传感器噪声 (wobble)
 *
 * 移植自 refer/mockWalk/src/core/simulator.py
 */

package tv.kyshu.xposed.hooks.sensor

import android.content.Context
import org.json.JSONObject
import tv.kyshu.xposed.utils.Logger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

data class HarmonicComponent(val magnitude: Float, val phase: Float)
data class PulseComponent(val mu: Float, val sigmaPhi: Float, val amplitude: Float)

data class GaitModelParams(
    val frequency: Float,
    val gravity: FloatArray,
    val noiseSigma: Float,
    val noiseSigmaAccel: Float,
    val noiseSigmaGyro: Float,
    val noiseSigmaGravity: Float,
    val humanize: Boolean,
    val humanizeIntensity: Float,
    val dcAccel: FloatArray,
    val dcGyro: FloatArray,
    val fourierAccel: List<List<HarmonicComponent>>?,
    val fourierGyro: List<List<HarmonicComponent>>?,
    val fourierGravity: List<List<HarmonicComponent>>?,
    val pulseAccel: List<List<PulseComponent>>?,
    val pulseGyro: List<List<PulseComponent>>?,
    val pulseGravity: List<List<PulseComponent>>?,
    val freqMulAccel: FloatArray,
    val freqMulGyro: FloatArray,
    val freqMulGravity: FloatArray,
)

class GaitSimulator(private val params: GaitModelParams) {

    enum class State { RUNNING, STATIONARY }
    var currentState: State = State.RUNNING

    // Drift state (6轴: 0-2 accel, 3-5 gyro)
    private val bias = FloatArray(6)
    private var phaseJitter = 0.0f
    private val gainJitterLinear = FloatArray(3)
    private val gainJitterGyro = FloatArray(3)
    private val gainJitterGravity = FloatArray(3)

    // Humanization state
    private var freqDrift = 0.0f
    private var freqDriftTarget = 0.0f
    private var tremorPhase = 0.0f

    private fun mulAt(arr: FloatArray, idx: Int): Float =
        if (idx < arr.size) arr[idx] else 1.0f

    private fun sigmaFor(sensor: String): Float {
        val base = params.noiseSigma
        return when (sensor) {
            "linear" -> if (params.noiseSigmaAccel > 0f) params.noiseSigmaAccel else base
            "gyro" -> if (params.noiseSigmaGyro > 0f) params.noiseSigmaGyro else base
            else -> if (params.noiseSigmaGravity > 0f) params.noiseSigmaGravity else base
        }.coerceAtLeast(0f)
    }

    private fun wrapAngle(x: Float): Float {
        val twoPi = (2.0 * PI).toFloat()
        var v = (x + PI.toFloat()) % twoPi
        if (v < 0f) v += twoPi
        return v - PI.toFloat()
    }

    // Gaussian 采样 (Box-Muller)
    private fun nextGaussian(): Float {
        val u1 = Random.nextFloat().coerceAtLeast(1e-7f)
        val u2 = Random.nextFloat()
        return (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1.toDouble())) * cos(2.0 * PI * u2)).toFloat()
    }

    private fun cos(x: Double): Double = kotlin.math.cos(x)

    /**
     * 计算指定时刻的运动值。
     * @param tSec 秒
     * @return [accel[3], gyro[3], gravity[3], linear[3]]
     */
    fun getMotionValues(tSec: Float): Array<FloatArray> {
        val intensity = if (params.humanizeIntensity < 0f) 0f else params.humanizeIntensity

        if (!params.humanize || intensity <= 0f) {
            return computePure(tSec)
        }

        if (currentState != State.RUNNING) {
            return arrayOf(FloatArray(3), FloatArray(3), FloatArray(3), FloatArray(3))
        }

        // === Humanization: 抖动 + jerk + drift + tremor ===

        // 1. 随机游走偏置 (2% 概率更新)
        if (Random.nextInt(50) == 0) {
            for (i in 0 until 6) {
                bias[i] += (Random.nextFloat() - 0.5f) * 0.02f * intensity
                bias[i] = bias[i].coerceIn(-0.5f, 0.5f)
            }
        }

        // 2. 频率漂移 (0.8% 概率更新目标)
        if (Random.nextInt(125) == 0) {
            freqDriftTarget = (Random.nextFloat() - 0.5f) * 0.16f * params.frequency * intensity
        }
        freqDrift += (freqDriftTarget - freqDrift) * 0.02f
        val effectiveFreq = params.frequency + freqDrift
        val omega = (2.0 * PI * effectiveFreq).toFloat()

        // 3. 生理震颤 (~10Hz)
        tremorPhase += (10.0f + (Random.nextFloat() - 0.5f) * 4.0f) * 0.02f
        val tremorAmp = 0.1f * sigmaFor("linear") * intensity
        val tremorVal = sin(tremorPhase) * tremorAmp

        // 4. 相位抖动 (AR(1) 衰减)
        val sigmaLinear = sigmaFor("linear")
        phaseJitter = phaseJitter * 0.9992f + nextGaussian() * (0.002f * sigmaLinear * intensity)
        val phase = omega * tSec + phaseJitter

        val outGyro = FloatArray(3)
        val outGravity = FloatArray(3)
        val outLinear = FloatArray(3)
        val outAccel = FloatArray(3)

        // --- Gyro ---
        if (params.fourierGyro != null && params.fourierGyro.size >= 3) {
            val sigmaGyro = sigmaFor("gyro")
            for (i in 0 until 3) {
                gainJitterGyro[i] = gainJitterGyro[i] * 0.985f + nextGaussian() * 0.015f * intensity
                var signal = 0f
                val axisPhase = phase * mulAt(params.freqMulGyro, i)

                params.fourierGyro[i].forEachIndexed { index, comp ->
                    val k = index + 1
                    signal += comp.magnitude * sin(k * axisPhase + comp.phase)
                }

                // 高斯脉冲
                params.pulseGyro?.getOrNull(i)?.forEach { p ->
                    val d = wrapAngle(axisPhase - p.mu)
                    signal += p.amplitude * exp(-0.5f * (d / p.sigmaPhi).toDouble().pow(2)).toFloat()
                }

                val gain = (1.0f + gainJitterGyro[i] * (0.03f * sigmaGyro)).coerceIn(0.7f, 1.3f)
                val wobble = nextGaussian() * (0.02f * sigmaGyro * intensity)
                val tremorGyro = tremorVal * 0.5f * (if (i == 1) 0.2f else 1.0f)
                val dc = if (i < params.dcGyro.size) params.dcGyro[i] else 0f
                outGyro[i] = (signal * gain) + dc + bias[i + 3] + wobble + tremorGyro +
                    (Random.nextFloat() - 0.5f) * 0.04f * intensity
            }
        }

        // --- Gravity ---
        if (params.fourierGravity != null && params.fourierGravity.size >= 3) {
            val sigmaGravity = sigmaFor("gravity")
            for (i in 0 until 3) {
                gainJitterGravity[i] = gainJitterGravity[i] * 0.992f + nextGaussian() * 0.008f * intensity
                var signal = 0f
                val axisPhase = phase * mulAt(params.freqMulGravity, i)

                params.fourierGravity[i].forEachIndexed { index, comp ->
                    val k = index + 1
                    signal += comp.magnitude * sin(k * axisPhase + comp.phase)
                }

                params.pulseGravity?.getOrNull(i)?.forEach { p ->
                    val d = wrapAngle(axisPhase - p.mu)
                    signal += p.amplitude * exp(-0.5f * (d / p.sigmaPhi).toDouble().pow(2)).toFloat()
                }

                val gain = (1.0f + gainJitterGravity[i] * (0.01f * sigmaGravity)).coerceIn(0.85f, 1.15f)
                val wobble = nextGaussian() * (0.004f * sigmaGravity * intensity)
                outGravity[i] = params.gravity[i] + (signal * gain) + wobble
            }
        } else {
            for (i in 0 until 3) outGravity[i] = params.gravity[i]
        }

        // --- Linear Accel (含 jerk) ---
        if (params.fourierAccel != null && params.fourierAccel.size >= 3) {
            val sigmaLin = sigmaFor("linear")
            for (i in 0 until 3) {
                gainJitterLinear[i] = gainJitterLinear[i] * 0.982f + nextGaussian() * 0.018f * intensity
                var signal = 0f
                val axisPhase = phase * mulAt(params.freqMulAccel, i)

                params.fourierAccel[i].forEachIndexed { index, comp ->
                    val k = index + 1
                    signal += comp.magnitude * sin(k * axisPhase + comp.phase)
                }

                params.pulseAccel?.getOrNull(i)?.forEach { p ->
                    val d = wrapAngle(axisPhase - p.mu)
                    signal += p.amplitude * exp(-0.5f * (d / p.sigmaPhi).toDouble().pow(2)).toFloat()
                }

                // jerk 冲击：与信号幅度相关
                val jerk = (Random.nextFloat() - 0.5f) * sigmaLin * intensity * (1.0f + kotlin.math.abs(signal))
                val gain = (1.0f + gainJitterLinear[i] * (0.03f * sigmaLin)).coerceIn(0.7f, 1.3f)
                val wobble = nextGaussian() * (0.03f * sigmaLin * intensity)
                val tremorLinear = tremorVal * (if (i == 1) 0.5f else 1.0f)

                val dc = if (i < params.dcAccel.size) params.dcAccel[i] else 0f
                outLinear[i] = (signal * gain) + dc + bias[i] + jerk * 0.25f + wobble + tremorLinear
            }
        }

        // accel = linear + gravity
        for (i in 0 until 3) {
            outAccel[i] = outLinear[i] + outGravity[i]
        }

        return arrayOf(outAccel, outGyro, outGravity, outLinear)
    }

    private fun computePure(tSec: Float): Array<FloatArray> {
        val omega = (2.0 * PI * params.frequency).toFloat()
        val phase = omega * tSec
        val outGyro = FloatArray(3)
        val outGravity = FloatArray(3)
        val outLinear = FloatArray(3)
        val outAccel = FloatArray(3)

        if (params.fourierGyro != null && params.fourierGyro.size >= 3) {
            for (i in 0 until 3) {
                var signal = 0f
                val axisPhase = phase * mulAt(params.freqMulGyro, i)
                params.fourierGyro[i].forEachIndexed { index, comp ->
                    signal += comp.magnitude * sin((index + 1) * axisPhase + comp.phase)
                }
                params.pulseGyro?.getOrNull(i)?.forEach { p ->
                    val d = wrapAngle(axisPhase - p.mu)
                    signal += p.amplitude * exp(-0.5f * (d / p.sigmaPhi).toDouble().pow(2)).toFloat()
                }
                outGyro[i] = signal + (if (i < params.dcGyro.size) params.dcGyro[i] else 0f)
            }
        }

        if (params.fourierGravity != null && params.fourierGravity.size >= 3) {
            for (i in 0 until 3) {
                var signal = 0f
                val axisPhase = phase * mulAt(params.freqMulGravity, i)
                params.fourierGravity[i].forEachIndexed { index, comp ->
                    signal += comp.magnitude * sin((index + 1) * axisPhase + comp.phase)
                }
                params.pulseGravity?.getOrNull(i)?.forEach { p ->
                    val d = wrapAngle(axisPhase - p.mu)
                    signal += p.amplitude * exp(-0.5f * (d / p.sigmaPhi).toDouble().pow(2)).toFloat()
                }
                outGravity[i] = params.gravity[i] + signal
            }
        } else {
            for (i in 0 until 3) outGravity[i] = params.gravity[i]
        }

        if (params.fourierAccel != null && params.fourierAccel.size >= 3) {
            for (i in 0 until 3) {
                var signal = 0f
                val axisPhase = phase * mulAt(params.freqMulAccel, i)
                params.fourierAccel[i].forEachIndexed { index, comp ->
                    signal += comp.magnitude * sin((index + 1) * axisPhase + comp.phase)
                }
                params.pulseAccel?.getOrNull(i)?.forEach { p ->
                    val d = wrapAngle(axisPhase - p.mu)
                    signal += p.amplitude * exp(-0.5f * (d / p.sigmaPhi).toDouble().pow(2)).toFloat()
                }
                outLinear[i] = signal + (if (i < params.dcAccel.size) params.dcAccel[i] else 0f)
            }
        }

        for (i in 0 until 3) outAccel[i] = outLinear[i] + outGravity[i]
        return arrayOf(outAccel, outGyro, outGravity, outLinear)
    }

    companion object {
        private const val ASSET_NAME = "gait_default.json"

        /**
         * 从 assets/gait_default.json 加载默认步态参数。
         * 失败时返回 null。
         */
        fun loadDefault(context: Context?): GaitSimulator? {
            if (context == null) return null
            return try {
                val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                val params = parseJson(JSONObject(json))
                GaitSimulator(params)
            } catch (e: Throwable) {
                Logger.error("GaitSimulator: Failed to load $ASSET_NAME", e)
                null
            }
        }

        /**
         * 从 JSON 字符串加载步态参数。
         */
        fun loadFromJson(jsonStr: String): GaitSimulator? {
            return try {
                val params = parseJson(JSONObject(jsonStr))
                GaitSimulator(params)
            } catch (e: Throwable) {
                Logger.error("GaitSimulator: Failed to parse JSON", e)
                null
            }
        }

        private fun parseJson(obj: JSONObject): GaitModelParams {
            fun parseHarmonics(key: String): List<List<HarmonicComponent>>? {
                val arr = obj.optJSONArray(key) ?: return null
                if (arr.length() != 3) return null
                val result = ArrayList<List<HarmonicComponent>>()
                for (i in 0 until 3) {
                    val axisArr = arr.getJSONArray(i)
                    val components = ArrayList<HarmonicComponent>()
                    var j = 0
                    while (j + 1 < axisArr.length()) {
                        components.add(HarmonicComponent(
                            axisArr.getDouble(j).toFloat(),
                            axisArr.getDouble(j + 1).toFloat()
                        ))
                        j += 2
                    }
                    result.add(components)
                }
                return result
            }

            fun parsePulses(key: String): List<List<PulseComponent>>? {
                val arr = obj.optJSONArray(key) ?: return null
                if (arr.length() != 3) return null
                val result = ArrayList<List<PulseComponent>>()
                for (i in 0 until 3) {
                    val axisArr = arr.getJSONArray(i)
                    val components = ArrayList<PulseComponent>()
                    var j = 0
                    while (j + 2 < axisArr.length()) {
                        components.add(PulseComponent(
                            axisArr.getDouble(j).toFloat(),
                            axisArr.getDouble(j + 1).toFloat(),
                            axisArr.getDouble(j + 2).toFloat()
                        ))
                        j += 3
                    }
                    result.add(components)
                }
                return result
            }

            fun parseFloatArray(key: String, default: FloatArray): FloatArray {
                val arr = obj.optJSONArray(key) ?: return default
                if (arr.length() != 3) return default
                return FloatArray(3) { arr.getDouble(it).toFloat() }
            }

            return GaitModelParams(
                frequency = obj.getDouble("frequency").toFloat(),
                gravity = parseFloatArray("gravity", floatArrayOf(0f, -9.81f, 0f)),
                noiseSigma = obj.getDouble("noiseSigma").toFloat(),
                noiseSigmaAccel = obj.optDouble("noiseSigmaAccel", 0.0).toFloat(),
                noiseSigmaGyro = obj.optDouble("noiseSigmaGyro", 0.0).toFloat(),
                noiseSigmaGravity = obj.optDouble("noiseSigmaGravity", 0.0).toFloat(),
                humanize = obj.optBoolean("humanize", true),
                humanizeIntensity = obj.optDouble("humanizeIntensity", 1.0).toFloat(),
                dcAccel = parseFloatArray("dcAccel", FloatArray(3)),
                dcGyro = parseFloatArray("dcGyro", FloatArray(3)),
                fourierAccel = parseHarmonics("fourierAccel"),
                fourierGyro = parseHarmonics("fourierGyro"),
                fourierGravity = parseHarmonics("fourierGravity"),
                pulseAccel = parsePulses("pulseAccel"),
                pulseGyro = parsePulses("pulseGyro"),
                pulseGravity = parsePulses("pulseGravity"),
                freqMulAccel = parseFloatArray("freqMulAccel", floatArrayOf(1f, 1f, 1f)),
                freqMulGyro = parseFloatArray("freqMulGyro", floatArrayOf(1f, 1f, 1f)),
                freqMulGravity = parseFloatArray("freqMulGravity", floatArrayOf(1f, 1f, 1f)),
            )
        }
    }
}

// 扩展：Double.pow
private fun Double.pow(exp: Int): Double = java.lang.Math.pow(this, exp.toDouble())
private fun Double.pow(exp: Double): Double = java.lang.Math.pow(this, exp)
