package tv.kyshu

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.navigation.NavHostController
import dagger.hilt.android.AndroidEntryPoint
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.InfoWindow
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.geocode.GeoCodeResult
import com.baidu.mapapi.search.geocode.GeoCoder
import com.baidu.mapapi.search.geocode.OnGetGeoCoderResultListener
import com.baidu.mapapi.search.geocode.ReverseGeoCodeResult
import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.sug.SuggestionSearchOption
import com.baidu.mapapi.model.LatLng
import kotlinx.coroutines.launch
import tv.kyshu.android.permission.RequestPermissions
import tv.kyshu.android.root.ShellUtils
import tv.kyshu.android.window.OverlayUtils
import tv.kyshu.bdmap.Poi
import tv.kyshu.bdmap.toPoi
import tv.kyshu.ext.gcj02
import tv.kyshu.ext.wgs84
import tv.kyshu.ui.compose.theme.PortalTheme
import tv.kyshu.ui.environment.EnvironmentDataFragment
import tv.kyshu.ui.environment.EnvironmentMapFragment
import tv.kyshu.ui.gnss.GnssMockFragment
import tv.kyshu.ui.home.HomeFragment
import tv.kyshu.ui.mock.MockFragment
import tv.kyshu.ui.mock.RouteEditFragment
import tv.kyshu.ui.mock.RouteMockFragment
import tv.kyshu.ui.settings.AppCoordinateFragment
import tv.kyshu.ui.settings.MapSettingsFragment
import tv.kyshu.ui.settings.SensorSettingsFragment
import tv.kyshu.ui.settings.SettingsFragment
import tv.kyshu.ui.viewmodel.BaiduMapViewModel
import tv.kyshu.ui.viewmodel.MockServiceViewModel
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.compose.portalComposeView
import tv.kyshu.ui.notification.NotificationUtils
import tv.kyshu.xposed.utils.Log as XLog

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val requestMultiplePermissions = RequestPermissions(this)
    private val baiduMapViewModel by viewModels<BaiduMapViewModel>()
    private val mockServiceViewModel by viewModels<MockServiceViewModel>()
    private var mSuggestionSearch: SuggestionSearch? = null
    private var composeNavController: NavHostController? = null
    private var permissionDenied by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        if (!ShellUtils.hasRoot()) Toast.makeText(this, "无Root可能导致传感器Hook失效", Toast.LENGTH_LONG).show()

        initGeoCoder()
        initNotification()
        mockServiceViewModel.initRocker(this)
        setContent {
            PortalTheme {
                if (permissionDenied) PermissionRequiredScreen() else PortalApp()
            }
        }
        lifecycleScope.launch {
            if (checkPermission()) {
                mockServiceViewModel.locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
            }
        }
    }

    private fun configureSystemBars() {
        window.statusBarColor = getColor(R.color.theme_appbar_color)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    private fun initGeoCoder() {
        baiduMapViewModel.mGeoCoder = GeoCoder.newInstance()
        baiduMapViewModel.mGeoCoder?.setOnGetGeoCodeResultListener(object : OnGetGeoCoderResultListener {
            override fun onGetGeoCodeResult(geoCodeResult: GeoCodeResult) = Unit
            override fun onGetReverseGeoCodeResult(result: ReverseGeoCodeResult) {
                if (result.error != SearchResult.ERRORNO.NO_ERROR) {
                    XLog.e("MainActivity", "Reverse GeoCode error: ${result.error}")
                } else with(baiduMapViewModel) {
                    markName = result.address.toString()
                    if (showDetailView) showDetailInfo(result.location.wgs84, result.location)
                }
            }
        })
    }

    private fun showDetailInfo(wgsLoc: Pair<Double, Double>, gcjLoc: LatLng) {
        val infoView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                PortalTheme {
                    Surface(
                        modifier = Modifier.widthIn(max = 280.dp),
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 6.dp,
                        shadowElevation = 4.dp,
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text(
                                "${wgsLoc.second.toString().take(10)}, ${wgsLoc.first.toString().take(10)}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                baiduMapViewModel.markName ?: "未知地址",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        val maxWidth = (280 * resources.displayMetrics.density).toInt()
        val spec = View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST)
        infoView.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        infoView.layout(0, 0, infoView.measuredWidth, infoView.measuredHeight)
        baiduMapViewModel.baiduMap.showInfoWindow(InfoWindow(BitmapDescriptorFactory.fromView(infoView), gcjLoc, -95, null))
    }

    private fun initNotification() {
        baiduMapViewModel.mNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationUtils(this).getAndroidChannelNotification("Portal后台定位服务", "正在后台定位").build()
        } else {
            Notification.Builder(this)
                .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setContentTitle("Portal后台定位服务")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentText("正在后台定位")
                .setWhen(System.currentTimeMillis())
                .build()
        }.also { it.defaults = Notification.DEFAULT_SOUND }
    }

    private fun requiredPermissions(): MutableSet<String> = mutableSetOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.VIBRATE,
    ).apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(Manifest.permission.FOREGROUND_SERVICE) }

    private suspend fun checkPermission(): Boolean {
        val (_, denied) = requestMultiplePermissions.request(requiredPermissions())
        if (denied.isEmpty()) {
            requireFloatWindows()
            return true
        }
        denied.forEach { permission ->
            val message = when (permission) {
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> "Portal需要完整位置权限"
                Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS -> "Portal需要额外位置命令权限和系统交互"
                Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.INTERNET -> "Portal需要访问网络"
                Manifest.permission.VIBRATE -> "Portal需要访问传感器"
                else -> "需要 $permission 才能运行"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        permissionDenied = true
        return false
    }

    private fun requireFloatWindows(): Boolean {
        if (OverlayUtils.hasOverlayPermissions(this)) return true
        Toast.makeText(this, "请授权悬浮窗权限", Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(Uri.parse("package:$packageName")))
        }.onFailure { Toast.makeText(this, "跳转失败，请手动去设置授权", Toast.LENGTH_LONG).show() }
        return false
    }

    @Composable
    private fun PermissionRequiredScreen() {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("缺少必要权限", style = MaterialTheme.typography.headlineSmall)
            Text("请在系统设置中授予位置、网络和传感器权限后重新打开 Portal。", modifier = Modifier.padding(top = 12.dp))
            Button(onClick = { permissionDenied = false; recreate() }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("重新检查") }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PortalApp() {
        val navController = rememberNavController()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val currentRoute by navController.currentBackStackEntryAsState()
        val route = currentRoute?.destination?.route ?: ROUTE_HOME
        var searchOpen by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var searchResults by remember { mutableStateOf<List<Poi>>(emptyList()) }
        composeNavController = navController

        BackHandler(enabled = searchOpen || route != ROUTE_HOME) {
            if (searchOpen) searchOpen = false else navController.popBackStack()
        }
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Text("Portal", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
                    Text("位置与传感器工具", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                    destinations.forEach { destination ->
                        NavigationDrawerItem(
                            label = { Text(destination.title) },
                            icon = { Text(destination.glyph) },
                            selected = route == destination.route,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(destinations.firstOrNull { it.route == route }?.title ?: "Portal") },
                        navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Text("☰", style = MaterialTheme.typography.titleLarge) } },
                        actions = {
                            if (route == ROUTE_HOME) IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchResults = emptyList() }) { Text("⌕", style = MaterialTheme.typography.titleLarge) }
                        },
                    )
                },
            ) { padding ->
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (searchOpen && route == ROUTE_HOME) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                requestSuggestions(it) { searchResults = it }
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            label = { Text("搜索地点") },
                            singleLine = true,
                        )
                        if (searchResults.isNotEmpty()) {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(horizontal = 16.dp)) {
                                items(searchResults, key = { "${it.name}:${it.longitude}:${it.latitude}" }) { poi ->
                                    NavigationDrawerItem(
                                        label = { Column { Text(poi.name); Text(poi.address, style = MaterialTheme.typography.bodySmall) } },
                                        selected = false,
                                        onClick = {
                                            selectPoi(poi)
                                            searchOpen = false
                                            searchResults = emptyList()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                    NavHost(navController, startDestination = ROUTE_HOME, modifier = Modifier.fillMaxSize()) {
                        destinations.forEach { destination ->
                            composable(destination.route) { FragmentDestination(destination.route) }
                        }
                        composable(ROUTE_ROUTE_EDIT) { FragmentDestination(ROUTE_ROUTE_EDIT) }
                    }
                }
            }
        }
    }

    @Composable
    private fun FragmentDestination(route: String) {
        val context = LocalContext.current
        val manager = (context as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager ?: return
        val containerId = remember(route) { View.generateViewId() }
        AndroidView(
            factory = { FragmentContainerView(context).apply { id = containerId } },
            modifier = Modifier.fillMaxSize(),
        )
        DisposableEffect(route, containerId) {
            val tag = "portal-compose-$route"
            manager.commit {
                setReorderingAllowed(true)
                replace(containerId, fragmentFor(route), tag)
            }
            onDispose {
                manager.findFragmentByTag(tag)?.let { fragment ->
                    if (!manager.isStateSaved) manager.commit { remove(fragment) }
                }
            }
        }
    }

    private fun fragmentFor(route: String): Fragment = when (route) {
        ROUTE_HOME -> HomeFragment()
        ROUTE_MOCK -> MockFragment()
        ROUTE_GNSS -> GnssMockFragment()
        ROUTE_SENSOR -> SensorSettingsFragment()
        ROUTE_ROUTE -> RouteMockFragment()
        ROUTE_ROUTE_EDIT -> RouteEditFragment()
        ROUTE_SETTINGS -> SettingsFragment()
        ROUTE_ENV_MAP -> EnvironmentMapFragment()
        ROUTE_ENV_DATA -> EnvironmentDataFragment()
        ROUTE_COORDINATE -> AppCoordinateFragment()
        ROUTE_MAP_SETTINGS -> MapSettingsFragment()
        else -> HomeFragment()
    }

    private fun requestSuggestions(query: String, onResult: (List<Poi>) -> Unit) {
        if (query.isBlank()) { onResult(emptyList()); return }
        if (mSuggestionSearch == null) {
            mSuggestionSearch = SuggestionSearch.newInstance().also { search ->
                search.setOnGetSuggestionResultListener { result ->
                    val data = result?.takeIf { it.allSuggestions != null }?.toPoi(baiduMapViewModel.currentLocation).orEmpty()
                    runOnUiThread { onResult(data) }
                }
            }
        }
        runCatching {
            mSuggestionSearch?.requestSuggestion(SuggestionSearchOption().keyword(query).city(mCityString))
        }.onFailure { Toast.makeText(this, "搜索出错", Toast.LENGTH_SHORT).show() }
    }

    private fun selectPoi(poi: Poi) {
        baiduMapViewModel.markName = poi.name
        baiduMapViewModel.markedLoc = poi.latitude to poi.longitude
        if (baiduMapViewModel.isExists) {
            val point = (poi.latitude to poi.longitude).gcj02
            baiduMapViewModel.baiduMap.setMapStatus(MapStatusUpdateFactory.newLatLng(point))
            baiduMapViewModel.baiduMap.clear()
            baiduMapViewModel.baiduMap.addOverlay(MarkerOptions().position(point))
        } else Toast.makeText(this, "地图未加载", Toast.LENGTH_SHORT).show()
    }

    fun openRouteEditor() {
        composeNavController?.navigate(ROUTE_ROUTE_EDIT) { launchSingleTop = true }
    }

    override fun onResume() {
        super.onResume()
        val manager = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        if (MockServiceHelper.isServiceInit()) runCatching { MockServiceHelper.syncConfig(manager) }
    }

    override fun onDestroy() {
        mSuggestionSearch?.destroy()
        mSuggestionSearch = null
        baiduMapViewModel.mGeoCoder?.destroy()
        baiduMapViewModel.mGeoCoder = null
        composeNavController = null
        super.onDestroy()
    }

    private data class Destination(val route: String, val title: String, val glyph: String)

    companion object {
        const val ROUTE_HOME = "home"
        const val ROUTE_MOCK = "mock"
        const val ROUTE_GNSS = "gnss"
        const val ROUTE_SENSOR = "sensor"
        const val ROUTE_ROUTE = "route"
        const val ROUTE_ROUTE_EDIT = "route_edit"
        const val ROUTE_SETTINGS = "settings"
        const val ROUTE_ENV_MAP = "environment_map"
        const val ROUTE_ENV_DATA = "environment_data"
        const val ROUTE_COORDINATE = "app_coordinate"
        const val ROUTE_MAP_SETTINGS = "map_settings"
        private const val REQUEST_PERMISSIONS_CODE = 111
        internal var mCityString: String? = null
            set(value) {
                if (field != value) {
                    field = value
                    XLog.d("MainActivity", "cityString: $value")
                }
            }
        private val destinations = listOf(
            Destination(ROUTE_HOME, "首页地图", "⌂"),
            Destination(ROUTE_MOCK, "位置模拟", "◎"),
            Destination(ROUTE_GNSS, "GNSS 模拟", "◉"),
            Destination(ROUTE_SENSOR, "传感器设置", "⌁"),
            Destination(ROUTE_ROUTE, "路线模拟", "↝"),
            Destination(ROUTE_ENV_MAP, "环境图谱", "⌖"),
            Destination(ROUTE_ENV_DATA, "环境数据", "▦"),
            Destination(ROUTE_COORDINATE, "应用坐标系", "◇"),
            Destination(ROUTE_MAP_SETTINGS, "地图设置", "▣"),
            Destination(ROUTE_SETTINGS, "综合设置", "⚙"),
        )
    }
}
