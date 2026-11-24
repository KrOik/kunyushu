package tv.kyshu.ui.mock

import dagger.hilt.android.AndroidEntryPoint
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
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
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.LogoPosition
import com.baidu.mapapi.map.MapPoi
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tv.kyshu.MainActivity
import tv.kyshu.Portal
import tv.kyshu.bdmap.locateMe
import tv.kyshu.ext.gcj02
import tv.kyshu.ext.jsonHistoricalRoutes
import tv.kyshu.ext.mapType
import tv.kyshu.ext.wgs84
import tv.kyshu.ui.compose.portalComposeView
import tv.kyshu.ui.viewmodel.BaiduMapViewModel

@AndroidEntryPoint
class RouteEditFragment : Fragment() {
    private val baiduMapViewModel by activityViewModels<BaiduMapViewModel>()
    private lateinit var mapView: MapView
    private lateinit var locationClient: LocationClient
    private val points = ArrayList<Pair<Double, Double>>()
    private var drawing = false
    private var lastPoint: Pair<Double, Double>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        mapView = MapView(requireContext()).also { it.onCreate(requireContext(), savedInstanceState) }
        baiduMapViewModel.isExists = true
        baiduMapViewModel.baiduMap = mapView.map
        mapView.showZoomControls(true)
        mapView.showScaleControl(true)
        mapView.logoPosition = LogoPosition.logoPostionRightTop
        with(mapView.map) {
            mapType = requireContext().mapType
            setMapStatus(MapStatusUpdateFactory.zoomTo(19f))
            compassPosition = Point(50, 50)
            setCompassEnable(true)
            uiSettings.isCompassEnabled = true
            uiSettings.isOverlookingGesturesEnabled = true
            isMyLocationEnabled = true
            setOnMapClickListener(object : BaiduMap.OnMapClickListener {
                override fun onMapClick(loc: LatLng) = Unit
                override fun onMapPoiClick(poi: MapPoi) = Unit
            })
            setOnMapTouchListener { event ->
                if (!drawing) return@setOnMapTouchListener
                val current = mapStatus.target.wgs84
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (points.isEmpty()) points += current
                        lastPoint = current
                    }
                    MotionEvent.ACTION_MOVE -> lastPoint?.let { drawLine(it, current) }
                    MotionEvent.ACTION_UP -> { points += current; lastPoint = null; refresh() }
                }
            }
        }
        initializeLocation()
        return portalComposeView { RouteEditScreen() }
    }

    private fun initializeLocation() {
        locationClient = LocationClient(requireContext())
        val option = LocationClientOption().apply {
            isOpenGps = true
            enableSimulateGps = false
            setIsNeedAddress(true)
            setCoorType(Portal.DEFAULT_COORD_STR)
            setScanSpan(3000)
        }
        locationClient.locOption = option
        locationClient.registerLocationListener(object : BDAbstractLocationListener() {
            override fun onReceiveLocation(loc: BDLocation?) {
                loc ?: return
                baiduMapViewModel.currentLocation = loc.wgs84
                baiduMapViewModel.baiduMap.setMyLocationData(MyLocationData.Builder().accuracy(loc.radius).direction(loc.direction).latitude(loc.latitude).longitude(loc.longitude).build())
                MainActivity.mCityString = loc.city
            }
        })
        baiduMapViewModel.mLocationClient = locationClient
        locationClient.enableLocInForeground(1, baiduMapViewModel.mNotification)
        locationClient.start()
    }

    @Composable
    private fun RouteEditScreen() {
        var pointsCount by remember { mutableStateOf(points.size) }
        var satelliteMap by remember { mutableStateOf(mapView.map.mapType == BaiduMap.MAP_TYPE_SATELLITE) }
        var saveDialog by remember { mutableStateOf(false) }
        var routeName by remember { mutableStateOf("") }
        var routePoints by remember { mutableStateOf("") }
        var customSpeed by remember { mutableStateOf("1.4") }
        var waypointWaits by remember { mutableStateOf("") }
        var useRoadRoute by remember { mutableStateOf(false) }
        var selectedMode by remember { mutableStateOf(RouteTravelMode.WALKING) }
        var modeExpanded by remember { mutableStateOf(false) }
        var saveError by remember { mutableStateOf<String?>(null) }

        fun openSaveDialog() {
            if (points.size < 2) { Toast.makeText(requireContext(), "至少需要两个途经点", Toast.LENGTH_SHORT).show(); return }
            routeName = "路线-${System.currentTimeMillis()}"
            routePoints = JSON.toJSONString(points)
            customSpeed = "1.4"
            waypointWaits = ""
            selectedMode = RouteTravelMode.WALKING
            useRoadRoute = false
            saveError = null
            saveDialog = true
        }

        Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { mapView }, modifier = Modifier.weight(1f))
            Card(modifier = Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("编辑路线", style = MaterialTheme.typography.titleLarge)
                    Text(if (drawing) "正在绘制：拖动地图添加途经点" else "点击开始绘制，然后拖动地图添加途经点", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("已添加 $pointsCount 个途经点")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(!satelliteMap, { satelliteMap = false; mapView.map.mapType = BaiduMap.MAP_TYPE_NORMAL; requireContext().mapType = BaiduMap.MAP_TYPE_NORMAL }, SegmentedButtonDefaults.itemShape(0, 2), label = { Text("普通地图") })
                        SegmentedButton(satelliteMap, { satelliteMap = true; mapView.map.mapType = BaiduMap.MAP_TYPE_SATELLITE; requireContext().mapType = BaiduMap.MAP_TYPE_SATELLITE }, SegmentedButtonDefaults.itemShape(1, 2), label = { Text("卫星地图") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { drawing = true; points.clear(); lastPoint = null; refresh(); pointsCount = 0 }, modifier = Modifier.weight(1f)) { Text("开始绘制") }
                        OutlinedButton(onClick = { if (points.isNotEmpty()) points.removeAt(points.lastIndex); refresh(); pointsCount = points.size }, modifier = Modifier.weight(1f)) { Text("撤回") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { Toast.makeText(requireContext(), "正在定位当前位置", Toast.LENGTH_SHORT).show(); mapView.map.locateMe() }, modifier = Modifier.weight(1f)) { Text("我的位置") }
                        Button(onClick = { drawing = false; openSaveDialog() }, enabled = points.size >= 2, modifier = Modifier.weight(1f)) { Text("完成并保存") }
                    }
                }
            }
        }
        if (saveDialog) AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("保存路线") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(routeName, { routeName = it; saveError = null }, label = { Text("路线名称") }, singleLine = true)
                    OutlinedTextField(routePoints, { routePoints = it; saveError = null }, label = { Text("路线点 JSON（WGS84）") }, minLines = 3)
                    OutlinedButton(onClick = { modeExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("出行模式：${routeModeLabel(selectedMode)}") }
                    DropdownMenu(modeExpanded, { modeExpanded = false }) {
                        RouteTravelMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(routeModeLabel(mode)) }, onClick = { selectedMode = mode; modeExpanded = false }) }
                    }
                    OutlinedTextField(customSpeed, { customSpeed = it }, label = { Text("自定义速度（m/s）") }, singleLine = true)
                    OutlinedTextField(waypointWaits, { waypointWaits = it }, label = { Text("途经点等待秒数（逗号分隔）") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(useRoadRoute, { useRoadRoute = it }); Text("使用真实道路规划") }
                    saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    val error = saveRoute(routeName, routePoints, selectedMode, customSpeed, waypointWaits, useRoadRoute)
                    if (error == null) saveDialog = false else saveError = error
                }) { Text("保存") }
            },
        )
    }

    private fun routeModeLabel(mode: RouteTravelMode) = when (mode) {
        RouteTravelMode.WALKING -> "步行 1.4 m/s"
        RouteTravelMode.RUNNING -> "跑步 3.0 m/s"
        RouteTravelMode.CYCLING -> "骑行 5.5 m/s"
        RouteTravelMode.DRIVING -> "驾驶 15 m/s"
        RouteTravelMode.CUSTOM -> "自定义"
    }

    private fun saveRoute(name: String, json: String, mode: RouteTravelMode, speedText: String, waitsText: String, road: Boolean): String? {
        if (name.isBlank()) return "路线名称不能为空"
        val routePoints = runCatching { JSON.parseArray(json).map { raw -> (raw as JSONObject).getDouble("first") to (raw as JSONObject).getDouble("second") } }.getOrElse { return "路线点 JSON 格式错误" }
        if (routePoints.size < 2) return "路线至少需要两个点"
        if (routePoints.any { it.first !in -90.0..90.0 || it.second !in -180.0..180.0 }) return "路线经纬度超出范围"
        val speed = speedText.toDoubleOrNull() ?: 1.4
        if (speed !in 0.1..100.0) return "自定义速度必须在 0.1–100 m/s"
        val waits = waitsText.split(',').filter { it.isNotBlank() }.mapNotNull { it.trim().toIntOrNull() }.map { it.coerceIn(0, 3_600) }
        val route = HistoricalRoute(name, routePoints, mode, speed, waits, road)
        val array = if (requireContext().jsonHistoricalRoutes.isBlank()) JSONArray() else JSON.parseArray(requireContext().jsonHistoricalRoutes)
        array.add(JSON.parseObject(JSON.toJSONString(route)))
        requireContext().jsonHistoricalRoutes = array.toJSONString()
        Toast.makeText(requireContext(), "路线已保存", Toast.LENGTH_SHORT).show()
        return null
    }

    private fun refresh() {
        baiduMapViewModel.baiduMap.clear()
        for (i in 0 until points.size - 1) drawLine(points[i], points[i + 1])
    }

    private fun drawLine(start: Pair<Double, Double>, end: Pair<Double, Double>) {
        baiduMapViewModel.baiduMap.addOverlay(PolylineOptions().color(Color.argb(178, 0, 78, 255)).width(10).points(java.util.List.of(start.gcj02, end.gcj02)))
    }

    override fun onResume() {
        super.onResume(); mapView.onResume(); mapView.map.isMyLocationEnabled = true
        if (!locationClient.isStarted) locationClient.start()
    }

    override fun onPause() {
        mapView.map.isMyLocationEnabled = false; mapView.onPause()
        if (locationClient.isStarted) locationClient.stop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) { mapView.onSaveInstanceState(outState); super.onSaveInstanceState(outState) }

    override fun onDestroyView() {
        drawing = false
        if (::locationClient.isInitialized && locationClient.isStarted) locationClient.stop()
        mapView.onDestroy()
        baiduMapViewModel.isExists = false
        super.onDestroyView()
    }
}

