package com.dynamictransport.dynamicone.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.dynamictransport.dynamicone.domain.model.ShiftCommandFailure
import com.dynamictransport.dynamicone.domain.model.ShiftCommandState
import com.dynamictransport.dynamicone.domain.model.ShiftCommandType
import com.dynamictransport.dynamicone.domain.model.VehicleIdentification
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Daily shift starts and ends of one driver (docs/16 §7, §9). Written before sending; the command id is the idempotency
 * key and [payloadJson] is the request without the elapsed time, sent unchanged every time.
 */
@Entity(
    tableName = "shift_commands",
    indices = [Index(value = ["driverId", "state"])],
)
data class ShiftCommandEntity(
    @PrimaryKey val commandId: String,
    val driverId: String,
    val type: ShiftCommandType,
    val payloadJson: String,
    /** Phone time of the start or end. */
    val occurredAt: Instant,
    /** `SystemClock.elapsedRealtime()` at [occurredAt]. */
    val occurredElapsedMillis: Long,
    /** Boot count at [occurredAt]; the elapsed time is only comparable within the same boot. */
    val bootCount: Int?,
    val state: ShiftCommandState,
    val failure: ShiftCommandFailure?,
    val vehiclePlate: String?,
    val identifiedBy: VehicleIdentification?,
    val defectCount: Int,
    /** For an end: the start it closes. */
    val startCommandId: String?,
    /** For a start: the server's vehicle check, needed to send the defect photos. */
    val checkId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** A defect photo of a shift start, sent after the start was recorded (docs/16 §8). The file is app-private. */
@Entity(
    tableName = "check_photos",
    indices = [Index(value = ["commandId"])],
)
data class CheckPhotoEntity(
    @PrimaryKey val photoId: String,
    val commandId: String,
    val filePath: String,
    val state: ShiftCommandState,
    val updatedAt: Instant,
)

/** The latest server answer the driver screens and the alarm need offline and after a restart (JSON of the DTO). */
@Entity(
    tableName = "driver_cache",
    primaryKeys = ["driverId", "key"],
)
data class DriverCacheEntity(
    val driverId: String,
    val key: String,
    val json: String,
    val updatedAt: Instant,
) {
    companion object {
        const val SHIFT = "shift"
        const val DRIVER_TIMES = "driver_times"
        const val CHECK_TEMPLATE = "check_template"
    }
}

@Dao
interface ShiftCommandDao {
    @Query("SELECT * FROM shift_commands WHERE driverId = :driverId ORDER BY occurredAt DESC, commandId")
    fun observe(driverId: String): Flow<List<ShiftCommandEntity>>

    @Query("SELECT * FROM shift_commands WHERE driverId = :driverId ORDER BY occurredAt DESC, commandId")
    suspend fun getAll(driverId: String): List<ShiftCommandEntity>

    @Query("SELECT * FROM shift_commands WHERE commandId = :commandId")
    suspend fun get(commandId: String): ShiftCommandEntity?

    /** Waiting commands, oldest first. `SENDING` rows are included: only an interrupted send leaves them behind. */
    @Query("SELECT * FROM shift_commands WHERE driverId = :driverId AND state IN ('PENDING', 'SENDING') ORDER BY occurredAt, commandId")
    suspend fun getUnsent(driverId: String): List<ShiftCommandEntity>

    @Insert
    suspend fun insert(command: ShiftCommandEntity)

    @Query("UPDATE shift_commands SET state = :state, failure = :failure, updatedAt = :updatedAt WHERE commandId = :commandId")
    suspend fun updateState(commandId: String, state: ShiftCommandState, failure: ShiftCommandFailure?, updatedAt: Instant)

    @Query("UPDATE shift_commands SET state = 'SYNCED', failure = NULL, checkId = :checkId, updatedAt = :updatedAt WHERE commandId = :commandId")
    suspend fun markSynced(commandId: String, checkId: String?, updatedAt: Instant)

    @Query("DELETE FROM shift_commands WHERE commandId = :commandId")
    suspend fun delete(commandId: String)

    @Query("DELETE FROM shift_commands WHERE state = 'SYNCED' AND occurredAt < :before")
    suspend fun deleteSyncedBefore(before: Instant)

    @Query("DELETE FROM shift_commands WHERE driverId != :driverId")
    suspend fun deleteForOtherDrivers(driverId: String)
}

@Dao
interface CheckPhotoDao {
    @Query("SELECT * FROM check_photos ORDER BY photoId")
    fun observeAll(): Flow<List<CheckPhotoEntity>>

    @Query("SELECT * FROM check_photos WHERE commandId = :commandId ORDER BY photoId")
    suspend fun forCommand(commandId: String): List<CheckPhotoEntity>

    /** Replaces rows an interrupted store left behind for the same photo ids. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(photos: List<CheckPhotoEntity>)

    @Query("SELECT filePath FROM check_photos")
    suspend fun allFilePaths(): List<String>

    @Query("UPDATE check_photos SET state = :state, updatedAt = :updatedAt WHERE photoId = :photoId")
    suspend fun updateState(photoId: String, state: ShiftCommandState, updatedAt: Instant)

    @Query("DELETE FROM check_photos WHERE commandId = :commandId")
    suspend fun deleteForCommand(commandId: String)

    /** Photos whose command is gone (removed, kept too long or of another driver); their files are deleted too. */
    @Query("SELECT * FROM check_photos WHERE commandId NOT IN (SELECT commandId FROM shift_commands)")
    suspend fun getOrphans(): List<CheckPhotoEntity>

    @Query("DELETE FROM check_photos WHERE photoId = :photoId")
    suspend fun delete(photoId: String)
}

@Dao
interface DriverCacheDao {
    @Query("SELECT * FROM driver_cache WHERE driverId = :driverId AND `key` = :key")
    suspend fun get(driverId: String, key: String): DriverCacheEntity?

    @Upsert
    suspend fun upsert(entry: DriverCacheEntity)

    @Query("DELETE FROM driver_cache WHERE driverId != :driverId")
    suspend fun deleteForOtherDrivers(driverId: String)

    @Query("DELETE FROM driver_cache")
    suspend fun deleteAll()
}
