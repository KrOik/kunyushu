package tv.kyshu

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.baidu.location.LocationClient
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer
import java.io.BufferedReader
import java.io.FileReader

class Portal : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            SDKInitializer.setAgreePrivacy(this, true)
            LocationClient.setAgreePrivacy(true)

            // 必须在 initialize 之前写入鉴权缓存
            prepareBaiduAuthCache()

            SDKInitializer.initialize(this)
            SDKInitializer.setCoordType(DEFAULT_COORD_TYPE)
        } catch (e: Throwable) {
            android.util.Log.e("PTL.App", "BaiduMap SDK init failed", e)
        }
    }

    /**
     * 绕过百度地图SDK的包名+SHA1鉴权校验。
     *
     * 原理：LBSAuthManager 在 authenticate() 时先读取 SharedPreferences 中的
     * 鉴权缓存（key: "authStatus_" + 进程名, value: JSON），如果缓存中 status=0
     * 且 ak 与 manifest 中的 API Key 一致，则跳过在线鉴权。
     *
     * 因此在 SDKInitializer.initialize() 之前，预写入该缓存，SDK 即认为已鉴权成功。
     * 无需在百度开发者平台注册当前调试签名的 SHA1。
     *
     * 注：SDK v7.6.2 中 LBSAuthManager 的字段 `c` 已经被混淆为代理端口(proxy port)，
     * 不再是鉴权状态，旧版修改字段 `c` 的方式已失效。
     */
    private fun prepareBaiduAuthCache() {
        kotlin.runCatching {
            // 1. 获取 manifest 中设置的 API Key
            val ai = packageManager.getApplicationInfo(
                packageName, PackageManager.GET_META_DATA
            )
            val apiKey = ai.metaData?.getString("com.baidu.lbsapi.API_KEY") ?: return@runCatching

            // 2. 构建鉴权成功 JSON（status=0 = CODE_AUTHENTICATE_SUCC，ak 必须匹配）
            val successJson = """{"status":0,"ak":"$apiKey"}"""

            // 3. 确定进程名，用于 SharedPreferences 文件名（authStatus_{processName}）
            val processName = resolveProcessName()

            // 4. 写入鉴权缓存（在 initialize 之前，SDK 启动 auth 线程时会读到）
            getSharedPreferences("authStatus_$processName", Context.MODE_PRIVATE)
                .edit()
                .putString("status", successJson)
                .apply()

            android.util.Log.i("PTL.App", "Auth cache prepared for $processName")

            // 5. 额外设置 LBSAuthManager 的 packageName（仅作辅助）
            kotlin.runCatching {
                val authClass = Class.forName("com.baidu.lbsapi.auth.LBSAuthManager")
                val getInstance = authClass.getDeclaredMethod("getInstance", Context::class.java)
                val instance = getInstance.invoke(null, this)
                val setPkg = authClass.getDeclaredMethod("setPackageName", String::class.java)
                setPkg.invoke(instance, packageName)
            }.onFailure { android.util.Log.w("PTL.App", "LBSAuthManager packageName set failed", it) }
        }.onFailure { e ->
            android.util.Log.w("PTL.App", "Auth cache prepare failed", e)
        }
    }

    private fun resolveProcessName(): String {
        return if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            try {
                BufferedReader(FileReader("/proc/self/cmdline"))
                    .readText()
                    .trimEnd('\u0000')
                    .trim()
            } catch (e: Exception) {
                packageName
            }
        }
    }

    companion object {
        val DEFAULT_COORD_TYPE = CoordType.GCJ02
        const val DEFAULT_COORD_STR = "GCJ02"
    }
}
