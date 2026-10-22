package com.dynamictransport.dynamicone.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.time.Instant

/**
 * A TAR warning set on this device's AlarmManager (docs/26 §6). Kept so the alarms are set again after a restart
 * and so a warning that already rang is not rung twice for the same window.
 */
@Entity(tableName = "tar_warnings")
data class TarWarningEntity(
    /** `tripId/stopId/leadMinutes`. */
    @PrimaryKey val key: String,
    val tripId: String,
    val tripNo: String,
    val stopId: String,
    val stopName: String?,
    val code: String,
    /** End of the TAR window (UTC). */
    val endAt: Instant,
    val timeZone: String,
    val leadMinutes: Long,
    /** Device time at which the alarm is set (server trigger time minus the clock offset). */
    val triggerAt: Instant,
    val firedAt: Instant?,
    val updatedAt: Instant,
)

@Dao
interface TarWarningDao {
    @Query("SELECT * FROM tar_warnings ORDER BY triggerAt")
    suspend fun all(): List<TarWarningEntity>

    @Query("SELECT * FROM tar_warnings WHERE `key` = :key")
    suspend fun get(key: String): TarWarningEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TarWarningEntity)

    @Query("UPDATE tar_warnings SET firedAt = :firedAt, updatedAt = :firedAt WHERE `key` = :key")
    suspend fun markFired(key: String, firedAt: Instant)

    @Query("DELETE FROM tar_warnings WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM tar_warnings")
    suspend fun deleteAll()
}
