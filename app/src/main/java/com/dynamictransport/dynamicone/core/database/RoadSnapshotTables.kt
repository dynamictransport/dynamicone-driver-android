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

/** Where a snapshot request stands on this tablet (docs/24): from the push to the server's confirmation. */
enum class SnapshotRowState {
    /** Known from the push; the driver still has to open the app, or the capture is about to run. */
    REQUESTED,

    /** Photo on the tablet, upload pending. */
    CAPTURED,

    /** Upload running. */
    SENDING,

    /** The server has the photo. */
    UPLOADED,

    /** Not taken; the reason still has to reach the server. */
    FAILED_PENDING,

    /** Not taken; the server knows. */
    FAILED,

    /** Closed on the server before the tablet could deliver (expired, cancelled): nothing shown as an error. */
    CLOSED,
}

@Entity(
    tableName = "snapshot_requests",
    indices = [Index(value = ["driverId", "requestedAt"])],
)
data class SnapshotRequestEntity(
    @PrimaryKey val requestId: String,
    val tripId: String,
    val driverId: String,
    val requestedByDisplayName: String?,
    val requestedAt: Instant,
    val expiresAt: Instant,
    /** Wire name of the preferred camera (`External` / `Back`). */
    val preferredCamera: String,
    val state: SnapshotRowState,
    /** App-private file of the prepared JPEG while it waits for the upload. */
    val photoPath: String?,
    val capturedAt: Instant?,
    /** Wire name of the camera that took the photo. */
    val camera: String?,
    /** Wire name of the failure reason (docs/24 §7). */
    val failureReason: String?,
    val failedAt: Instant?,
    val updatedAt: Instant,
)

@Dao
interface SnapshotRequestDao {
    @Query("SELECT * FROM snapshot_requests WHERE requestId = :requestId")
    suspend fun get(requestId: String): SnapshotRequestEntity?

    @Query("SELECT * FROM snapshot_requests WHERE driverId = :driverId ORDER BY requestedAt DESC LIMIT 1")
    fun observeLatest(driverId: String): Flow<SnapshotRequestEntity?>

    /** Rows with something still to send: photos and failure reports. */
    @Query("SELECT * FROM snapshot_requests WHERE driverId = :driverId AND state IN ('CAPTURED', 'SENDING', 'FAILED_PENDING') ORDER BY requestedAt")
    suspend fun pendingSends(driverId: String): List<SnapshotRequestEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SnapshotRequestEntity)

    @Query("UPDATE snapshot_requests SET state = :state, updatedAt = :updatedAt WHERE requestId = :requestId")
    suspend fun updateState(requestId: String, state: SnapshotRowState, updatedAt: Instant)

    @Query(
        "UPDATE snapshot_requests SET state = :state, photoPath = :photoPath, capturedAt = :capturedAt, camera = :camera, updatedAt = :updatedAt " +
            "WHERE requestId = :requestId",
    )
    suspend fun markCaptured(requestId: String, state: SnapshotRowState, photoPath: String?, capturedAt: Instant, camera: String, updatedAt: Instant)

    @Query("UPDATE snapshot_requests SET state = :state, failureReason = :reason, failedAt = :failedAt, updatedAt = :updatedAt WHERE requestId = :requestId")
    suspend fun markFailed(requestId: String, state: SnapshotRowState, reason: String, failedAt: Instant, updatedAt: Instant)

    @Query("UPDATE snapshot_requests SET photoPath = NULL, updatedAt = :updatedAt WHERE requestId = :requestId")
    suspend fun clearPhoto(requestId: String, updatedAt: Instant)

    /** Rows older than [before] are not needed any more (their files are deleted by the caller first). */
    @Query("SELECT * FROM snapshot_requests WHERE requestedAt < :before")
    suspend fun olderThan(before: Instant): List<SnapshotRequestEntity>

    @Query("DELETE FROM snapshot_requests WHERE requestId IN (:requestIds)")
    suspend fun delete(requestIds: List<String>)
}
