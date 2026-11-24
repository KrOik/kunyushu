package tv.kyshu.environment

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EnvironmentDao {
    @Insert suspend fun insertSample(sample: EnvironmentSample): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertWifi(items: List<WifiObservation>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCells(items: List<CellObservation>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBle(items: List<BleObservation>)

    @Transaction
    @Query("SELECT * FROM environment_samples ORDER BY capturedAtMillis DESC")
    fun observeAll(): Flow<List<CompleteEnvironmentSample>>

    @Transaction
    @Query("SELECT * FROM environment_samples ORDER BY capturedAtMillis DESC")
    suspend fun getAll(): List<CompleteEnvironmentSample>

    @Transaction
    @Query("SELECT * FROM environment_samples WHERE id = :id")
    suspend fun getById(id: Long): CompleteEnvironmentSample?

    @Transaction
    @Query("""
        SELECT * FROM environment_samples
        WHERE latitude BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLon AND :maxLon
        ORDER BY ((latitude - :lat) * (latitude - :lat) +
                  (longitude - :lon) * (longitude - :lon)) ASC
        LIMIT :limit
    """)
    suspend fun nearby(
        lat: Double, lon: Double,
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double,
        limit: Int,
    ): List<CompleteEnvironmentSample>

    @Update suspend fun updateSample(sample: EnvironmentSample)
    @Query("DELETE FROM environment_samples WHERE id IN (:ids)") suspend fun delete(ids: List<Long>)
    @Query("DELETE FROM environment_samples") suspend fun clear()
    @Query("SELECT COUNT(*) FROM environment_samples") fun observeCount(): Flow<Int>
}
