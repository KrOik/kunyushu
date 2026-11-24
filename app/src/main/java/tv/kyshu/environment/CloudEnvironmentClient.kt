package tv.kyshu.environment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudEnvironmentClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun validateWigle(token: String): Boolean = requestOk(
        Request.Builder().url("https://api.wigle.net/api/v2/profile/user")
            .header("Authorization", wigleAuthorization(token)).build(),
    )

    suspend fun validateOpenCellId(token: String): Boolean {
        if (token.isBlank()) return false
        val url = "https://opencellid.org/cell/getInArea".toHttpUrl().newBuilder()
            .addQueryParameter("key", token)
            .addQueryParameter("BBOX", "0,0,0.0001,0.0001")
            .addQueryParameter("format", "json")
            .addQueryParameter("limit", "1")
            .build()
        return requestOk(Request.Builder().url(url).build())
    }

    suspend fun fetchWigle(lat: Double, lon: Double, token: String): EnvironmentCapture = withContext(Dispatchers.IO) {
        require(token.isNotBlank())
        val span = 0.001
        val url = "https://api.wigle.net/api/v2/network/search".toHttpUrl().newBuilder()
            .addQueryParameter("latrange1", (lat - span).toString())
            .addQueryParameter("latrange2", (lat + span).toString())
            .addQueryParameter("longrange1", (lon - span).toString())
            .addQueryParameter("longrange2", (lon + span).toString())
            .addQueryParameter("resultsPerPage", "32")
            .build()
        val root = executeJson(Request.Builder().url(url).header("Authorization", wigleAuthorization(token)).build())
        val results = root.optJSONArray("results") ?: JSONArray()
        val wifi = buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val bssid = item.optString("netid").uppercase()
                if (!MAC.matches(bssid)) continue
                val channel = item.optInt("channel", 0)
                add(WifiObservation(
                    sampleId = 0,
                    ssid = item.optString("ssid"),
                    bssid = bssid,
                    rssiDbm = (-45 - index * 2).coerceAtLeast(-95),
                    frequencyMhz = channelToFrequency(channel),
                    capabilities = item.optString("encryption").takeIf { it.isNotBlank() } ?: "[ESS]",
                ))
            }
        }
        EnvironmentCapture(lat, lon, wifi = wifi, source = EnvironmentSource.WIGLE, label = "WiGLE", note = "云端查询")
    }

    suspend fun fetchOpenCellId(lat: Double, lon: Double, token: String): EnvironmentCapture = withContext(Dispatchers.IO) {
        require(token.isNotBlank())
        val span = 0.01
        val bbox = "${lat - span},${lon - span},${lat + span},${lon + span}"
        val url = "https://opencellid.org/cell/getInArea".toHttpUrl().newBuilder()
            .addQueryParameter("key", token)
            .addQueryParameter("BBOX", bbox)
            .addQueryParameter("format", "json")
            .addQueryParameter("limit", "32")
            .build()
        val root = executeJson(Request.Builder().url(url).build())
        val array = root.optJSONArray("cells") ?: root.optJSONArray("results") ?: JSONArray()
        val cells = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val rat = normalizeRat(item.optString("radio", item.optString("type", "LTE"))) ?: continue
                val mcc = item.optInt("mcc", -1).takeIf { it in 100..999 }?.toString() ?: continue
                val mnc = item.optInt("mnc", item.optInt("net", -1)).takeIf { it in 0..999 }
                    ?.toString()?.padStart(2, '0') ?: continue
                val area = item.optInt("area", item.optInt("lac", item.optInt("tac", -1))).takeIf { it >= 0 }
                val id = item.optLong("cellid", item.optLong("cid", item.optLong("ci", -1))).takeIf { it >= 0 } ?: continue
                val pci = item.optInt("pci", item.optInt("psc", -1)).takeIf { it >= 0 }
                val key = listOf(rat, mcc, mnc, area ?: 0, id, pci ?: 0).joinToString("_")
                add(CellObservation(
                    sampleId = 0, cellKey = key, rat = rat, mcc = mcc, mnc = mnc,
                    tacOrLac = area, cellId = id, pciOrPsc = pci,
                    rssiDbm = item.optInt("signal", -95).coerceIn(-140, 0),
                    registered = index == 0,
                ))
            }
        }
        EnvironmentCapture(lat, lon, cells = cells, source = EnvironmentSource.OPENCELLID, label = "OpenCellID", note = "云端查询")
    }

    private suspend fun requestOk(request: Request): Boolean = withContext(Dispatchers.IO) {
        if (request.header("Authorization")?.endsWith("Og==") == true) return@withContext false
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    private fun executeJson(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw CloudEnvironmentException("HTTP ${response.code}")
        val body = response.body?.string().orEmpty()
        runCatching { JSONObject(body) }.getOrElse { throw CloudEnvironmentException("服务返回无效 JSON") }
    }

    private fun wigleAuthorization(token: String): String =
        if (token.startsWith("Basic ", true)) token else if (token.contains(':')) Credentials.basic(
            token.substringBefore(':'), token.substringAfter(':'), Charsets.UTF_8,
        ) else "Basic $token"

    private fun normalizeRat(value: String): String? = when (value.uppercase()) {
        "GSM" -> "GSM"; "UMTS", "WCDMA" -> "WCDMA"; "LTE", "4G" -> "LTE"
        "NR", "5G" -> "NR"; "CDMA" -> "CDMA"; else -> null
    }

    private fun channelToFrequency(channel: Int): Int = when {
        channel in 1..13 -> 2407 + channel * 5
        channel == 14 -> 2484
        channel in 32..177 -> 5000 + channel * 5
        else -> 2412
    }

    companion object { private val MAC = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") }
}

class CloudEnvironmentException(message: String) : Exception(message)
