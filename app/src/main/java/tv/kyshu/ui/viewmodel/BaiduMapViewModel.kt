package tv.kyshu.ui.viewmodel

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import android.app.Notification
import androidx.lifecycle.ViewModel
import com.baidu.location.LocationClient
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MyLocationConfiguration
import tv.kyshu.R
import com.baidu.mapapi.search.geocode.GeoCoder
import tv.kyshu.bdmap.setMapConfig

@HiltViewModel
class BaiduMapViewModel @Inject constructor() : ViewModel() {
    var isExists = false
    lateinit var baiduMap: BaiduMap
    lateinit var mLocationClient: LocationClient

    /**
     * Current location
     * WGS84
     */
    var currentLocation: Pair<Double, Double>? = null

    var markName: String? = null

    /**
     * Marked location
     * WGS84
     * first => latitude
     * second => longitude
     */
    var markedLoc: Pair<Double, Double>? = null
    var showDetailView = false

    /* Notification */
    var mNotification: Notification? = null

    /**
     * 2024.10.10: Cancels the default follow perspective
     */
    var perspectiveState = MyLocationConfiguration.LocationMode.NORMAL
        set(value) {
            field = value
            if (::baiduMap.isInitialized) {
                baiduMap.setMapConfig(value, null)
            }
        }

    val mMapIndicator: BitmapDescriptor? by lazy {
        BitmapDescriptorFactory.fromResource(R.drawable.icon_selected_location_16)
    }

    var mGeoCoder: GeoCoder? = null

    override fun onCleared() {
        super.onCleared()
        mGeoCoder?.destroy()
        mGeoCoder = null
        mNotification = null
    }
}