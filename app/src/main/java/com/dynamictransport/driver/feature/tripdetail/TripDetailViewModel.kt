package com.dynamictransport.driver.feature.tripdetail

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dynamictransport.driver.R
import com.dynamictransport.driver.domain.model.ActionReceipt
import com.dynamictransport.driver.domain.model.ActionResult
import com.dynamictransport.driver.domain.model.BreakState
import com.dynamictransport.driver.domain.model.ExecutionAction
import com.dynamictransport.driver.domain.model.ExecutionActionCode
import com.dynamictransport.driver.domain.model.PendingCommandState
import com.dynamictransport.driver.domain.model.PendingTripAction
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.model.RejectReason
import com.dynamictransport.driver.domain.model.Trip
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.model.TripExecution
import com.dynamictransport.driver.domain.model.TripLocation
import com.dynamictransport.driver.domain.model.TripState
import com.dynamictransport.driver.domain.model.TripWorkflow
import com.dynamictransport.driver.domain.repository.AppInfo
import com.dynamictransport.driver.domain.repository.MessageRepository
import com.dynamictransport.driver.domain.repository.NetworkMonitor
import com.dynamictransport.driver.domain.repository.ShiftRepository
import com.dynamictransport.driver.domain.repository.TripExecutionRepository
import com.dynamictransport.driver.domain.repository.TripRepository
import com.dynamictransport.driver.domain.rules.DriverInputRules
import com.dynamictransport.driver.domain.statemachine.ActionPlan
import com.dynamictransport.driver.domain.statemachine.NextActionResolver
import com.dynamictransport.driver.domain.statemachine.PrimaryAction
import com.dynamictransport.driver.domain.usecase.SubmitTripActionUseCase
import com.dynamictransport.driver.feature.common.SingleFlight
import com.dynamictransport.driver.feature.common.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

const val TRIP_ID_ARG = "tripId"

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
    /** Outcome of the latest reload; null while it runs. */
    val refreshResult: RefreshResult? = null,
    val submitting: Boolean = false,
    @param:StringRes val message: Int? = null,
    /** Trip action workflow: the server plan (null until read). */
    val execution: TripExecution? = null,
    /** An action stored on the device whose outcome is not confirmed. */
    val pendingAction: PendingTripAction? = null,
    val receipts: List<ActionReceipt> = emptyList(),
    /** Incoming messages of this trip without a read receipt. */
    val unreadMessages: Int = 0,
) {
    /** The steps of this trip come from the server plan (everything after accepting). */
    val usesServerActions: Boolean
        get() = trip != null && trip.workflow.usesServerActions && trip.state != TripState.ASSIGNED

    val isLegacyWorkflow: Boolean get() = trip?.workflow == TripWorkflow.LEGACY_STATUSES

    /** Start or stop/container completions, exactly as offered by the server. */
    val progressActions: List<ExecutionAction> get() = execution?.availableActions.orEmpty().filter { it.isProgress }

    val restAction: ExecutionAction? get() = execution?.availableActions?.firstOrNull { it.code == ExecutionActionCode.REST }

    val problemAvailable: Boolean get() = execution?.availableActions?.any { it.code == ExecutionActionCode.PROBLEM } == true

    val planLoading: Boolean get() = execution == null && refreshResult == null

    val planUnavailable: Boolean get() = execution == null && refreshResult != null

    /** Evidence slots that need an upload according to the server checklist; null when not read. */
    val openDocumentCount: Int? get() = execution?.documents?.count { it.needsUpload }

    val navigationStop: TripLocation?
        get() {
            val current = trip ?: return null
            return current.nextStop?.takeIf { !current.state.isTerminal }
        }
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
) : ViewModel() {

    private val tripId: String = checkNotNull(savedStateHandle[TRIP_ID_ARG])
    private val singleFlight = SingleFlight()
    private val message = MutableStateFlow<Int?>(null)
    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)

    private val content = combine(
        tripRepository.observeTrip(tripId),
        shiftRepository.observeShift(),
        tripRepository.observeActionHistory(tripId),
        networkMonitor.isOnline,
        lastRefresh,
    ) { trip, shift, history, online, refresh ->
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
        )
    }

    private val execution = combine(
        executionRepository.observeExecution(tripId),
        executionRepository.observePendingAction(tripId),
        executionRepository.observeReceipts(tripId),
        ::Triple,
    )

    private val unreadMessages = messageRepository.observeConversations().map { it.unreadFor(tripId) }

    val uiState: StateFlow<TripDetailUiState> =
        combine(content, execution, singleFlight.busy, message, unreadMessages) { state, (plan, pending, receipts), busy, currentMessage, unread ->
            state.copy(
                execution = plan,
                pendingAction = pending,
                receipts = receipts,
                submitting = busy || pending?.state == PendingCommandState.SENDING,
                message = currentMessage,
                unreadMessages = unread,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripDetailUiState())

    init {
        refresh()
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
        viewModelScope.launch {
            lastRefresh.value = null
            val result = tripRepository.refreshTrip(tripId)
            lastRefresh.value = result
            val trip = tripRepository.observeTrip(tripId).first()
            if (trip?.workflow == TripWorkflow.TRIP_ACTIONS) executionRepository.refreshReceipts(tripId)
            // With a trip on screen a failed reload is only a hint; the last loaded state stays visible.
            result.messageRes()?.let { if (trip != null) message.value = it }
        }
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
