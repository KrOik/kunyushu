package tv.kyshu.ui.home

import dagger.hilt.android.AndroidEntryPoint
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.LogoPosition
import com.baidu.mapapi.map.MapPoi
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.MyLocationConfiguration
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.kyshu.MainActivity
import tv.kyshu.Portal
import tv.kyshu.R
import tv.kyshu.bdmap.locateMe
import tv.kyshu.bdmap.setMapConfig
import tv.kyshu.environment.EnvironmentSettings
import tv.kyshu.ext.gcj02
import tv.kyshu.ext.mapType
import tv.kyshu.ext.rawHistoricalLocations
import tv.kyshu.ext.selectRoute
import tv.kyshu.ext.wgs84
import tv.kyshu.ui.compose.portalComposeView
import tv.kyshu.ui.viewmodel.BaiduMapViewModel
import tv.kyshu.ui.viewmodel.MockServiceViewModel
import java.math.BigDecimal

@AndroidEntryPoint
class HomeFragment : Fragment() {
    private val baiduMapViewModel by activityViewModels<BaiduMapViewModel>()
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()
    private lateinit var mapView: MapView
    private lateinit var mLocationClient: LocationClient
    private var mapDragJob: Job? = null

    @SuppressLint("SetTextI18n")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        mapView = MapView(requireContext()).also { it.onCreate(requireContext(), savedInstanceState) }
        with(baiduMapViewModel) {
            isExists = true
            baiduMap = mapView.map
        }
        mapView.showZoomControls(true)
        mapView.showScaleControl(true)
        mapView.logoPosition = LogoPosition.logoPostionRightTop
        with(mapView.map) {
            setMapStatus(MapStatusUpdateFactory.zoomTo(19f))
            mapType = context?.mapType ?: BaiduMap.MAP_TYPE_NORMAL
            compassPosition = Point(50, 50)
            setCompassEnable(true)
            uiSettings.isCompassEnabled = true
            uiSettings.isOverlookingGesturesEnabled = true
            isMyLocationEnabled = true
            setMapConfig(baiduMapViewModel.perspectiveState, R.drawable.icon_my_location)
            setOnMapClickListener(object : BaiduMap.OnMapClickListener {
                override fun onMapClick(loc: LatLng) {
                    baiduMapViewModel.markedLoc = loc.wgs84
                    baiduMapViewModel.showDetailView = false
                    baiduMapViewModel.mGeoCoder?.reverseGeoCode(ReverseGeoCodeOption().location(loc))
                    markMap()
                }
                override fun onMapPoiClick(poi: MapPoi) = Unit
            })
            setOnMapLongClickListener { loc ->
                loc ?: return@setOnMapLongClickListener
                baiduMapViewModel.markedLoc = loc.wgs84
                baiduMapViewModel.showDetailView = true
                baiduMapViewModel.mGeoCoder?.reverseGeoCode(ReverseGeoCodeOption().location(loc))
                markMap()
            }
            var gestureMove = false
            setOnMapStatusChangeListener(object : BaiduMap.OnMapStatusChangeListener {
                override fun onMapStatusChangeStart(status: MapStatus?) = Unit
                override fun onMapStatusChangeStart(status: MapStatus?, reason: Int) {
                    gestureMove = reason == BaiduMap.OnMapStatusChangeListener.REASON_GESTURE
                }
                override fun onMapStatusChange(status: MapStatus?) = Unit
                override fun onMapStatusChangeFinish(status: MapStatus?) {
                    val target = status?.target ?: return
                    if (!gestureMove || !mockServiceViewModel.isServiceStart() || !EnvironmentSettings(requireContext()).liveMapUpdateEnabled) return
                    gestureMove = false
                    val wgs = target.wgs84
                    mapDragJob?.cancel()
                    mapDragJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        delay(350)
                        mockServiceViewModel.locationManager?.let { manager ->
                            mockServiceViewModel.setLocationAndReplay(manager, wgs.first, wgs.second, true)
                        }
                    }
                }
            })
        }

        mLocationClient = LocationClient(requireContext())
        val option = LocationClientOption().apply {
            isOpenGps = true
            enableSimulateGps = false
            setIsNeedAddress(true)
            setNeedDeviceDirect(false)
            isLocationNotify = false
            setIgnoreKillProcess(true)
            setIsNeedLocationDescribe(false)
            setIsNeedLocationPoiList(false)
            isOpenGnss = true
            setIsNeedAltitude(false)
            locationMode = LocationClientOption.LocationMode.Hight_Accuracy
            setCoorType(Portal.DEFAULT_COORD_STR)
            setScanSpan(3000)
        }
        mLocationClient.locOption = option
        mLocationClient.registerLocationListener(object : BDAbstractLocationListener() {
            override fun onReceiveLocation(loc: BDLocation?) {
                loc ?: return
                MainActivity.mCityString = loc.city
                baiduMapViewModel.currentLocation = loc.wgs84
                baiduMapViewModel.baiduMap.setMyLocationData(
                    MyLocationData.Builder().accuracy(loc.radius).direction(loc.direction).latitude(loc.latitude).longitude(loc.longitude).build(),
                )
            }
        })
        baiduMapViewModel.mLocationClient = mLocationClient
        mLocationClient.enableLocInForeground(1, baiduMapViewModel.mNotification)
        mLocationClient.start()
        return portalComposeView { HomeScreen() }
    }

    @Composable
    private fun HomeScreen() {
        var satellite by remember { mutableStateOf(mapView.map.mapType == BaiduMap.MAP_TYPE_SATELLITE) }
        var routeVisible by remember { mutableStateOf(false) }
        var actionsExpanded by remember { mutableStateOf(false) }
        var coordinateDialog by remember { mutableStateOf(false) }
        var addLocationDialog by remember { mutableStateOf(false) }
        var latitude by remember { mutableStateOf(baiduMapViewModel.currentLocation?.first?.toString().orEmpty()) }
        var longitude by remember { mutableStateOf(baiduMapViewModel.currentLocation?.second?.toString().orEmpty()) }
        var locationName by remember { mutableStateOf("") }
        var locationAddress by remember { mutableStateOf("") }
        var locationLatLon by remember { mutableStateOf("") }
        var dialogError by remember { mutableStateOf<String?>(null) }

        fun openCoordinateDialog() {
            baiduMapViewModel.currentLocation?.let {
                latitude = BigDecimal.valueOf(it.first).toPlainString()
                longitude = BigDecimal.valueOf(it.second).toPlainString()
            }
            dialogError = null
            coordinateDialog = true
        }
        fun openAddLocationDialog() {
            val loc = baiduMapViewModel.markedLoc ?: baiduMapViewModel.currentLocation
            if (loc == null) { Toast.makeText(requireContext(), "暂无可保存的位置", Toast.LENGTH_SHORT).show(); return }
            locationName = if (baiduMapViewModel.markedLoc == null) "当前位置-${System.currentTimeMillis()}" else "标点位置-${System.currentTimeMillis()}"
            locationAddress = baiduMapViewModel.markName ?: "位置地址"
            locationLatLon = "${BigDecimal.valueOf(loc.second).toPlainString()}, ${BigDecimal.valueOf(loc.first).toPlainString()}"
            dialogError = null
            addLocationDialog = true
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Card(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f))) {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { satellite = false; mapView.map.mapType = BaiduMap.MAP_TYPE_NORMAL; requireContext().mapType = BaiduMap.MAP_TYPE_NORMAL }) { Text("普通") }
                    OutlinedButton(onClick = { satellite = true; mapView.map.mapType = BaiduMap.MAP_TYPE_SATELLITE; requireContext().mapType = BaiduMap.MAP_TYPE_SATELLITE }) { Text("卫星") }
                }
            }
            Card(modifier = Modifier.align(Alignment.TopStart).padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f))) {
                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("显示路线", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = routeVisible, onCheckedChange = {
                        routeVisible = it
                        if (it) requireContext().selectRoute?.let { route -> previewRoute(route.route); route.route.firstOrNull()?.let { point -> mapView.map.setMapStatus(MapStatusUpdateFactory.newLatLng(point.gcj02)) } }
                        else mapView.map.clear()
                    })
                }
            }
            Column(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (actionsExpanded) {
                    Button(onClick = { Toast.makeText(requireContext(), "正在定位当前位置", Toast.LENGTH_SHORT).show(); mapView.map.locateMe() }) { Text("跟随当前位置") }
                    Button(onClick = ::openCoordinateDialog) { Text("输入目标坐标") }
                    Button(onClick = ::openAddLocationDialog) { Text("保存当前位置") }
                }
                Button(onClick = { actionsExpanded = !actionsExpanded }) { Text(if (actionsExpanded) "收起操作" else "更多操作") }
            }
        }

        if (coordinateDialog) AlertDialog(
            onDismissRequest = { coordinateDialog = false },
            title = { Text("输入经纬度（WGS84）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(latitude, { latitude = it; dialogError = null }, label = { Text("纬度") }, singleLine = true)
                    OutlinedTextField(longitude, { longitude = it; dialogError = null }, label = { Text("经度") }, singleLine = true)
                    dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { coordinateDialog = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    val lat = latitude.toDoubleOrNull(); val lon = longitude.toDoubleOrNull()
                    if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) dialogError = "请输入有效的纬度和经度"
                    else {
                        baiduMapViewModel.markedLoc = lat to lon
                        markMap(true)
                        baiduMapViewModel.perspectiveState = MyLocationConfiguration.LocationMode.NORMAL
                        baiduMapViewModel.mGeoCoder?.reverseGeoCode(ReverseGeoCodeOption().location(LatLng(lat, lon)))
                        coordinateDialog = false
                    }
                }) { Text("确定") }
            },
        )
        if (addLocationDialog) AlertDialog(
            onDismissRequest = { addLocationDialog = false },
            title = { Text("保存位置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(locationName, { locationName = it; dialogError = null }, label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(locationAddress, { locationAddress = it; dialogError = null }, label = { Text("地址") }, singleLine = true)
                    OutlinedTextField(locationLatLon, { locationLatLon = it; dialogError = null }, label = { Text("经度, 纬度") }, singleLine = true)
                    dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { addLocationDialog = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    val parts = locationLatLon.split(",")
                    val lon = parts.getOrNull(0)?.trim()?.toDoubleOrNull(); val lat = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                    if (locationName.isBlank()) dialogError = "名称不能为空"
                    else if (locationAddress.isBlank()) dialogError = "地址不能为空"
                    else if (parts.size != 2 || lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) dialogError = "经纬度格式错误"
                    else { saveHistoricalLocation(locationName, locationAddress, lat, lon); addLocationDialog = false }
                }) { Text("保存") }
            },
        )
    }

    private fun saveHistoricalLocation(name: String, address: String, lat: Double, lon: Double) {
        val locations = requireContext().rawHistoricalLocations.toMutableSet()
        var candidate = name
        var suffix = 0
        while (locations.any { it.substringBefore(',') == candidate }) candidate = "$name(${++suffix})"
        locations += "$candidate,$address,${BigDecimal.valueOf(lat).toPlainString()},${BigDecimal.valueOf(lon).toPlainString()}"
        requireContext().rawHistoricalLocations = locations
        Toast.makeText(requireContext(), "位置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun previewRoute(points: kotlin.collections.List<Pair<Double, Double>>) {
        baiduMapViewModel.baiduMap.clear()
        for (i in 0 until points.size - 1) {
            baiduMapViewModel.baiduMap.addOverlay(PolylineOptions().color(Color.argb(178, 0, 78, 255)).width(10).points(java.util.List.of(points[i].gcj02, points[i + 1].gcj02)))
        }
    }

    private fun markMap(moveEyes: Boolean = false) {
        val loc = baiduMapViewModel.markedLoc?.gcj02 ?: return
        baiduMapViewModel.baiduMap.clear()
        baiduMapViewModel.baiduMap.addOverlay(MarkerOptions().position(loc).icon(baiduMapViewModel.mMapIndicator))
        if (moveEyes) baiduMapViewModel.baiduMap.setMapStatus(MapStatusUpdateFactory.newLatLng(loc))
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        mapView.map.isMyLocationEnabled = true
        if (!mLocationClient.isStarted) mLocationClient.start()
    }

    override fun onPause() {
        mapView.map.isMyLocationEnabled = false
        mapView.onPause()
        if (mLocationClient.isStarted) mLocationClient.stop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        mapDragJob?.cancel()
        baiduMapViewModel.isExists = false
        if (::mLocationClient.isInitialized && mLocationClient.isStarted) mLocationClient.stop()
        mapView.onDestroy()
        super.onDestroyView()
    }
}

