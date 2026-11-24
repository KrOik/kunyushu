package tv.kyshu.xposed.factory

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import tv.kyshu.xposed.config.BleDeviceProfile
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.random.Random

object BleScanResultFactory {
    fun fromProfileOrNull(
        profile: BleDeviceProfile,
        timestampNanos: Long = System.nanoTime(),
    ): ScanResult? {
        val address = profile.address.ifBlank { randomBleMac() }
        val device = createBluetoothDevice(address) ?: return null
        val record = createScanRecord(profile) ?: return null
        return createExtendedScanResult(device, record, profile, timestampNanos)
            ?: runCatching { ScanResult(device, record, profile.rssiDbm, timestampNanos) }.getOrNull()
    }

    fun createScanRecord(profile: BleDeviceProfile): ScanRecord? =
        createScanRecord(createAdvertisingBytes(profile))

    fun createAdvertisingBytes(profile: BleDeviceProfile): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeAdStructure(0x01, byteArrayOf(if (profile.connectable) 0x06 else 0x04))
        if (profile.name.isNotBlank()) {
            out.writeAdStructure(0x09, profile.name.toByteArray(Charsets.UTF_8))
        }
        profile.serviceUuids.mapNotNull { runCatching { ParcelUuid.fromString(it) }.getOrNull() }
            .forEach { uuid ->
                out.writeAdStructure(0x07, uuid.uuid.toLittleEndianBytes())
                val serviceData = profile.serviceDataHex.hexToBytesOrNull()
                if (serviceData != null && serviceData.isNotEmpty()) {
                    out.writeAdStructure(0x21, uuid.uuid.toLittleEndianBytes() + serviceData)
                }
            }
        profile.serviceSolicitationUuids.mapNotNull { runCatching { ParcelUuid.fromString(it) }.getOrNull() }
            .forEach { uuid ->
                out.writeAdStructure(0x15, uuid.uuid.toLittleEndianBytes())
            }
        profile.txPowerDbm?.let { txPower ->
            out.writeAdStructure(0x0A, byteArrayOf(txPower.coerceIn(-127, 126).toByte()))
        }
        profile.manufacturerDataHex.hexToBytesOrNull()?.takeIf { it.isNotEmpty() }?.let { manufacturerData ->
            out.writeAdStructure(0xFF, manufacturerData)
        }
        return out.toByteArray()
    }

    fun spoofExisting(
        scanResult: ScanResult,
        address: String = randomBleMac(),
        rssi: Int = Random.nextInt(-88, -42),
    ): Boolean =
        runCatching {
            scanResult.setField("mRssi", rssi)
            createBluetoothDevice(address)?.let { device ->
                scanResult.setField("mDevice", device)
            }
            true
        }.getOrDefault(false)

    fun createBluetoothDevice(address: String): BluetoothDevice? =
        runCatching {
            val constructor = BluetoothDevice::class.java.getDeclaredConstructor(String::class.java)
            if (!constructor.isAccessible) constructor.isAccessible = true
            constructor.newInstance(address)
        }.getOrElse {
            runCatching {
                val constructor = BluetoothDevice::class.java.getDeclaredConstructor(
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                )
                if (!constructor.isAccessible) constructor.isAccessible = true
                constructor.newInstance(address, 0)
            }.getOrNull()
        }

    private fun randomBleMac(): String {
        return String.format(
            "%02X:%02X:%02X:%02X:%02X:%02X",
            Random.nextInt(0xC0, 0x100),
            Random.nextInt(0x100),
            Random.nextInt(0x100),
            Random.nextInt(0x100),
            Random.nextInt(0x100),
            Random.nextInt(0x100),
        )
    }

    private fun Any.setField(name: String, value: Any) {
        val field = javaClass.getDeclaredField(name)
        if (!field.isAccessible) field.isAccessible = true
        field.set(this, value)
    }

    private fun createExtendedScanResult(
        device: BluetoothDevice,
        record: ScanRecord,
        profile: BleDeviceProfile,
        timestampNanos: Long,
    ): ScanResult? =
        runCatching {
            val constructor = ScanResult::class.java.getConstructor(
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ScanRecord::class.java,
                Long::class.javaPrimitiveType,
            )
            constructor.newInstance(
                device,
                profile.eventType(),
                profile.primaryPhy.coerceIn(0, 3),
                profile.secondaryPhy.coerceIn(0, 3),
                profile.advertisingSid?.coerceIn(0, 15) ?: ScanResult.SID_NOT_PRESENT,
                profile.txPowerDbm?.coerceIn(-127, 126) ?: ScanResult.TX_POWER_NOT_PRESENT,
                profile.rssiDbm,
                profile.periodicAdvertisingInterval?.coerceIn(0, 65535)
                    ?: ScanResult.PERIODIC_INTERVAL_NOT_PRESENT,
                record,
                timestampNanos,
            )
        }.getOrNull()

    private fun createScanRecord(bytes: ByteArray): ScanRecord? =
        runCatching {
            val method = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
            if (!method.isAccessible) method.isAccessible = true
            method.invoke(null, bytes) as? ScanRecord
        }.getOrNull()

    private fun ByteArrayOutputStream.writeAdStructure(type: Int, data: ByteArray) {
        if (data.size > 254) return
        write(data.size + 1)
        write(type)
        write(data)
    }

    private fun UUID.toLittleEndianBytes(): ByteArray {
        val msb = mostSignificantBits
        val lsb = leastSignificantBits
        return byteArrayOf(
            (lsb and 0xFF).toByte(),
            ((lsb ushr 8) and 0xFF).toByte(),
            ((lsb ushr 16) and 0xFF).toByte(),
            ((lsb ushr 24) and 0xFF).toByte(),
            ((lsb ushr 32) and 0xFF).toByte(),
            ((lsb ushr 40) and 0xFF).toByte(),
            ((lsb ushr 48) and 0xFF).toByte(),
            ((lsb ushr 56) and 0xFF).toByte(),
            (msb and 0xFF).toByte(),
            ((msb ushr 8) and 0xFF).toByte(),
            ((msb ushr 16) and 0xFF).toByte(),
            ((msb ushr 24) and 0xFF).toByte(),
            ((msb ushr 32) and 0xFF).toByte(),
            ((msb ushr 40) and 0xFF).toByte(),
            ((msb ushr 48) and 0xFF).toByte(),
            ((msb ushr 56) and 0xFF).toByte(),
        )
    }

    private fun BleDeviceProfile.eventType(): Int {
        val dataStatus = if (this.dataStatus == ScanResult.DATA_TRUNCATED) {
            ScanResult.DATA_TRUNCATED
        } else {
            ScanResult.DATA_COMPLETE
        }
        val dataStatusBits = dataStatus shl 5
        val legacyBit = if (legacy) 0x10 else 0
        val connectableBit = if (connectable) 0x01 else 0
        return dataStatusBits or legacyBit or connectableBit
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (isBlank()) return byteArrayOf()
        if (length % 2 != 0) return null
        val result = ByteArray(length / 2)
        for (index in result.indices) {
            val value = substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
            result[index] = value.toByte()
        }
        return result
    }
}
