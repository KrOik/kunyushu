@file:Suppress("PrivateApi")
package tv.kyshu.xposed.hooks.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.BuildConfig
import tv.kyshu.xposed.factory.BleScanResultFactory
import tv.kyshu.xposed.stability.DomainGuard
import tv.kyshu.xposed.stability.DomainGuard.Domain
import tv.kyshu.xposed.stability.WirelessRuntime
import tv.kyshu.xposed.stability.replaceOrPassthrough
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.afterHook
import tv.kyshu.xposed.utils.beforeHook
import tv.kyshu.xposed.utils.hookAllMethods
import tv.kyshu.xposed.utils.onceHookAllMethod

object BluetoothHook {
    operator fun invoke(classLoader: ClassLoader) {
        if (!BuildConfig.ENABLE_BLE_MOCK || DomainGuard.isDomainDisabled(Domain.BLE)) {
            Logger.warn("[BluetoothHook] BLE hook disabled by guard")
            return
        }

        installStep("IBluetooth.onTransact") { hookIBluetoothBinder(classLoader) }
        installStep("BluetoothLeScanner.startScan") { hookBluetoothLeScanner() }
        installStep("BluetoothAdapter.getBondedDevices") { hookBluetoothAdapterDevices() }
        installStep("BluetoothManager.getConnectedDevices") { hookBluetoothManagerDevices() }
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
        val hooks = BluetoothLeScanner::class.java.hookAllMethods("startScan", afterHook {
            if (!isRuntimeEnabled()) return@afterHook
            args.filterIsInstance<ScanCallback>().forEach { callback ->
                hookScanCallback(callback)
                dispatchProfileScanResults(callback)
            }
        })
        if (hooks.isEmpty()) {
            Logger.warn("[BluetoothHook] BluetoothLeScanner.startScan not hooked")
        }
    }

    private fun hookBluetoothAdapterDevices() {
        val hooks = BluetoothAdapter::class.java.hookAllMethods("getBondedDevices", beforeHook {
            val snapshot = WirelessRuntime.snapshotOrLegacy()
            if (!isRuntimeEnabled(snapshot) || snapshot.bleDevices.isEmpty()) return@beforeHook
            val original = result
            result = replaceOrPassthrough(Domain.BLE, "getBondedDevices", original) {
                profileBondedDevices(snapshot)
            }
        })
        if (hooks.isEmpty()) {
            Logger.warn("[BluetoothHook] BluetoothAdapter.getBondedDevices not hooked")
        }
    }

    private fun hookBluetoothManagerDevices() {
        val hooks = BluetoothManager::class.java.hookAllMethods("getConnectedDevices", beforeHook {
            val snapshot = WirelessRuntime.snapshotOrLegacy()
            if (!isRuntimeEnabled(snapshot) || snapshot.bleDevices.isEmpty()) return@beforeHook
            val original = result
            result = replaceOrPassthrough(Domain.BLE, "getConnectedDevices", original) {
                profileConnectedDevices(snapshot)
            }
        })
        if (hooks.isEmpty()) {
            Logger.warn("[BluetoothHook] BluetoothManager.getConnectedDevices not hooked")
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
                // Kuroko-conservative hide path (priority 1): for UIDs in the policy
                // table without a BLE policy entry, suppress real scan results
                // entirely (no fake-object construction). BLE has no dedicated policy
                // bit — reuse POLICY_HIDE_WIFI (radio-leak family).
                val selfUid = android.os.Process.myUid()
                if (tv.kyshu.xposed.config.UidPolicy.epochValue() > 0 &&
                    tv.kyshu.xposed.config.UidPolicy.isTargetUid(selfUid) &&
                    !tv.kyshu.xposed.config.UidPolicy.hasPolicy(selfUid, tv.kyshu.xposed.config.UidPolicy.POLICY_HIDE_WIFI)
                ) {
                    result = null
                    return@beforeHook
                }
                val snapshot = WirelessRuntime.snapshotOrLegacy()
                if (!isRuntimeEnabled(snapshot)) return@beforeHook
                if (snapshot.bleDevices.isEmpty()) {
                    (args.getOrNull(1) as? ScanResult)?.let { spoofScanResult(it) }
                }
            })
        }.onFailure {
            recordCallbackFailure("onScanResult", it)
        }

        runCatching {
            callbackClass.onceHookAllMethod("onBatchScanResults", beforeHook {
                val selfUid = android.os.Process.myUid()
                if (tv.kyshu.xposed.config.UidPolicy.epochValue() > 0 &&
                    tv.kyshu.xposed.config.UidPolicy.isTargetUid(selfUid) &&
                    !tv.kyshu.xposed.config.UidPolicy.hasPolicy(selfUid, tv.kyshu.xposed.config.UidPolicy.POLICY_HIDE_WIFI)
                ) {
                    result = null
                    return@beforeHook
                }
                val snapshot = WirelessRuntime.snapshotOrLegacy()
                if (!isRuntimeEnabled(snapshot)) return@beforeHook
                if (snapshot.bleDevices.isEmpty()) {
                    (args.firstOrNull() as? List<*>)?.filterIsInstance<ScanResult>()?.take(32)?.forEach {
                        spoofScanResult(it)
                    }
                }
            })
        }.onFailure {
            recordCallbackFailure("onBatchScanResults", it)
        }
    }

    private fun isRuntimeEnabled(snapshot: tv.kyshu.xposed.stability.WirelessRuntimeSnapshot = WirelessRuntime.snapshotOrLegacy()): Boolean =
        BuildConfig.ENABLE_BLE_MOCK && FakeLoc.enable && snapshot.bleEnabled &&
            !DomainGuard.isDomainDisabled(Domain.BLE)

    internal fun profileScanResults(timestampNanos: Long = System.nanoTime()): List<ScanResult> =
        profileScanResults(WirelessRuntime.snapshotOrLegacy(), timestampNanos)

    private fun profileScanResults(snapshot: tv.kyshu.xposed.stability.WirelessRuntimeSnapshot, timestampNanos: Long): List<ScanResult> =
        if (!isRuntimeEnabled(snapshot)) {
            emptyList()
        } else {
            snapshot.bleDevices.take(32).mapIndexed { index, profile ->
                BleScanResultFactory.fromProfileOrNull(profile, timestampNanos)
                    ?: throw IllegalStateException("BLE ScanResult construction failed at index $index")
            }
        }

    internal fun profileBondedDevices(snapshot: tv.kyshu.xposed.stability.WirelessRuntimeSnapshot = WirelessRuntime.snapshotOrLegacy()): Set<BluetoothDevice> =
        if (!isRuntimeEnabled(snapshot)) {
            emptySet()
        } else {
            snapshot.bleDevices
                .filter { it.bonded }
                .toBluetoothDevicesStrict()
                .toSet()
        }

    internal fun profileConnectedDevices(snapshot: tv.kyshu.xposed.stability.WirelessRuntimeSnapshot = WirelessRuntime.snapshotOrLegacy()): List<BluetoothDevice> =
        if (!isRuntimeEnabled(snapshot)) {
            emptyList()
        } else {
            snapshot.bleDevices
                .filter { it.connected }
                .toBluetoothDevicesStrict()
        }

    internal fun dispatchProfileScanResultsForTest(
        callback: ScanCallback,
        timestampNanos: Long = System.nanoTime(),
    ): Int = dispatchProfileScanResults(callback, timestampNanos)

    internal fun resetForTest() {
        DomainGuard.resetDomain(Domain.BLE)
        WirelessRuntime.resetForTest()
    }

    private fun dispatchProfileScanResults(
        callback: ScanCallback,
        timestampNanos: Long = System.nanoTime(),
    ): Int {
        val snapshot = WirelessRuntime.snapshotOrLegacy()
        val results = replaceOrPassthrough(Domain.BLE, "dispatchProfileScanResults.build", emptyList()) {
            profileScanResults(snapshot, timestampNanos)
        }
        if (results.isEmpty()) return 0

        runCatching {
            callback.onBatchScanResults(results)
        }.onFailure {
            recordCallbackFailure("onBatchScanResults.inject", it)
        }

        results.forEach { result ->
            runCatching {
                callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }.onFailure {
                recordCallbackFailure("onScanResult.inject", it)
            }
        }
        return results.size
    }

    private fun List<tv.kyshu.xposed.config.BleDeviceProfile>.toBluetoothDevicesStrict(): List<BluetoothDevice> =
        take(32).mapIndexed { index, profile ->
            val address = profile.address.ifBlank { throw IllegalStateException("BLE address missing at index $index") }
            BleScanResultFactory.createBluetoothDevice(address)
                ?: throw IllegalStateException("BluetoothDevice construction failed at index $index")
        }

    private fun spoofScanResult(scanResult: ScanResult) {
        if (!BleScanResultFactory.spoofExisting(scanResult)) {
            recordCallbackFailure("spoofScanResult", IllegalStateException("ScanResult spoof failed"))
        }
    }

    private fun recordCallbackFailure(label: String, throwable: Throwable) {
        DomainGuard.tripNow(Domain.BLE, label, throwable)
    }
}
