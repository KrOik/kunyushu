@file:Suppress("UNCHECKED_CAST")
package tv.kyshu.xposed.hooks.sensor

import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorEvent
import android.os.SystemClock
import android.util.ArrayMap
import android.util.SparseArray
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.afterHook
import tv.kyshu.xposed.utils.beforeHook
import tv.kyshu.xposed.utils.hookAllMethods
import tv.kyshu.xposed.utils.hookMethodAfter
import tv.kyshu.xposed.utils.onceHook
import tv.kyshu.xposed.utils.onceHookAllMethod
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// https://github.com/Frazew/VirtualSensor/blob/master/app/src/main/java/fr/frazew/virtualgyroscope/XposedMod.java#L298
object SystemSensorManagerHook {
    // listener -> 该 listener 注册的所有 Sensor（支持多传感器注册）
    private val listenerMap = ConcurrentHashMap<SensorEventListener, CopyOnWriteArrayList<Sensor>>()
    // sensor handle -> sensor type 缓存（dispatchSensorEvent 用 handle 定位类型）
    private val sensorHandleMap = ConcurrentHashMap<Int, Int>()
    // sensor handle -> 上次 hook 时间（节流用）
    private val lastHookTimeMap = ConcurrentHashMap<Int, Long>()
    // 步数自增计数器（不依赖真实传感器事件）
    @Volatile
    private var mCurrentStepCount: Long = 0L
    @Volatile
    private var mStepGoalEnd: Long = 0L
    private var mLastConfiguredStepCount: Int = -1
    private var mWasStepMockEnabled: Boolean = false

    // 姿态缓存
    private var yawRad = 0.0f
    private var lastPoseSampleMs = 0L
    private val cachedQuat = FloatArray(4).apply { this[3] = 1.0f }
    private val cachedMag = FloatArray(3)
    private val cachedEuler = FloatArray(3)

    // 步态仿真器（默认从 assets/gait_default.json 加载录制数据解算）
    @Volatile
    private var gaitSimulator: GaitSimulator? = null
    private var lastGaitJson = ""
    private var lastGaitLoadAt = 0L

    operator fun invoke(classLoader: ClassLoader) {
        ensureGaitSimulator()
        unlockGeoSensor(classLoader)
        hookSystemSensorManager(classLoader)
        hookSystemSensorManagerQueue(classLoader)
    }

    /**
     * 加载/刷新步态仿真器。
     * 优先级：用户自定义 gaitParamsJson > 默认 assets/gait_default.json（录制数据解算）
     */
    private fun ensureGaitSimulator() {
        val now = System.currentTimeMillis()
        // 至少 5 秒间隔，避免频繁加载
        if (gaitSimulator != null && now - lastGaitLoadAt < 5000L) return

        val userJson = FakeLoc.gaitData.gaitParamsJson.trim()
        if (userJson.isNotEmpty() && userJson != lastGaitJson) {
            // 用户自定义 JSON
            gaitSimulator = GaitSimulator.loadFromJson(userJson)
            lastGaitJson = userJson
            lastGaitLoadAt = now
            if (gaitSimulator != null) {
                Logger.info("GaitSimulator: loaded user-defined gait params")
            }
        } else if (gaitSimulator == null) {
            // 默认从 assets 加载录制数据解算的步态
            gaitSimulator = GaitSimulator.loadDefault(FakeLoc.getContext())
            lastGaitLoadAt = now
            if (gaitSimulator != null) {
                Logger.info("GaitSimulator: loaded default recorded gait from assets")
            } else {
                Logger.warn("GaitSimulator: default asset load failed, will retry")
            }
        }
    }

    // ==================== SensorEventQueue 层注入（核心） ====================

    private fun hookSystemSensorManagerQueue(classLoader: ClassLoader) {
        val candidates = ArrayList<Class<*>>()

        // 策略1: 已知类名
        val knownNames = arrayOf(
            "android.hardware.SystemSensorManager\$SensorEventQueue",
            "android.hardware.SystemSensorManager\$BaseEventQueue"
        )
        for (name in knownNames) {
            val clazz = XposedHelpers.findClassIfExists(name, classLoader)
            if (clazz != null) candidates.add(clazz)
        }

        // 策略2: 反射扫描内部类
        try {
            val cSystemSensorManager = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager", classLoader)
            if (cSystemSensorManager != null) {
                val innerClasses = cSystemSensorManager.declaredClasses
                for (inner in innerClasses) {
                    val hasMethod = inner.declaredMethods.any {
                        it.name == "dispatchSensorEvent" && it.parameterTypes.size >= 2
                    }
                    if (hasMethod && !candidates.contains(inner)) {
                        candidates.add(inner)
                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("Found sensor event queue via reflection: ${inner.name}")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Logger.warn("Failed to scan SystemSensorManager inner classes", e)
        }

        if (candidates.isEmpty()) {
            Logger.error("FATAL: Failed to find any SensorEventQueue class! Sensor hook will NOT work.")
            return
        }

        // Hook 所有候选类
        for (clazz in candidates) {
            try {
                XposedHelpers.findAndHookMethod(clazz, "dispatchSensorEvent",
                    Int::class.javaPrimitiveType,
                    FloatArray::class.java,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            handleDispatchSensorEvent(param)
                        }
                    })
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("Hooked dispatchSensorEvent in ${clazz.name}")
                }
            } catch (e: Throwable) {
                // 部分 ROM 签名不同，尝试动态查找
                try {
                    val method = clazz.declaredMethods.firstOrNull { it.name == "dispatchSensorEvent" }
                    if (method != null) {
                        XposedBridge.hookMethod(method, object : de.robv.android.xposed.XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                handleDispatchSensorEvent(param)
                            }
                        })
                        Logger.debug("Hooked dispatchSensorEvent (dynamic) in ${clazz.name}")
                    }
                } catch (e2: Throwable) {
                    Logger.error("Failed to hook dispatchSensorEvent in ${clazz.name}", e2)
                }
            }
        }
    }

    private fun handleDispatchSensorEvent(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
        if (!FakeLoc.sensorData.hookSensor) return
        if (!FakeLoc.sensorData.enableStepCountMock && !FakeLoc.sensorData.enableShakeMock) return

        val eventQueue = param.thisObject

        // 排除 RealSensorListener 避免递归
        try {
            val listener = XposedHelpers.getObjectField(eventQueue, "mListener")
            if (listener != null && listener.javaClass.name.contains("RealSensorListener")) {
                return
            }
        } catch (e: Throwable) {
            // 忽略字段缺失
        }

        val handle = param.args[0] as Int
        val values = param.args[1] as FloatArray

        // handle -> type 映射
        if (!sensorHandleMap.containsKey(handle)) {
            try {
                var sensor: Sensor? = null
                // 方法1: mActiveSensors (SparseArray<Sensor>)
                val activeSensors = XposedHelpers.getObjectField(eventQueue, "mActiveSensors") as? SparseArray<*>
                if (activeSensors != null) {
                    sensor = activeSensors.get(handle) as? Sensor
                }
                // 方法2: mSensors (SparseArray<Sensor>)
                if (sensor == null) {
                    val mSensors = XposedHelpers.getObjectField(eventQueue, "mSensors") as? SparseArray<*>
                    if (mSensors != null) {
                        sensor = mSensors.get(handle) as? Sensor
                    }
                }
                // 方法3: 回退到 mManager
                if (sensor == null) {
                    val manager = XposedHelpers.getObjectField(eventQueue, "mManager")
                    if (manager != null) {
                        val sensors = XposedHelpers.callMethod(manager, "getSensorList", Sensor.TYPE_ALL) as List<*>
                        for (s in sensors) {
                            val h = XposedHelpers.getIntField(s, "mHandle")
                            if (h == handle) {
                                sensor = s as Sensor
                                break
                            }
                        }
                    }
                }
                if (sensor != null) {
                    sensorHandleMap[handle] = sensor.type
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("Queue: Mapped handle $handle to type ${sensor.type}")
                    }
                }
            } catch (e: Throwable) {
                Logger.error("Error identifying sensor handle $handle", e)
            }
        }

        val type = sensorHandleMap[handle] ?: return

        // 节流：doze/screen off/background 时降低频率
        val minIntervalMs = when {
            FakeLoc.isDeviceIdleMode -> FakeLoc.sensorData.dozeSensorIntervalMs
            FakeLoc.isScreenOff -> 300L
            FakeLoc.isAppInBackground -> 200L
            else -> 0L
        }
        if (minIntervalMs > 0L) {
            val nowMs = SystemClock.elapsedRealtime()
            val lastMs = lastHookTimeMap[handle] ?: 0L
            if (nowMs - lastMs < minIntervalMs) {
                param.result = 0
                return
            }
            lastHookTimeMap[handle] = nowMs
        }

        val nowMs = SystemClock.elapsedRealtime()

        // 运动传感器：in-place 覆盖 values
        if (isMotionSensor(type)) {
            if (FakeLoc.sensorData.enableStepCountMock) {
                if (FakeLoc.sensorData.keepWalkingWaveform) {
                    getMockMotionValues(type, nowMs, values)
                } else {
                    getMockStaticValues(type, nowMs, values)
                }
            } else if (FakeLoc.sensorData.enableShakeMock && type == Sensor.TYPE_ACCELEROMETER) {
                getMockShakeAccelValues(nowMs, values)
            }
        } else if (type == Sensor.TYPE_STEP_COUNTER) {
            if (FakeLoc.sensorData.enableStepCountMock) {
                // 用自增计数器覆盖，避免真实步数泄漏
                val valueToUse = if (mCurrentStepCount > 0) mCurrentStepCount else values[0].toLong()
                values[0] = valueToUse.toFloat()
            }
        } else if (type == Sensor.TYPE_STEP_DETECTOR) {
            if (FakeLoc.sensorData.enableStepCountMock) {
                // 抑制真实步检测事件，避免双重计数
                param.result = 1
            }
        }
    }

    // ==================== SystemSensorManager 层 hook ====================

    private fun hookSystemSensorManager(classLoader: ClassLoader) {
        val cSystemSensorManager = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager", classLoader)
        if (cSystemSensorManager == null) {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to find SystemSensorManager")
            }
            return
        }

        val hookRegisterListenerImpl = beforeHook {
            val listener = args[0] as SensorEventListener
            // 排除 RealSensorListener
            if (listener.javaClass.name.contains("RealSensorListener")) {
                return@beforeHook
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("RegisterListenerImpl: $listener, sensor: ${args[1]}")
            }

            val sensor = args[1] as? Sensor ?: return@beforeHook

            // 缓存 handle -> type
            try {
                val handle = XposedHelpers.getIntField(sensor, "mHandle")
                sensorHandleMap[handle] = sensor.type
            } catch (e: Throwable) {
                // 忽略
            }

            // listenerMap 支持多传感器
            val sensors = listenerMap.getOrPut(listener) { CopyOnWriteArrayList() }
            if (sensors.none { it.type == sensor.type }) {
                sensors.add(sensor)
            }

            // onSensorChanged hook：用 event.sensor.type 定位类型（而非 listenerMap 查找）
            listener.javaClass.onceHookAllMethod("onSensorChanged", beforeHook {
                if (!FakeLoc.sensorData.hookSensor) return@beforeHook

                val event = args[0] as? SensorEvent ?: return@beforeHook
                val sensorType = event.sensor.type

                event.timestamp = SystemClock.elapsedRealtimeNanos()
                event.accuracy = 3

                val nowMs = SystemClock.elapsedRealtime()

                if (isMotionSensor(sensorType)) {
                    if (FakeLoc.sensorData.enableStepCountMock) {
                        if (FakeLoc.sensorData.keepWalkingWaveform) {
                            getMockMotionValues(sensorType, nowMs, event.values)
                        } else {
                            getMockStaticValues(sensorType, nowMs, event.values)
                        }
                    } else if (FakeLoc.sensorData.enableShakeMock && sensorType == Sensor.TYPE_ACCELEROMETER) {
                        getMockShakeAccelValues(nowMs, event.values)
                    }
                } else if (sensorType == Sensor.TYPE_STEP_COUNTER) {
                    if (FakeLoc.sensorData.enableStepCountMock) {
                        val valueToUse = if (mCurrentStepCount > 0) mCurrentStepCount else event.values[0].toLong()
                        event.values[0] = valueToUse.toFloat()
                    }
                }
            })
        }
        cSystemSensorManager.declaredMethods.filter {
            it.name == "registerListenerImpl" && it.parameterTypes.isNotEmpty()
                    && it.parameterTypes[0] == SensorEventListener::class.java
                    && it.parameterTypes[1] == Sensor::class.java
        }.forEach {
            it.onceHook(hookRegisterListenerImpl)
        }

        val hookUnregisterListenerImpl = beforeHook {
            val listener = args[0] as SensorEventListener
            if (FakeLoc.enableDebugLog) {
                Logger.debug("UnregisterListenerImpl: $listener")
            }
            val sensor = if (args.size > 1) args[1] as? Sensor else null
            if (sensor == null) {
                listenerMap.remove(listener)
            } else {
                listenerMap[listener]?.removeIf { it.type == sensor.type }
                if (listenerMap[listener]?.isEmpty() == true) {
                    listenerMap.remove(listener)
                }
            }
        }
        cSystemSensorManager.declaredMethods.filter {
            it.name == "unregisterListenerImpl" && it.parameterTypes.isNotEmpty()
                    && it.parameterTypes[0] == SensorEventListener::class.java
        }.forEach {
            it.onceHook(hookUnregisterListenerImpl)
        }

        cSystemSensorManager.hookAllMethods("getSensorList", afterHook {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getSensorList: type: ${args[0]} -> $result")
            }
        })
        cSystemSensorManager.hookAllMethods("getFullSensorsList", afterHook {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getFullSensorsList-> $result")
            }
        })
    }

    // ==================== Mock 值生成 ====================

    private fun isMotionSensor(type: Int): Boolean {
        return type == Sensor.TYPE_ACCELEROMETER ||
            type == Sensor.TYPE_LINEAR_ACCELERATION ||
            type == Sensor.TYPE_GRAVITY ||
            type == Sensor.TYPE_GYROSCOPE ||
            type == Sensor.TYPE_MAGNETIC_FIELD ||
            type == Sensor.TYPE_ORIENTATION ||
            type == Sensor.TYPE_ROTATION_VECTOR ||
            type == Sensor.TYPE_GAME_ROTATION_VECTOR ||
            type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
    }

    private fun getMockMotionValues(sensorType: Int, timeMs: Long, outValues: FloatArray) {
        ensureGaitSimulator()
        val simulator = gaitSimulator

        if (simulator != null) {
            // 使用 mockWalk 录制数据解算的步态（Fourier+Pulse+抖动+jerk+drift+tremor）
            simulator.currentState = GaitSimulator.State.RUNNING
            val tSec = timeMs / 1000.0f
            val (accel, gyro, gravity, linear) = simulator.getMotionValues(tSec)

            when (sensorType) {
                Sensor.TYPE_ACCELEROMETER -> {
                    outValues[0] = accel[0]
                    outValues[1] = accel[1]
                    outValues[2] = accel[2]
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    outValues[0] = linear[0]
                    outValues[1] = linear[1]
                    outValues[2] = linear[2]
                }
                Sensor.TYPE_GRAVITY -> {
                    outValues[0] = gravity[0]
                    outValues[1] = gravity[1]
                    outValues[2] = gravity[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    outValues[0] = gyro[0]
                    outValues[1] = gyro[1]
                    outValues[2] = gyro[2]
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    outValues[0] = 25.0f + (Random.nextFloat() - 0.5f) * 0.15f
                    outValues[1] = 2.0f + (Random.nextFloat() - 0.5f) * 0.15f
                    outValues[2] = -42.0f + (Random.nextFloat() - 0.5f) * 0.15f
                }
                Sensor.TYPE_ORIENTATION -> {
                    outValues[0] = cachedEuler[0]
                    outValues[1] = cachedEuler[1]
                    outValues[2] = cachedEuler[2]
                }
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                    outValues[0] = cachedQuat[0]
                    outValues[1] = cachedQuat[1]
                    outValues[2] = cachedQuat[2]
                    if (outValues.size >= 4) outValues[3] = cachedQuat[3]
                }
            }
            updatePoseFromGyro(timeMs)
            return
        }

        // 回退：无录制数据时使用简化波形
        val t = timeMs / 1000.0
        val freq = FakeLoc.gaitData.gaitFreq.toDouble()
        val amp = FakeLoc.gaitData.gaitAmpScale.toDouble()
        val phase = 2.0 * PI * freq * t

        when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> {
                outValues[0] = (amp * 2.0 * sin(phase)).toFloat()
                outValues[1] = (amp * 8.0 * sin(phase + PI / 2)).toFloat()
                outValues[2] = (9.81f + amp * 4.0 * sin(phase)).toFloat()
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                outValues[0] = (amp * 2.0 * sin(phase)).toFloat()
                outValues[1] = (amp * 8.0 * sin(phase + PI / 2)).toFloat()
                outValues[2] = (amp * 4.0 * sin(phase)).toFloat()
            }
            Sensor.TYPE_GRAVITY -> {
                outValues[0] = (Random.nextFloat() - 0.5f) * 0.03f
                outValues[1] = -9.81f + (Random.nextFloat() - 0.5f) * 0.03f
                outValues[2] = (Random.nextFloat() - 0.5f) * 0.03f
            }
            Sensor.TYPE_GYROSCOPE -> {
                outValues[0] = (amp * 0.1 * sin(phase + 1.0)).toFloat()
                outValues[1] = (amp * 0.05 * sin(phase + 2.0)).toFloat()
                outValues[2] = (amp * 0.03 * sin(phase)).toFloat()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                outValues[0] = 25.0f + (Random.nextFloat() - 0.5f) * 0.15f
                outValues[1] = 2.0f + (Random.nextFloat() - 0.5f) * 0.15f
                outValues[2] = -42.0f + (Random.nextFloat() - 0.5f) * 0.15f
            }
            Sensor.TYPE_ORIENTATION -> {
                outValues[0] = cachedEuler[0]
                outValues[1] = cachedEuler[1]
                outValues[2] = cachedEuler[2]
            }
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                outValues[0] = cachedQuat[0]
                outValues[1] = cachedQuat[1]
                outValues[2] = cachedQuat[2]
                if (outValues.size >= 4) outValues[3] = cachedQuat[3]
            }
        }
        updatePoseFromGyro(timeMs)
    }

    private fun getMockStaticValues(sensorType: Int, timeMs: Long, outValues: FloatArray) {
        when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> {
                outValues[0] = (Random.nextFloat() - 0.5f) * 0.06f
                outValues[1] = -9.81f + (Random.nextFloat() - 0.5f) * 0.06f
                outValues[2] = (Random.nextFloat() - 0.5f) * 0.06f
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                outValues[0] = (Random.nextFloat() - 0.5f) * 0.05f
                outValues[1] = (Random.nextFloat() - 0.5f) * 0.05f
                outValues[2] = (Random.nextFloat() - 0.5f) * 0.05f
            }
            Sensor.TYPE_GRAVITY -> {
                outValues[0] = (Random.nextFloat() - 0.5f) * 0.03f
                outValues[1] = -9.81f + (Random.nextFloat() - 0.5f) * 0.03f
                outValues[2] = (Random.nextFloat() - 0.5f) * 0.03f
            }
            Sensor.TYPE_GYROSCOPE -> {
                outValues[0] = (Random.nextFloat() - 0.5f) * 0.02f
                outValues[1] = (Random.nextFloat() - 0.5f) * 0.02f
                outValues[2] = (Random.nextFloat() - 0.5f) * 0.02f
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                outValues[0] = 25.0f + (Random.nextFloat() - 0.5f) * 0.15f
                outValues[1] = 2.0f + (Random.nextFloat() - 0.5f) * 0.15f
                outValues[2] = -42.0f + (Random.nextFloat() - 0.5f) * 0.15f
            }
            Sensor.TYPE_ORIENTATION -> {
                outValues[0] = cachedEuler[0]
                outValues[1] = cachedEuler[1]
                outValues[2] = cachedEuler[2]
            }
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                outValues[0] = cachedQuat[0]
                outValues[1] = cachedQuat[1]
                outValues[2] = cachedQuat[2]
                if (outValues.size >= 4) outValues[3] = cachedQuat[3]
            }
        }

        updatePoseFromGyro(timeMs)
    }

    private fun getMockShakeAccelValues(timeMs: Long, outValues: FloatArray) {
        val phase = (2.0 * PI * 7.5 * timeMs / 1000.0).toFloat()
        val amp = 18.0f * sin(phase)
        val noise = (Random.nextFloat() - 0.5f) * 2.5f
        outValues[0] = amp + noise
        outValues[1] = -9.81f + amp * 0.3f + noise
        outValues[2] = noise
    }

    private fun updatePoseFromGyro(timeMs: Long) {
        val last = lastPoseSampleMs
        val deltaMs = if (last <= 0L) 0L else (timeMs - last).coerceIn(0L, 200L)
        lastPoseSampleMs = timeMs
        if (deltaMs > 0L) {
            val dt = deltaMs.toFloat() / 1000.0f
            val drift = (Random.nextFloat() - 0.5f) * 0.01f
            yawRad = wrapPi(yawRad + drift * dt)
        }
        // 简化姿态：重力沿 -Y，yaw 旋转
        computeSimplePose(yawRad, cachedQuat, cachedMag, cachedEuler)
    }

    private fun computeSimplePose(yaw: Float, outQuat: FloatArray, outMag: FloatArray, outEuler: FloatArray) {
        // 四元数：先对齐 Z→-Y（重力方向），再绕 Y 旋转 yaw
        val half = yaw * 0.5f
        val s = sin(half)
        val c = cos(half)
        // q = qAlign * qYaw，简化为绕 Z 轴旋转
        outQuat[0] = 0.0f
        outQuat[1] = 0.0f
        outQuat[2] = s
        outQuat[3] = c

        // 磁场（世界坐标旋转到设备坐标）
        val bx = 25.0f
        val by = 2.0f
        val bz = -42.0f
        // 绕 Z 轴旋转
        val cosY = cos(yaw)
        val sinY = sin(yaw)
        outMag[0] = bx * cosY - by * sinY
        outMag[1] = bx * sinY + by * cosY
        outMag[2] = bz

        // 欧拉角
        val radToDeg = (180.0f / PI.toFloat())
        outEuler[0] = ((yaw * radToDeg) % 360.0f + 360.0f) % 360.0f
        outEuler[1] = 0.0f
        outEuler[2] = 0.0f
    }

    private fun wrapPi(v: Float): Float {
        var x = v
        val twoPi = (2.0 * PI).toFloat()
        val pi = PI.toFloat()
        while (x > pi) x -= twoPi
        while (x < -pi) x += twoPi
        return x
    }

    // ==================== 步数计数器更新（由外部触发） ====================

    /**
     * 由配置加载或定时器调用，更新步数目标。
     * 步数计数器只增不减，达到目标后停止。
     */
    fun updateStepGoal() {
        val enableStep = FakeLoc.sensorData.enableStepCountMock
        val userStepCount = FakeLoc.sensorData.stepCount.toLong()

        val stepMockJustEnabled = enableStep && !mWasStepMockEnabled
        mWasStepMockEnabled = enableStep
        val stepCountChanged = FakeLoc.sensorData.stepCount != mLastConfiguredStepCount

        if (enableStep && (stepMockJustEnabled || stepCountChanged)) {
            mStepGoalEnd = mCurrentStepCount + userStepCount
            mLastConfiguredStepCount = FakeLoc.sensorData.stepCount
            Logger.info("SystemSensorManagerHook: Step goal: current=$mCurrentStepCount + add=$userStepCount => end=$mStepGoalEnd")
        }
    }

    /**
     * 按时间增量推进步数计数器。应在定时循环中调用。
     * 返回新增步数（用于 step detector 分发）。
     */
    fun advanceStepCounter(deltaMs: Long): Long {
        if (!FakeLoc.sensorData.enableStepCountMock || mCurrentStepCount >= mStepGoalEnd) {
            return 0L
        }
        val freqHz = FakeLoc.gaitData.gaitFreq.coerceIn(0.6f, 4.0f).toDouble()
        val stepIntervalMs = 1000.0 / freqHz
        val add = (deltaMs / stepIntervalMs).toLong()
        if (add <= 0L) return 0L
        val newCount = (mCurrentStepCount + add).coerceAtMost(mStepGoalEnd)
        val stepsAdded = newCount - mCurrentStepCount
        mCurrentStepCount = newCount
        return stepsAdded
    }

    fun getCurrentStepCount(): Long = mCurrentStepCount

    // ==================== Geo Sensor 解锁 ====================

    private fun unlockGeoSensor(classLoader: ClassLoader) {
        val cSystemConfig = XposedHelpers.findClassIfExists("com.android.server.SystemConfig", classLoader)
            ?: return

        val openGLVersion = run {
            val cSystemProperties = XposedHelpers.findClassIfExists("android.os.SystemProperties", classLoader)
                ?: return@run 0
            XposedHelpers.callStaticMethod(cSystemProperties, "getInt", "ro.opengles.version", FeatureInfo.GL_ES_VERSION_UNDEFINED) as Int
        }

        cSystemConfig.hookMethodAfter("getAvailableFeatures") {
            val features = result as ArrayMap<String, FeatureInfo>
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getAvailableFeatures: ${features.keys}")
            }

            if (!features.contains(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
                val gyroFeature = FeatureInfo()
                gyroFeature.name = PackageManager.FEATURE_SENSOR_GYROSCOPE
                gyroFeature.reqGlEsVersion = openGLVersion
                features[PackageManager.FEATURE_SENSOR_GYROSCOPE] = gyroFeature
            }
            if (!features.contains(PackageManager.FEATURE_SENSOR_COMPASS)) {
                val compassFeature = FeatureInfo()
                compassFeature.name = PackageManager.FEATURE_SENSOR_COMPASS
                compassFeature.reqGlEsVersion = openGLVersion
                features[PackageManager.FEATURE_SENSOR_COMPASS] = compassFeature
            }

            if (!features.contains(PackageManager.FEATURE_SENSOR_ACCELEROMETER)) {
                val accelerometerFeature = FeatureInfo()
                accelerometerFeature.name = PackageManager.FEATURE_SENSOR_ACCELEROMETER
                accelerometerFeature.reqGlEsVersion = openGLVersion
                features[PackageManager.FEATURE_SENSOR_ACCELEROMETER] = accelerometerFeature
            }

            if (!features.contains(PackageManager.FEATURE_SENSOR_STEP_COUNTER)) {
                val lightFeature = FeatureInfo()
                lightFeature.name = PackageManager.FEATURE_SENSOR_STEP_COUNTER
                lightFeature.reqGlEsVersion = openGLVersion
                features[PackageManager.FEATURE_SENSOR_STEP_COUNTER] = lightFeature
            }

            if (!features.contains(PackageManager.FEATURE_SENSOR_STEP_DETECTOR)) {
                val lightFeature = FeatureInfo()
                lightFeature.name = PackageManager.FEATURE_SENSOR_STEP_DETECTOR
                lightFeature.reqGlEsVersion = openGLVersion
                features[PackageManager.FEATURE_SENSOR_STEP_DETECTOR] = lightFeature
            }

            result = features
        }
    }
}
