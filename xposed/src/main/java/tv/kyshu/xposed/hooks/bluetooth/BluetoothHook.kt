@file:Suppress("PrivateApi")
package tv.kyshu.xposed.hooks.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.stability.DomainGuard
import tv.kyshu.xposed.stability.DomainGuard.Domain
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.beforeHook
import tv.kyshu.xposed.utils.hookAllMethods
import tv.kyshu.xposed.utils.onceHookAllMethod
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

object BluetoothHook {
    private const val MAX_CALLBACK_FAILURES = 3
    private val callbackFailures = AtomicInteger(0)

    @Volatile
    private var softDisabled = false

    operator fun invoke(classLoader: ClassLoader) {
        if (softDisabled || DomainGuard.isDomainDisabled(Domain.BLE)) {
            Logger.warn("[BluetoothHook] BLE hook disabled by guard")
            return
        }

        installStep("IBluetooth.onTransact") { hookIBluetoothBinder(classLoader) }
        installStep("BluetoothLeScanner.startScan") { hookBluetoothLeScanner() }
        installStep("BluetoothAdapter.startDiscovery") { hookDiscoveryFallback() }
    }

    private fun installStep(label: String, block: () -> Unit) {
        runCatching(block).onFailure {
            Logger.warn("[BluetoothHook] $label skipped", it)
        }
    }

    private fun hookIBluetoothBinder(classLoader: ClassLoader) {
        val stubClass = XposedHelpers.findClassIfExists("android.bluetooth.IBluetooth\$Stub", classLoader)
        if (stubClass == null) {
            Logger.warn("[BluetoothHook] IBluetooth.Stub not found")
            return
        }

        val hooks = stubClass.hookAllMethods("onTransact", beforeHook {
            if (!FakeLoc.enable) return@beforeHook
            if (FakeLoc.enableDebugLog) {
                Logger.debug("[BluetoothHook] IBluetooth.onTransact code=${args.firstOrNull()}")
            }
        })
        if (hooks.isEmpty()) {
            Logger.warn("[BluetoothHook] IBluetooth.Stub.onTransact not hooked")
        }
    }

    private fun hookBluetoothLeScanner() {
        val hooks = BluetoothLeScanner::class.java.hookAllMethods("startScan", beforeHook {
            if (!FakeLoc.enable) return@beforeHook
            args.filterIsInstance<ScanCallback>().forEach { callback ->
                hookScanCallback(callback)
            }
        })
        if (hooks.isEmpty()) {
            Logger.warn("[BluetoothHook] BluetoothLeScanner.startScan not hooked")
        }
    }

    private fun hookDiscoveryFallback() {
        val hooks = BluetoothAdapter::class.java.hookAllMethods("startDiscovery", beforeHook {
            if (!FakeLoc.enable) return@beforeHook
            if (FakeLoc.enableDebugLog) {
                Logger.debug("[BluetoothHook] startDiscovery observed; BLE spoof remains best-effort")
            }
        })
        if (hooks.isEmpty()) {
            Logger.warn("[BluetoothHook] BluetoothAdapter.startDiscovery not hooked")
        }
    }

    private fun hookScanCallback(callback: ScanCallback) {
        val callbackClass = callback.javaClass

        runCatching {
            callbackClass.onceHookAllMethod("onScanResult", beforeHook {
                if (!FakeLoc.enable || softDisabled) return@beforeHook
                (args.getOrNull(1) as? ScanResult)?.let { spoofScanResult(it) }
            })
        }.onFailure {
            recordCallbackFailure("onScanResult", it)
        }

        runCatching {
            callbackClass.onceHookAllMethod("onBatchScanResults", beforeHook {
                if (!FakeLoc.enable || softDisabled) return@beforeHook
                (args.firstOrNull() as? List<*>)?.filterIsInstance<ScanResult>()?.take(32)?.forEach {
                    spoofScanResult(it)
                }
            })
        }.onFailure {
            recordCallbackFailure("onBatchScanResults", it)
        }
    }

    private fun spoofScanResult(scanResult: ScanResult) {
        runCatching {
            XposedHelpers.setIntField(scanResult, "mRssi", Random.nextInt(-88, -42))
            createBluetoothDevice(randomBleMac())?.let { device ->
                XposedHelpers.setObjectField(scanResult, "mDevice", device)
            }
        }.onFailure {
            recordCallbackFailure("spoofScanResult", it)
        }
    }

    private fun createBluetoothDevice(address: String): BluetoothDevice? {
        return runCatching {
            XposedHelpers.newInstance(BluetoothDevice::class.java, address) as? BluetoothDevice
        }.getOrNull()
    }

    private fun recordCallbackFailure(label: String, throwable: Throwable) {
        val failures = callbackFailures.incrementAndGet()
        Logger.warn("[BluetoothHook] $label failed ($failures/$MAX_CALLBACK_FAILURES)", throwable)
        if (failures >= MAX_CALLBACK_FAILURES) {
            softDisabled = true
            Logger.warn("[BluetoothHook] disabled after repeated callback failures")
        }
    }

    private fun randomBleMac(): String {
        return String.format(
            "%02X:%02X:%02X:%02X:%02X:%02X",
            Random.nextInt(0xC0, 0x100),
            Random.nextInt(0x100),
            Random.nextInt(0x100),
            Random.nextInt(0x100),
            Random.nextInt(0x100),
            Random.nextInt(0x100)
        )
    }
}
