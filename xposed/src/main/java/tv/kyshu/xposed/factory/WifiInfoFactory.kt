package tv.kyshu.xposed.factory

import android.net.wifi.WifiInfo
import tv.kyshu.xposed.config.WifiData
import tv.kyshu.xposed.config.WifiConnectedProfile
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import java.net.InetAddress
import kotlin.random.Random

object WifiInfoFactory {
    fun createConnectedInfo(realInfo: WifiInfo, profile: WifiConnectedProfile): WifiInfoMockResult? {
        val data = WifiData().apply {
            mockSSID = profile.ssid
            mockBSSID = profile.bssid
            mockMac = profile.macAddress
            mockIp = profile.ipv4Address
        }
        return createConnectedInfo(realInfo, data)
    }

    fun createConnectedInfo(
        realInfo: WifiInfo,
        wifiData: WifiData = FakeLoc.wifiData,
    ): WifiInfoMockResult? {
        val fakeInfo = copyOrEmpty(realInfo) ?: return null
        val mac = selectedMac(wifiData.mockMac, randomLocalMac())
        val bssid = selectedMac(wifiData.mockBSSID, randomMac())
        val ssid = wifiData.mockSSID.ifBlank { "WiFi_${Random.nextInt(1000, 9999)}" }

        invokeSetter(fakeInfo, "setMacAddress", mac)
        invokeSetter(fakeInfo, "setBSSID", bssid)
        invokeSetter(fakeInfo, "setSSID", ssid)

        runCatching {
            val inetAddr = InetAddress.getByName(wifiData.mockIp)
            invokeSetter(fakeInfo, "setInetAddress", inetAddr)
        }.onFailure {
            Logger.warn("[WifiInfoFactory] setInetAddress failed", it)
        }

        wifiData.updateSignalState()
        val rssi = (-65 + wifiData.currentRssiOffset).coerceIn(-127, -1)
        setField(fakeInfo, "mRssi", rssi)

        return WifiInfoMockResult(
            info = fakeInfo,
            ssid = ssid,
            bssid = bssid,
            mac = mac,
            rssiDbm = rssi,
        )
    }

    private fun copyOrEmpty(realInfo: WifiInfo): WifiInfo? =
        runCatching {
            WifiInfo::class.java.getDeclaredConstructor(WifiInfo::class.java).also {
                if (!it.isAccessible) it.isAccessible = true
            }.newInstance(realInfo)
        }.recoverCatching {
            Logger.warn("[WifiInfoFactory] WifiInfo copy ctor unavailable, fallback to empty instance", it)
            WifiInfo::class.java.getDeclaredConstructor().also { constructor ->
                if (!constructor.isAccessible) constructor.isAccessible = true
            }.newInstance()
        }.onFailure {
            Logger.warn("[WifiInfoFactory] WifiInfo construction failed", it)
        }.getOrNull()

    private fun selectedMac(configured: String, fallback: String): String =
        if (configured.isNotBlank() && configured != WifiData.DEFAULT_MAC) configured else fallback

    private fun invokeSetter(target: WifiInfo, name: String, value: Any) {
        runCatching {
            val method = target.javaClass.methods.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == 1
            } ?: target.javaClass.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == 1
            } ?: return
            if (!method.isAccessible) method.isAccessible = true
            method.invoke(target, value)
        }.onFailure {
            Logger.warn("[WifiInfoFactory] $name failed", it)
        }
    }

    private fun setField(target: WifiInfo, name: String, value: Int) {
        runCatching {
            val field = target.javaClass.getDeclaredField(name)
            if (!field.isAccessible) field.isAccessible = true
            field.setInt(target, value)
        }.onFailure {
            Logger.warn("[WifiInfoFactory] $name failed", it)
        }
    }

    private fun randomLocalMac(): String =
        String.format(
            "02:%02x:%02x:%02x:%02x:%02x",
            Random.nextInt(256),
            Random.nextInt(256),
            Random.nextInt(256),
            Random.nextInt(256),
            Random.nextInt(256),
        )

    private fun randomMac(): String =
        String.format(
            "%02x:%02x:%02x:%02x:%02x:%02x",
            Random.nextInt(256),
            Random.nextInt(256),
            Random.nextInt(256),
            Random.nextInt(256),
            Random.nextInt(256),
            Random.nextInt(256),
        )
}

data class WifiInfoMockResult(
    val info: WifiInfo,
    val ssid: String,
    val bssid: String,
    val mac: String,
    val rssiDbm: Int,
)
