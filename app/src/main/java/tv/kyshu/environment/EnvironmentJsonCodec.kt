package tv.kyshu.environment

import org.json.JSONArray
import org.json.JSONObject

object EnvironmentJsonCodec {
    const val SCHEMA_VERSION = 1
    private const val MAX_SAMPLES = 20_000
    private const val MAX_OBSERVATIONS_PER_DOMAIN = 128

    fun encode(samples: List<CompleteEnvironmentSample>): String = JSONObject().apply {
        put("schema_version", SCHEMA_VERSION)
        put("exported_at", System.currentTimeMillis())
        put("samples", JSONArray().apply { samples.forEach { put(it.toJson()) } })
    }.toString(2)

    fun decode(json: String): List<Pair<EnvironmentCapture, EnvironmentSample>> {
        val root = JSONObject(json)
        require(root.optInt("schema_version", -1) == SCHEMA_VERSION) { "不支持的环境数据版本" }
        val samples = root.optJSONArray("samples") ?: throw IllegalArgumentException("缺少 samples")
        require(samples.length() <= MAX_SAMPLES) { "样本数量超过限制" }
        return buildList {
            for (index in 0 until samples.length()) {
                val item = samples.getJSONObject(index)
                val lat = item.getDouble("latitude")
                val lon = item.getDouble("longitude")
                require(lat in -90.0..90.0 && lon in -180.0..180.0) { "坐标越界" }
                val sample = EnvironmentSample(
                    latitude = lat, longitude = lon,
                    capturedAtMillis = item.optLong("captured_at", System.currentTimeMillis()),
                    source = EnvironmentSource.IMPORT.name,
                    label = item.optString("label").take(200),
                    note = item.optString("note").take(2_000),
                    preferredWifiBssid = item.optStringOrNull("preferred_wifi"),
                    preferredCellKey = item.optStringOrNull("preferred_cell"),
                    preferredBleAddress = item.optStringOrNull("preferred_ble"),
                )
                val wifi = item.optJSONArray("wifi").objects(MAX_OBSERVATIONS_PER_DOMAIN).mapNotNull { it.toWifi() }
                val cells = item.optJSONArray("cells").objects(MAX_OBSERVATIONS_PER_DOMAIN).mapNotNull { it.toCell() }
                val ble = item.optJSONArray("ble").objects(MAX_OBSERVATIONS_PER_DOMAIN).mapNotNull { it.toBle() }
                add(EnvironmentCapture(lat, lon, wifi, cells, ble, EnvironmentSource.IMPORT, sample.label, sample.note) to sample)
            }
        }
    }

    private fun CompleteEnvironmentSample.toJson() = JSONObject().apply {
        put("latitude", sample.latitude); put("longitude", sample.longitude)
        put("captured_at", sample.capturedAtMillis); put("source", sample.source)
        put("label", sample.label); put("note", sample.note)
        put("preferred_wifi", sample.preferredWifiBssid); put("preferred_cell", sample.preferredCellKey)
        put("preferred_ble", sample.preferredBleAddress)
        put("wifi", JSONArray().apply { wifi.forEach { put(JSONObject().apply {
            put("ssid", it.ssid); put("bssid", it.bssid); put("rssi", it.rssiDbm); put("frequency", it.frequencyMhz)
            put("capabilities", it.capabilities); put("standard", it.standard); put("channel_width", it.channelWidth)
            put("connected", it.connected); put("mac", it.macAddress); put("link_speed", it.linkSpeedMbps)
        }) } })
        put("cells", JSONArray().apply { cells.forEach { put(JSONObject().apply {
            put("key", it.cellKey); put("rat", it.rat); put("mcc", it.mcc); put("mnc", it.mnc)
            put("area", it.tacOrLac); put("cell_id", it.cellId); put("pci", it.pciOrPsc); put("arfcn", it.arfcn)
            put("rssi", it.rssiDbm); put("registered", it.registered)
        }) } })
        put("ble", JSONArray().apply { ble.forEach { put(JSONObject().apply {
            put("address", it.address); put("name", it.name); put("rssi", it.rssiDbm)
            put("manufacturer_data", it.manufacturerDataHex); put("service_data", it.serviceDataHex)
            put("tx_power", it.txPowerDbm); put("connectable", it.connectable)
        }) } })
    }

    private fun JSONObject.toWifi(): WifiObservation? {
        val bssid = optString("bssid").uppercase().takeIf { MAC.matches(it) } ?: return null
        return WifiObservation(sampleId = 0, ssid = optString("ssid"), bssid = bssid,
            rssiDbm = optInt("rssi", -70).coerceIn(-127, 0), frequencyMhz = optInt("frequency", 2412).coerceIn(1, 8000),
            capabilities = optString("capabilities"), standard = optString("standard"), channelWidth = optInt("channel_width", 0),
            connected = optBoolean("connected"), macAddress = optString("mac"), linkSpeedMbps = optInt("link_speed", 0).coerceAtLeast(0))
    }
    private fun JSONObject.toCell(): CellObservation? {
        val rat = optString("rat").uppercase().takeIf { it in setOf("GSM", "WCDMA", "LTE", "NR", "CDMA", "TD_SCDMA") } ?: return null
        val mcc = optString("mcc").takeIf { it.matches(Regex("\\d{3}")) } ?: return null
        val mnc = optString("mnc").takeIf { it.matches(Regex("\\d{2,3}")) } ?: return null
        val id = optLongOrNull("cell_id") ?: return null
        val area = optIntOrNull("area"); val pci = optIntOrNull("pci")
        val key = optString("key").ifBlank { listOf(rat, mcc, mnc, area ?: 0, id, pci ?: 0).joinToString("_") }
        return CellObservation(sampleId = 0, cellKey = key, rat = rat, mcc = mcc, mnc = mnc,
            tacOrLac = area, cellId = id, pciOrPsc = pci, arfcn = optIntOrNull("arfcn"),
            rssiDbm = optInt("rssi", -95).coerceIn(-140, 0), registered = optBoolean("registered"))
    }
    private fun JSONObject.toBle(): BleObservation? {
        val address = optString("address").uppercase().takeIf { MAC.matches(it) } ?: return null
        return BleObservation(sampleId = 0, address = address, name = optString("name"),
            rssiDbm = optInt("rssi", -70).coerceIn(-127, 20), manufacturerDataHex = optString("manufacturer_data").hex(),
            serviceDataHex = optString("service_data").hex(), txPowerDbm = optIntOrNull("tx_power")?.coerceIn(-127, 126),
            connectable = optBoolean("connectable", true))
    }
    private fun JSONArray?.objects(max: Int): List<JSONObject> = if (this == null) emptyList() else (0 until length().coerceAtMost(max)).mapNotNull(::optJSONObject)
    private fun JSONObject.optStringOrNull(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }
    private fun JSONObject.optIntOrNull(key: String) = if (has(key) && !isNull(key)) optInt(key).takeIf { it >= 0 } else null
    private fun JSONObject.optLongOrNull(key: String) = if (has(key) && !isNull(key)) optLong(key).takeIf { it >= 0 } else null
    private fun String.hex() = uppercase().takeIf { it.length % 2 == 0 && it.matches(Regex("[0-9A-F]*")) }.orEmpty()
    private val MAC = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
}
