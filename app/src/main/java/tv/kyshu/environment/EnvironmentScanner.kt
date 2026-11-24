package tv.kyshu.environment

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class EnvironmentScanner(private val context: Context) {
    @SuppressLint("MissingPermission")
    suspend fun capture(latitude: Double, longitude: Double, settings: EnvironmentSettings): EnvironmentCapture =
        withContext(Dispatchers.IO) {
            EnvironmentCapture(
                latitude = latitude,
                longitude = longitude,
                wifi = if (settings.scanWifi) scanWifi() else emptyList(),
                cells = if (settings.scanCell) scanCells() else emptyList(),
                ble = if (settings.scanBle) scanBle() else emptyList(),
            )
        }

    @SuppressLint("MissingPermission")
    fun scanWifi(): List<WifiObservation> {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val connection = runCatching { manager.connectionInfo }.getOrNull()
        val connectedBssid = connection?.bssid?.takeUnless { it == "02:00:00:00:00:00" }
        val results = runCatching { manager.scanResults }.getOrDefault(emptyList())
        val observations = results.mapNotNull { result -> result.toObservation(connectedBssid, connection) }.toMutableList()
        if (connectedBssid != null && observations.none { it.bssid.equals(connectedBssid, true) }) {
            observations += WifiObservation(
                sampleId = 0,
                ssid = connection.ssid?.removeSurrounding("\"").orEmpty().takeUnless { it == "<unknown ssid>" }.orEmpty(),
                bssid = connectedBssid,
                rssiDbm = connection.rssi.coerceIn(-127, 0),
                frequencyMhz = connection.frequency.coerceAtLeast(1),
                connected = true,
                macAddress = connection.macAddress.orEmpty(),
                linkSpeedMbps = connection.linkSpeed.coerceAtLeast(0),
            )
        }
        return observations.distinctBy { it.bssid.uppercase() }.take(32)
    }

    @Suppress("DEPRECATION")
    private fun WifiScanResult.toObservation(connectedBssid: String?, connection: android.net.wifi.WifiInfo?): WifiObservation? {
        val address = BSSID?.takeIf { MAC.matches(it) } ?: return null
        val isConnected = address.equals(connectedBssid, true)
        return WifiObservation(
            sampleId = 0,
            ssid = SSID.orEmpty(),
            bssid = address,
            rssiDbm = level.coerceIn(-127, 0),
            frequencyMhz = frequency.coerceAtLeast(1),
            capabilities = capabilities.orEmpty(),
            standard = if (Build.VERSION.SDK_INT >= 30) wifiStandard.toString() else "",
            channelWidth = if (Build.VERSION.SDK_INT >= 23) channelWidth else 0,
            connected = isConnected,
            macAddress = if (isConnected) connection?.macAddress.orEmpty() else "",
            linkSpeedMbps = if (isConnected) connection?.linkSpeed?.coerceAtLeast(0) ?: 0 else 0,
        )
    }

    @SuppressLint("MissingPermission")
    fun scanCells(): List<CellObservation> {
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return emptyList()
        return runCatching { manager.allCellInfo.orEmpty().mapNotNull(::cellObservation) }.getOrDefault(emptyList()).take(32)
    }

    private fun cellObservation(info: CellInfo): CellObservation? = when (info) {
        is CellInfoLte -> with(info.cellIdentity) {
            cell("LTE", mcc(), mnc(), tac.valid(), ci.toLong().valid(), pci.valid(), earfcn.valid(), info.cellSignalStrength.dbm, info.isRegistered)
        }
        is CellInfoGsm -> with(info.cellIdentity) {
            cell("GSM", mcc(), mnc(), lac.valid(), cid.toLong().valid(), null, arfcn.valid(), info.cellSignalStrength.dbm, info.isRegistered)
        }
        is CellInfoWcdma -> with(info.cellIdentity) {
            cell("WCDMA", mcc(), mnc(), lac.valid(), cid.toLong().valid(), psc.valid(), uarfcn.valid(), info.cellSignalStrength.dbm, info.isRegistered)
        }
        is CellInfoCdma -> with(info.cellIdentity) {
            cell("CDMA", "000", "00", networkId.valid(), basestationId.toLong().valid(), systemId.valid(), null, info.cellSignalStrength.dbm, info.isRegistered)
        }
        is CellInfoNr -> if (Build.VERSION.SDK_INT >= 29) {
            val identity = info.cellIdentity as? android.telephony.CellIdentityNr ?: return null
            cell("NR", identity.mccString.orEmpty(), identity.mncString.orEmpty(), identity.tac.valid(), identity.nci.valid(), identity.pci.valid(), identity.nrarfcn.valid(), info.cellSignalStrength.dbm, info.isRegistered)
        } else null
        else -> null
    }

    @Suppress("DEPRECATION") private fun android.telephony.CellIdentityLte.mcc() = if (Build.VERSION.SDK_INT >= 28) mccString.orEmpty() else mcc.toString()
    @Suppress("DEPRECATION") private fun android.telephony.CellIdentityLte.mnc() = if (Build.VERSION.SDK_INT >= 28) mncString.orEmpty() else mnc.toString().padStart(2, '0')
    @Suppress("DEPRECATION") private fun android.telephony.CellIdentityGsm.mcc() = if (Build.VERSION.SDK_INT >= 28) mccString.orEmpty() else mcc.toString()
    @Suppress("DEPRECATION") private fun android.telephony.CellIdentityGsm.mnc() = if (Build.VERSION.SDK_INT >= 28) mncString.orEmpty() else mnc.toString().padStart(2, '0')
    @Suppress("DEPRECATION") private fun android.telephony.CellIdentityWcdma.mcc() = if (Build.VERSION.SDK_INT >= 28) mccString.orEmpty() else mcc.toString()
    @Suppress("DEPRECATION") private fun android.telephony.CellIdentityWcdma.mnc() = if (Build.VERSION.SDK_INT >= 28) mncString.orEmpty() else mnc.toString().padStart(2, '0')

    private fun cell(rat: String, mcc: String, mnc: String, area: Int?, id: Long?, pci: Int?, arfcn: Int?, dbm: Int, registered: Boolean): CellObservation? {
        if (id == null || mcc.length != 3 || mnc.length !in 2..3) return null
        val key = listOf(rat, mcc, mnc, area ?: 0, id, pci ?: 0).joinToString("_")
        return CellObservation(
            sampleId = 0, cellKey = key, rat = rat, mcc = mcc, mnc = mnc,
            tacOrLac = area, cellId = id, pciOrPsc = pci, arfcn = arfcn,
            rssiDbm = dbm.coerceIn(-140, 0), registered = registered,
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun scanBle(durationMillis: Long = 2_000): List<BleObservation> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptyList()
        val scanner = manager.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return emptyList()
        return suspendCancellableCoroutine { continuation ->
            val results = linkedMapOf<String, BleObservation>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) { results[result.device.address] = result.toObservation() }
                override fun onBatchScanResults(batch: MutableList<ScanResult>) { batch.forEach { results[it.device.address] = it.toObservation() } }
                override fun onScanFailed(errorCode: Int) { if (continuation.isActive) continuation.resume(emptyList()) }
            }
            runCatching { scanner.startScan(callback) }.onFailure {
                continuation.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            val handler = Handler(Looper.getMainLooper())
            val finish = Runnable {
                runCatching { scanner.stopScan(callback) }
                if (continuation.isActive) continuation.resume(results.values.take(64))
            }
            handler.postDelayed(finish, durationMillis.coerceIn(250, 30_000))
            continuation.invokeOnCancellation {
                handler.removeCallbacks(finish)
                runCatching { scanner.stopScan(callback) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toObservation(): BleObservation {
        val manufacturerHex = scanRecord?.manufacturerSpecificData?.let { data ->
            (0 until data.size()).joinToString("") { index -> data.valueAt(index).toHex() }
        }.orEmpty()
        val serviceHex = scanRecord?.serviceData?.entries?.joinToString("") { it.value.toHex() }.orEmpty()
        return BleObservation(
            sampleId = 0, address = device.address, name = device.name.orEmpty(),
            rssiDbm = rssi.coerceIn(-127, 20), manufacturerDataHex = manufacturerHex,
            serviceDataHex = serviceHex, txPowerDbm = txPower.takeUnless { it == 127 },
            connectable = if (Build.VERSION.SDK_INT >= 26) isConnectable else true,
        )
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
    private fun Int.valid() = takeUnless { it == Int.MAX_VALUE || it < 0 }
    private fun Long.valid() = takeUnless { it == Long.MAX_VALUE || it < 0 }

    companion object { private val MAC = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") }
}
