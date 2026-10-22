package com.dynamictransport.dynamicone.feature.tripdetail

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.ActionReceipt
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.BreakState
import com.dynamictransport.dynamicone.domain.model.DocumentSlot
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import com.dynamictransport.dynamicone.domain.model.PendingTripAction
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.RejectReason
import com.dynamictransport.dynamicone.domain.model.TrailerOption
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripExecution
import com.dynamictransport.dynamicone.domain.model.TripLocation
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.TripTrailerState
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.MessageRepository
import com.dynamictransport.dynamicone.domain.repository.NetworkMonitor
import com.dynamictransport.dynamicone.domain.repository.ServerClock
import com.dynamictransport.dynamicone.domain.repository.ShiftRepository
import com.dynamictransport.dynamicone.domain.repository.TripExecutionRepository
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import com.dynamictransport.dynamicone.domain.rules.DriverInputRules
import com.dynamictransport.dynamicone.domain.statemachine.ActionPlan
import com.dynamictransport.dynamicone.domain.statemachine.NextActionResolver
import com.dynamictransport.dynamicone.domain.statemachine.PrimaryAction
import com.dynamictransport.dynamicone.domain.usecase.SubmitTripActionUseCase
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

const val TRIP_ID_ARG = "tripId"

/** Outcome and time of the trip reloads plus the server clock correction (docs/26). */
private data class RefreshInfo(
    val result: RefreshResult?,
    val lastUpdatedAt: Instant?,
    val clockOffset: Duration,
)

/** What the trip action workflow knows about one trip right now. */
private data class ExecutionSnapshot(
    val plan: TripExecution?,
    val pending: PendingTripAction?,
    val receipts: List<ActionReceipt>,
    val trailers: TripTrailerState?,
)

data class TripDetailUiState(
    val loading: Boolean = true,
    val trip: Trip? = null,
    val plan: ActionPlan = ActionPlan(primary = null),
    val timeline: List<TimelineStep> = emptyList(),
    val breakStartedAt: Instant? = null,
    val isOnline: Boolean = true,
    /** Demo keeps actions in an offline queue; the real API needs a connection for every action. */
    val offlineActionsQueued: Boolean = true,
    /** The trip could not be loaded and nothing is known about it. */
    @param:StringRes val loadError: Int? = null,
    /** Outcome of the latest reload; null while the first one runs. */
    val refreshResult: RefreshResult? = null,
    /** Device time of the latest successful reload of this trip; null until one succeeded in this session. */
    val lastUpdatedAt: Instant? = null,
    /** Server time minus device time, from the HTTP `Date` header; TAR countdowns use it (docs/26). */
    val clockOffset: Duration = Duration.ZERO,
    val submitting: Boolean = false,
    @param:StringRes val message: Int? = null,
    /** Trip action workflow: the server plan (null until read). */
    val execution: TripExecution? = null,
    /** An action stored on the device whose outcome is not confirmed. */
    val pendingAction: PendingTripAction? = null,
    val receipts: List<ActionReceipt> = emptyList(),
    /** Incoming messages of this trip without a read receipt. */
    val unreadMessages: Int = 0,
    /** Daily shift (docs/16): starting it opens the flow at the vehicle instead of a single tap. */
    val shiftStartsAtVehicle: Boolean = false,
    /** Trailer choice of this trip (docs/20); null until read or for trips without the endpoint. */
    val trailers: TripTrailerState? = null,
) {
    /** Work on this trip waits for the daily shift; a stored action is still resolved first. */
    val shiftStartRequired: Boolean get() = shiftStartsAtVehicle && plan.primary == PrimaryAction.StartShift && pendingAction == null

    /** The steps of this trip come from the server plan (everything after accepting). */
    val usesServerActions: Boolean
        get() = trip != null && trip.workflow.usesServerActions && trip.state != TripState.ASSIGNED

    val isLegacyWorkflow: Boolean get() = trip?.workflow == TripWorkflow.LEGACY_STATUSES

    /**
     * Start or stop/container completions, exactly as offered by the server. A step that waits for the
     * trailer confirmation is left out: the trailer is its own step (docs/20).
     */
    val progressActions: List<ExecutionAction>
        get() = execution?.availableActions.orEmpty().filter { it.isProgress && !it.blockedByTrailer }

    /**
     * The trip cannot start before the driver confirms the trailer under the truck (docs/20). Reported by
     * the plan itself and by the Start option it blocks, so an older field alone never hides the step.
     */
    val trailerConfirmationRequired: Boolean
        get() = execution?.trailerConfirmationRequired == true ||
            execution?.availableActions.orEmpty().any { it.blockedByTrailer }

    /** The trailer the driver confirmed for this trip, once the list has been read. */
    val confirmedTrailer: TrailerOption? get() = trailers?.confirmed

    val restAction: ExecutionAction? get() = execution?.availableActions?.firstOrNull { it.code == ExecutionActionCode.REST }

    val problemAvailable: Boolean get() = execution?.availableActions?.any { it.code == ExecutionActionCode.PROBLEM } == true

    val planLoading: Boolean get() = execution == null && refreshResult == null

    val planUnavailable: Boolean get() = execution == null && refreshResult != null

    /** Evidence slots that need an upload according to the server checklist; null when not read. */
    val openDocumentCount: Int? get() = execution?.documents?.count { it.needsUpload }

    /** Evidence the server requires before an offered step can be confirmed (docs/18), in checklist order. */
    val blockingDocuments: List<DocumentSlot>
        get() = if (usesServerActions) execution?.blockingSlots.orEmpty() else emptyList()

    val navigationStop: TripLocation?
        get() {
            val current = trip ?: return null
            return current.nextStop?.takeIf { !current.state.isTerminal }
        }

    /** The trip on screen could not be reloaded: what is shown is the last successful read. */
    val stale: Boolean get() = trip != null && refreshResult != null && refreshResult != RefreshResult.SUCCESS
}

@HiltViewModel
class TripDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tripRepository: TripRepository,
    private val shiftRepository: ShiftRepository,
    networkMonitor: NetworkMonitor,
    appInfo: AppInfo,
    private val submitTripAction: SubmitTripActionUseCase,
    private val executionRepository: TripExecutionRepository,
    messageRepository: MessageRepository,
    serverClock: ServerClock,
    private val clock: Clock,
) : ViewModel() {

    private val tripId: String = checkNotNull(savedStateHandle[TRIP_ID_ARG])
    private val singleFlight = SingleFlight()
    private val message = MutableStateFlow<Int?>(null)
    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)
    private val lastUpdatedAt = MutableStateFlow<Instant?>(null)
    private val reloadMutex = Mutex()
    private var polling: Job? = null

    /** The reload started in [init] covers the first resume; later resumes read again. */
    private var initialReloadPending = true

    private val refreshInfo = combine(lastRefresh, lastUpdatedAt, serverClock.offset, ::RefreshInfo)

    private val content = combine(
        tripRepository.observeTrip(tripId),
        shiftRepository.observeShift(),
        tripRepository.observeActionHistory(tripId),
        networkMonitor.isOnline,
        refreshInfo,
    ) { trip, shift, history, online, info ->
        val refresh = info.result
        TripDetailUiState(
            loading = trip == null && refresh == null,
            trip = trip,
            plan = trip?.let { NextActionResolver.forTrip(it, shift) } ?: ActionPlan(primary = null),
            timeline = trip?.takeIf { !it.workflow.usesServerActions }?.let { TripTimeline.build(it, history) }.orEmpty(),
            breakStartedAt = if (trip?.breakState == BreakState.ON_BREAK) {
                history.lastOrNull { it.action == TripActionType.START_BREAK }?.occurredAt
            } else {
                null
            },
            isOnline = online,
            offlineActionsQueued = appInfo.isDemoMode,
            loadError = refresh?.takeIf { trip == null }?.messageRes(),
            refreshResult = refresh,
            lastUpdatedAt = info.lastUpdatedAt,
            clockOffset = info.clockOffset,
            shiftStartsAtVehicle = shift.requiresVehicleCheck,
        )
    }

    private val execution = combine(
        executionRepository.observeExecution(tripId),
        executionRepository.observePendingAction(tripId),
        executionRepository.observeReceipts(tripId),
        executionRepository.observeTrailers(tripId),
        ::ExecutionSnapshot,
    )

    private val unreadMessages = messageRepository.observeConversations().map { it.unreadFor(tripId) }

    val uiState: StateFlow<TripDetailUiState> =
        combine(content, execution, singleFlight.busy, message, unreadMessages) { state, snapshot, busy, currentMessage, unread ->
            state.copy(
                execution = snapshot.plan,
                pendingAction = snapshot.pending,
                receipts = snapshot.receipts,
                trailers = snapshot.trailers,
                submitting = busy || snapshot.pending?.state == PendingCommandState.SENDING,
                message = currentMessage,
                unreadMessages = unread,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripDetailUiState())

    init {
        refresh()
    }

    /**
     * While the screen is in the foreground the trip is read again every [POLL_INTERVAL_MILLIS] so a changed or
     * removed TAR shows up (docs/26); the TAR countdown itself ticks on the device. A return from the background
     * reads at once. One loop at a time: leaving the screen cancels it.
     */
    fun onResume() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            if (initialReloadPending) initialReloadPending = false else reload(quiet = true)
            while (true) {
                delay(POLL_INTERVAL_MILLIS)
                reload(quiet = true)
            }
        }
    }

    fun onPause() {
        polling?.cancel()
        polling = null
    }

    fun onPrimaryAction(action: PrimaryAction) {
        singleFlight.launch(viewModelScope) {
            val result = when (action) {
                PrimaryAction.StartShift -> shiftRepository.startShift()
                is PrimaryAction.OnTrip -> submitTripAction(action.tripId, action.action)
                else -> null
            }
            val feedback = when (result) {
                is ActionResult.Rejected -> result.reason.messageRes()
                // A break is a single report that does not change the trip step: confirm it.
                ActionResult.Queued ->
                    R.string.trip_break_reported.takeIf { (action as? PrimaryAction.OnTrip)?.action == TripActionType.START_BREAK }
                null -> null
            }
            feedback?.let { message.value = it }
        }
    }

    fun onStartBreak() = onPrimaryAction(PrimaryAction.OnTrip(tripId, TripActionType.START_BREAK))

    /** One option of the server plan, sent with its stop and container unchanged. */
    fun onExecutionAction(action: ExecutionAction) {
        singleFlight.launch(viewModelScope) {
            message.value = when (val result = executionRepository.submit(tripId, action, note = null)) {
                ActionResult.Queued -> if (action.code == ExecutionActionCode.REST) R.string.trip_break_reported else R.string.exec_recorded
                is ActionResult.Rejected -> result.reason.messageRes()
            }
        }
    }

    /** Resends the stored action with the same command id and payload. */
    fun onResendPending() {
        singleFlight.launch(viewModelScope) {
            message.value = when (val result = executionRepository.resendPending(tripId)) {
                ActionResult.Queued -> R.string.exec_recorded
                is ActionResult.Rejected -> result.reason.messageRes()
            }
        }
    }

    /** Drops the stored action and shows the current plan. */
    fun onDiscardPending() {
        singleFlight.launch(viewModelScope) {
            executionRepository.discardPending(tripId).messageRes()?.let { message.value = it }
        }
    }

    fun onRetry() = refresh()

    fun showMessage(@StringRes messageRes: Int) {
        message.value = messageRes
    }

    fun onMessageShown() {
        message.value = null
    }

    private fun refresh() {
        viewModelScope.launch { reload(quiet = false) }
    }

    /**
     * A quiet reload (polling, return to the foreground) keeps the previous outcome on screen while it runs and
     * reports a failure only through the stale banner, never as a message every minute.
     */
    private suspend fun reload(quiet: Boolean) = reloadMutex.withLock {
        if (!quiet) lastRefresh.value = null
        val result = tripRepository.refreshTrip(tripId)
        if (result == RefreshResult.SUCCESS) lastUpdatedAt.value = clock.instant()
        lastRefresh.value = result
        val trip = tripRepository.observeTrip(tripId).first()
        if (trip?.workflow == TripWorkflow.TRIP_ACTIONS) executionRepository.refreshReceipts(tripId)
        // Only an accepted trip can still choose its trailer; a running one keeps the recorded choice.
        if (trip?.workflow == TripWorkflow.TRIP_ACTIONS && trip.state == TripState.ACCEPTED) {
            executionRepository.refreshTrailers(tripId)
        }
        // With a trip on screen a failed reload is only a hint; the last loaded state stays visible.
        if (!quiet) result.messageRes()?.let { if (trip != null) message.value = it }
    }

    companion object {
        const val POLL_INTERVAL_MILLIS = 60_000L
    }
}

data class RejectTripUiState(
    val tripNo: String? = null,
    val reason: RejectReason? = null,
    val note: String = "",
    val submitting: Boolean = false,
    @param:StringRes val error: Int? = null,
    val completed: Boolean = false,
) {
    val noteRequired: Boolean get() = reason == RejectReason.OTHER
    val canSubmit: Boolean get() = DriverInputRules.validateReject(reason, note) == null
}

@HiltViewModel
class RejectTripViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    tripRepository: TripRepository,
    private val submitTripAction: SubmitTripActionUseCase,
) : ViewModel() {

    private val tripId: String = checkNotNull(savedStateHandle[TRIP_ID_ARG])
    private val form = MutableStateFlow(RejectTripUiState())
    private val singleFlight = SingleFlight()

    val uiState: StateFlow<RejectTripUiState> =
        combine(form, singleFlight.busy, tripRepository.observeTrip(tripId)) { state, busy, trip ->
            state.copy(submitting = busy, tripNo = trip?.tripNo)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RejectTripUiState())

    fun onReasonSelected(reason: RejectReason) = form.update { it.copy(reason = reason, error = null) }

    fun onNoteChanged(note: String) =
        form.update { it.copy(note = note.take(DriverInputRules.REJECT_NOTE_MAX_LENGTH), error = null) }

    fun onSubmit() {
        val current = form.value
        singleFlight.launch(viewModelScope) {
            when (val result = submitTripAction(tripId, TripActionType.REJECT_TRIP, current.reason, current.note)) {
                ActionResult.Queued -> form.update { it.copy(completed = true) }
                is ActionResult.Rejected -> form.update { it.copy(error = result.reason.messageRes()) }
            }
        }
    }
}
