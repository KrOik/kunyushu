package tv.kyshu.environment

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class EnvironmentSource { LOCAL_SCAN, WIGLE, OPENCELLID, IMPORT }

@Entity(tableName = "environment_samples", indices = [Index(value = ["latitude", "longitude"])])
data class EnvironmentSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val capturedAtMillis: Long = System.currentTimeMillis(),
    val source: String = EnvironmentSource.LOCAL_SCAN.name,
    val label: String = "",
    val note: String = "",
    val preferredWifiBssid: String? = null,
    val preferredCellKey: String? = null,
    val preferredBleAddress: String? = null,
)

@Entity(
    tableName = "environment_wifi",
    foreignKeys = [ForeignKey(
        entity = EnvironmentSample::class,
        parentColumns = ["id"], childColumns = ["sampleId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sampleId"), Index(value = ["sampleId", "bssid"], unique = true)],
)
data class WifiObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sampleId: Long,
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val capabilities: String = "",
    val standard: String = "",
    val channelWidth: Int = 0,
    val connected: Boolean = false,
    val macAddress: String = "",
    val linkSpeedMbps: Int = 0,
)

@Entity(
    tableName = "environment_cells",
    foreignKeys = [ForeignKey(
        entity = EnvironmentSample::class,
        parentColumns = ["id"], childColumns = ["sampleId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sampleId"), Index(value = ["sampleId", "cellKey"], unique = true)],
)
data class CellObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sampleId: Long,
    val cellKey: String,
    val rat: String,
    val mcc: String,
    val mnc: String,
    val tacOrLac: Int? = null,
    val cellId: Long? = null,
    val pciOrPsc: Int? = null,
    val arfcn: Int? = null,
    val rssiDbm: Int = -95,
    val registered: Boolean = false,
)

@Entity(
    tableName = "environment_ble",
    foreignKeys = [ForeignKey(
        entity = EnvironmentSample::class,
        parentColumns = ["id"], childColumns = ["sampleId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sampleId"), Index(value = ["sampleId", "address"], unique = true)],
)
data class BleObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sampleId: Long,
    val address: String,
    val name: String = "",
    val rssiDbm: Int = -70,
    val manufacturerDataHex: String = "",
    val serviceDataHex: String = "",
    val txPowerDbm: Int? = null,
    val connectable: Boolean = true,
)

data class CompleteEnvironmentSample(
    @Embedded val sample: EnvironmentSample,
    @Relation(parentColumn = "id", entityColumn = "sampleId") val wifi: List<WifiObservation>,
    @Relation(parentColumn = "id", entityColumn = "sampleId") val cells: List<CellObservation>,
    @Relation(parentColumn = "id", entityColumn = "sampleId") val ble: List<BleObservation>,
)

data class EnvironmentCapture(
    val latitude: Double,
    val longitude: Double,
    val wifi: List<WifiObservation> = emptyList(),
    val cells: List<CellObservation> = emptyList(),
    val ble: List<BleObservation> = emptyList(),
    val source: EnvironmentSource = EnvironmentSource.LOCAL_SCAN,
    val label: String = "",
    val note: String = "",
)
