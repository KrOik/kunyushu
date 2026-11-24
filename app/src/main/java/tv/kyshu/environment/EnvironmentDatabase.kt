package tv.kyshu.environment

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EnvironmentSample::class, WifiObservation::class, CellObservation::class, BleObservation::class],
    version = 1,
    exportSchema = true,
)
abstract class EnvironmentDatabase : RoomDatabase() {
    abstract fun environmentDao(): EnvironmentDao

    companion object {
        @Volatile private var instance: EnvironmentDatabase? = null

        fun get(context: Context): EnvironmentDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                EnvironmentDatabase::class.java,
                "portal_environment.db",
            ).build().also { instance = it }
        }
    }
}
