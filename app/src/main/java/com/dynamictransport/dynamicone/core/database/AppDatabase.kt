package com.dynamictransport.dynamicone.core.database

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
        AttendancePunchEntity::class,
        ShiftCommandEntity::class,
        CheckPhotoEntity::class,
        DriverCacheEntity::class,
        HrSignatureEntity::class,
        HrDocumentCacheEntity::class,
        SnapshotRequestEntity::class,
        WakeUpCallEntity::class,
        TarWarningEntity::class,
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AppDatabase.Version1To2::class),
        // v3: office attendance punches stored on the phone.
        AutoMigration(from = 2, to = 3),
        // v4: driver daily shift commands, defect photos and the driver's cached server answers (docs/16).
        AutoMigration(from = 3, to = 4),
        // v5: personnel document signatures waiting on the phone and the pending document list (docs/17).
        AutoMigration(from = 4, to = 5),
        // v6: remote road snapshot requests and the photos / failure reports waiting on the tablet (docs/24).
        AutoMigration(from = 5, to = 6),
        // v7: wake-up calls of the planner and the answers waiting on the device (docs/25).
        AutoMigration(from = 6, to = 7),
        // v8: TAR warnings set on the device's alarm manager (docs/26 §6).
        AutoMigration(from = 7, to = 8),
    ],
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun shiftDao(): ShiftDao
    abstract fun driverProfileDao(): DriverProfileDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun documentDao(): DocumentDao
    abstract fun pendingCommandDao(): PendingCommandDao
    abstract fun syncMetaDao(): SyncMetaDao
    abstract fun attendancePunchDao(): AttendancePunchDao
    abstract fun shiftCommandDao(): ShiftCommandDao
    abstract fun checkPhotoDao(): CheckPhotoDao
    abstract fun driverCacheDao(): DriverCacheDao
    abstract fun hrDocumentDao(): HrDocumentDao
    abstract fun snapshotRequestDao(): SnapshotRequestDao
    abstract fun wakeUpCallDao(): WakeUpCallDao
    abstract fun tarWarningDao(): TarWarningDao

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
        const val NAME = "dynamicone.db"
    }
}

class DatabaseConverters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
