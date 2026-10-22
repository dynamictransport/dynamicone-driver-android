package com.dynamictransport.dynamicone.feature.trailer

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ActionTone
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.TrailerOption
import com.dynamictransport.dynamicone.domain.repository.NetworkMonitor
import com.dynamictransport.dynamicone.domain.repository.TripExecutionRepository
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import com.dynamictransport.dynamicone.feature.common.ActionBar
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.currentLocale
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
import java.time.Instant
import javax.inject.Inject

/**
 * Trailer confirmation after accepting a trip (docs/20_DRIVER_TRAILER_CONFIRMATION.md).
 * The driver picks the trailer that is really under the truck from the office's active list;
 * the planned one is only marked, never confirmed for them.
 */

/** From this many options on, the list gets a search field; below it, scrolling is faster than typing. */
private const val SEARCH_FROM = 8

data class ConfirmTrailerUiState(
    val loading: Boolean = true,
    val tripNo: String? = null,
    val options: List<TrailerOption> = emptyList(),
    val plannedId: String? = null,
    val confirmedId: String? = null,
    val confirmedAt: Instant? = null,
    val selectedId: String? = null,
    val query: String = "",
    /** The server accepts a (new) choice: an accepted trip that has not started. */
    val canConfirm: Boolean = false,
    val submitting: Boolean = false,
    val isOnline: Boolean = true,
    @param:StringRes val message: Int? = null,
    /** The list could not be read and nothing is known about it. */
    @param:StringRes val loadError: Int? = null,
    val completed: Boolean = false,
) {
    val hasSearch: Boolean get() = options.size >= SEARCH_FROM

    /** The trailers to show: the planned one first, filtered by the search text. */
    val visibleOptions: List<TrailerOption>
        get() {
            val text = query.trim()
            if (text.isEmpty()) return options
            return options.filter { it.searchText.contains(text, ignoreCase = true) }
        }

    val listEmpty: Boolean get() = options.isEmpty() && loadError == null

    val confirmedOption: TrailerOption? get() = options.firstOrNull { it.id == confirmedId }

    /** Confirming the choice that is already recorded would change nothing. */
    val canSubmit: Boolean get() = canConfirm && selectedId != null && selectedId != confirmedId && !submitting
}

@HiltViewModel
class ConfirmTrailerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    tripRepository: TripRepository,
    networkMonitor: NetworkMonitor,
    private val executionRepository: TripExecutionRepository,
) : ViewModel() {

    private val tripId: String = checkNotNull(savedStateHandle[TRIP_ID_ARG])
    private val singleFlight = SingleFlight()
    private val form = MutableStateFlow(ConfirmTrailerUiState())
    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)

    val uiState: StateFlow<ConfirmTrailerUiState> = combine(
        form,
        executionRepository.observeTrailers(tripId),
        tripRepository.observeTrip(tripId),
        networkMonitor.isOnline,
        combine(singleFlight.busy, lastRefresh, ::Pair),
    ) { state, trailers, trip, online, (busy, refresh) ->
        state.copy(
            loading = trailers == null && refresh == null,
            tripNo = trip?.tripNo,
            options = trailers?.ordered.orEmpty(),
            plannedId = trailers?.plannedTrailerId,
            confirmedId = trailers?.confirmedTrailerId,
            confirmedAt = trailers?.confirmedAt,
            // The recorded choice is shown as selected; a different one is the driver's own decision.
            selectedId = state.selectedId ?: trailers?.confirmed?.id,
            canConfirm = trailers?.canConfirm == true,
            submitting = busy,
            isOnline = online,
            loadError = refresh?.takeIf { trailers == null }?.messageRes(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConfirmTrailerUiState())

    init {
        refresh()
    }

    fun onQueryChanged(query: String) = form.update { it.copy(query = query.take(QUERY_MAX_LENGTH)) }

    fun onSelect(trailerId: String) = form.update { it.copy(selectedId = trailerId) }

    fun onConfirm() {
        val selected = form.value.selectedId ?: return
        singleFlight.launch(viewModelScope) {
            when (val result = executionRepository.confirmTrailer(tripId, selected)) {
                ActionResult.Queued -> form.update { it.copy(completed = true, message = R.string.trailer_confirmed_message) }
                is ActionResult.Rejected -> form.update { it.copy(message = result.reason.messageRes()) }
            }
        }
    }

    fun onRetry() = refresh()

    fun onMessageShown() = form.update { it.copy(message = null) }

    private fun refresh() {
        viewModelScope.launch {
            lastRefresh.value = null
            lastRefresh.value = executionRepository.refreshTrailers(tripId)
        }
    }

    private companion object {
        const val QUERY_MAX_LENGTH = 20
    }
}

@Composable
fun ConfirmTrailerRoute(
    onBack: () -> Unit,
    onConfirmed: () -> Unit,
    viewModel: ConfirmTrailerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The trip screen shows the confirmed trailer and the next step.
    LaunchedEffect(state.completed) {
        if (state.completed) onConfirmed()
    }
    ConfirmTrailerScreen(
        state = state,
        onBack = onBack,
        onQueryChanged = viewModel::onQueryChanged,
        onSelect = viewModel::onSelect,
        onConfirm = viewModel::onConfirm,
        onRetry = viewModel::onRetry,
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun ConfirmTrailerScreen(
    state: ConfirmTrailerUiState,
    onBack: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)

    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.trailer_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
        bottomBar = {
            if (!state.listEmpty && state.loadError == null) {
                ActionBar {
                    if (!state.canConfirm) {
                        Text(
                            text = stringResource(R.string.trailer_locked),
                            style = MaterialTheme.typography.bodyMedium,
                            color = DriverTheme.colors.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("trailer_locked"),
                        )
                    } else {
                        PrimaryActionButton(
                            text = stringResource(R.string.trailer_confirm),
                            onClick = onConfirm,
                            icon = Icons.Filled.CheckCircle,
                            tone = ActionTone.Positive,
                            enabled = state.canSubmit,
                            loading = state.submitting,
                            modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val loadError = state.loadError
        when {
            loadError != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .readableWidth()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
            ) {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = stringResource(loadError),
                    body = null,
                    modifier = Modifier.testTag("trailer_load_error"),
                ) {
                    Spacer(Modifier.size(12.dp))
                    SecondaryActionButton(
                        text = stringResource(R.string.common_retry),
                        onClick = onRetry,
                        icon = Icons.Filled.Refresh,
                    )
                }
            }

            state.loading -> LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .testTag("trailer_loading"),
            )

            state.listEmpty -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .readableWidth()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
            ) {
                EmptyState(
                    icon = Icons.Filled.LocalShipping,
                    title = stringResource(R.string.trailer_none_title),
                    body = stringResource(R.string.trailer_none_body),
                    modifier = Modifier.testTag("trailer_none"),
                )
            }

            else -> TrailerList(state, padding.calculateTopPadding(), padding.calculateBottomPadding(), onQueryChanged, onSelect)
        }
    }
}

@Composable
private fun TrailerList(
    state: ConfirmTrailerUiState,
    topPadding: Dp,
    bottomPadding: Dp,
    onQueryChanged: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val visible = state.visibleOptions
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(
            start = Dimens.ScreenPadding,
            end = Dimens.ScreenPadding,
            top = topPadding + 12.dp,
            bottom = bottomPadding + 12.dp,
        ),
        modifier = Modifier
            .fillMaxSize()
            .readableWidth()
            .testTag("trailer_list"),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.tripNo?.let {
                    StatusBanner(stringResource(R.string.incident_trip, it), Icons.Filled.LocalShipping, StatusTone.Neutral)
                }
                val confirmed = state.confirmedOption
                if (confirmed != null) {
                    val locale = currentLocale()
                    val time = state.confirmedAt?.let { DriverFormats.dayTime(it, locale) }
                    StatusBanner(
                        message = if (time == null) {
                            stringResource(R.string.trailer_confirmed_banner, confirmed.registrationNumber)
                        } else {
                            stringResource(R.string.trailer_confirmed_banner_at, confirmed.registrationNumber, time)
                        },
                        icon = Icons.Filled.CheckCircle,
                        tone = StatusTone.Positive,
                        modifier = Modifier.testTag("trailer_confirmed"),
                    )
                }
                Text(
                    text = stringResource(if (confirmed == null) R.string.trailer_question else R.string.trailer_change_hint),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (state.hasSearch) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChanged,
                        singleLine = true,
                        label = { Text(stringResource(R.string.trailer_search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChanged("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.trailer_search_clear))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Search,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("trailer_search"),
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            item(key = "no_match") {
                Text(
                    text = stringResource(R.string.trailer_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DriverTheme.colors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("trailer_no_match"),
                )
            }
        }

        items(visible, key = { it.id }) { option ->
            TrailerRow(
                option = option,
                selected = option.id == state.selectedId,
                planned = option.id == state.plannedId,
                enabled = state.canConfirm,
                onSelect = { onSelect(option.id) },
            )
        }
    }
}

/** One trailer: registration large enough to compare with the plate on the trailer, company code below it. */
@Composable
private fun TrailerRow(
    option: TrailerOption,
    selected: Boolean,
    planned: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val border = if (selected) DriverTheme.colors.brand else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) DriverTheme.colors.infoContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect, role = Role.RadioButton)
            .testTag("trailer_option_${option.id}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = option.registrationNumber,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                val details = listOfNotNull(
                    option.companyCode,
                    stringResource(R.string.trailer_planned).takeIf { planned },
                )
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (planned) DriverTheme.colors.brand else DriverTheme.colors.muted,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}
