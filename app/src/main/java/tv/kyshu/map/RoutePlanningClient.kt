package tv.kyshu.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.kyshu.ui.mock.HistoricalRoute
import tv.kyshu.ui.mock.RouteTravelMode
import tv.kyshu.ext.Loc4j
import tv.kyshu.xposed.config.CoordinateSystem
import tv.kyshu.xposed.utils.CoordinateTransforms
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class PlannedRoute(val points: List<Pair<Double, Double>>, val waits: List<Int>, val engine: MapEngine)

class RoutePlanningClient(context: Context) {
    private val settings = MapEngineSettings(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    suspend fun plan(route: HistoricalRoute): Result<PlannedRoute> = withContext(Dispatchers.IO) {
        if (route.route.size < 2) return@withContext Result.failure(IllegalArgumentException("路线至少需要两个点"))
        val failures = mutableListOf<String>()
        for (engine in settings.routeEngines()) {
            val result = runCatching {
                when (engine) {
                    MapEngine.AMAP -> planAmap(route)
                    MapEngine.BAIDU -> planBaidu(route)
                    MapEngine.GOOGLE -> planGoogle(route)
                    MapEngine.AUTO -> error("AUTO is not a concrete provider")
                }
            }
            if (result.isSuccess) return@withContext result
            failures += "$engine:${result.exceptionOrNull()?.message.orEmpty()}"
        }
        Result.failure(IllegalStateException(failures.joinToString("; ").ifBlank { "未配置可用的路线 API 密钥" }))
    }

    private fun planAmap(route: HistoricalRoute): PlannedRoute {
        val key = requireNotNull(settings.key(MapEngine.AMAP))
        val type = when (route.travelMode) {
            RouteTravelMode.CYCLING -> "bicycling"
            RouteTravelMode.DRIVING -> "driving"
            else -> "walking"
        }
        val all = mutableListOf<Pair<Double, Double>>()
        var trafficLights = 0
        route.route.zipWithNext().forEach { (start, end) ->
            val gcjStart = CoordinateTransforms.convertWgs84(start.first, start.second, CoordinateSystem.GCJ02)
            val gcjEnd = CoordinateTransforms.convertWgs84(end.first, end.second, CoordinateSystem.GCJ02)
            val url = "https://restapi.amap.com/v5/direction/$type?key=${enc(key)}&origin=${gcjStart.second},${gcjStart.first}&destination=${gcjEnd.second},${gcjEnd.first}&show_fields=cost"
            val json = requestJson(url)
            check(json.optString("status") == "1") { json.optString("info", "高德路线请求失败") }
            val path = json.getJSONObject("route").getJSONArray("paths").getJSONObject(0)
            val steps = path.getJSONArray("steps")
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                appendLonLatPolyline(all, step.optString("polyline"), convertGcjToWgs = true)
                trafficLights += step.optJSONObject("cost")?.optInt("traffic_lights", 0) ?: 0
            }
        }
        return planned(all, trafficLights, MapEngine.AMAP)
    }

    private fun planBaidu(route: HistoricalRoute): PlannedRoute {
        val key = requireNotNull(settings.key(MapEngine.BAIDU))
        val mode = when (route.travelMode) {
            RouteTravelMode.CYCLING -> "riding"
            RouteTravelMode.DRIVING -> "driving"
            else -> "walking"
        }
        val all = mutableListOf<Pair<Double, Double>>()
        route.route.zipWithNext().forEach { (start, end) ->
            val url = "https://api.map.baidu.com/directionlite/v1/$mode?origin=${start.first},${start.second}&destination=${end.first},${end.second}&coord_type=wgs84&ak=${enc(key)}"
            val json = requestJson(url)
            check(json.optInt("status", -1) == 0) { json.optString("message", "百度路线请求失败") }
            val steps = json.getJSONObject("result").getJSONArray("routes").getJSONObject(0).getJSONArray("steps")
            for (i in 0 until steps.length()) appendLonLatPolyline(all, steps.getJSONObject(i).optString("path"))
        }
        return planned(all, if (route.travelMode == RouteTravelMode.DRIVING) all.size / 80 else 0, MapEngine.BAIDU)
    }

    private fun planGoogle(route: HistoricalRoute): PlannedRoute {
        val key = requireNotNull(settings.key(MapEngine.GOOGLE))
        val mode = when (route.travelMode) {
            RouteTravelMode.CYCLING -> "bicycling"
            RouteTravelMode.DRIVING -> "driving"
            else -> "walking"
        }
        val all = mutableListOf<Pair<Double, Double>>()
        route.route.zipWithNext().forEach { (start, end) ->
            val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${start.first},${start.second}&destination=${end.first},${end.second}&mode=$mode&key=${enc(key)}"
            val json = requestJson(url)
            check(json.optString("status") == "OK") { json.optString("error_message", json.optString("status")) }
            val encoded = json.getJSONArray("routes").getJSONObject(0).getJSONObject("overview_polyline").getString("points")
            appendGooglePolyline(all, encoded)
        }
        return planned(all, if (route.travelMode == RouteTravelMode.DRIVING) all.size / 100 else 0, MapEngine.GOOGLE)
    }

    private fun planned(points: List<Pair<Double, Double>>, trafficLights: Int, engine: MapEngine): PlannedRoute {
        check(points.size >= 2) { "路线服务未返回有效路径" }
        val waits = MutableList(points.size) { 0 }
        if (trafficLights > 0) {
            val count = trafficLights.coerceAtMost((points.size - 2).coerceAtLeast(0))
            for (n in 1..count) waits[(n * (points.lastIndex) / (count + 1)).coerceIn(1, points.lastIndex - 1)] = 15
        }
        return PlannedRoute(points.distinctConsecutive(), waits, engine)
    }

    private fun requestJson(url: String): JSONObject {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return JSONObject(body)
        }
    }

    private fun appendLonLatPolyline(
        target: MutableList<Pair<Double, Double>>,
        value: String,
        convertGcjToWgs: Boolean = false,
    ) {
        value.split(';').forEach { token ->
            val pair = token.split(',')
            if (pair.size == 2) {
                val lon = pair[0].toDoubleOrNull(); val lat = pair[1].toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    target += if (convertGcjToWgs) Loc4j.gcj2wgs(lat, lon) else lat to lon
                }
            }
        }
    }

    private fun appendGooglePolyline(target: MutableList<Pair<Double, Double>>, encoded: String) {
        var index = 0; var lat = 0; var lon = 0
        while (index < encoded.length) {
            fun next(): Int {
                var result = 0; var shift = 0; var b: Int
                do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && index < encoded.length)
                return if (result and 1 != 0) (result shr 1).inv() else result shr 1
            }
            lat += next(); lon += next(); target += lat / 1e5 to lon / 1e5
        }
    }

    private fun List<Pair<Double, Double>>.distinctConsecutive() = filterIndexed { index, point -> index == 0 || point != this[index - 1] }
    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
