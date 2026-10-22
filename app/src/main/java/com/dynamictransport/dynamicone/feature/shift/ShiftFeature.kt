package com.dynamictransport.dynamicone.feature.shift

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RvHookup
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.alarm.RestAlarmController
import com.dynamictransport.dynamicone.core.design.component.ActionTone
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.HoldToConfirmButton
import com.dynamictransport.dynamicone.core.design.component.InfoRow
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.LocalShiftCommand
import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.ShiftCommandResult
import com.dynamictransport.dynamicone.domain.model.ShiftCommandState
import com.dynamictransport.dynamicone.domain.model.ShiftCommandType
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.domain.model.VehicleIdentification
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.DailyShiftRepository
import com.dynamictransport.dynamicone.domain.repository.DriverTimesRepository
import com.dynamictransport.dynamicone.domain.repository.ShiftRepository
import com.dynamictransport.dynamicone.domain.repository.SyncRepository
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import com.dynamictransport.dynamicone.domain.statemachine.ShiftStateMachine
import com.dynamictransport.dynamicone.domain.usecase.EndShiftUseCase
import com.dynamictransport.dynamicone.feature.common.ActionBar
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.StatusUi
import com.dynamictransport.dynamicone.feature.common.StatusUiBadge
import com.dynamictransport.dynamicone.feature.common.SyncStateBadge
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.formatDuration
import com.dynamictransport.dynamicone.feature.common.messageRes
import com.dynamictransport.dynamicone.feature.common.rememberCurrentInstant
import com.dynamictransport.dynamicone.feature.shiftstart.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

data class ShiftUiState(
    val loading: Boolean = true,
    val shift: Shift = Shift.NotStarted,
    val canEndShift: Boolean = true,
    /** Daily shift ended while a trip runs (it continues in the next shift). */
    val tripInProgress: Boolean = false,
    val completedTrips: Int = 0,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val submitting: Boolean = false,
    @param:StringRes val message: Int? = null,
    /** Daily shift: starts and ends on the phone that are waiting or were rejected. */
    val commands: List<LocalShiftCommand> = emptyList(),
    val driverDay: DriverDayState? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShiftViewModel @Inject constructor(
    private val shiftRepository: ShiftRepository,
    tripRepository: TripRepository,
    syncRepository: SyncRepository,
    private val endShift: EndShiftUseCase,
    private val dailyShift: DailyShiftRepository,
    driverTimes: DriverTimesRepository,
    private val restAlarm: RestAlarmController,
    appInfo: AppInfo,
) : ViewModel() {

    private val singleFlight = SingleFlight()
    private val message = MutableStateFlow<Int?>(null)
    private val shift = shiftRepository.observeShift()

    private val completedTrips = shift.flatMapLatest { current ->
        current.startedAt?.let { tripRepository.observeFinishedTripCount(it) } ?: flowOf(0)
    }

    private val driverDay = if (appInfo.isDriverShiftEnabled) {
        combine(driverTimes.observeDriverTimes(), restAlarm.status) { times, alarm -> DriverDayState(times, alarm) }
    } else {
        flowOf(null)
    }

    private val content = combine(
        shift,
        tripRepository.observeTrips(),
        syncRepository.observeOverview(),
        completedTrips,
    ) { currentShift, trips, overview, completed ->
        val noTripRunning = ShiftStateMachine.canEndShift(trips)
        ShiftUiState(
            loading = false,
            shift = currentShift,
            // A daily shift ends for the daily rest, also during a long trip (docs/16 §9).
            canEndShift = currentShift.requiresVehicleCheck || noTripRunning,
            tripInProgress = !noTripRunning,
            completedTrips = completed,
            pendingCount = overview.pendingCount,
            failedCount = overview.failedCount,
        )
    }

    private val daily = combine(dailyShift.observeCommands(), driverDay, ::Pair)

    val uiState: StateFlow<ShiftUiState> =
        combine(content, daily, singleFlight.busy, message) { state, (commands, day), busy, currentMessage ->
            state.copy(
                commands = commands.filter { it.state != ShiftCommandState.SYNCED || it.photosWaiting + it.photosFailed > 0 },
                driverDay = day,
                submitting = busy,
                message = currentMessage,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShiftUiState())

    fun onStartShift() = perform { shiftRepository.startShift() }

    fun onEndShift() = perform { endShift() }

    fun onResend(commandId: String) {
        singleFlight.launch(viewModelScope) {
            when (val result = dailyShift.resend(commandId)) {
                is ShiftCommandResult.Failed -> message.value = result.failure.messageRes()
                is ShiftCommandResult.NotAllowed -> message.value = result.reason.messageRes()
                else -> Unit
            }
        }
    }

    fun onDiscard(commandId: String) {
        viewModelScope.launch { dailyShift.discard(commandId) }
    }

    fun onDismissRestAlarm(rest: RestWindow) {
        viewModelScope.launch { restAlarm.dismiss(rest) }
    }

    fun onRestoreRestAlarm() {
        viewModelScope.launch { restAlarm.restore() }
    }

    fun onResume() = restAlarm.refreshPermissions()

    fun onMessageShown() {
        message.value = null
    }

    private fun perform(action: suspend () -> ActionResult) {
        singleFlight.launch(viewModelScope) {
            (action() as? ActionResult.Rejected)?.let { message.value = it.reason.messageRes() }
        }
    }
}

@Composable
fun ShiftRoute(
    onBack: () -> Unit,
    onStartDailyShift: () -> Unit = {},
    onOpenRestAlarmSettings: () -> Unit = {},
    viewModel: ShiftViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    ShiftScreen(
        state = state,
        onBack = onBack,
        // A daily shift starts at the vehicle: tag and check (docs/16).
        onStartShift = { if (state.shift.requiresVehicleCheck) onStartDailyShift() else viewModel.onStartShift() },
        onEndShift = viewModel::onEndShift,
        onMessageShown = viewModel::onMessageShown,
        onResend = viewModel::onResend,
        onDiscard = viewModel::onDiscard,
        onDismissRestAlarm = viewModel::onDismissRestAlarm,
        onRestoreRestAlarm = viewModel::onRestoreRestAlarm,
        onOpenRestAlarmSettings = onOpenRestAlarmSettings,
    )
}

@Composable
fun ShiftScreen(
    state: ShiftUiState,
    onBack: () -> Unit,
    onStartShift: () -> Unit,
    onEndShift: () -> Unit,
    onMessageShown: () -> Unit,
    onResend: (String) -> Unit = {},
    onDiscard: (String) -> Unit = {},
    onDismissRestAlarm: (RestWindow) -> Unit = {},
    onRestoreRestAlarm: () -> Unit = {},
    onOpenRestAlarmSettings: () -> Unit = {},
    now: Instant = rememberCurrentInstant(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)
    val locale = currentLocale()
    val shift = state.shift

    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.shift_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
        bottomBar = {
            if (!state.loading) {
                ActionBar {
                    when {
                        // No shift endpoints on the backend: a disabled button, never a fake success.
                        !shift.isTracked -> {
                            Text(
                                text = stringResource(R.string.feature_not_connected),
                                style = MaterialTheme.typography.bodyMedium,
                                color = DriverTheme.colors.muted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("shift_unavailable"),
                            )
                            PrimaryActionButton(
                                text = stringResource(R.string.action_start_shift),
                                onClick = {},
                                icon = Icons.Filled.PlayCircle,
                                enabled = false,
                                modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                            )
                        }

                        shift.isActive -> {
                            if (!state.canEndShift) {
                                Text(
                                    text = stringResource(R.string.shift_end_blocked),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = DriverTheme.colors.muted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else if (shift.requiresVehicleCheck && state.tripInProgress) {
                                Text(
                                    text = stringResource(R.string.shift_end_during_trip),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = DriverTheme.colors.muted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("shift_end_during_trip"),
                                )
                            }
                            HoldToConfirmButton(
                                text = stringResource(R.string.action_end_shift),
                                hint = stringResource(R.string.common_hold_to_confirm),
                                onConfirmed = onEndShift,
                                icon = Icons.Filled.StopCircle,
                                tone = ActionTone.Neutral,
                                enabled = state.canEndShift,
                                loading = state.submitting,
                                modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                            )
                        }

                        else -> PrimaryActionButton(
                            text = stringResource(R.string.action_start_shift),
                            onClick = onStartShift,
                            icon = Icons.Filled.PlayCircle,
                            loading = state.submitting,
                            modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .readableWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        ) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusUiBadge(shift.status.toStatusUi())
                    SyncStateBadge(shift.syncState)
                }
                shift.startedAt?.let {
                    InfoRow(Icons.Filled.PlayCircle, stringResource(R.string.shift_started_at), DriverFormats.dayTime(it, locale))
                }
                shift.startedAt?.let { start ->
                    val end = if (shift.status == ShiftStatus.FINISHED) shift.endedAt ?: now else now
                    InfoRow(Icons.Filled.Timer, stringResource(R.string.shift_duration), formatDuration(Duration.between(start, end)))
                }
                if (shift.status == ShiftStatus.FINISHED) {
                    shift.endedAt?.let {
                        InfoRow(Icons.Filled.StopCircle, stringResource(R.string.shift_ended_at), DriverFormats.dayTime(it, locale))
                    }
                    if (shift.endedByTachograph) {
                        InfoRow(Icons.Filled.Bedtime, stringResource(R.string.shift_ended_by), stringResource(R.string.shift_ended_by_tachograph))
                    }
                }
                if (shift.isTracked) {
                    InfoRow(
                        Icons.Filled.LocalShipping,
                        stringResource(R.string.shift_vehicle),
                        shift.vehiclePlate ?: stringResource(R.string.shift_not_assigned),
                    )
                    if (!shift.requiresVehicleCheck || shift.trailerPlate != null) {
                        InfoRow(
                            Icons.Filled.RvHookup,
                            stringResource(R.string.shift_trailer),
                            shift.trailerPlate ?: stringResource(R.string.shift_not_assigned),
                        )
                    }
                }
                if (shift.identifiedBy == VehicleIdentification.MANUAL) {
                    InfoRow(Icons.Filled.Nfc, stringResource(R.string.shift_vehicle_identified), stringResource(R.string.shift_vehicle_manual))
                }
                shift.vehicleCheck?.let { check ->
                    InfoRow(
                        if (check.defectCount == 0) Icons.Filled.TaskAlt else Icons.Filled.Warning,
                        stringResource(R.string.shift_vehicle_check),
                        if (check.defectCount == 0) {
                            stringResource(R.string.check_summary_no_defects)
                        } else {
                            pluralStringResource(R.plurals.check_summary_defects, check.defectCount, check.defectCount)
                        },
                    )
                }
                if (shift.startedBeforeRestEnd) {
                    InfoRow(Icons.Filled.Bedtime, stringResource(R.string.shift_rest_note), stringResource(R.string.shift_started_before_rest_end))
                }
            }
            state.driverDay?.let { day ->
                DriverDayCard(
                    state = day,
                    shift = shift,
                    now = now,
                    onDismissAlarm = onDismissRestAlarm,
                    onRestoreAlarm = onRestoreRestAlarm,
                    onOpenAlarmSettings = onOpenRestAlarmSettings,
                )
            }
            state.commands.forEach { command -> CommandCard(command, state.submitting, onResend, onDiscard) }
            if (shift.status == ShiftStatus.ACTIVE || shift.status == ShiftStatus.FINISHED) {
                SectionCard {
                    Text(stringResource(R.string.shift_summary), style = MaterialTheme.typography.titleMedium)
                    InfoRow(Icons.Filled.TaskAlt, stringResource(R.string.shift_completed_trips), state.completedTrips.toString())
                    InfoRow(
                        Icons.Filled.CloudUpload,
                        stringResource(R.string.shift_pending_actions),
                        (state.pendingCount + state.failedCount).toString(),
                    )
                }
            }
            if (state.pendingCount + state.failedCount > 0) {
                StatusBanner(stringResource(R.string.shift_pending_warning), Icons.Filled.CloudUpload, StatusTone.Info)
            }
        }
    }
}

/** A start or end on the phone: waiting (with the server's reason when it answered) or rejected with actions. */
@Composable
private fun CommandCard(command: LocalShiftCommand, busy: Boolean, onResend: (String) -> Unit, onDiscard: (String) -> Unit) {
    val locale = currentLocale()
    val failed = command.state == ShiftCommandState.FAILED
    SectionCard(modifier = Modifier.testTag("shift_command_${command.commandId}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    failed -> Icons.Filled.SyncProblem
                    command.isUnsent -> Icons.Filled.CloudUpload
                    else -> Icons.Filled.Info
                },
                contentDescription = null,
                tint = if (failed) DriverTheme.colors.danger else DriverTheme.colors.muted,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (command.type == ShiftCommandType.START) R.string.shift_command_start else R.string.shift_command_end,
                        DriverFormats.dayTime(command.occurredAt, locale),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                val detail = when {
                    failed -> command.failure?.messageRes()?.let { stringResource(it) }
                    command.isUnsent -> command.failure?.let { stringResource(it.messageRes()) } ?: stringResource(R.string.shift_command_waiting)
                    command.photosFailed > 0 -> pluralStringResource(R.plurals.shift_photos_failed, command.photosFailed, command.photosFailed)
                    command.photosWaiting > 0 -> pluralStringResource(R.plurals.shift_photos_waiting, command.photosWaiting, command.photosWaiting)
                    else -> null
                }
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (failed) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onDiscard(command.commandId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.attendance_remove))
                }
                TextButton(onClick = { onResend(command.commandId) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.attendance_resend))
                }
            }
        }
    }
}

private fun ShiftStatus.toStatusUi(): StatusUi = when (this) {
    ShiftStatus.ACTIVE -> StatusUi(R.string.shift_status_active, Icons.Filled.PlayCircle, StatusTone.Positive)
    ShiftStatus.NOT_STARTED -> StatusUi(R.string.shift_status_not_started, Icons.Filled.PauseCircle, StatusTone.Neutral)
    ShiftStatus.FINISHED -> StatusUi(R.string.shift_status_finished, Icons.Filled.CheckCircle, StatusTone.Neutral)
    ShiftStatus.UNAVAILABLE -> StatusUi(R.string.shift_status_unavailable, Icons.Filled.CloudOff, StatusTone.Neutral)
}
