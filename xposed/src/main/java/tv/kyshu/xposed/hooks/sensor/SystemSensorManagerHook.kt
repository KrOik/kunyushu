@file:Suppress("UNCHECKED_CAST")
package tv.kyshu.xposed.hooks.sensor

import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorEvent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.ArrayMap
import android.util.SparseArray
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.stability.DomainGuard
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
import tv.kyshu.xposed.utils.safeCacheDir
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale
import kotlin.math.roundToLong

// https://github.com/Frazew/VirtualSensor/blob/master/app/src/main/java/fr/frazew/virtualgyroscope/XposedMod.java#L298
object SystemSensorManagerHook {
    private const val DEFAULT_SENSOR_PUSH_INTERVAL_MS = 500L
    private const val STEP_PUSH_IDLE_LIMIT = 20
    private const val STEP_PUSH_ERROR_LIMIT = 3
    private const val SENSOR_SHAPE_FIELD_LIMIT = 32
    private const val SENSOR_SHAPE_METHOD_LIMIT = 24
    private const val SENSOR_SHAPE_VALUE_LIMIT = 12

    private val diagnosticKeys = ConcurrentHashMap.newKeySet<String>()
    private fun logOnce(key: String, message: String) {
        if (diagnosticKeys.add(key)) {
            Logger.info(message)
        }
    }
    private fun logOnce(key: String, message: () -> String) {
        if (diagnosticKeys.add(key)) {
            Logger.info(message())
        }
    }

    // listener -> 该 listener 注册的所有 Sensor（支持多传感器注册）
    private val listenerMap = ConcurrentHashMap<SensorEventListener, CopyOnWriteArrayList<Sensor>>()
    private val listenerDeliveryHandlers = ConcurrentHashMap<SensorEventListener, Handler>()
    private val mainSensorDeliveryHandler: Handler? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { Handler(Looper.getMainLooper()) }
            .onFailure {
                logOnce("stepPusher.mainHandler.failed.${it.javaClass.simpleName}") {
                    "[SensorHook][StepPusher][Shape] main handler unavailable: ${it.javaClass.simpleName}:${it.message ?: ""}"
                }
            }
            .getOrNull()
    }
    // sensor handle -> sensor type 缓存（dispatchSensorEvent 用 handle 定位类型）
    private val sensorHandleMap = ConcurrentHashMap<Int, Int>()
    // sensor handle -> 上次 hook 时间（节流用）
    private val lastHookTimeMap = ConcurrentHashMap<Int, Long>()
    // 步数自增计数器（不依赖真实传感器事件）
    @Volatile
    private var mCurrentStepCount: Long = 0L
    @Volatile
    private var mStepGoalEnd: Long = 0L
    @Volatile
    private var mLastConfiguredStepCount: Int = -1
    @Volatile
    private var mWasStepMockEnabled: Boolean = false
    // 步数持久化（避免进程重启归零）
    private val stepCountFile: File? by lazy {
        FakeLoc.getContext()?.safeCacheDir()?.let { dir ->
            if (dir.exists() || dir.mkdirs()) File(dir, "step_counter.dat") else null
        }
    }
    private var lastStepSaveMs: Long = 0L
    private var stepSaveCounter: Int = 0

    // 磁场漂移（随机游走使基值缓慢变化）
    private val magBase = floatArrayOf(25.0f, 2.0f, -42.0f)
    private var lastMagUpdateMs: Long = 0L
    private var lastStepAdvanceMs: Long = 0L
    private var stepAdvanceCarryMs: Double = 0.0
    // 步数主动事件推送线程
    @Volatile
    private var stepEventThread: Thread? = null
    private val stepEventLock = Any()


    // 姿态缓存
    private var yawRad = 0.0f
    private var lastPoseSampleMs = 0L
    private val cachedQuat = FloatArray(4).apply { this[3] = 1.0f }
    private val cachedMag = FloatArray(3)
    private val cachedEuler = FloatArray(3)

    operator fun invoke(classLoader: ClassLoader) {
        Logger.debug("[SensorHook] install start: hookSensor=${FakeLoc.sensorData.hookSensor}, motion=${FakeLoc.sensorData.enableMotionMock}, step=${FakeLoc.sensorData.enableStepCountMock}, shake=${FakeLoc.sensorData.enableShakeMock}, keepWalking=${FakeLoc.sensorData.keepWalkingWaveform}")
        loadStepCount()
        ensureGaitSimulator()
        unlockGeoSensor(classLoader)
        hookSystemSensorManager(classLoader)
        hookSystemSensorManagerQueue(classLoader)
    }
    // 步态仿真器（默认从 assets/gait_default.json 加载录制数据解算）
    @Volatile
    private var gaitSimulator: GaitSimulator? = null
    private var lastGaitJson = ""
    private var lastGaitLoadAt = 0L


    /**
     * 加载/刷新步态仿真器。
     * 优先级：用户自定义 gaitParamsJson > 默认 assets/gait_default.json（录制数据解算）
     */
    private fun ensureGaitSimulator() {
        val now = System.currentTimeMillis()
        val userJson = FakeLoc.gaitData.gaitParamsJson.trim()
        val jsonChanged = userJson != lastGaitJson
        // 至少 5 秒间隔，避免频繁加载；配置变化不受节流影响。
        if (!jsonChanged && gaitSimulator != null && now - lastGaitLoadAt < 5000L) return

        if (userJson.isNotEmpty() && (jsonChanged || gaitSimulator == null)) {
            // 用户自定义 JSON
            val next = GaitSimulator.loadFromJson(userJson)
            lastGaitLoadAt = now
            if (next != null) {
                gaitSimulator = next
                lastGaitJson = userJson
                Logger.info("GaitSimulator: loaded user-defined gait params")
            } else {
                Logger.warn("GaitSimulator: user-defined gait params invalid, keeping previous simulator")
            }
        } else if (gaitSimulator == null || jsonChanged) {
            // 默认从 assets 加载录制数据解算的步态
            gaitSimulator = GaitSimulator.loadDefault(FakeLoc.getContext())
            lastGaitJson = ""
            lastGaitLoadAt = now
            if (gaitSimulator != null) {
                Logger.info("GaitSimulator: loaded default recorded gait from assets")
            } else {
                Logger.warn("GaitSimulator: default asset load failed, will retry")
            }
        }
    }

    private fun recordClassShape(label: String, clazz: Class<*>, reason: String) {
        logOnce("shape.class.$label.${clazz.name}.$reason") {
            "[SensorHook][Shape] $label reason=$reason class=${clazz.name} hierarchy=${describeClassHierarchy(clazz)}"
        }
    }

    private fun recordQueueShape(eventQueue: Any, reason: String, handle: Int) {
        val clazz = eventQueue.javaClass
        logOnce("shape.queue.${clazz.name}.$reason.$handle") {
            "[SensorHook][Shape] queue reason=$reason handle=$handle class=${clazz.name} " +
                "runtimeFields=${describeRuntimeFields(eventQueue, handle)} " +
                "hierarchy=${describeClassHierarchy(clazz)} knownHandles=${describeKnownHandleMap()}"
        }
    }

    private fun describeClassHierarchy(clazz: Class<*>): String {
        val parts = ArrayList<String>()
        var current: Class<*>? = clazz
        var depth = 0
        while (current != null && current != Any::class.java && depth < 6) {
            val fields = current.declaredFields
                .sortedBy { it.name }
                .joinToString(limit = SENSOR_SHAPE_FIELD_LIMIT, truncated = "...") {
                    "${it.name}:${shortClassName(it.type)}"
                }
            val methods = current.declaredMethods
                .filter { isInterestingSensorMethod(it.name) }
                .sortedWith(compareBy<Method> { it.name }.thenBy { it.parameterTypes.size })
                .joinToString(limit = SENSOR_SHAPE_METHOD_LIMIT, truncated = "...") { methodSignature(it) }
            parts += "${current.name}{fields=[$fields], methods=[$methods]}"
            current = current.superclass
            depth += 1
        }
        return parts.joinToString(" <- ")
    }

    private fun describeRuntimeFields(owner: Any, handle: Int? = null): String {
        val fields = allFieldsInHierarchy(owner.javaClass)
            .filter {
                it.name.contains("sensor", ignoreCase = true) ||
                    it.name.contains("listener", ignoreCase = true) ||
                    it.name == "mManager" ||
                    it.name == "this\$0" ||
                    SparseArray::class.java.isAssignableFrom(it.type)
            }
            .distinctBy { "${it.declaringClass.name}#${it.name}" }
            .take(SENSOR_SHAPE_FIELD_LIMIT)

        if (fields.isEmpty()) return "(no sensor/listener/sparse fields)"
        return fields.joinToString("; ") { field ->
            runCatching {
                field.isAccessible = true
                val value = field.get(owner)
                "${field.declaringClass.simpleName}.${field.name}:${describeFieldValue(value, handle)}"
            }.getOrElse {
                "${field.declaringClass.simpleName}.${field.name}:<${it.javaClass.simpleName}:${it.message ?: ""}>"
            }
        }
    }

    private fun describeFieldValue(value: Any?, handle: Int? = null): String {
        return when (value) {
            null -> "null"
            is SparseArray<*> -> sparseArraySummary(value, handle)
            is Sensor -> "Sensor(${sensorSummary(value)})"
            is Collection<*> -> {
                val sample = value.take(SENSOR_SHAPE_VALUE_LIMIT).joinToString { objectSummary(it) }
                "${value.javaClass.name}(size=${value.size}, sample=[$sample])"
            }
            is Array<*> -> {
                val sample = value.take(SENSOR_SHAPE_VALUE_LIMIT).joinToString { objectSummary(it) }
                "${value.javaClass.name}(size=${value.size}, sample=[$sample])"
            }
            else -> value.javaClass.name
        }
    }

    private fun sparseArraySummary(array: SparseArray<*>, handle: Int? = null): String {
        val size = array.size()
        val keys = (0 until minOf(size, SENSOR_SHAPE_VALUE_LIMIT)).joinToString(prefix = "[", postfix = "]") {
            array.keyAt(it).toString()
        }
        val valueTypes = (0 until minOf(size, SENSOR_SHAPE_VALUE_LIMIT)).joinToString(prefix = "[", postfix = "]") {
            objectSummary(array.valueAt(it))
        }
        val hit = handle?.let { runCatching { array.get(it) }.getOrNull() }
        return "${array.javaClass.name}(size=$size, keys=$keys, valueTypes=$valueTypes, hit=${objectSummary(hit)})"
    }

    private fun objectSummary(value: Any?): String {
        return when (value) {
            null -> "null"
            is Sensor -> "Sensor(type=${value.type}, handle=${readSensorHandle(value) ?: "?"}, name=${value.name})"
            else -> value.javaClass.name
        }
    }

    private fun allFieldsInHierarchy(clazz: Class<*>): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = clazz
        var depth = 0
        while (current != null && current != Any::class.java && depth < 8) {
            fields += current.declaredFields
            current = current.superclass
            depth += 1
        }
        return fields
    }

    private fun findFieldInHierarchy(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        var depth = 0
        while (current != null && current != Any::class.java && depth < 8) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
                depth += 1
            }
        }
        return null
    }

    private fun isInterestingSensorMethod(name: String): Boolean {
        return name == "dispatchSensorEvent" ||
            name == "registerListenerImpl" ||
            name == "unregisterListenerImpl" ||
            name == "getSensorList" ||
            name == "getFullSensorList" ||
            name == "getFullSensorsList" ||
            name == "getDefaultSensor" ||
            name.contains("Sensor", ignoreCase = true) ||
            name.contains("Listener", ignoreCase = true)
    }

    private fun methodSignature(method: Method): String {
        val params = method.parameterTypes.joinToString(",") { shortClassName(it) }
        return "${method.name}($params):${shortClassName(method.returnType)}"
    }

    private fun shortClassName(clazz: Class<*>): String {
        return clazz.simpleName.ifEmpty { clazz.name }
    }

    private fun readSensorHandle(sensor: Sensor): Int? {
        return runCatching { XposedHelpers.getIntField(sensor, "mHandle") }.getOrNull()
    }

    private fun sensorSummary(sensor: Sensor): String {
        val stringType = runCatching { sensor.stringType }.getOrNull()
        val reportingMode = runCatching { sensor.reportingMode }.getOrNull()
        val wakeUp = runCatching { sensor.isWakeUpSensor }.getOrNull()
        val maxDelay = runCatching { sensor.maxDelay }.getOrNull()
        return "type=${sensor.type}, handle=${readSensorHandle(sensor) ?: "?"}, name=${sensor.name}, " +
            "vendor=${sensor.vendor}, stringType=${stringType ?: ""}, reportingMode=${reportingMode ?: "?"}, " +
            "wakeUp=${wakeUp ?: "?"}, minDelay=${sensor.minDelay}, maxDelay=${maxDelay ?: "?"}"
    }

    private fun summarizeSensorList(sensors: List<*>): String {
        val sensorObjects = sensors.filterIsInstance<Sensor>()
        val typeCounts = sensorObjects.groupingBy { it.type }.eachCount()
            .entries
            .sortedBy { it.key }
            .joinToString(limit = SENSOR_SHAPE_VALUE_LIMIT, truncated = "...") { "${it.key}:${it.value}" }
        val targets = sensorObjects
            .filter { isMotionSensor(it.type) || it.type == Sensor.TYPE_STEP_COUNTER || it.type == Sensor.TYPE_STEP_DETECTOR }
            .take(SENSOR_SHAPE_VALUE_LIMIT)
            .joinToString(" | ") { sensorSummary(it) }
        return "size=${sensors.size}, sensorObjects=${sensorObjects.size}, typeCounts=[$typeCounts], targets=[$targets]"
    }

    private fun describeKnownHandleMap(): String {
        return sensorHandleMap.entries
            .sortedBy { it.key }
            .take(SENSOR_SHAPE_VALUE_LIMIT)
            .joinToString(prefix = "[", postfix = "]") { "${it.key}->${it.value}" }
    }

    private fun listenerMapSummary(): String {
        val entries = listenerMap.entries.toList()
        var stepCounterListeners = 0
        var stepDetectorListeners = 0
        var motionListeners = 0
        val typeCounts = HashMap<Int, Int>()
        entries.forEach { entry ->
            val types = entry.value.map { it.type }
            if (types.contains(Sensor.TYPE_STEP_COUNTER)) stepCounterListeners += 1
            if (types.contains(Sensor.TYPE_STEP_DETECTOR)) stepDetectorListeners += 1
            if (types.any { isMotionSensor(it) }) motionListeners += 1
            types.forEach { typeCounts[it] = (typeCounts[it] ?: 0) + 1 }
        }
        val counts = typeCounts.entries.sortedBy { it.key }
            .joinToString(limit = SENSOR_SHAPE_VALUE_LIMIT, truncated = "...") { "${it.key}:${it.value}" }
        val samples = entries.take(5).joinToString(" | ") { entry ->
            val types = entry.value.map { it.type }.distinct().joinToString(",")
            "${entry.key.javaClass.name}[$types]"
        }
        return "listeners=${entries.size}, stepCounter=$stepCounterListeners, stepDetector=$stepDetectorListeners, " +
            "motion=$motionListeners, typeCounts=[$counts], samples=[$samples]"
    }

    private fun stepPusherState(): String {
        return "hookSensor=${FakeLoc.sensorData.hookSensor}, enableMotion=${FakeLoc.sensorData.enableMotionMock}, " +
            "enableStep=${FakeLoc.sensorData.enableStepCountMock}, effectiveMotion=${isEffectiveMotionMockEnabled()}, current=$mCurrentStepCount, " +
            "goal=$mStepGoalEnd, configured=${FakeLoc.sensorData.stepCount}, threadAlive=${stepEventThread?.isAlive == true}, " +
            listenerMapSummary()
    }

    private fun isActiveSensorPusherEnabled(): Boolean {
        return FakeLoc.sensorData.enableStepCountMock ||
            FakeLoc.sensorData.enableMotionMock ||
            FakeLoc.sensorData.enableShakeMock
    }

    private fun isEffectiveMotionMockEnabled(): Boolean {
        return FakeLoc.sensorData.enableMotionMock || FakeLoc.sensorData.enableStepCountMock
    }

    internal fun stepPushIntervalMsForFrequency(freqHz: Float): Long {
        return (1000.0 / freqHz.coerceIn(0.6f, 4.0f)).roundToLong().coerceAtLeast(1L)
    }

    private fun currentStepPushIntervalMs(): Long {
        return if (FakeLoc.sensorData.enableStepCountMock) {
            stepPushIntervalMsForFrequency(FakeLoc.gaitData.gaitFreq)
        } else {
            DEFAULT_SENSOR_PUSH_INTERVAL_MS
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
        Logger.info("[SensorHook] queue known candidates=${candidates.map { it.name }}")

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
            Logger.error("[SensorHook] FATAL: Failed to find any SensorEventQueue class; passive dispatch hook will NOT work")
            return
        }

        candidates.distinct().forEach { clazz ->
            recordClassShape("queueCandidate", clazz, "install")
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
                            runCatching {
                                handleDispatchSensorEvent(param)
                            }.onFailure {
                                Logger.warn("[SensorHook] dispatchSensorEvent callback failed; disabling SENSOR domain", it)
                                DomainGuard.disableDomain(DomainGuard.Domain.SENSOR,
                                    "dispatch callback failed: ${it.javaClass.simpleName}: ${it.message ?: ""}")
                                DomainGuard.reportCrash(DomainGuard.Domain.SENSOR, it)
                            }
                        }
                    })
                Logger.info("[SensorHook] passive dispatch hook installed: ${clazz.name}#dispatchSensorEvent(int,float[],int,long)")
            } catch (e: Throwable) {
                recordClassShape("queueCandidate", clazz, "direct-hook-failed-${e.javaClass.simpleName}")
                // 部分 ROM 签名不同，尝试动态查找
                try {
                    val method = clazz.declaredMethods.firstOrNull { it.name == "dispatchSensorEvent" }
                    if (method != null) {
                        XposedBridge.hookMethod(method, object : de.robv.android.xposed.XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    handleDispatchSensorEvent(param)
                                }.onFailure {
                                    Logger.warn("[SensorHook] dynamic dispatchSensorEvent callback failed; disabling SENSOR domain", it)
                                    DomainGuard.disableDomain(DomainGuard.Domain.SENSOR,
                                        "dynamic dispatch callback failed: ${it.javaClass.simpleName}: ${it.message ?: ""}")
                                    DomainGuard.reportCrash(DomainGuard.Domain.SENSOR, it)
                                }
                            }
                        })
                        Logger.info("[SensorHook] passive dispatch hook installed dynamically: ${clazz.name}#${method.name}${method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }}")
                    } else {
                        recordClassShape("queueCandidate", clazz, "dispatch-method-missing")
                        Logger.warn("[SensorHook] dispatchSensorEvent not found in candidate ${clazz.name}")
                    }
                } catch (e2: Throwable) {
                    recordClassShape("queueCandidate", clazz, "dynamic-hook-failed-${e2.javaClass.simpleName}")
                    Logger.error("[SensorHook] Failed to hook dispatchSensorEvent in ${clazz.name}", e2)
                }
            }
        }
    }

    private fun handleDispatchSensorEvent(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
        if (DomainGuard.isDomainDisabled(DomainGuard.Domain.SENSOR)) {
            logOnce("dispatch.domainDisabled", "[SensorHook] passive dispatch skipped: SENSOR domain disabled")
            return
        }
        if (!FakeLoc.sensorData.hookSensor) {
            logOnce("dispatch.disabled", "[SensorHook] passive dispatch skipped: hookSensor=false")
            return
        }
        if (!FakeLoc.sensorData.enableStepCountMock && !FakeLoc.sensorData.enableShakeMock && !FakeLoc.sensorData.enableMotionMock) {
            logOnce("dispatch.noMode", "[SensorHook] passive dispatch skipped: step/shake/motion all disabled")
            return
        }

        val eventQueue = param.thisObject

        // 排除 RealSensorListener 避免递归
        try {
            val listener = XposedHelpers.getObjectField(eventQueue, "mListener")
            if (listener != null && listener.javaClass.name.contains("RealSensorListener")) {
                logOnce("dispatch.realListener", "[SensorHook] passive dispatch ignored RealSensorListener=${listener.javaClass.name}")
                return
            }
        } catch (e: Throwable) {
            // 忽略字段缺失
        }

        val handle = param.args[0] as Int
        val values = param.args[1] as FloatArray

        // handle -> type 映射
        if (!sensorHandleMap.containsKey(handle)) {
            val sensor = findSensorForHandle(eventQueue, handle)
            if (sensor != null) {
                sensorHandleMap[handle] = sensor.type
                logOnce("dispatch.map.$handle", "[SensorHook] passive handle mapped: handle=$handle ${sensorSummary(sensor)}")
            } else {
                logOnce("dispatch.map.miss.$handle") {
                    "[SensorHook][MapMiss] passive handle map miss: handle=$handle queue=${eventQueue.javaClass.name} " +
                        "knownHandles=${describeKnownHandleMap()} ${listenerMapSummary()}"
                }
            }
        }

        val type = sensorHandleMap[handle] ?: run {
            logOnce("dispatch.unmapped.$handle") {
                "[SensorHook][MapMiss] passive dispatch skipped: unknown sensor handle=$handle queue=${eventQueue.javaClass.name}"
            }
            return
        }

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
                logOnce("dispatch.throttle.$type", "[SensorHook] passive dispatch throttled: type=$type interval=$minIntervalMs idle=${FakeLoc.isDeviceIdleMode} screenOff=${FakeLoc.isScreenOff} background=${FakeLoc.isAppInBackground}")
                return
            }
            lastHookTimeMap[handle] = nowMs
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (isMotionSensor(type)) {
            if (FakeLoc.sensorData.enableShakeMock && type == Sensor.TYPE_ACCELEROMETER) {
                getMockShakeAccelValues(nowMs, values)
                logOnce("dispatch.shake.$type", "[SensorHook] passive IMU replaced by shake waveform: type=$type")
            } else if (shouldMockMotionSensor(type)) {
                if (FakeLoc.sensorData.keepWalkingWaveform || FakeLoc.sensorData.enableStepCountMock) {
                    getMockMotionValues(type, nowMs, values)
                    logOnce("dispatch.motion.walk.$type", "[SensorHook] passive IMU replaced by walking waveform: type=$type step=${FakeLoc.sensorData.enableStepCountMock} motion=${FakeLoc.sensorData.enableMotionMock}")
                } else {
                    getMockStaticValues(type, nowMs, values)
                    logOnce("dispatch.motion.static.$type", "[SensorHook] passive IMU replaced by static waveform: type=$type")
                }
            } else {
                logOnce("dispatch.motion.pass.$type", "[SensorHook] passive IMU passed through: enableMotionMock=false type=$type")
            }
        } else if (type == Sensor.TYPE_STEP_COUNTER) {
            if (FakeLoc.sensorData.enableStepCountMock) {
                // 用自增计数器覆盖，避免真实步数泄漏
                replaceStepCounterValue(values)
                logOnce("dispatch.stepCounter", "[SensorHook] passive step counter replaced: current=$mCurrentStepCount goal=$mStepGoalEnd")
            }
        } else if (type == Sensor.TYPE_STEP_DETECTOR) {
            if (FakeLoc.sensorData.enableStepCountMock) {
                // 按步进频率概率产生步检测事件
                val freqHz = FakeLoc.gaitData.gaitFreq.coerceIn(0.6f, 4.0f)
                val probability = (freqHz * 0.5f).coerceAtMost(0.8f)
                if (kotlin.random.Random.nextFloat() < probability) {
                    values[0] = 1.0f
                    logOnce("dispatch.stepDetector.hit", "[SensorHook] passive step detector emitted: freq=$freqHz probability=$probability")
                } else {
                    param.result = 1
                    logOnce("dispatch.stepDetector.drop", "[SensorHook] passive step detector suppressed: freq=$freqHz probability=$probability")
                }
            }
        }
    }

    private fun findSensorForHandle(eventQueue: Any, handle: Int): Sensor? {
        sensorFromSparseArrayField(eventQueue, "mActiveSensors", handle)?.let { return it }
        sensorFromSparseArrayField(eventQueue, "mSensors", handle)?.let { return it }

        val managerAttempts = ArrayList<String>()
        for (managerField in arrayOf("mManager", "this\$0")) {
            val manager = getObjectFieldOrNull(eventQueue, managerField)
            if (manager == null) {
                managerAttempts += "$managerField=null"
                continue
            }
            managerAttempts += "$managerField=${manager.javaClass.name}"
            sensorFromManager(manager, handle)?.let {
                logOnce("dispatch.map.manager.$handle.$managerField") {
                    "[SensorHook] passive handle mapped from manager field=$managerField handle=$handle ${sensorSummary(it)}"
                }
                return it
            }
        }
        recordQueueShape(eventQueue, "handle-map-miss managers=${managerAttempts.joinToString("|")}", handle)
        return null
    }

    private fun sensorFromSparseArrayField(owner: Any, fieldName: String, handle: Int): Sensor? {
        return runCatching {
            val value = XposedHelpers.getObjectField(owner, fieldName)
            val sparseArray = value as? SparseArray<*>
            if (sparseArray == null) {
                logOnce("dispatch.field.nonSparse.${owner.javaClass.name}.$fieldName") {
                    "[SensorHook][Shape] sensor queue field is not SparseArray: field=$fieldName " +
                        "owner=${owner.javaClass.name} value=${describeFieldValue(value, handle)}"
                }
                return@runCatching null
            }
            val sensor = sparseArray.get(handle) as? Sensor
            if (sensor == null) {
                logOnce("dispatch.field.noHit.${owner.javaClass.name}.$fieldName.$handle") {
                    "[SensorHook][MapMiss] sensor queue field no hit: field=$fieldName handle=$handle " +
                        sparseArraySummary(sparseArray, handle)
                }
            }
            sensor
        }.onFailure {
            logOnce("dispatch.field.${owner.javaClass.name}.$fieldName") {
                "[SensorHook][Shape] sensor queue field unavailable: field=$fieldName owner=${owner.javaClass.name} " +
                    "error=${it.javaClass.simpleName}:${it.message ?: ""}"
            }
        }.getOrNull()
    }

    private fun getObjectFieldOrNull(owner: Any, fieldName: String): Any? {
        return runCatching {
            XposedHelpers.getObjectField(owner, fieldName)
        }.onFailure {
            val declared = findFieldInHierarchy(owner.javaClass, fieldName)
            logOnce("dispatch.field.${owner.javaClass.name}.$fieldName") {
                "[SensorHook][Shape] sensor queue field unavailable: field=$fieldName owner=${owner.javaClass.name} " +
                    "declared=${declared != null} error=${it.javaClass.simpleName}:${it.message ?: ""}"
            }
        }.getOrNull()
    }

    private fun sensorFromManager(manager: Any, handle: Int): Sensor? {
        val attempts = ArrayList<String>()
        var source = ""
        var sensors: List<*>? = null
        for (methodName in arrayOf("getSensorList", "getFullSensorList", "getFullSensorsList")) {
            val attempt = runCatching {
                val result = if (methodName == "getSensorList") {
                    XposedHelpers.callMethod(manager, methodName, Sensor.TYPE_ALL)
                } else {
                    XposedHelpers.callMethod(manager, methodName)
                }
                result as? List<*>
            }
            val value = attempt.getOrNull()
            if (value != null) {
                source = methodName
                sensors = value
                break
            }
            val error = attempt.exceptionOrNull()
            attempts += "$methodName:${error?.javaClass?.simpleName ?: "null"}"
        }

        val sensorList = sensors
        if (sensorList == null) {
            logOnce("manager.list.miss.${manager.javaClass.name}") {
                "[SensorHook][Shape] manager sensor list unavailable: manager=${manager.javaClass.name} " +
                    "attempts=${attempts.joinToString(",")} hierarchy=${describeClassHierarchy(manager.javaClass)}"
            }
            return null
        }

        logOnce("manager.list.${manager.javaClass.name}.$source") {
            "[SensorHook][Shape] manager sensor list loaded: manager=${manager.javaClass.name} source=$source " +
                summarizeSensorList(sensorList)
        }

        val sensor = sensorList.firstNotNullOfOrNull { candidate ->
            val sensor = candidate as? Sensor ?: return@firstNotNullOfOrNull null
            val candidateHandle = runCatching {
                XposedHelpers.getIntField(sensor, "mHandle")
            }.getOrNull()
            if (candidateHandle == handle) sensor else null
        }
        if (sensor == null) {
            logOnce("manager.handle.miss.${manager.javaClass.name}.$handle") {
                "[SensorHook][MapMiss] manager sensor list has no handle=$handle source=$source " +
                    summarizeSensorList(sensorList)
            }
        }
        return sensor
    }

    // ==================== SystemSensorManager 层 hook ====================

    private fun hookSystemSensorManager(classLoader: ClassLoader) {
        val cSystemSensorManager = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager", classLoader)
        if (cSystemSensorManager == null) {
            Logger.warn("[SensorHook] Failed to find SystemSensorManager")
            return
        }
        Logger.info("[SensorHook] SystemSensorManager found: ${cSystemSensorManager.name}, methods=${cSystemSensorManager.declaredMethods.size}")
        recordClassShape("SystemSensorManager", cSystemSensorManager, "install")

        val hookRegisterListenerImpl = beforeHook {
            val listener = args.getOrNull(0) as? SensorEventListener ?: run {
                Logger.warn("[SensorHook] registerListenerImpl skipped: listener is null or invalid")
                return@beforeHook
            }
            // 排除 RealSensorListener
            if (listener.javaClass.name.contains("RealSensorListener")) {
                logOnce("register.realListener", "[SensorHook] registerListenerImpl ignored RealSensorListener=${listener.javaClass.name}")
                return@beforeHook
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("RegisterListenerImpl: $listener, sensor: ${args[1]}")
            }

            val sensor = args[1] as? Sensor ?: run {
                logOnce("register.sensor.invalid.${listener.javaClass.name}") {
                    "[SensorHook][Register] registerListenerImpl skipped: sensor arg invalid " +
                        "listener=${listener.javaClass.name}, arg=${objectSummary(args.getOrNull(1))}, argc=${args.size}"
                }
                return@beforeHook
            }
            (args.getOrNull(3) as? Handler)?.let { handler ->
                listenerDeliveryHandlers[listener] = handler
                logOnce("register.handler.${listener.javaClass.name}.${handler.looper.thread.name}") {
                    "[SensorHook][Register] delivery handler captured: listener=${listener.javaClass.name}, " +
                        "thread=${handler.looper.thread.name}"
                }
            }
            logOnce("register.${listener.javaClass.name}.${sensor.type}") {
                "[SensorHook][Register] registerListenerImpl hit: listener=${listener.javaClass.name}, " +
                    "sensor=${sensorSummary(sensor)}, delay=${args.getOrNull(2)}, handler=${objectSummary(args.getOrNull(3))}, " +
                    "maxBatchLatency=${args.getOrNull(4)}, reservedFlags=${args.getOrNull(5)}"
            }

            // 缓存 handle -> type
            try {
                val handle = XposedHelpers.getIntField(sensor, "mHandle")
                sensorHandleMap[handle] = sensor.type
                logOnce("register.handle.$handle", "[SensorHook] register mapped handle=$handle ${sensorSummary(sensor)}")
            } catch (e: Throwable) {
                Logger.warn("[SensorHook][Shape] register failed to read sensor handle: ${sensorSummary(sensor)}", e)
            }

            // listenerMap 支持多传感器
            val sensors = listenerMap.getOrPut(listener) { CopyOnWriteArrayList() }
            if (sensors.none { it.type == sensor.type }) {
                sensors.add(sensor)
                logOnce("register.listenerMap.${listener.javaClass.name}.${sensor.type}") {
                    "[SensorHook][StepPusher] listenerMap updated after register: listener=${listener.javaClass.name}, " +
                        "sensorType=${sensor.type}, ${listenerMapSummary()}"
                }
            }
            if ((FakeLoc.sensorData.enableStepCountMock &&
                    (sensor.type == Sensor.TYPE_STEP_COUNTER || sensor.type == Sensor.TYPE_STEP_DETECTOR)) ||
                (FakeLoc.sensorData.enableMotionMock && isMotionSensor(sensor.type)) ||
                (FakeLoc.sensorData.enableShakeMock && sensor.type == Sensor.TYPE_ACCELEROMETER)
            ) {
                ensureStepEventPusher()
            }

            // onSensorChanged hook：用 event.sensor.type 定位类型（而非 listenerMap 查找）
            listener.javaClass.onceHookAllMethod("onSensorChanged", beforeHook {
                if (!FakeLoc.sensorData.hookSensor) {
                    logOnce("listener.disabled", "[SensorHook] listener onSensorChanged skipped: hookSensor=false")
                    return@beforeHook
                }

                val event = args[0] as? SensorEvent ?: return@beforeHook
                val sensorType = event.sensor.type

                event.timestamp = SystemClock.elapsedRealtimeNanos()
                event.accuracy = 3

                val nowMs = SystemClock.elapsedRealtime()

                if (isMotionSensor(sensorType)) {
                    if (FakeLoc.sensorData.enableShakeMock && sensorType == Sensor.TYPE_ACCELEROMETER) {
                        getMockShakeAccelValues(nowMs, event.values)
                        logOnce("listener.shake.$sensorType", "[SensorHook] listener IMU replaced by shake waveform: type=$sensorType")
                    } else if (shouldMockMotionSensor(sensorType)) {
                        if (FakeLoc.sensorData.keepWalkingWaveform || FakeLoc.sensorData.enableStepCountMock) {
                            getMockMotionValues(sensorType, nowMs, event.values)
                            logOnce("listener.motion.walk.$sensorType", "[SensorHook] listener IMU replaced by walking waveform: type=$sensorType step=${FakeLoc.sensorData.enableStepCountMock} motion=${FakeLoc.sensorData.enableMotionMock}")
                        } else {
                            getMockStaticValues(sensorType, nowMs, event.values)
                            logOnce("listener.motion.static.$sensorType", "[SensorHook] listener IMU replaced by static waveform: type=$sensorType")
                        }
                    } else {
                        logOnce("listener.motion.pass.$sensorType", "[SensorHook] listener IMU passed through: enableMotionMock=false type=$sensorType")
                    }
                } else if (sensorType == Sensor.TYPE_STEP_COUNTER) {
                    if (FakeLoc.sensorData.enableStepCountMock) {
                        replaceStepCounterValue(event.values)
                        logOnce("listener.stepCounter", "[SensorHook] listener step counter replaced: current=$mCurrentStepCount goal=$mStepGoalEnd")
                    }
                }
            })
        }
        val registerMethods = cSystemSensorManager.declaredMethods.filter {
            it.name == "registerListenerImpl" && it.parameterTypes.isNotEmpty()
                    && it.parameterTypes[0] == SensorEventListener::class.java
                    && it.parameterTypes[1] == Sensor::class.java
        }
        if (registerMethods.isEmpty()) {
            Logger.warn("[SensorHook] registerListenerImpl target overload not found")
        }
        registerMethods.forEach {
            it.onceHook(hookRegisterListenerImpl)
            Logger.info("[SensorHook] hooked registerListenerImpl ${it.parameterTypes.joinToString(prefix = "(", postfix = ")") { type -> type.simpleName }}")
        }

        val hookUnregisterListenerImpl = beforeHook {
            val listener = args.getOrNull(0) as? SensorEventListener ?: run {
                Logger.warn("[SensorHook] unregisterListenerImpl skipped: listener is null or invalid")
                return@beforeHook
            }
            if (FakeLoc.enableDebugLog) {
                Logger.debug("UnregisterListenerImpl: $listener")
            }
            val sensor = if (args.size > 1) args[1] as? Sensor else null
            if (sensor == null) {
                listenerMap.remove(listener)
                listenerDeliveryHandlers.remove(listener)
                logOnce("unregister.all.${listener.javaClass.name}") {
                    "[SensorHook][StepPusher] unregisterListenerImpl removed listener=${listener.javaClass.name}; " +
                        listenerMapSummary()
                }
            } else {
                listenerMap[listener]?.removeIf { it.type == sensor.type }
                if (listenerMap[listener]?.isEmpty() == true) {
                    listenerMap.remove(listener)
                    listenerDeliveryHandlers.remove(listener)
                }
                logOnce("unregister.${listener.javaClass.name}.${sensor.type}") {
                    "[SensorHook][StepPusher] unregisterListenerImpl removed sensor=${sensorSummary(sensor)} " +
                        "listener=${listener.javaClass.name}; ${listenerMapSummary()}"
                }
            }
        }
        val unregisterMethods = cSystemSensorManager.declaredMethods.filter {
            it.name == "unregisterListenerImpl" && it.parameterTypes.isNotEmpty()
                    && it.parameterTypes[0] == SensorEventListener::class.java
        }
        if (unregisterMethods.isEmpty()) {
            Logger.warn("[SensorHook] unregisterListenerImpl target overload not found")
        }
        unregisterMethods.forEach {
            it.onceHook(hookUnregisterListenerImpl)
            Logger.info("[SensorHook] hooked unregisterListenerImpl ${it.parameterTypes.joinToString(prefix = "(", postfix = ")") { type -> type.simpleName }}")
        }

        val getSensorListHooks = cSystemSensorManager.hookAllMethods("getSensorList", afterHook {
            (result as? List<*>)?.let { sensors ->
                logOnce("sensorList.getSensorList.${args.getOrNull(0)}") {
                    "[SensorHook][Shape] getSensorList result: type=${args.getOrNull(0)} ${summarizeSensorList(sensors)}"
                }
            }
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getSensorList: type: ${args[0]} -> $result")
            }
        })
        val getFullSensorListHooks = cSystemSensorManager.hookAllMethods("getFullSensorList", afterHook {
            (result as? List<*>)?.let { sensors ->
                logOnce("sensorList.getFullSensorList") {
                    "[SensorHook][Shape] getFullSensorList result: ${summarizeSensorList(sensors)}"
                }
            }
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getFullSensorList-> $result")
            }
        })
        val getFullSensorsListHooks = cSystemSensorManager.hookAllMethods("getFullSensorsList", afterHook {
            (result as? List<*>)?.let { sensors ->
                logOnce("sensorList.getFullSensorsList") {
                    "[SensorHook][Shape] getFullSensorsList result: ${summarizeSensorList(sensors)}"
                }
            }
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getFullSensorsList-> $result")
            }
        })
        Logger.info("[SensorHook] hooked sensor list methods: getSensorList=${getSensorListHooks.size}, getFullSensorList=${getFullSensorListHooks.size}, getFullSensorsList=${getFullSensorsListHooks.size}")
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

    internal fun shouldMockMotionSensor(type: Int): Boolean {
        if (!isMotionSensor(type)) return false
        if (FakeLoc.sensorData.enableShakeMock && type == Sensor.TYPE_ACCELEROMETER) return true
        return isEffectiveMotionMockEnabled()
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
                Sensor.TYPE_MAGNETIC_FIELD -> updateMagField(outValues)
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
            advanceStepsIfNeeded(timeMs)
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
            Sensor.TYPE_MAGNETIC_FIELD -> updateMagField(outValues)
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
        advanceStepsIfNeeded(timeMs)
    }

    private fun advanceStepsIfNeeded(timeMs: Long) {
        if (FakeLoc.sensorData.enableStepCountMock) {
            updateStepGoal()
            val now = SystemClock.elapsedRealtime()
            val deltaMs = if (lastStepAdvanceMs == 0L) {
                currentStepPushIntervalMs()
            } else {
                (now - lastStepAdvanceMs).coerceAtMost(2000L)
            }
            advanceStepCounter(deltaMs)
            lastStepAdvanceMs = now
            ensureStepEventPusher()
        }
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
            Sensor.TYPE_MAGNETIC_FIELD -> updateMagField(outValues)
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

        // 磁场（世界坐标旋转到设备坐标）使用漂移基值
        val bx = magBase[0]
        val by = magBase[1]
        val bz = magBase[2]
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

    // ==================== 步数持久化 ====================

    private fun loadStepCount() {
        val file = stepCountFile ?: run {
            logOnce("stepPersist.fileNull") {
                "[SensorHook][StepPusher] persisted step count unavailable: context=${objectSummary(FakeLoc.getContext())}"
            }
            return
        }
        try {
            if (file.exists() && file.canRead()) {
                val text = file.readText().trim()
                val count = text.toLongOrNull()
                if (count != null && count >= 0L) {
                    mCurrentStepCount = count
                    Logger.info("[SensorHook][StepPusher] loaded persisted step count: count=$count file=${file.absolutePath}")
                }
            } else {
                logOnce("stepPersist.empty") {
                    "[SensorHook][StepPusher] persisted step count file not readable yet: file=${file.absolutePath}, exists=${file.exists()}, canRead=${file.canRead()}"
                }
            }
        } catch (e: Exception) {
            Logger.warn("[SensorHook][StepPusher] failed to load step count", e)
        }
    }

    private fun saveStepCount() {
        val file = stepCountFile ?: run {
            logOnce("stepPersist.saveFileNull") {
                "[SensorHook][StepPusher] persisted step count save skipped: context=${objectSummary(FakeLoc.getContext())}"
            }
            return
        }
        // 节流：最多每 5 秒写一次，或每 100 步写一次
        val now = System.currentTimeMillis()
        stepSaveCounter++
        if (now - lastStepSaveMs < 5000L && stepSaveCounter < 100) return
        stepSaveCounter = 0
        lastStepSaveMs = now
        try {
            file.writeText(mCurrentStepCount.toString())
        } catch (e: Exception) {
            Logger.warn("[SensorHook][StepPusher] failed to save step count", e)
        }
    }

    // ==================== 磁场漂移 ====================

    private fun updateMagField(outValues: FloatArray) {
        val now = System.currentTimeMillis()
        // 每 10 秒漂移一次，每次 ±0.5 μT
        if (now - lastMagUpdateMs > 10_000L) {
            for (i in 0 until 3) {
                magBase[i] += (Random.nextFloat() - 0.5f) * 0.5f
                // 限制漂移范围 ±5 μT
                magBase[i] = magBase[i].coerceIn(
                    floatArrayOf(20.0f, -3.0f, -47.0f)[i],
                    floatArrayOf(30.0f, 7.0f, -37.0f)[i]
                )
            }
            lastMagUpdateMs = now
        }
        outValues[0] = magBase[0] + (Random.nextFloat() - 0.5f) * 0.15f
        outValues[1] = magBase[1] + (Random.nextFloat() - 0.5f) * 0.15f
        outValues[2] = magBase[2] + (Random.nextFloat() - 0.5f) * 0.15f
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

        if (!enableStep) {
            stepAdvanceCarryMs = 0.0
            logOnce("stepGoal.disabled") {
                "[SensorHook][StepPusher] updateStepGoal skipped: ${stepPusherState()}"
            }
            if (isActiveSensorPusherEnabled()) {
                ensureStepEventPusher()
            }
            return
        }

        if (enableStep && (stepMockJustEnabled || stepCountChanged)) {
            mStepGoalEnd = mCurrentStepCount + userStepCount
            mLastConfiguredStepCount = FakeLoc.sensorData.stepCount
            stepAdvanceCarryMs = 0.0
            Logger.info("[SensorHook][StepPusher] step goal updated: justEnabled=$stepMockJustEnabled changed=$stepCountChanged current=$mCurrentStepCount add=$userStepCount end=$mStepGoalEnd ${listenerMapSummary()}")
        } else {
            logOnce("stepGoal.unchanged.${FakeLoc.sensorData.stepCount}") {
                "[SensorHook][StepPusher] step goal unchanged: ${stepPusherState()}"
            }
        }
        ensureStepEventPusher()
    }

    /**
     * 按时间增量推进步数计数器。应在定时循环中调用。
     * 返回新增步数（用于 step detector 分发）。
     */
    fun advanceStepCounter(deltaMs: Long): Long {
        if (!FakeLoc.sensorData.enableStepCountMock) {
            stepAdvanceCarryMs = 0.0
            logOnce("stepAdvance.disabled") {
                "[SensorHook][StepPusher] advanceStepCounter skipped: enableStepCountMock=false deltaMs=$deltaMs"
            }
            return 0L
        }
        if (mCurrentStepCount >= mStepGoalEnd) {
            stepAdvanceCarryMs = 0.0
            logOnce("stepAdvance.goalReached.$mStepGoalEnd") {
                "[SensorHook][StepPusher] advanceStepCounter goal reached: ${stepPusherState()} deltaMs=$deltaMs"
            }
            return 0L
        }
        val freqHz = FakeLoc.gaitData.gaitFreq.coerceIn(0.6f, 4.0f).toDouble()
        val stepIntervalMs = stepPushIntervalMsForFrequency(FakeLoc.gaitData.gaitFreq).toDouble()
        val availableMs = deltaMs.coerceAtLeast(0L).toDouble() + stepAdvanceCarryMs
        val add = (availableMs / stepIntervalMs).toLong()
        if (add <= 0L) {
            stepAdvanceCarryMs = availableMs.coerceAtMost(stepIntervalMs)
            logOnce("stepAdvance.noAdd") {
                "[SensorHook][StepPusher] advanceStepCounter no increment: deltaMs=$deltaMs carry=${String.format(Locale.US, "%.1f", stepAdvanceCarryMs)} freqHz=$freqHz intervalMs=$stepIntervalMs ${stepPusherState()}"
            }
            return 0L
        }
        val newCount = (mCurrentStepCount + add).coerceAtMost(mStepGoalEnd)
        val stepsAdded = newCount - mCurrentStepCount
        mCurrentStepCount = newCount
        stepAdvanceCarryMs = if (mCurrentStepCount >= mStepGoalEnd) {
            0.0
        } else {
            (availableMs - stepsAdded * stepIntervalMs).coerceIn(0.0, stepIntervalMs)
        }
        saveStepCount()
        logOnce("stepAdvance.first") {
            "[SensorHook][StepPusher] advanceStepCounter advanced: added=$stepsAdded deltaMs=$deltaMs carry=${String.format(Locale.US, "%.1f", stepAdvanceCarryMs)} ${stepPusherState()}"
        }
        return stepsAdded
    }

    fun getCurrentStepCount(): Long = mCurrentStepCount

    private fun replaceStepCounterValue(values: FloatArray) {
        if (values.isNotEmpty()) {
            values[0] = mCurrentStepCount.coerceAtLeast(0L).toFloat()
        }
    }

    // ==================== 步数主动事件推送 ====================

    /**
     * 启动步数主动事件推送线程。
     * 当enableStepCountMock开启且无硬件事件时，主动向注册的listener推送TYPE_STEP_COUNTER事件。
     */
    private fun ensureStepEventPusher() {
        synchronized(stepEventLock) {
            logOnce("stepPusher.ensure.${FakeLoc.sensorData.enableStepCountMock}.${stepEventThread?.isAlive == true}") {
                "[SensorHook][StepPusher] ensure requested: ${stepPusherState()}"
            }
            if (stepEventThread?.isAlive == true) {
                logOnce("stepPusher.already", "[SensorHook][StepPusher] active StepEventPusher already running: ${stepPusherState()}")
                return
            }
            if (!isActiveSensorPusherEnabled()) {
                logOnce("stepPusher.disabled", "[SensorHook][StepPusher] active StepEventPusher skipped: ${stepPusherState()}")
                return
            }

            stepEventThread = Thread({
                Logger.info("[SensorHook][StepPusher] active StepEventPusher loop started: ${stepPusherState()}")
                var idlePolls = 0
                var consecutiveErrors = 0
                var lastLoopAtMs = SystemClock.elapsedRealtime()
                while (!Thread.currentThread().isInterrupted && isActiveSensorPusherEnabled()) {
                    if (DomainGuard.isDomainDisabled(DomainGuard.Domain.SENSOR)) {
                        Logger.warn("[SensorHook][StepPusher] active StepEventPusher stopped: SENSOR domain disabled")
                        break
                    }
                    try {
                        val pushIntervalMs = currentStepPushIntervalMs()
                        Thread.sleep(pushIntervalMs)
                        val loopNowMs = SystemClock.elapsedRealtime()
                        val elapsedMs = (loopNowMs - lastLoopAtMs).coerceIn(1L, 2_000L)
                        lastLoopAtMs = loopNowMs

                        if (FakeLoc.sensorData.enableStepCountMock) {
                            updateStepGoal()
                        }
                        val stepsAdded = if (FakeLoc.sensorData.enableStepCountMock) {
                            advanceStepCounter(elapsedMs)
                        } else {
                            0L
                        }
                        val currentCount = mCurrentStepCount
                        val nowMs = loopNowMs

                        var delivered = 0
                        var stepListeners = 0
                        var motionListeners = 0
                        for ((listener, sensors) in listenerMap.entries) {
                            for (sensor in sensors) {
                                val values = when {
                                    sensor.type == Sensor.TYPE_STEP_COUNTER && FakeLoc.sensorData.enableStepCountMock -> {
                                        stepListeners += 1
                                        if (currentCount > 0L) floatArrayOf(currentCount.toFloat()) else null
                                    }
                                    sensor.type == Sensor.TYPE_STEP_DETECTOR && FakeLoc.sensorData.enableStepCountMock -> {
                                        stepListeners += 1
                                        if (stepsAdded > 0L) floatArrayOf(1.0f) else null
                                    }
                                    shouldMockMotionSensor(sensor.type) -> {
                                        motionListeners += 1
                                        val out = FloatArray(sensorValueSize(sensor.type))
                                        if (FakeLoc.sensorData.enableShakeMock && sensor.type == Sensor.TYPE_ACCELEROMETER) {
                                            getMockShakeAccelValues(nowMs, out)
                                        } else if (FakeLoc.sensorData.keepWalkingWaveform || FakeLoc.sensorData.enableStepCountMock) {
                                            getMockMotionValues(sensor.type, nowMs, out)
                                        } else {
                                            getMockStaticValues(sensor.type, nowMs, out)
                                        }
                                        out
                                    }
                                    else -> {
                                        logOnce("stepPusher.ineligible.${sensor.type}") {
                                            "[SensorHook][StepPusher][Shape] active listener sensor not eligible: " +
                                                "sensor=${sensorSummary(sensor)}, step=${FakeLoc.sensorData.enableStepCountMock}, " +
                                                "motion=${FakeLoc.sensorData.enableMotionMock}, shake=${FakeLoc.sensorData.enableShakeMock}"
                                        }
                                        null
                                    }
                                } ?: continue
                                val event = createSensorEvent(sensor, values)
                                if (event != null) {
                                    if (deliverSensorEvent(listener, event)) {
                                        delivered++
                                    } else {
                                        consecutiveErrors += 1
                                        if (consecutiveErrors >= STEP_PUSH_ERROR_LIMIT) {
                                            DomainGuard.disableDomain(
                                                DomainGuard.Domain.SENSOR,
                                                "StepEventPusher delivery failures >= $STEP_PUSH_ERROR_LIMIT",
                                            )
                                            break
                                        }
                                    }
                                } else {
                                    logOnce("stepPusher.eventNull", "[SensorHook][StepPusher] active StepEventPusher could not create SensorEvent; ${stepPusherState()}")
                                }
                            }
                        }
                        if (DomainGuard.isDomainDisabled(DomainGuard.Domain.SENSOR)) {
                            break
                        }
                        if (delivered > 0) {
                            consecutiveErrors = 0
                            idlePolls = 0
                            logOnce("stepPusher.delivered", "[SensorHook][StepPusher] active StepEventPusher delivered first event: listeners=$delivered step=$currentCount ${stepPusherState()}")
                        } else if (stepListeners == 0 && motionListeners == 0) {
                            idlePolls += 1
                            if (idlePolls >= STEP_PUSH_IDLE_LIMIT) {
                                Logger.info("[SensorHook][StepPusher] active StepEventPusher stopped: no active sensor listeners after ${idlePolls * pushIntervalMs}ms; ${stepPusherState()}")
                                break
                            }
                            logOnce("stepPusher.noListener", "[SensorHook][StepPusher] active StepEventPusher found no active sensor listeners; ${stepPusherState()}")
                        } else {
                            idlePolls = 0
                            logOnce("stepPusher.noDelivery.$stepListeners.$motionListeners") {
                                "[SensorHook][StepPusher][Shape] active StepEventPusher had eligible listeners but delivered no event: " +
                                    "stepListeners=$stepListeners, motionListeners=$motionListeners, stepsAdded=$stepsAdded, current=$currentCount, ${stepPusherState()}"
                            }
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Throwable) {
                        consecutiveErrors += 1
                        Logger.warn("[SensorHook][StepPusher] active StepEventPusher error", e)
                        DomainGuard.reportCrash(DomainGuard.Domain.SENSOR, e)
                        if (consecutiveErrors >= STEP_PUSH_ERROR_LIMIT) {
                            DomainGuard.disableDomain(
                                DomainGuard.Domain.SENSOR,
                                "StepEventPusher loop failures >= $STEP_PUSH_ERROR_LIMIT",
                            )
                            break
                        }
                    }
                }
                Logger.info("[SensorHook][StepPusher] active StepEventPusher loop stopped: ${stepPusherState()}")
                synchronized(stepEventLock) {
                    if (stepEventThread === Thread.currentThread()) {
                        stepEventThread = null
                    }
                }
            }, "StepEventPusher")
            stepEventThread?.isDaemon = true
            stepEventThread?.start()
            Logger.info("[SensorHook][StepPusher] active StepEventPusher thread started: ${stepPusherState()}")
        }
    }

    private fun sensorValueSize(sensorType: Int): Int {
        return when (sensorType) {
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> 4
            else -> 3
        }
    }

    private fun createSensorEvent(sensor: Sensor, values: FloatArray): SensorEvent? {
        return runCatching {
            val context = FakeLoc.getContext()
            logOnce("stepEvent.context.${context?.javaClass?.name ?: "null"}") {
                "[SensorHook][StepPusher][Shape] createSensorEvent context=${objectSummary(context)}"
            }

            val ctor = SensorEvent::class.java.getDeclaredConstructors().firstOrNull {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
                ?: run {
                    logOnce("stepEvent.constructorNull") {
                        "[SensorHook][StepPusher][Shape] createSensorEvent int constructor unavailable"
                    }
                    return@runCatching null
                }
            logOnce("stepEvent.constructors") {
                val constructors = SensorEvent::class.java.declaredConstructors.joinToString(limit = SENSOR_SHAPE_METHOD_LIMIT, truncated = "...") {
                    it.parameterTypes.joinToString(prefix = "(", postfix = ")") { type -> shortClassName(type) }
                }
                "[SensorHook][StepPusher][Shape] SensorEvent constructors=$constructors"
            }
            ctor.isAccessible = true
            val event = ctor.newInstance(values.size) as SensorEvent
            event.sensor = sensor
            values.copyInto(event.values, endIndex = minOf(values.size, event.values.size))
            event.timestamp = SystemClock.elapsedRealtimeNanos()
            event.accuracy = 3
            event
        }.onFailure {
            logOnce("stepEvent.create.failed.${it.javaClass.simpleName}") {
                "[SensorHook][StepPusher][Shape] createSensorEvent failed: ${it.javaClass.simpleName}:${it.message ?: ""}, sensor=${sensorSummary(sensor)}"
            }
        }.getOrNull()
    }

    private fun deliverSensorEvent(listener: SensorEventListener, event: SensorEvent): Boolean {
        val handler = listenerDeliveryHandlers[listener] ?: mainDeliveryHandler()
        if (handler == null) {
            return invokeSensorListener(listener, event)
        }
        return runCatching {
            handler.post {
                invokeSensorListener(listener, event)
            }
        }.getOrElse {
            Logger.warn("[SensorHook][StepPusher] active StepEventPusher failed to post listener event: ${listener.javaClass.name}", it)
            false
        }
    }

    private fun mainDeliveryHandler(): Handler? {
        return mainSensorDeliveryHandler
    }

    private fun invokeSensorListener(listener: SensorEventListener, event: SensorEvent): Boolean {
        return runCatching {
            listener.onSensorChanged(event)
            true
        }.getOrElse {
            listenerMap.remove(listener)
            listenerDeliveryHandlers.remove(listener)
            Logger.warn("[SensorHook][StepPusher] active StepEventPusher listener failed and was removed: ${listener.javaClass.name}", it)
            DomainGuard.reportCrash(DomainGuard.Domain.SENSOR, it)
            false
        }
    }

    internal fun rememberDeliveryHandlerForTest(listener: SensorEventListener, handler: Handler?) {
        if (handler == null) {
            listenerDeliveryHandlers.remove(listener)
        } else {
            listenerDeliveryHandlers[listener] = handler
        }
    }

    internal fun clearDeliveryHandlersForTest() {
        listenerDeliveryHandlers.clear()
    }

    internal fun deliverSensorEventForTest(listener: SensorEventListener, event: SensorEvent): Boolean {
        return deliverSensorEvent(listener, event)
    }

    internal fun resetStepCounterForTest(current: Long = 0L, goal: Long = current) {
        mCurrentStepCount = current.coerceAtLeast(0L)
        mStepGoalEnd = goal.coerceAtLeast(mCurrentStepCount)
        mLastConfiguredStepCount = -1
        mWasStepMockEnabled = false
        stepAdvanceCarryMs = 0.0
        lastStepAdvanceMs = 0L
    }

    internal fun replaceStepCounterValueForTest(realValue: Float): Float {
        val values = floatArrayOf(realValue)
        replaceStepCounterValue(values)
        return values[0]
    }

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
