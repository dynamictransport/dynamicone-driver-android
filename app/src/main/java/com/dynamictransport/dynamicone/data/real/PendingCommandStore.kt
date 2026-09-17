package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.database.PendingCommandDao
import com.dynamictransport.dynamicone.core.database.PendingCommandEntity
import com.dynamictransport.dynamicone.core.database.PendingCommandKind
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.LocationEvidence
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import com.dynamictransport.dynamicone.domain.model.PendingMessage
import com.dynamictransport.dynamicone.domain.model.PendingTripAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable store of Driver API commands with an idempotent command id (trip actions and messages).
 * A command is written before it is sent, so a lost response or an app restart can resend exactly the
 * same command id and payload. Commands are bound to the driver who created them.
 */
@Singleton
class PendingCommandStore @Inject constructor(
    private val dao: PendingCommandDao,
    private val sessionPreferences: SessionPreferences,
    private val clock: Clock,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Rows still `SENDING` from before this process started were interrupted: their outcome is unknown. */
    private val processStartedAt: Instant = clock.instant()

    @Serializable
    private data class StoredTripAction(
        val tripId: String,
        val expectedVersion: String,
        val action: String,
        val label: String? = null,
        val stopId: String? = null,
        val containerId: String? = null,
        val note: String? = null,
        val observedAtUtc: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val accuracyMeters: Double? = null,
    )

    @Serializable
    private data class StoredMessage(
        val tripId: String? = null,
        val body: String,
    )

    fun observeTripAction(tripId: String): Flow<PendingTripAction?> =
        dao.observeTripActions(tripId).map { rows -> rows.firstNotNullOfOrNull(::toTripAction) }

    suspend fun tripAction(tripId: String): PendingTripAction? = dao.getTripActions(tripId).firstNotNullOfOrNull(::toTripAction)

    /** False when nobody is signed in. */
    suspend fun save(action: PendingTripAction): Boolean {
        val driverId = sessionPreferences.driverId() ?: return false
        val payload = StoredTripAction(
            tripId = action.tripId,
            expectedVersion = action.expectedVersion,
            action = DriverApiMapping.actionName(action.action.code),
            label = action.action.label,
            stopId = action.action.stopId,
            containerId = action.action.containerId,
            note = action.note,
            observedAtUtc = action.location?.observedAt?.toString(),
            latitude = action.location?.latitude,
            longitude = action.location?.longitude,
            accuracyMeters = action.location?.accuracyMeters,
        )
        dao.insert(
            PendingCommandEntity(
                commandId = action.commandId,
                driverId = driverId,
                kind = PendingCommandKind.TRIP_ACTION,
                tripId = action.tripId,
                payloadJson = json.encodeToString(StoredTripAction.serializer(), payload),
                state = action.state,
                createdAt = action.createdAt,
                updatedAt = clock.instant(),
            ),
        )
        return true
    }

    fun observeMessages(tripId: String?): Flow<List<PendingMessage>> =
        dao.observeMessages(tripId).map { rows -> rows.mapNotNull(::toMessage) }

    suspend fun message(commandId: String): PendingMessage? =
        dao.get(commandId)?.takeIf { it.kind == PendingCommandKind.MESSAGE }?.let(::toMessage)

    suspend fun save(message: PendingMessage): Boolean {
        val driverId = sessionPreferences.driverId() ?: return false
        dao.insert(
            PendingCommandEntity(
                commandId = message.commandId,
                driverId = driverId,
                kind = PendingCommandKind.MESSAGE,
                tripId = message.tripId,
                payloadJson = json.encodeToString(StoredMessage.serializer(), StoredMessage(message.tripId, message.body)),
                state = message.state,
                createdAt = message.createdAt,
                updatedAt = clock.instant(),
            ),
        )
        return true
    }

    suspend fun markState(commandId: String, state: PendingCommandState) = dao.updateState(commandId, state, clock.instant())

    suspend fun delete(commandId: String) = dao.delete(commandId)

    /** Messages the server list already contains. */
    suspend fun deleteAll(commandIds: Collection<String>) {
        if (commandIds.isNotEmpty()) dao.delete(commandIds.toList())
    }

    suspend fun deleteTripActions(tripId: String) = dao.deleteTripActions(tripId)

    /** Commands whose server outcome is not confirmed yet. */
    fun observeUnconfirmedCount(): Flow<Int> = dao.observeUnconfirmedCount()

    suspend fun clear() = dao.deleteAll()

    suspend fun keepOnly(driverId: String) = dao.deleteForOtherDrivers(driverId)

    private fun effectiveState(entity: PendingCommandEntity): PendingCommandState =
        if (entity.state == PendingCommandState.SENDING && entity.updatedAt.isBefore(processStartedAt)) {
            PendingCommandState.OUTCOME_UNKNOWN
        } else {
            entity.state
        }

    private fun toTripAction(entity: PendingCommandEntity): PendingTripAction? {
        val stored = decode { json.decodeFromString(StoredTripAction.serializer(), entity.payloadJson) } ?: return null
        val code = DriverApiMapping.actionCode(stored.action) ?: return null
        val observedAt = stored.observedAtUtc?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val location = if (observedAt != null && stored.latitude != null && stored.longitude != null) {
            LocationEvidence(observedAt, stored.latitude, stored.longitude, stored.accuracyMeters)
        } else {
            null
        }
        return PendingTripAction(
            commandId = entity.commandId,
            tripId = stored.tripId,
            expectedVersion = stored.expectedVersion,
            action = ExecutionAction(code, stored.label, stored.stopId, stored.containerId),
            note = stored.note,
            location = location,
            createdAt = entity.createdAt,
            state = effectiveState(entity),
        )
    }

    private fun toMessage(entity: PendingCommandEntity): PendingMessage? {
        val stored = decode { json.decodeFromString(StoredMessage.serializer(), entity.payloadJson) } ?: return null
        return PendingMessage(entity.commandId, stored.tripId, stored.body, entity.createdAt, effectiveState(entity))
    }

    private inline fun <T> decode(block: () -> T): T? = try {
        block()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
