@file:Suppress("UNCHECKED_CAST")
package tv.kyshu.xposed.hooks.sensor

import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorEvent
import android.util.ArrayMap
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

// https://github.com/Frazew/VirtualSensor/blob/master/app/src/main/java/fr/frazew/virtualgyroscope/XposedMod.java#L298
object SystemSensorManagerHook {
    private val listenerMap = ConcurrentHashMap<SensorEventListener, Int>()

    operator fun invoke(classLoader: ClassLoader) {
        unlockGeoSensor(classLoader)

        hookSystemSensorManager(classLoader)
        hookSystemSensorManagerQueue(classLoader)
    }

    private fun hookSystemSensorManagerQueue(classLoader: ClassLoader) {
        val cSystemSensorManagerQueue = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
            ?: return

        // Hook dispatchSensorEvent to log and pass through real values
        cSystemSensorManagerQueue.onceHookAllMethod("dispatchSensorEvent", beforeHook {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("SensorEventQueue.dispatchSensorEvent: handle=${args[0]}")
            }
            // Real sensor values pass through unmodified
        })
    }

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
            if (FakeLoc.enableDebugLog) {
                Logger.debug("RegisterListenerImpl: $listener, sensor: ${args[1]}")
            }

            val sensor = args[1] as? Sensor ?: return@beforeHook
            listenerMap[listener] = sensor.type

            listener.javaClass.onceHookAllMethod("onSensorChanged", beforeHook {
                val sensorType = listenerMap[listener] ?: return@beforeHook
                if (!FakeLoc.sensorData.hookSensor) return@beforeHook

                val event = args[0] as? SensorEvent ?: return@beforeHook
                event.timestamp = System.nanoTime()
                event.accuracy = 3

                when (sensorType) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val t = System.currentTimeMillis() / 1000.0
                        val freq = FakeLoc.gaitData.gaitFreq
                        val amp = FakeLoc.gaitData.gaitAmpScale * 2.0f
                        val phase = 2.0 * Math.PI * freq * t
                        event.values[0] = (amp * 0.1 * Math.sin(phase + 1.0)).toFloat()
                        event.values[1] = (amp * 0.05 * Math.sin(phase + 2.0)).toFloat()
                        event.values[2] = (9.81f + amp * Math.sin(phase)).toFloat()
                    }
                    Sensor.TYPE_STEP_COUNTER -> {
                        event.values[0] = FakeLoc.sensorData.stepCount.toFloat()
                        if (FakeLoc.sensorData.enableStepCountMock) {
                            FakeLoc.sensorData.stepCount++
                        }
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        event.values[0] = (Math.random().toFloat() - 0.5f) * 0.02f
                        event.values[1] = (Math.random().toFloat() - 0.5f) * 0.02f
                        event.values[2] = (Math.random().toFloat() - 0.5f) * 0.02f
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
            listenerMap.remove(listener)
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

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("Added FEATURE_SENSOR_GYROSCOPE")
                }
            }
            if (!features.contains(PackageManager.FEATURE_SENSOR_COMPASS)) {
                val compassFeature = FeatureInfo()
                compassFeature.name = PackageManager.FEATURE_SENSOR_COMPASS
                compassFeature.reqGlEsVersion = openGLVersion
                features[PackageManager.FEATURE_SENSOR_COMPASS] = compassFeature

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("Added FEATURE_SENSOR_COMPASS")
                }
            }

            if (!features.contains(PackageManager.FEATURE_SENSOR_ACCELEROMETER)) {
                val accelerometerFeature = FeatureInfo()
                accelerometerFeature.name = PackageManager.FEATURE_SENSOR_ACCELEROMETER
                accelerometerFeature.reqGlEsVersion = openGLVersion
                features[PackageManager.FEATURE_SENSOR_ACCELEROMETER] = accelerometerFeature

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("Added FEATURE_SENSOR_ACCELEROMETER")
                }
            }

            if (!features.contains(PackageManager.FEATURE_SENSOR_STEP_COUNTER)) {
                val lightFeature = FeatureInfo()
                lightFeature.name = PackageManager.FEATURE_SENSOR_STEP_COUNTER
                lightFeature.reqGlEsVersion = openGLVersion
                features[PackageManager.FEATURE_SENSOR_STEP_COUNTER] = lightFeature

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("Added FEATURE_SENSOR_STEP_COUNTER")
                }
            }

            if (!features.contains(PackageManager.FEATURE_SENSOR_STEP_DETECTOR)) {
                val lightFeature = FeatureInfo()
                lightFeature.name = PackageManager.FEATURE_SENSOR_STEP_DETECTOR
                lightFeature.reqGlEsVersion = openGLVersion
                features[PackageManager.FEATURE_SENSOR_STEP_DETECTOR] = lightFeature

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("Added FEATURE_SENSOR_STEP_DETECTOR")
                }
            }
//            kotlin.runCatching {
//                XposedHelpers.setObjectField(thisObject, "mAvailableFeatures", features)
//            }.onFailure {
//                Logger.warn("Failed to set mAvailableFeatures", it)
//            }
            result = features
        }
    }
}