package com.dynamictransport.dynamicone.feature.attendance

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.nfc.NfcAvailability
import com.dynamictransport.dynamicone.core.nfc.NfcStatusProvider
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.AttendanceStatus
import com.dynamictransport.dynamicone.domain.model.LocalPunch
import com.dynamictransport.dynamicone.domain.model.LocalPunchState
import com.dynamictransport.dynamicone.domain.model.PunchFailure
import com.dynamictransport.dynamicone.domain.model.PunchResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.repository.AttendanceRepository
import com.dynamictransport.dynamicone.domain.repository.AuthRepository
import com.dynamictransport.dynamicone.domain.repository.NetworkMonitor
import com.dynamictransport.dynamicone.domain.rules.AttendanceRules
import com.dynamictransport.dynamicone.domain.rules.AttendanceSituation
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** One punch of today on the screen: recorded by the server or still on the phone. */
data class TodayPunch(
    val key: String,
    val direction: AttendanceDirection,
    val time: Instant,
    val syncState: SyncState,
    val pointName: String?,
)

/** Feedback for the latest tag scan, shown until the next scan. */
sealed interface PunchOutcome {
    data class Recorded(val direction: AttendanceDirection, val time: Instant) : PunchOutcome

    /** [hold]: the server answered but did not take the punch yet (403); it stays on the phone. */
    data class Saved(val direction: AttendanceDirection, val hold: PunchFailure? = null) : PunchOutcome

    data class Failed(val direction: AttendanceDirection, val failure: PunchFailure) : PunchOutcome

    data object UnreadableTag : PunchOutcome

    data object NotLoggedIn : PunchOutcome
}

data class AttendanceUiState(
    val loading: Boolean = true,
    val userName: String? = null,
    val situation: AttendanceSituation? = null,
    val nfc: NfcAvailability = NfcAvailability.AVAILABLE,
    /** Waiting for a tag for this direction. */
    val scanning: AttendanceDirection? = null,
    val submitting: Boolean = false,
    val outcome: PunchOutcome? = null,
    /** Newest first. */
    val today: List<TodayPunch> = emptyList(),
    /** Rejected punches (any day) with their reason. */
    val failed: List<LocalPunch> = emptyList(),
    val waitingCount: Int = 0,
    /** Why the waiting punches were not taken, when the server answered at all (403). */
    val waitingHold: PunchFailure? = null,
    val isOnline: Boolean = true,
    /** The server does not serve office attendance yet. */
    val unavailable: Boolean = false,
    /** The account may not record office attendance (any more): `403 attendance_access_required`. */
    val accessRemoved: Boolean = false,
    @param:StringRes val refreshError: Int? = null,
    val refreshing: Boolean = false,
)

private data class Controls(
    val nfc: NfcAvailability,
    val scanning: AttendanceDirection?,
    val outcome: PunchOutcome?,
    val submitting: Boolean,
    val refreshResult: RefreshResult?,
    val refreshing: Boolean,
    /** Moves with the screen's clock so the work day changes while the screen stays open. */
    val now: Instant,
)

@HiltViewModel
class AttendanceViewModel @Inject constructor(
    private val attendance: AttendanceRepository,
    authRepository: AuthRepository,
    networkMonitor: NetworkMonitor,
    private val nfcStatus: NfcStatusProvider,
    private val clock: Clock,
) : ViewModel() {

    private val nfc = MutableStateFlow(nfcStatus.availability())
    private val scanning = MutableStateFlow<AttendanceDirection?>(null)
    private val outcome = MutableStateFlow<PunchOutcome?>(null)
    private val refreshResult = MutableStateFlow<RefreshResult?>(null)
    private val refreshing = MutableStateFlow(false)
    private val now = MutableStateFlow(clock.instant())
    private val singleFlight = SingleFlight()

    private val controls = combine(
        combine(nfc, scanning, outcome, ::Triple),
        combine(singleFlight.busy, refreshResult, refreshing, ::Triple),
        now,
    ) { (nfcState, scanDirection, lastOutcome), (busy, result, isRefreshing), currentTime ->
        Controls(nfcState, scanDirection, lastOutcome, busy, result, isRefreshing, currentTime)
    }

    val uiState: StateFlow<AttendanceUiState> = combine(
        attendance.observeStatus(),
        attendance.observeLocalPunches(),
        authRepository.observeProfile(),
        networkMonitor.isOnline,
        controls,
    ) { status, local, profile, online, control ->
        val situation = AttendanceRules.situation(status, local, control.now, ZoneId.systemDefault())
        val result = control.refreshResult
        val waiting = local.filter { it.state == LocalPunchState.PENDING || it.state == LocalPunchState.SENDING }
        AttendanceUiState(
            // Until the first read finishes, only what the phone knows can be shown.
            loading = status == null && result == null && local.isEmpty(),
            userName = profile?.displayName?.takeIf { it.isNotBlank() },
            situation = situation,
            nfc = control.nfc,
            scanning = control.scanning,
            submitting = control.submitting,
            outcome = control.outcome,
            today = todayPunches(status, local, situation),
            failed = local.filter { it.state == LocalPunchState.FAILED },
            waitingCount = waiting.size,
            waitingHold = waiting.firstNotNullOfOrNull { it.failure },
            isOnline = online,
            unavailable = result == RefreshResult.NOT_SUPPORTED,
            accessRemoved = result == RefreshResult.ACCESS_REQUIRED,
            // Offline is shown by the connectivity badge; the phone still records scans.
            refreshError = result
                ?.takeIf { it != RefreshResult.OFFLINE && it != RefreshResult.NOT_SUPPORTED && it != RefreshResult.ACCESS_REQUIRED }
                ?.messageRes(),
            refreshing = control.refreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AttendanceUiState())

    /** The screen came to the front: NFC may have been switched on in the settings, the state may have changed. */
    fun onResume() {
        nfc.value = nfcStatus.availability()
        now.value = clock.instant()
        refresh()
    }

    /**
     * The screen's clock moved on. A new work day of the office time zone means yesterday's answer is stale: the
     * server decides the action again (docs/15 §5.2, an open check-in of yesterday offers `CheckIn` today).
     */
    fun onTimeChanged() {
        val previous = now.value
        val current = clock.instant()
        if (current == previous) return
        now.value = current
        val zone = uiState.value.situation?.zone ?: ZoneId.systemDefault()
        if (previous.atZone(zone).toLocalDate() != current.atZone(zone).toLocalDate()) refresh()
    }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                refreshResult.value = attendance.refreshStatus()
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Starts waiting for the tag of the offered action. */
    fun onPrimaryAction() {
        val state = uiState.value
        val situation = state.situation ?: return
        if (state.submitting || state.scanning != null || state.unavailable || state.accessRemoved) return
        nfc.value = nfcStatus.availability()
        if (nfc.value != NfcAvailability.AVAILABLE) return
        outcome.value = null
        scanning.value = situation.nextDirection
    }

    fun onCancelScan() {
        scanning.value = null
    }

    /** Only the first tag of a scan counts: reading stops at once, so holding the phone longer adds nothing. */
    fun onTagRead(tagId: ByteArray) {
        val direction = scanning.value ?: return
        scanning.value = null
        val uid = AttendanceRules.tagUid(tagId)
        if (uid == null) {
            outcome.value = PunchOutcome.UnreadableTag
            return
        }
        singleFlight.launch(viewModelScope) {
            outcome.value = attendance.recordPunch(direction, uid).toOutcome(direction)
        }
    }

    fun onResend(punch: LocalPunch) {
        singleFlight.launch(viewModelScope) {
            outcome.value = attendance.resend(punch.commandId).toOutcome(punch.direction)
        }
    }

    fun onDismiss(punch: LocalPunch) {
        viewModelScope.launch { attendance.dismiss(punch.commandId) }
        if (outcome.value is PunchOutcome.Failed) outcome.value = null
    }

    fun onOutcomeDismissed() {
        outcome.value = null
    }

    private fun PunchResult.toOutcome(direction: AttendanceDirection): PunchOutcome = when (this) {
        is PunchResult.Recorded -> PunchOutcome.Recorded(direction, punch.occurredAt)
        is PunchResult.Saved -> PunchOutcome.Saved(direction, hold)
        is PunchResult.Failed -> PunchOutcome.Failed(direction, failure)
        PunchResult.UnreadableTag -> PunchOutcome.UnreadableTag
        PunchResult.NotLoggedIn -> PunchOutcome.NotLoggedIn
    }

    private fun todayPunches(status: AttendanceStatus?, local: List<LocalPunch>, situation: AttendanceSituation): List<TodayPunch> {
        val zone = situation.zone
        val fromPhone = local
            .filter { it.state != LocalPunchState.FAILED && it.time.atZone(zone).toLocalDate() == situation.today }
            .map { punch ->
                TodayPunch(
                    key = punch.commandId,
                    direction = punch.direction,
                    time = punch.time,
                    syncState = if (punch.state == LocalPunchState.SYNCED) SyncState.SYNCED else SyncState.PENDING,
                    pointName = punch.pointName,
                )
            }
        // The newest server punch may come from the office terminal or another phone.
        val fromServer = status?.lastPunch
            ?.takeIf { last -> last.occurredAt.atZone(zone).toLocalDate() == situation.today }
            ?.takeIf { last -> local.none { it.commandId == last.commandId } }
            ?.let { TodayPunch(it.id, it.direction, it.occurredAt, SyncState.SYNCED, it.pointName) }
        return (fromPhone + listOfNotNull(fromServer)).sortedByDescending { it.time }
    }
}

/**
 * A punch the server answered about but did not take yet: it waits on the phone and is sent again with the same
 * command id (docs/15 §5.3). Only `403` produces this today.
 */
@StringRes
fun PunchFailure.holdMessageRes(): Int = when (this) {
    PunchFailure.ACCESS_REQUIRED -> R.string.attendance_hold_access
    // A final answer never holds a punch; it is shown as not recorded instead.
    else -> R.string.attendance_failure_rejected
}

@StringRes
fun PunchFailure.messageRes(direction: AttendanceDirection): Int = when (this) {
    PunchFailure.TAG_UNKNOWN -> R.string.attendance_failure_tag_unknown
    PunchFailure.WRONG_TAG_DIRECTION ->
        if (direction == AttendanceDirection.IN) R.string.attendance_failure_wrong_tag_in else R.string.attendance_failure_wrong_tag_out
    PunchFailure.TIME_REJECTED -> R.string.attendance_failure_time
    PunchFailure.ACCESS_REQUIRED -> R.string.attendance_failure_access
    PunchFailure.CONFLICT, PunchFailure.REJECTED -> R.string.attendance_failure_rejected
}
