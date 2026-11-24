package tv.kyshu.android.root

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import tv.kyshu.BuildConfig

/**
 * Captures non-unique device and app state for root probe evidence.
 *
 * Do not add serials, build fingerprints, Android ID, telephony identifiers, or
 * attestation identifiers here. The evidence model also rejects those names.
 */
object RootProbeDeviceContext {
    fun capture(
        context: Context,
        moduleScope: String,
        targetProcess: String = currentProcessName(context),
    ): RootProbeEvidence.DeviceContext {
        val appContext = context.applicationContext ?: context
        return RootProbeEvidence.DeviceContext(
            sdk = Build.VERSION.SDK_INT,
            rom = buildRomLabel(),
            manufacturer = Build.MANUFACTURER.orUnknown(),
            brand = Build.BRAND.orUnknown(),
            model = Build.MODEL.orUnknown(),
            moduleScope = moduleScope.ifBlank { "unknown" },
            targetProcess = targetProcess.ifBlank { currentProcessName(appContext) },
            permissions = permissionSnapshot(appContext),
            featureFlags = featureFlagSnapshot(),
        )
    }

    private fun currentProcessName(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName().ifBlank { context.packageName }
        } else {
            context.packageName
        }
    }

    private fun buildRomLabel(): String {
        val release = Build.VERSION.RELEASE.orUnknown()
        val codename = Build.VERSION.CODENAME.orUnknown()
        return "Android $release $codename".trim()
    }

    private fun permissionSnapshot(context: Context): Map<String, Boolean> {
        return trackedPermissions().associateWith { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }.toSortedMap()
    }

    private fun trackedPermissions(): List<String> {
        return buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS)
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.CHANGE_WIFI_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            add(Manifest.permission.NFC)
        }
    }

    private fun featureFlagSnapshot(): Map<String, Boolean> {
        return sortedMapOf(
            "ENABLE_LOCATION_MOCK" to BuildConfig.ENABLE_LOCATION_MOCK,
            "ENABLE_GNSS_MOCK" to BuildConfig.ENABLE_GNSS_MOCK,
            "ENABLE_NMEA_MOCK" to BuildConfig.ENABLE_NMEA_MOCK,
            "ENABLE_SENSOR_MOCK" to BuildConfig.ENABLE_SENSOR_MOCK,
            "ENABLE_WIFI_MOCK" to BuildConfig.ENABLE_WIFI_MOCK,
            "ENABLE_CELL_MOCK" to BuildConfig.ENABLE_CELL_MOCK,
            "ENABLE_BLE_MOCK" to BuildConfig.ENABLE_BLE_MOCK,
            "ENABLE_NFC_MOCK" to BuildConfig.ENABLE_NFC_MOCK,
        )
    }

    private fun String?.orUnknown(): String = this?.takeIf { it.isNotBlank() } ?: "unknown"
}
