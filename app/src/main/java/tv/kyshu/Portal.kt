package tv.kyshu

import android.app.Application
import android.content.Context
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

            // 绕过百度SDK鉴权：通过反射强制标记认证状态为成功
            // 无需在百度开发者平台注册当前调试签名的SHA1
            bypassBaiduMapAuth()
        } catch (e: Throwable) {
            android.util.Log.e("Portal", "BaiduMap SDK init failed", e)
        }
    }

    /**
     * 绕过百度地图SDK的包名+SHA1鉴权校验。
     *
     * 原理：LBSAuthManager 内部通过静态字段 `c` (int) 存储鉴权状态，
     * 初始值为 -1，鉴权成功后设为 0 (CODE_AUTHENTICATE_SUCC)。
     * 通过反射直接将该字段置为 0，SDK 各组件（MapView 等）即认为已鉴权。
     *
     * 同时设置包名为 key 注册时绑定的 com.kunyushu.kunyushu。
     */
    private fun bypassBaiduMapAuth() {
        kotlin.runCatching {
            val authClass = Class.forName("com.baidu.lbsapi.auth.LBSAuthManager")
            val getInstance = authClass.getDeclaredMethod("getInstance", Context::class.java)
            val instance = getInstance.invoke(null, this)

            // 设置包名以匹配API Key的注册信息
            val setPkg = authClass.getDeclaredMethod("setPackageName", String::class.java)
            setPkg.invoke(instance, "com.kunyushu.kunyushu")

            // 获取 CODE_AUTHENTICATE_SUCC 常量值（0）
            val succField = authClass.getDeclaredField("CODE_AUTHENTICATE_SUCC")
            val succCode = succField.getInt(null)

            // 反射修改私有静态字段 c（混淆名，存储鉴权状态）
            val statusField = authClass.getDeclaredField("c")
            statusField.isAccessible = true
            val oldVal = statusField.getInt(null)
            statusField.setInt(null, succCode)

            android.util.Log.i("Portal", "Auth bypass: auth status $oldVal -> $succCode")

            // 延迟5秒后再次覆盖，防止异步鉴权回调覆盖状态
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                kotlin.runCatching {
                    val current = statusField.getInt(null)
                    if (current != succCode) {
                        statusField.setInt(null, succCode)
                        android.util.Log.d("Portal", "Auth bypass re-applied: $current -> $succCode")
                    }
                }
            }, 5000)
        }.onFailure { e ->
            android.util.Log.w("Portal", "Auth bypass failed", e)
        }
    }

    companion object {
        val DEFAULT_COORD_TYPE = CoordType.GCJ02
        const val DEFAULT_COORD_STR = "GCJ02"
    }
}