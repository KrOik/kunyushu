@file:Suppress("SetTextI18n")

package tv.kyshu.ui.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import tv.kyshu.databinding.FragmentGnssMockBinding
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tv.kyshu.R
import tv.kyshu.android.widget.SatelliteData
import tv.kyshu.ext.altitudeJitter
import tv.kyshu.ext.enableAGPS
import tv.kyshu.ext.enableGetFromLocation
import tv.kyshu.ext.enableNMEA
import tv.kyshu.ext.enableRequestGeofence
import tv.kyshu.ext.hookSensor
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.xposed.utils.Log
import tv.kyshu.gnss.EphemerisManager

class GnssMockFragment : Fragment() {
    private var _binding: FragmentGnssMockBinding? = null
    private val binding get() = _binding!!
    private lateinit var locationManager: LocationManager
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var isGnssCallbackRegistered = false
    private var lastGnssUiUpdateAt = 0L

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(requireContext(), "所有权限都已授予", Toast.LENGTH_SHORT).show()
            registerGnssStatusCallback()
        } else {
            val deniedPermissions = permissions.filter { !it.value }.keys.joinToString()
            Toast.makeText(requireContext(), "以下权限被拒绝: $deniedPermissions", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGnssMockBinding.inflate(inflater, container, false)
        val root: View = binding.root

        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val permissionsToRequest = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val permissionsNeeded = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (permissionsNeeded.isEmpty()) {
            registerGnssStatusCallback()
        } else {
            requestMultiplePermissionsLauncher.launch(permissionsNeeded)
        }

        // 星历下载 — 从 SharedPreferences 读取持久化的路径（跨页面切换不会丢失）
        val prefs = requireContext().getSharedPreferences(
            tv.kyshu.service.MockServiceHelper.PROVIDER_NAME,
            android.content.Context.MODE_PRIVATE
        )
        val rinexPath = prefs.getString("rinexPath", null)
        binding.ephemerisStatus.text = if (rinexPath != null) {
            "星历文件: $rinexPath"
        } else {
            "未加载星历，卫星位置使用确定性轨道模板"
        }
        binding.btnDownloadEphemeris.setOnClickListener {
            Log.buttonOp("GNSS", "下载星历")
            binding.btnDownloadEphemeris.isEnabled = false
            binding.ephemerisStatus.text = "正在下载..."
            showToast("正在下载星历")
            EphemerisManager.updateEphemeris(requireContext(), locationManager) { success, msg ->
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.btnDownloadEphemeris.isEnabled = true
                    binding.ephemerisStatus.text = msg
                    // 下载成功时，SharedPreferences 已由 EphemerisManager 写入
                    if (success) {
                        val savedPath = prefs.getString("rinexPath", null)
                        if (savedPath != null) binding.ephemerisStatus.text = "星历文件: $savedPath"
                    }
                    showToast(msg)
                }
            }
        }

        // 功能设置
        binding.switchRequestGeofence.isChecked = requireContext().enableRequestGeofence
        binding.switchRequestGeofence.setOnCheckedChangeListener { _, isChecked ->
            requireContext().enableRequestGeofence = isChecked
            Log.switchOp("GNSS", "Geofence", isChecked)
            showToast("重启GNSS模拟生效")
        }

        binding.switchGetFromLocation.isChecked = requireContext().enableGetFromLocation
        binding.switchGetFromLocation.setOnCheckedChangeListener { _, isChecked ->
            requireContext().enableGetFromLocation = isChecked
            Log.switchOp("GNSS", "getFromLocation", isChecked)
            showToast("重启GNSS模拟生效")
        }

        binding.switchEnableAgps.isChecked = requireContext().enableAGPS
        binding.switchEnableAgps.setOnCheckedChangeListener { _, isChecked ->
            requireContext().enableAGPS = isChecked
            Log.switchOp("GNSS", "AGPS", isChecked)
            showToast("重启GNSS模拟生效")
        }

        binding.switchEnableNmea.isChecked = requireContext().enableNMEA
        binding.switchEnableNmea.setOnCheckedChangeListener { _, isChecked ->
            requireContext().enableNMEA = isChecked
            Log.switchOp("GNSS", "NMEA", isChecked)
            showToast("重启GNSS模拟生效")
        }

        return root
    }

    private fun showToast(message: String) = lifecycleScope.launch(Dispatchers.Main) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetTextI18n")
    private fun ensureGnssStatusCallback() {
        if (gnssStatusCallback != null) return
        gnssStatusCallback = object: GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                if (_binding == null) return
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastGnssUiUpdateAt < 1_000L) return
                lastGnssUiUpdateAt = now

                val satelliteCount = status.satelliteCount
                var inViewSatelliteCount = 0
                var inUseSatelliteCount = 0
                val signalStrength = arrayListOf<Float>()
                var gpsCount = 0
                var glonassCount = 0
                var beidouCount = 0

                val satellites = mutableListOf<SatelliteData>()
                for (i in 0 until satelliteCount) {
                    val svid = status.getSvid(i)
                    val carrierToNoise = status.getCn0DbHz(i)
                    val elevation = status.getElevationDegrees(i)
                    val azimuth = status.getAzimuthDegrees(i)
                    val usedInFix = status.usedInFix(i)

                    satellites.add(SatelliteData(
                        prn = svid,
                        snr = carrierToNoise,
                        elevation = elevation,
                        azimuth = azimuth,
                        hasAlmanac = false,
                        hasEphemeris = false,
                        usedInFix = usedInFix
                    ))

                    inViewSatelliteCount += 1
                    if (usedInFix) {
                        inUseSatelliteCount += 1
                        signalStrength.add(carrierToNoise)
                    }

                    when (svid) {
                        in 1..32 -> gpsCount += 1
                        in 65..96 -> glonassCount += 1
                        in 201..237 -> beidouCount += 1
                    }
                }

                val avgSignalStrength = signalStrength.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
                binding.signalStrengthRightItem.text = "${avgSignalStrength.toString().take(5)} dB-Hz"
                binding.inViewRightItem.text = "$inViewSatelliteCount"
                binding.inUseRightItem.text = "$inUseSatelliteCount"
                binding.gpsCount.text = "GPS: $gpsCount"
                binding.glonassCount.text = "GNS: $glonassCount"
                binding.beidouCount.text = "BD: $beidouCount"
                binding.satelliteRadaView.setSatellites(satellites)
            }
        }
    }

    private fun registerGnssStatusCallback() {
        if (_binding == null || isGnssCallbackRegistered) return
        ensureGnssStatusCallback()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.registerGnssStatusCallback(requireContext().mainExecutor, gnssStatusCallback ?: return)
            } else {
                @Suppress("DEPRECATION")
                locationManager.registerGnssStatusCallback(gnssStatusCallback ?: return, null)
            }
            isGnssCallbackRegistered = true
        } catch (e: SecurityException) {
            Log.e("GnssMockFragment", "registerGnssStatusCallback failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::locationManager.isInitialized &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            registerGnssStatusCallback()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterGnssStatusCallback()
    }

    private fun unregisterGnssStatusCallback() {
        val cb = gnssStatusCallback ?: return
        if (::locationManager.isInitialized && isGnssCallbackRegistered) {
            runCatching { locationManager.unregisterGnssStatusCallback(cb) }
            isGnssCallbackRegistered = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterGnssStatusCallback()
        _binding = null
    }
}
