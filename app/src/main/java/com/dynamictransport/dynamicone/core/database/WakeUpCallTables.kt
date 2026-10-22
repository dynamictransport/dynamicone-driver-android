package com.dynamictransport.dynamicone.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** What a wake-up call (docs/25) did on this device, in the order it moves. */
enum class WakeUpRowState {
    /** Rings (or rang when the process was stopped; it rings again at the next start while the deadline holds). */
    RINGING,

    /** The driver answered; the answer still has to reach the server. */
    ACKNOWLEDGED_PENDING,

    /** The server has the answer. */
    ACKNOWLEDGED,

    /** Rang until the deadline; the report still has to reach the server. */
    EXPIRED_PENDING,

    /** The server has the report (or closed the call itself). */
    EXPIRED,

    /** Silenced by the server: answered on another device, cancelled, or no longer open when read. */
    STOPPED,
}

/** One wake-up call per id; `requestedAt` orders the calls of a driver. */
@Entity(
    tableName = "wake_up_calls",
    indices = [Index(value = ["driverId", "requestedAt"])],
)
data class WakeUpCallEntity(
    @PrimaryKey val callId: String,
    val driverId: String,
    /** Wire name of the reason (docs/25 §5). */
    val reason: String,
    val requestedByDisplayName: String?,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val tripId: String?,
    val tripNo: String?,
    val state: WakeUpRowState,
    val acknowledgedAt: Instant?,
    /** When the answer or the ring-out happened on this device (sent as `occurredAt`). */
    val resultAt: Instant?,
    val updatedAt: Instant,
)

@Dao
interface WakeUpCallDao {
    @Query("SELECT * FROM wake_up_calls WHERE callId = :callId")
    suspend fun get(callId: String): WakeUpCallEntity?

    @Query("SELECT * FROM wake_up_calls WHERE driverId = :driverId ORDER BY requestedAt DESC LIMIT 1")
    fun observeLatest(driverId: String): Flow<WakeUpCallEntity?>

    /** Calls that were ringing when the process stopped. */
    @Query("SELECT * FROM wake_up_calls WHERE driverId = :driverId AND state = 'RINGING' ORDER BY requestedAt")
    suspend fun ringing(driverId: String): List<WakeUpCallEntity>

    /** Rows with a result still to send. */
    @Query("SELECT * FROM wake_up_calls WHERE driverId = :driverId AND state IN ('ACKNOWLEDGED_PENDING', 'EXPIRED_PENDING') ORDER BY requestedAt")
    suspend fun pendingSends(driverId: String): List<WakeUpCallEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: WakeUpCallEntity)

    @Query("UPDATE wake_up_calls SET state = :state, updatedAt = :updatedAt WHERE callId = :callId")
    suspend fun updateState(callId: String, state: WakeUpRowState, updatedAt: Instant)

    @Query(
        "UPDATE wake_up_calls SET state = :state, acknowledgedAt = :acknowledgedAt, resultAt = :resultAt, updatedAt = :updatedAt " +
            "WHERE callId = :callId",
    )
    suspend fun markResult(callId: String, state: WakeUpRowState, acknowledgedAt: Instant?, resultAt: Instant, updatedAt: Instant)

    /** Rows older than [before] are not needed any more. */
    @Query("SELECT * FROM wake_up_calls WHERE requestedAt < :before")
    suspend fun olderThan(before: Instant): List<WakeUpCallEntity>

    @Query("DELETE FROM wake_up_calls WHERE callId IN (:callIds)")
    suspend fun delete(callIds: List<String>)
}
