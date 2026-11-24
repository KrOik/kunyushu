package tv.kyshu.environment

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class EnvironmentRepositoryTest {
    private lateinit var database: EnvironmentDatabase
    private lateinit var repository: EnvironmentRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), EnvironmentDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = EnvironmentRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun `exact sample resolves preferred connected wifi and profiles`() = runBlocking {
        val id = repository.save(EnvironmentCapture(
            latitude = 31.2304, longitude = 121.4737,
            wifi = listOf(
                WifiObservation(sampleId = 0, ssid = "near", bssid = "02:00:00:00:00:01", rssiDbm = -50, frequencyMhz = 2412),
                WifiObservation(sampleId = 0, ssid = "preferred", bssid = "02:00:00:00:00:02", rssiDbm = -65, frequencyMhz = 5200, connected = true),
            ),
            cells = listOf(CellObservation(sampleId = 0, cellKey = "LTE_460_01_1_2_3", rat = "LTE", mcc = "460", mnc = "01", cellId = 2, registered = true)),
            ble = listOf(BleObservation(sampleId = 0, address = "02:00:00:00:00:03", name = "beacon")),
        ))
        val sample = repository.getById(id)!!.sample
        repository.update(sample.copy(preferredWifiBssid = "02:00:00:00:00:01"))

        val resolved = repository.resolve(31.2304, 121.4737)

        assertEquals("near", resolved.wifiConnected?.ssid)
        assertEquals(2, resolved.wifiScan?.entries?.size)
        assertEquals(1, resolved.cellTowers.size)
        assertEquals(1, resolved.bleDevices.size)
        assertEquals(listOf(id), resolved.sourceSampleIds)
    }

    @Test fun `outside radius passes through without fabricated profiles`() = runBlocking {
        repository.save(EnvironmentCapture(31.0, 121.0, wifi = listOf(
            WifiObservation(sampleId = 0, ssid = "far", bssid = "02:00:00:00:00:01", rssiDbm = -50, frequencyMhz = 2412),
        )))
        val resolved = repository.resolve(32.0, 122.0, radiusMeters = 50.0)
        assertNull(resolved.wifiConnected)
        assertTrue(resolved.wifiScan == null || resolved.wifiScan!!.entries.isEmpty())
        assertTrue(!resolved.hasAnyProfile)
    }
}
