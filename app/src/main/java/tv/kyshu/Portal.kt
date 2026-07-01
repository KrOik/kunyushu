package tv.kyshu

import android.app.Application
import com.baidu.location.LocationClient
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer

class Portal: Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            SDKInitializer.setAgreePrivacy(this, true)
            LocationClient.setAgreePrivacy(true)

            SDKInitializer.initialize(this)
            SDKInitializer.setCoordType(DEFAULT_COORD_TYPE)
        } catch (e: Throwable) {
            android.util.Log.e("Portal", "BaiduMap SDK init failed", e)
        }
    }

    private fun isBaiduMapKeyValid(): Boolean {
        val key = kotlin.runCatching {
            applicationContext.packageManager
                .getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
                .metaData?.getString("com.baidu.lbsapi.API_KEY")
        }.getOrNull()
        return !key.isNullOrBlank() && key != "UzCD3gLYg3M9Ue2749hm1jsHX2DyfMUF"
    }

    companion object {
        val DEFAULT_COORD_TYPE = CoordType.GCJ02
        const val DEFAULT_COORD_STR = "GCJ02"

        //val DEFAULT_COORD_TYPE = CoordType.BD09LL
        //const val DEFAULT_COORD_STR = "bd09ll"
    }
}