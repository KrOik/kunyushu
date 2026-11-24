package tv.kyshu.environment

import androidx.room.withTransaction
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import tv.kyshu.xposed.config.BleDeviceProfile
import tv.kyshu.xposed.config.CellRat
import tv.kyshu.xposed.config.CellTowerProfile
import tv.kyshu.xposed.config.WifiConnectedProfile
import tv.kyshu.xposed.config.WifiScanEntry
import tv.kyshu.xposed.config.WifiScanProfile

data class ResolvedEnvironment(
    val wifiConnected: WifiConnectedProfile? = null,
    val wifiScan: WifiScanProfile? = null,
    val cellTowers: List<CellTowerProfile> = emptyList(),
    val bleDevices: List<BleDeviceProfile> = emptyList(),
    val sourceSampleIds: List<Long> = emptyList(),
) {
    val hasAnyProfile: Boolean
        get() = wifiConnected != null || !wifiScan?.entries.isNullOrEmpty() ||
            cellTowers.isNotEmpty() || bleDevices.isNotEmpty()
}

class EnvironmentRepository(private val database: EnvironmentDatabase) {
    private val dao = database.environmentDao()

    suspend fun save(capture: EnvironmentCapture): Long = database.withTransaction {
        saveInTransaction(capture)
    }

    private suspend fun saveInTransaction(capture: EnvironmentCapture): Long {
        val sampleId = dao.insertSample(EnvironmentSample(
            latitude = capture.latitude,
            longitude = capture.longitude,
            source = capture.source.name,
            label = capture.label,
            note = capture.note,
        ))
        dao.insertWifi(capture.wifi.map { it.copy(id = 0, sampleId = sampleId) })
        dao.insertCells(capture.cells.map { it.copy(id = 0, sampleId = sampleId) })
        dao.insertBle(capture.ble.map { it.copy(id = 0, sampleId = sampleId) })
        return sampleId
    }

    suspend fun import(capture: EnvironmentCapture, metadata: EnvironmentSample): Long = database.withTransaction {
        val id = saveInTransaction(capture)
        dao.updateSample(metadata.copy(id = id, source = EnvironmentSource.IMPORT.name))
        id
    }

    fun observeAll() = dao.observeAll()
    fun observeCount() = dao.observeCount()
    suspend fun getAll() = dao.getAll()
    suspend fun getById(id: Long) = dao.getById(id)
    suspend fun update(sample: EnvironmentSample) = dao.updateSample(sample)
    suspend fun delete(ids: List<Long>) = dao.delete(ids)
    suspend fun clear() = dao.clear()

    suspend fun resolve(lat: Double, lon: Double, radiusMeters: Double = 50.0, limit: Int = 3): ResolvedEnvironment {
        val latDelta = radiusMeters / 111_320.0
        val lonScale = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val lonDelta = radiusMeters / (111_320.0 * lonScale)
        val candidates = dao.nearby(
            lat, lon, lat - latDelta, lat + latDelta, lon - lonDelta, lon + lonDelta, limit * 3,
        ).mapNotNull { sample ->
            val distance = distanceMeters(lat, lon, sample.sample.latitude, sample.sample.longitude)
            sample.takeIf { distance <= radiusMeters }?.let { it to distance }
        }.sortedBy { it.second }.take(limit)
        if (candidates.isEmpty()) return ResolvedEnvironment()

        val weighted = candidates.map { (sample, distance) ->
            Triple(sample, distance, 1.0 / (distance.coerceAtLeast(1.0) * distance.coerceAtLeast(1.0)))
        }
        val closest = weighted.first().first
        val preferredWifi = closest.sample.preferredWifiBssid?.let { key -> closest.wifi.firstOrNull { it.bssid == key } }
            ?: closest.wifi.firstOrNull { it.connected }
        val wifiByBssid = linkedMapOf<String, MutableList<Pair<WifiObservation, Double>>>()
        val cellByKey = linkedMapOf<String, MutableList<Pair<CellObservation, Double>>>()
        val bleByAddress = linkedMapOf<String, MutableList<Pair<BleObservation, Double>>>()
        weighted.forEach { (sample, _, weight) ->
            sample.wifi.forEach { wifiByBssid.getOrPut(it.bssid.uppercase()) { mutableListOf() } += it to weight }
            sample.cells.forEach { cellByKey.getOrPut(it.cellKey) { mutableListOf() } += it to weight }
            sample.ble.forEach { bleByAddress.getOrPut(it.address.uppercase()) { mutableListOf() } += it to weight }
        }

        val wifiEntries = wifiByBssid.values.map { values ->
            val item = values.first().first
            WifiScanEntry(
                ssid = item.ssid,
                bssid = item.bssid,
                rssiDbm = weightedAverage(values) { it.rssiDbm },
                frequencyMhz = item.frequencyMhz,
                capabilities = item.capabilities,
                standard = item.standard,
                channelWidth = item.channelWidth,
            )
        }.sortedByDescending { it.rssiDbm }.take(32)

        val cellProfiles = cellByKey.values.mapNotNull { values ->
            val item = values.first().first
            val rat = runCatching { CellRat.valueOf(item.rat.uppercase()) }.getOrNull() ?: return@mapNotNull null
            CellTowerProfile(
                rat = rat, mcc = item.mcc, mnc = item.mnc,
                tacOrLac = item.tacOrLac, cellId = item.cellId, pciOrPsc = item.pciOrPsc,
                arfcn = item.arfcn, rssiDbm = weightedAverage(values) { it.rssiDbm },
                registered = closest.sample.preferredCellKey?.let { it == item.cellKey } ?: item.registered,
            )
        }
        val bleProfiles = bleByAddress.values.map { values ->
            val item = values.first().first
            BleDeviceProfile(
                address = item.address, name = item.name,
                rssiDbm = weightedAverage(values) { it.rssiDbm },
                manufacturerDataHex = item.manufacturerDataHex,
                serviceDataHex = item.serviceDataHex,
                txPowerDbm = item.txPowerDbm,
                connectable = item.connectable,
                connected = closest.sample.preferredBleAddress?.equals(item.address, true) == true,
            )
        }.take(64)

        return ResolvedEnvironment(
            wifiConnected = preferredWifi?.let {
                WifiConnectedProfile(
                    ssid = it.ssid, bssid = it.bssid, macAddress = it.macAddress,
                    rssiDbm = it.rssiDbm, linkSpeedMbps = it.linkSpeedMbps,
                    frequencyMhz = it.frequencyMhz, securityType = it.capabilities,
                )
            },
            wifiScan = WifiScanProfile(wifiEntries, maxEntries = wifiEntries.size),
            cellTowers = cellProfiles,
            bleDevices = bleProfiles,
            sourceSampleIds = candidates.map { it.first.sample.id },
        )
    }

    private fun <T> weightedAverage(values: List<Pair<T, Double>>, value: (T) -> Int): Int {
        val totalWeight = values.sumOf { it.second }
        return (values.sumOf { value(it.first) * it.second } / totalWeight).toInt()
    }

    companion object {
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2.0 * 6_378_137.0 * atan2(sqrt(a), sqrt(1.0 - a))
        }
    }
}
