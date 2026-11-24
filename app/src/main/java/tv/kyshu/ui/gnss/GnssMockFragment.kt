package tv.kyshu.ui.gnss

import dagger.hilt.android.AndroidEntryPoint
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tv.kyshu.android.widget.SatelliteData
import tv.kyshu.android.widget.SatelliteRadarView
import tv.kyshu.ext.altitudeJitter
import tv.kyshu.ext.enableAGPS
import tv.kyshu.ext.enableGetFromLocation
import tv.kyshu.ext.enableNMEA
import tv.kyshu.ext.enableRequestGeofence
import tv.kyshu.gnss.EphemerisManager
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.compose.components.PortalSection
import tv.kyshu.ui.compose.components.PortalSwitchRow
import tv.kyshu.ui.compose.theme.PortalTheme
import tv.kyshu.xposed.utils.Log

@AndroidEntryPoint
class GnssMockFragment : Fragment() {
    private lateinit var locationManager: LocationManager
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var isGnssCallbackRegistered = false
    private var lastGnssUiUpdateAt = 0L
    private var gnssUiState by mutableStateOf(GnssUiState())
    private var ephemerisStatus by mutableStateOf("")
    private var ephemerisDownloading by mutableStateOf(false)
    private var requestGeofenceEnabled by mutableStateOf(false)
    private var getFromLocationEnabled by mutableStateOf(false)
    private var agpsEnabled by mutableStateOf(false)
    private var nmeaEnabled by mutableStateOf(false)

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            Toast.makeText(requireContext(), "所有权限都已授予", Toast.LENGTH_SHORT).show()
            registerGnssStatusCallback()
        } else {
            Toast.makeText(requireContext(), "位置权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            loadEphemerisStatus()
            requestGeofenceEnabled = requireContext().enableRequestGeofence
            getFromLocationEnabled = requireContext().enableGetFromLocation
            agpsEnabled = requireContext().enableAGPS
            nmeaEnabled = requireContext().enableNMEA
            requestLocationPermissionIfNeeded()
            setContent { PortalTheme { GnssScreen() } }
        }

    @Composable
    private fun GnssScreen() {
        val context = requireContext()
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("GNSS 模拟", style = MaterialTheme.typography.headlineSmall)
            Text(
                "查看当前卫星状态并配置 GNSS 模拟行为。修改后需要重启 GNSS 模拟生效。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PortalSection("卫星信息") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("可见卫星", gnssUiState.inView.toString())
                        Metric("在用卫星", gnssUiState.inUse.toString())
                        Metric("平均信号", "${"%.1f".format(gnssUiState.averageSignal)} dB-Hz")
                    }
                    AndroidView(
                        factory = { viewContext -> SatelliteRadarView(viewContext, null) },
                        update = { it.setSatellites(gnssUiState.satellites) },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                    Text(
                        "GPS ${gnssUiState.gps} · GNS ${gnssUiState.glonass} · 北斗 ${gnssUiState.beidou}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PortalSection("星历") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        ephemerisStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { downloadEphemeris() },
                        enabled = !ephemerisDownloading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (ephemerisDownloading) "正在下载…" else "下载星历") }
                }
            }

            PortalSection("功能设置") {
                PortalSwitchRow(
                    "请求 Geofence",
                    "允许模拟器请求地理围栏",
                    requestGeofenceEnabled,
                    { requestGeofenceEnabled = it; context.enableRequestGeofence = it; changed("Geofence", it) },
                )
                PortalSwitchRow(
                    "从位置获取",
                    "允许从位置服务读取辅助信息",
                    getFromLocationEnabled,
                    { getFromLocationEnabled = it; context.enableGetFromLocation = it; changed("getFromLocation", it) },
                )
                PortalSwitchRow(
                    "启用 AGPS",
                    "使用辅助 GPS 数据",
                    agpsEnabled,
                    { agpsEnabled = it; context.enableAGPS = it; changed("AGPS", it) },
                )
                PortalSwitchRow(
                    "输出 NMEA",
                    "启用 NMEA 语句模拟",
                    nmeaEnabled,
                    { nmeaEnabled = it; context.enableNMEA = it; changed("NMEA", it) },
                )
            }
        }
    }

    @Composable
    private fun Metric(label: String, value: String) {
        Column {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    private fun changed(name: String, enabled: Boolean) {
        Log.switchOp("GNSS", name, enabled)
        Toast.makeText(requireContext(), "重启 GNSS 模拟后生效", Toast.LENGTH_SHORT).show()
    }

    private fun loadEphemerisStatus() {
        val prefs = requireContext().getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)
        ephemerisStatus = prefs.getString("rinexPath", null)?.let { "星历文件：$it" }
            ?: "未加载星历，卫星位置使用确定性轨道模板"
    }

    private fun downloadEphemeris() {
        Log.buttonOp("GNSS", "下载星历")
        ephemerisDownloading = true
        ephemerisStatus = "正在下载…"
        EphemerisManager.updateEphemeris(requireContext(), locationManager) { success, message ->
            lifecycleScope.launch(Dispatchers.Main) {
                ephemerisDownloading = false
                ephemerisStatus = if (success) {
                    val path = requireContext().getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)
                        .getString("rinexPath", null)
                    path?.let { "星历文件：$it" } ?: message
                } else message
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) registerGnssStatusCallback() else requestMultiplePermissionsLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun ensureGnssStatusCallback() {
        if (gnssStatusCallback != null) return
        gnssStatusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastGnssUiUpdateAt < 1_000L) return
                lastGnssUiUpdateAt = now
                val satellites = buildList {
                    for (i in 0 until status.satelliteCount) {
                        add(SatelliteData(status.getSvid(i), status.getCn0DbHz(i), status.getElevationDegrees(i), status.getAzimuthDegrees(i), false, false, status.usedInFix(i)))
                    }
                }
                val inUse = satellites.count { it.usedInFix }
                val signal = satellites.filter { it.usedInFix }.map { it.snr }
                gnssUiState = GnssUiState(
                    inView = satellites.size,
                    inUse = inUse,
                    averageSignal = signal.averageOrNull() ?: 0f,
                    gps = satellites.count { it.prn in 1..32 },
                    glonass = satellites.count { it.prn in 65..96 },
                    beidou = satellites.count { it.prn in 201..237 },
                    satellites = satellites,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssStatusCallback() {
        if (isGnssCallbackRegistered) return
        ensureGnssStatusCallback()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.registerGnssStatusCallback(requireContext().mainExecutor, gnssStatusCallback!!)
            } else {
                @Suppress("DEPRECATION")
                locationManager.registerGnssStatusCallback(gnssStatusCallback!!, null)
            }
            isGnssCallbackRegistered = true
        }.onFailure { Log.e("GnssMockFragment", "registerGnssStatusCallback failed", it) }
    }

    override fun onResume() { super.onResume(); if (::locationManager.isInitialized) registerGnssStatusCallback() }

    override fun onPause() {
        super.onPause()
        gnssStatusCallback?.let { if (isGnssCallbackRegistered) runCatching { locationManager.unregisterGnssStatusCallback(it) } }
        isGnssCallbackRegistered = false
    }

    private data class GnssUiState(
        val inView: Int = 0,
        val inUse: Int = 0,
        val averageSignal: Float = 0f,
        val gps: Int = 0,
        val glonass: Int = 0,
        val beidou: Int = 0,
        val satellites: List<SatelliteData> = emptyList(),
    )
}

private fun List<Float>.averageOrNull(): Float? = if (isEmpty()) null else average().toFloat()

