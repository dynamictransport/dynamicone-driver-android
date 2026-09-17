package com.dynamictransport.driver.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant

@Database(
    entities = [
        TripEntity::class,
        ShiftEntity::class,
        DriverProfileEntity::class,
        PendingActionEntity::class,
        DocumentEntity::class,
        PendingCommandEntity::class,
        SyncMetaEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = DriverDatabase.Version1To2::class)],
)
@TypeConverters(DatabaseConverters::class)
abstract class DriverDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun shiftDao(): ShiftDao
    abstract fun driverProfileDao(): DriverProfileDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun documentDao(): DocumentDao
    abstract fun pendingCommandDao(): PendingCommandDao
    abstract fun syncMetaDao(): SyncMetaDao

    /**
     * v2: Driver API command store added; the demo earnings cache is dropped (pay data is no longer cached
     * on the device); stored demo document types follow the Driver API categories (A1 is the customs document).
     */
    @DeleteTable(tableName = "earnings")
    class Version1To2 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE documents SET type = 'CUSTOMS' WHERE type = 'A1'")
            db.execSQL("UPDATE documents SET type = 'INTERCHANGE' WHERE type = 'INTERCHANGE_EIR'")
            db.execSQL("UPDATE documents SET type = 'OTHER' WHERE type = 'POD'")
        }
    }

    companion object {
        const val NAME = "driver.db"
    }
}

class DatabaseConverters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
