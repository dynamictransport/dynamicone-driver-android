package com.dynamictransport.dynamicone.feature.shift

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RvHookup
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ActionTone
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.HoldToConfirmButton
import com.dynamictransport.dynamicone.core.design.component.InfoRow
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

data class ShiftUiState(
    val loading: Boolean = true,
    val shift: Shift = Shift.NotStarted,
    val canEndShift: Boolean = true,
    val completedTrips: Int = 0,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val submitting: Boolean = false,
    @param:StringRes val message: Int? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShiftViewModel @Inject constructor(
    private val shiftRepository: ShiftRepository,
    tripRepository: TripRepository,
    syncRepository: SyncRepository,
    private val endShift: EndShiftUseCase,
) : ViewModel() {

    private val singleFlight = SingleFlight()
    private val message = MutableStateFlow<Int?>(null)
    private val shift = shiftRepository.observeShift()

    private val completedTrips = shift.flatMapLatest { current ->
        current.startedAt?.let { tripRepository.observeFinishedTripCount(it) } ?: flowOf(0)
    }

    private val content = combine(
        shift,
        tripRepository.observeTrips(),
        syncRepository.observeOverview(),
        completedTrips,
    ) { currentShift, trips, overview, completed ->
        ShiftUiState(
            loading = false,
            shift = currentShift,
            canEndShift = ShiftStateMachine.canEndShift(trips),
            completedTrips = completed,
            pendingCount = overview.pendingCount,
            failedCount = overview.failedCount,
        )
    }

    val uiState: StateFlow<ShiftUiState> =
        combine(content, singleFlight.busy, message) { state, busy, currentMessage ->
            state.copy(submitting = busy, message = currentMessage)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShiftUiState())

    fun onStartShift() = perform { shiftRepository.startShift() }

    fun onEndShift() = perform { endShift() }

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
fun ShiftRoute(onBack: () -> Unit, viewModel: ShiftViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ShiftScreen(
        state = state,
        onBack = onBack,
        onStartShift = viewModel::onStartShift,
        onEndShift = viewModel::onEndShift,
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun ShiftScreen(
    state: ShiftUiState,
    onBack: () -> Unit,
    onStartShift: () -> Unit,
    onEndShift: () -> Unit,
    onMessageShown: () -> Unit,
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
                }
                if (shift.isTracked) {
                    InfoRow(
                        Icons.Filled.LocalShipping,
                        stringResource(R.string.shift_vehicle),
                        shift.vehiclePlate ?: stringResource(R.string.shift_not_assigned),
                    )
                    InfoRow(
                        Icons.Filled.RvHookup,
                        stringResource(R.string.shift_trailer),
                        shift.trailerPlate ?: stringResource(R.string.shift_not_assigned),
                    )
                }
            }
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

private fun ShiftStatus.toStatusUi(): StatusUi = when (this) {
    ShiftStatus.ACTIVE -> StatusUi(R.string.shift_status_active, Icons.Filled.PlayCircle, StatusTone.Positive)
    ShiftStatus.NOT_STARTED -> StatusUi(R.string.shift_status_not_started, Icons.Filled.PauseCircle, StatusTone.Neutral)
    ShiftStatus.FINISHED -> StatusUi(R.string.shift_status_finished, Icons.Filled.CheckCircle, StatusTone.Neutral)
    ShiftStatus.UNAVAILABLE -> StatusUi(R.string.shift_status_unavailable, Icons.Filled.CloudOff, StatusTone.Neutral)
}
