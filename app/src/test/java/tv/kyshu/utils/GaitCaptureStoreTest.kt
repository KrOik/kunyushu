package tv.kyshu.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GaitCaptureStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readCsvSamplesAcceptsTimestampHeaderAndSkipsBadRows() {
        val file = temp.newFile("linear.csv")
        file.writeText(
            """
            timestamp_ns,x,y,z
            1000,1.0,2.0,3.0
            bad,row
            2000,4.0,5.0,6.0
            """.trimIndent(),
        )

        val result = GaitCaptureStore.readCsvSamples(file)

        assertEquals(2, result.linesParsed)
        assertEquals(1, result.linesSkipped)
        assertEquals(1000L, result.samples.first().timestamp)
        assertEquals(6.0f, result.samples.last().z)
    }

    @Test
    fun resolveImportDirectoryRejectsEmptyAndMissingPaths() {
        val empty = GaitCaptureStore.resolveImportDirectory("", "tv.kyshu")
        val missing = GaitCaptureStore.resolveImportDirectory(
            File(temp.root, "missing").absolutePath,
            "tv.kyshu",
        )

        assertNull(empty.directory)
        assertNotNull(empty.error)
        assertNull(missing.directory)
        assertNotNull(missing.error)
    }

    @Test
    fun cropToOverlapKeepsCommonTimeRange() {
        val linear = samplesAt(1L, 2L, 3L, 4L)
        val gyro = samplesAt(2L, 3L)
        val gravity = samplesAt(0L, 1L, 2L, 3L)

        val (croppedLinear, croppedGyro, croppedGravity) =
            GaitCaptureStore.cropToOverlap(linear, gyro, gravity)

        assertEquals(listOf(2L, 3L), croppedLinear.map { it.timestamp })
        assertEquals(listOf(2L, 3L), croppedGyro.map { it.timestamp })
        assertEquals(listOf(2L, 3L), croppedGravity.map { it.timestamp })
        assertTrue(croppedLinear.size == croppedGyro.size)
    }

    private fun samplesAt(vararg timestamps: Long): List<GaitAnalyzer.SensorSample> {
        return timestamps.map { GaitAnalyzer.SensorSample(it, 1f, 2f, 3f) }
    }
}
