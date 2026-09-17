package com.dynamictransport.driver.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.dynamictransport.driver.domain.model.PendingActionState
import com.dynamictransport.driver.domain.model.PendingCommandState
import com.dynamictransport.driver.domain.model.SyncErrorCode
import com.dynamictransport.driver.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY plannedStartAt IS NULL, plannedStartAt, tripNo")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips ORDER BY plannedStartAt IS NULL, plannedStartAt, tripNo")
    suspend fun getAll(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observe(id: String): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: String): TripEntity?

    @Upsert
    suspend fun upsert(trip: TripEntity)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM trips")
    suspend fun deleteAll()
}

@Dao
interface ShiftDao {
    @Query("SELECT * FROM shift WHERE slot = 0")
    fun observe(): Flow<ShiftEntity?>

    @Query("SELECT * FROM shift WHERE slot = 0")
    suspend fun get(): ShiftEntity?

    @Upsert
    suspend fun upsert(shift: ShiftEntity)

    @Query("DELETE FROM shift")
    suspend fun deleteAll()
}

@Dao
interface DriverProfileDao {
    @Query("SELECT * FROM driver_profile WHERE id = :id")
    fun observe(id: String): Flow<DriverProfileEntity?>

    @Upsert
    suspend fun upsert(profile: DriverProfileEntity)

    @Query("DELETE FROM driver_profile")
    suspend fun deleteAll()
}

private const val OPEN_STATES = "('PENDING', 'SENDING', 'FAILED_RETRYABLE')"

@Dao
interface PendingActionDao {
    @Insert
    suspend fun insert(action: PendingActionEntity): Long

    @Query("SELECT * FROM pending_actions WHERE driverId = :driverId AND state IN $OPEN_STATES ORDER BY id")
    suspend fun getSendable(driverId: String): List<PendingActionEntity>

    @Query("SELECT * FROM pending_actions WHERE entityType = :entityType AND entityId = :entityId AND state IN $OPEN_STATES ORDER BY id")
    suspend fun getOpenForEntity(entityType: PendingEntityType, entityId: String): List<PendingActionEntity>

    @Query(
        "SELECT COUNT(*) FROM pending_actions WHERE entityType = :entityType AND entityId = :entityId " +
            "AND state = 'FAILED_PERMANENT' AND acknowledged = 0",
    )
    suspend fun countUnacknowledgedFailures(entityType: PendingEntityType, entityId: String): Int

    @Query("SELECT * FROM pending_actions WHERE entityType = 'TRIP' AND entityId = :tripId AND state != 'FAILED_PERMANENT' ORDER BY id")
    fun observeTripHistory(tripId: String): Flow<List<PendingActionEntity>>

    @Query("SELECT COUNT(*) FROM pending_actions WHERE state IN $OPEN_STATES")
    fun observeOpenCount(): Flow<Int>

    @Query(
        "SELECT COUNT(DISTINCT entityId) FROM pending_actions WHERE tripAction = 'FINISH_TRIP' " +
            "AND state != 'FAILED_PERMANENT' AND createdAt >= :since",
    )
    fun observeFinishedTripCount(since: Instant): Flow<Int>

    @Query("SELECT * FROM pending_actions WHERE state = 'FAILED_PERMANENT' AND acknowledged = 0 ORDER BY id")
    fun observeUnacknowledgedFailures(): Flow<List<PendingActionEntity>>

    @Query("SELECT * FROM pending_actions WHERE state = 'FAILED_PERMANENT' AND acknowledged = 0 ORDER BY id")
    suspend fun getUnacknowledgedFailures(): List<PendingActionEntity>

    @Query("SELECT * FROM pending_actions WHERE entityType = :entityType AND entityId = :entityId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestForEntity(entityType: PendingEntityType, entityId: String): PendingActionEntity?

    @Query("SELECT DISTINCT entityId FROM pending_actions WHERE entityType = :entityType AND state != 'SYNCED'")
    suspend fun getEntityIdsWithUnsyncedActions(entityType: PendingEntityType): List<String>

    @Query("SELECT * FROM pending_actions WHERE clientActionId = :clientActionId")
    suspend fun getByClientActionId(clientActionId: String): PendingActionEntity?

    @Query("SELECT * FROM pending_actions ORDER BY id")
    suspend fun getAll(): List<PendingActionEntity>

    @Query("UPDATE pending_actions SET state = :state, retryCount = :retryCount, lastError = :lastError, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateState(id: Long, state: PendingActionState, retryCount: Int, lastError: SyncErrorCode?, updatedAt: Instant)

    /** Actions interrupted mid-send (process death) are safe to resend: the server is idempotent per clientActionId. */
    @Query("UPDATE pending_actions SET state = 'PENDING' WHERE state = 'SENDING'")
    suspend fun resetInFlight(): Int

    @Query("UPDATE pending_actions SET acknowledged = 1 WHERE clientActionId = :clientActionId")
    suspend fun acknowledge(clientActionId: String)

    @Query("DELETE FROM pending_actions")
    suspend fun deleteAll()
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE tripId = :tripId ORDER BY capturedAt")
    fun observeForTrip(tripId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE tripId = :tripId")
    suspend fun getForTrip(tripId: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE localId = :localId")
    suspend fun get(localId: String): DocumentEntity?

    @Upsert
    suspend fun upsert(document: DocumentEntity)

    @Query("UPDATE documents SET syncState = :syncState WHERE localId = :localId")
    suspend fun updateSyncState(localId: String, syncState: SyncState)

    @Query("DELETE FROM documents WHERE localId = :localId")
    suspend fun delete(localId: String)

    /** Keeps documents that still have to be uploaded. */
    @Query("DELETE FROM documents WHERE syncState = 'SYNCED'")
    suspend fun deleteSynced()

    @Query("DELETE FROM documents")
    suspend fun deleteAll()
}

@Dao
interface PendingCommandDao {
    @Query("SELECT * FROM pending_commands WHERE kind = 'TRIP_ACTION' AND tripId = :tripId ORDER BY createdAt, commandId")
    fun observeTripActions(tripId: String): Flow<List<PendingCommandEntity>>

    @Query("SELECT * FROM pending_commands WHERE kind = 'TRIP_ACTION' AND tripId = :tripId ORDER BY createdAt, commandId")
    suspend fun getTripActions(tripId: String): List<PendingCommandEntity>

    /** [tripId] null selects the general conversation only. */
    @Query(
        "SELECT * FROM pending_commands WHERE kind = 'MESSAGE' " +
            "AND ((:tripId IS NULL AND tripId IS NULL) OR tripId = :tripId) ORDER BY createdAt, commandId",
    )
    fun observeMessages(tripId: String?): Flow<List<PendingCommandEntity>>

    @Query("SELECT * FROM pending_commands WHERE commandId = :commandId")
    suspend fun get(commandId: String): PendingCommandEntity?

    @Query("SELECT COUNT(*) FROM pending_commands WHERE state != 'RECORDED'")
    fun observeUnconfirmedCount(): Flow<Int>

    @Insert
    suspend fun insert(command: PendingCommandEntity)

    @Query("UPDATE pending_commands SET state = :state, updatedAt = :updatedAt WHERE commandId = :commandId")
    suspend fun updateState(commandId: String, state: PendingCommandState, updatedAt: Instant)

    @Query("DELETE FROM pending_commands WHERE commandId = :commandId")
    suspend fun delete(commandId: String)

    @Query("DELETE FROM pending_commands WHERE commandId IN (:commandIds)")
    suspend fun delete(commandIds: List<String>)

    @Query("DELETE FROM pending_commands WHERE kind = 'TRIP_ACTION' AND tripId = :tripId")
    suspend fun deleteTripActions(tripId: String)

    @Query("DELETE FROM pending_commands WHERE driverId != :driverId")
    suspend fun deleteForOtherDrivers(driverId: String)

    @Query("DELETE FROM pending_commands")
    suspend fun deleteAll()
}

@Dao
interface SyncMetaDao {
    @Query("SELECT * FROM sync_meta WHERE `key` = :key")
    fun observe(key: String): Flow<SyncMetaEntity?>

    @Upsert
    suspend fun upsert(meta: SyncMetaEntity)
}
