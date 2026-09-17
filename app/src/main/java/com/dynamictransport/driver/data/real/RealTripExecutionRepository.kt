package com.dynamictransport.driver.data.real

import com.dynamictransport.driver.core.common.IdGenerator
import com.dynamictransport.driver.core.network.DriverActionCommandDto
import com.dynamictransport.driver.core.network.DriverApi
import com.dynamictransport.driver.core.network.RemoteException
import com.dynamictransport.driver.domain.model.ActionReceipt
import com.dynamictransport.driver.domain.model.ActionRejection
import com.dynamictransport.driver.domain.model.ActionResult
import com.dynamictransport.driver.domain.model.ExecutionAction
import com.dynamictransport.driver.domain.model.PendingCommandState
import com.dynamictransport.driver.domain.model.PendingTripAction
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.model.TripExecution
import com.dynamictransport.driver.domain.model.TripWorkflow
import com.dynamictransport.driver.domain.repository.TripExecutionRepository
import com.dynamictransport.driver.domain.rules.DriverInputRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trip action workflow: only options of the current server plan are sent, each with a command id that is
 * stored before sending. An unknown outcome keeps the command for an exact resend; a 409 drops it and the
 * current plan is read so the driver chooses again. A queued command is never rewritten.
 */
@Singleton
class RealTripExecutionRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
    private val trips: RealTripRepository,
    private val store: PendingCommandStore,
    private val ids: IdGenerator,
    private val clock: Clock,
) : TripExecutionRepository {

    private val receipts = MutableStateFlow<Map<String, List<ActionReceipt>>>(emptyMap())
    private val sendMutex = Mutex()

    override fun observeExecution(tripId: String): Flow<TripExecution?> = trips.observeExecution(tripId)

    override fun observePendingAction(tripId: String): Flow<PendingTripAction?> = store.observeTripAction(tripId)

    override fun observeReceipts(tripId: String): Flow<List<ActionReceipt>> =
        receipts.map { it[tripId].orEmpty() }.distinctUntilChanged()

    override suspend fun refreshReceipts(tripId: String): RefreshResult = try {
        val page = tracker.call { api.actions(tripId, 1, RECEIPT_PAGE_SIZE) }
        receipts.update { it + (tripId to page.items.orEmpty().map(DriverApiMapping::receipt)) }
        tracker.markReadSucceeded()
        RefreshResult.SUCCESS
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        e.toRefreshResult()
    }

    override suspend fun submit(tripId: String, action: ExecutionAction, note: String?): ActionResult {
        DriverInputRules.validateActionNote(action.code, note)?.let { return ActionResult.Rejected(it) }
        return sendMutex.withLock {
            if (store.tripAction(tripId) != null) return@withLock ActionResult.Rejected(ActionRejection.PENDING_ACTION)
            val execution = trips.currentExecution(tripId)
            if (execution == null || execution.workflow != TripWorkflow.TRIP_ACTIONS || action !in execution.availableActions) {
                return@withLock ActionResult.Rejected(ActionRejection.PLAN_CHANGED)
            }
            val command = PendingTripAction(
                commandId = ids.newId(),
                tripId = tripId,
                expectedVersion = execution.version,
                action = action,
                note = DriverInputRules.normalizeNote(note),
                location = null,
                createdAt = clock.instant(),
                state = PendingCommandState.SENDING,
            )
            if (!store.save(command)) return@withLock ActionResult.Rejected(ActionRejection.NOT_LOGGED_IN)
            send(command)
        }
    }

    override suspend fun resendPending(tripId: String): ActionResult = sendMutex.withLock {
        val command = store.tripAction(tripId) ?: return@withLock ActionResult.Rejected(ActionRejection.NOT_FOUND)
        send(command)
    }

    override suspend fun discardPending(tripId: String): RefreshResult {
        sendMutex.withLock { store.deleteTripActions(tripId) }
        return trips.refreshTrip(tripId)
    }

    fun clear() {
        receipts.value = emptyMap()
    }

    private suspend fun send(command: PendingTripAction): ActionResult {
        store.markState(command.commandId, PendingCommandState.SENDING)
        val response = try {
            tracker.call { api.executeAction(command.tripId, command.toDto()) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.markState(command.commandId, PendingCommandState.OUTCOME_UNKNOWN) }
            throw e
        } catch (e: RemoteException) {
            return failed(command, e)
        }
        store.delete(command.commandId)
        trips.refreshTrip(command.tripId)
        if (!response.succeeded) return ActionResult.Rejected(ActionRejection.FAILED)
        refreshReceipts(command.tripId)
        return ActionResult.Queued
    }

    private suspend fun failed(command: PendingTripAction, e: RemoteException): ActionResult = when {
        e.isOutcomeUnknown() -> {
            store.markState(command.commandId, PendingCommandState.OUTCOME_UNKNOWN)
            ActionResult.Rejected(ActionRejection.OUTCOME_UNKNOWN)
        }
        e is RemoteException.Conflict -> {
            store.delete(command.commandId)
            trips.refreshTrip(command.tripId)
            ActionResult.Rejected(ActionRejection.PLAN_CHANGED)
        }
        e is RemoteException.NotFound && !e.isApiDisabled -> {
            store.delete(command.commandId)
            trips.refreshTrip(command.tripId)
            ActionResult.Rejected(ActionRejection.NOT_FOUND)
        }
        e is RemoteException.Validation || e is RemoteException.Rejected || e is RemoteException.PayloadTooLarge -> {
            store.delete(command.commandId)
            ActionResult.Rejected(ActionRejection.FAILED)
        }
        else -> {
            // Session, permission, rate limit or disabled API: not processed, safe to resend unchanged later.
            store.markState(command.commandId, PendingCommandState.NOT_SENT)
            ActionResult.Rejected(e.toActionRejection())
        }
    }

    private fun PendingTripAction.toDto() = DriverActionCommandDto(
        commandId = commandId,
        expectedVersion = expectedVersion,
        action = DriverApiMapping.actionName(action.code),
        stopId = action.stopId,
        containerId = action.containerId,
        note = note,
        observedAtUtc = location?.observedAt?.toString(),
        latitude = location?.latitude,
        longitude = location?.longitude,
        accuracyMeters = location?.accuracyMeters,
    )

    private companion object {
        const val RECEIPT_PAGE_SIZE = 25
    }
}
