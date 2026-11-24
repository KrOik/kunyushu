package tv.kyshu.xposed.utils

import android.app.Application
import android.os.Build
import tv.kyshu.xposed.config.CoordinateSystem
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object CoordinateTransforms {
    fun forCurrentProcess(latitude: Double, longitude: Double): Pair<Double, Double> {
        val packageName = currentProcessName().substringBefore(':')
        val system = FakeLoc.locationData.coordinateOverrides[packageName] ?: CoordinateSystem.WGS84
        return convertWgs84(latitude, longitude, system)
    }

    fun convertWgs84(latitude: Double, longitude: Double, target: CoordinateSystem): Pair<Double, Double> = when (target) {
        CoordinateSystem.WGS84 -> latitude to longitude
        CoordinateSystem.GCJ02 -> wgs84ToGcj02(latitude, longitude)
        CoordinateSystem.BD09 -> wgs84ToGcj02(latitude, longitude).let { gcj02ToBd09(it.first, it.second) }
    }

    fun wgs84ToGcj02(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (outsideChina(latitude, longitude)) return latitude to longitude
        val dLat = transformLat(longitude - 105.0, latitude - 35.0)
        val dLon = transformLon(longitude - 105.0, latitude - 35.0)
        val radLat = Math.toRadians(latitude)
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val latitudeOffset = dLat * 180.0 / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        val longitudeOffset = dLon * 180.0 / (A / sqrtMagic * cos(radLat) * Math.PI)
        return latitude + latitudeOffset to longitude + longitudeOffset
    }

    fun gcj02ToBd09(latitude: Double, longitude: Double): Pair<Double, Double> {
        val z = sqrt(longitude * longitude + latitude * latitude) + 0.00002 * sin(latitude * X_PI)
        val theta = atan2(latitude, longitude) + 0.000003 * cos(longitude * X_PI)
        return z * sin(theta) + 0.006 to z * cos(theta) + 0.0065
    }

    private fun currentProcessName(): String = runCatching {
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getDeclaredMethod("currentProcessName").invoke(null) as? String
        }
    }.getOrNull().orEmpty()

    private fun outsideChina(lat: Double, lon: Double) = lon !in 72.004..137.8347 || lat !in 0.8293..55.8271
    private fun transformLat(x: Double, y: Double): Double {
        var value = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        value += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        value += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        value += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return value
    }
    private fun transformLon(x: Double, y: Double): Double {
        var value = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        value += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        value += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        value += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return value
    }

    private const val A = 6_378_245.0
    private const val EE = 0.006693421622965943
    private const val X_PI = Math.PI * 3000.0 / 180.0
}
