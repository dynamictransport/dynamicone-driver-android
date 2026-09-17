package com.dynamictransport.dynamicone.feature.incident

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ActionTone
import com.dynamictransport.dynamicone.core.design.component.ChoiceRow
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.IncidentCategory
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import com.dynamictransport.dynamicone.domain.rules.DriverInputRules
import com.dynamictransport.dynamicone.domain.statemachine.NextActionResolver
import com.dynamictransport.dynamicone.domain.usecase.ReportIncidentUseCase
import com.dynamictransport.dynamicone.feature.common.ActionBar
import com.dynamictransport.dynamicone.feature.common.NoteField
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.icon
import com.dynamictransport.dynamicone.feature.common.labelRes
import com.dynamictransport.dynamicone.feature.common.messageRes
import com.dynamictransport.dynamicone.feature.tripdetail.TRIP_ID_ARG
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReportIncidentUiState(
    val tripId: String? = null,
    val tripNo: String? = null,
    val category: IncidentCategory? = null,
    val note: String = "",
    val submitting: Boolean = false,
    @param:StringRes val error: Int? = null,
    val completed: Boolean = false,
    /** Real API: the report is confirmed by the server. Demo: stored in the offline queue. */
    val sentToServer: Boolean = false,
) {
    val canSubmit: Boolean get() = DriverInputRules.validateIncident(category, note) == null
}

@HiltViewModel
class ReportIncidentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tripRepository: TripRepository,
    private val reportIncident: ReportIncidentUseCase,
    appInfo: AppInfo,
) : ViewModel() {

    private val tripIdArgument: String? = savedStateHandle[TRIP_ID_ARG]
    private val form = MutableStateFlow(ReportIncidentUiState(tripId = tripIdArgument, sentToServer = !appInfo.isDemoMode))
    private val singleFlight = SingleFlight()

    val uiState: StateFlow<ReportIncidentUiState> =
        combine(form, singleFlight.busy) { state, busy -> state.copy(submitting = busy) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), form.value)

    init {
        viewModelScope.launch {
            val trips = tripRepository.currentTrips()
            val trip = tripIdArgument?.let { id -> trips.firstOrNull { it.id == id } }
                ?: NextActionResolver.currentWorkTrip(trips)
            form.update { it.copy(tripId = trip?.id ?: tripIdArgument, tripNo = trip?.tripNo) }
        }
    }

    fun onCategorySelected(category: IncidentCategory) = form.update { it.copy(category = category, error = null) }

    fun onNoteChanged(note: String) =
        form.update { it.copy(note = note.take(DriverInputRules.INCIDENT_NOTE_MAX_LENGTH), error = null) }

    fun onSubmit() {
        val current = form.value
        singleFlight.launch(viewModelScope) {
            when (val result = reportIncident(current.tripId, current.category, current.note)) {
                ActionResult.Queued -> form.update { it.copy(completed = true) }
                is ActionResult.Rejected -> form.update { it.copy(error = result.reason.messageRes()) }
            }
        }
    }
}

@Composable
fun ReportIncidentRoute(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: ReportIncidentViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReportIncidentScreen(
        state = state,
        onBack = onBack,
        onCategorySelected = viewModel::onCategorySelected,
        onNoteChanged = viewModel::onNoteChanged,
        onSubmit = viewModel::onSubmit,
        onDone = onDone,
    )
}

@Composable
fun ReportIncidentScreen(
    state: ReportIncidentUiState,
    onBack: () -> Unit,
    onCategorySelected: (IncidentCategory) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.incident_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
        bottomBar = {
            ActionBar {
                if (state.completed) {
                    PrimaryActionButton(
                        text = stringResource(R.string.incident_back_home),
                        onClick = onDone,
                        icon = Icons.Filled.Home,
                        tone = ActionTone.Neutral,
                        modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                    )
                } else {
                    state.error?.let {
                        Text(
                            text = stringResource(it),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    PrimaryActionButton(
                        text = stringResource(R.string.incident_submit),
                        onClick = onSubmit,
                        icon = Icons.AutoMirrored.Filled.Send,
                        tone = ActionTone.Neutral,
                        enabled = state.canSubmit,
                        loading = state.submitting,
                        modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        ) {
            if (state.completed) {
                EmptyState(
                    icon = Icons.Filled.CheckCircle,
                    title = stringResource(if (state.sentToServer) R.string.incident_done_title_sent else R.string.incident_done_title),
                    body = stringResource(if (state.sentToServer) R.string.incident_done_body_sent else R.string.incident_done_body),
                    modifier = Modifier.testTag("incident_done"),
                )
            } else {
                state.tripNo?.let {
                    StatusBanner(stringResource(R.string.incident_trip, it), Icons.Filled.LocalShipping, StatusTone.Neutral)
                }
                Text(stringResource(R.string.incident_question), style = MaterialTheme.typography.titleLarge)
                IncidentCategory.entries.forEach { category ->
                    ChoiceRow(
                        label = stringResource(category.labelRes()),
                        selected = state.category == category,
                        onSelect = { onCategorySelected(category) },
                        icon = category.icon(),
                        modifier = Modifier.testTag("incident_category_${category.name}"),
                    )
                }
                NoteField(
                    value = state.note,
                    onValueChange = onNoteChanged,
                    label = stringResource(R.string.incident_note),
                    maxLength = DriverInputRules.INCIDENT_NOTE_MAX_LENGTH,
                )
            }
        }
    }
}
