package com.dynamictransport.dynamicone.feature.trips

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.repository.SyncRepository
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.TripSummaryCard
import com.dynamictransport.dynamicone.feature.common.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TripsTab { ACTIVE, UPCOMING, COMPLETED }

data class TripsUiState(
    val loading: Boolean = true,
    val selectedTab: TripsTab = TripsTab.ACTIVE,
    val active: List<Trip> = emptyList(),
    val upcoming: List<Trip> = emptyList(),
    val completed: List<Trip> = emptyList(),
    val refreshing: Boolean = false,
    /** More pages exist on the server. */
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
    /** Shown instead of "no trips" when nothing could be loaded. */
    @param:StringRes val loadError: Int? = null,
    @param:StringRes val message: Int? = null,
) {
    fun tripsFor(tab: TripsTab): List<Trip> = when (tab) {
        TripsTab.ACTIVE -> active
        TripsTab.UPCOMING -> upcoming
        TripsTab.COMPLETED -> completed
    }
}

@HiltViewModel
class TripsViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {

    private val selectedTab = MutableStateFlow<TripsTab?>(null)
    private val singleFlight = SingleFlight()
    private val loadingMore = MutableStateFlow(false)
    private val message = MutableStateFlow<Int?>(null)

    private val paging = combine(tripRepository.observeCanLoadMore(), loadingMore, syncRepository.observeLastRefresh(), ::Triple)

    val uiState: StateFlow<TripsUiState> = combine(
        tripRepository.observeTrips(),
        selectedTab,
        singleFlight.busy,
        message,
        paging,
    ) { trips, tab, refreshing, currentMessage, (canLoadMore, isLoadingMore, lastRefresh) ->
        val grouped = trips.groupBy(::tabOf)
        val active = grouped[TripsTab.ACTIVE].orEmpty()
        TripsUiState(
            loading = lastRefresh == null && trips.isEmpty(),
            selectedTab = tab ?: if (active.isNotEmpty()) TripsTab.ACTIVE else TripsTab.UPCOMING,
            active = active,
            upcoming = grouped[TripsTab.UPCOMING].orEmpty(),
            completed = grouped[TripsTab.COMPLETED].orEmpty().sortedByDescending { it.plannedStartAt },
            refreshing = refreshing || lastRefresh == null,
            canLoadMore = canLoadMore,
            loadingMore = isLoadingMore,
            loadError = lastRefresh?.takeIf { trips.isEmpty() }?.messageRes(),
            message = currentMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripsUiState())

    fun onTabSelected(tab: TripsTab) {
        selectedTab.value = tab
    }

    fun onRefresh() {
        singleFlight.launch(viewModelScope) {
            message.value = syncRepository.refreshFromServer().messageRes()
        }
    }

    fun onLoadMore() {
        if (loadingMore.value) return
        loadingMore.value = true
        viewModelScope.launch {
            try {
                tripRepository.loadMoreTrips().messageRes()?.let { message.value = it }
            } finally {
                loadingMore.value = false
            }
        }
    }

    fun onMessageShown() {
        message.value = null
    }

    companion object {
        fun tabOf(trip: Trip): TripsTab = when {
            trip.state.isInProgress -> TripsTab.ACTIVE
            trip.state.isTerminal -> TripsTab.COMPLETED
            else -> TripsTab.UPCOMING
        }
    }
}

@Composable
fun TripsRoute(onOpenTrip: (String) -> Unit, viewModel: TripsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TripsScreen(
        state = state,
        onOpenTrip = onOpenTrip,
        onTabSelected = viewModel::onTabSelected,
        onRefresh = viewModel::onRefresh,
        onMessageShown = viewModel::onMessageShown,
        onLoadMore = viewModel::onLoadMore,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    state: TripsUiState,
    onOpenTrip: (String) -> Unit,
    onTabSelected: (TripsTab) -> Unit,
    onRefresh: () -> Unit,
    onMessageShown: () -> Unit,
    onLoadMore: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)

    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.nav_trips),
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.refreshing, modifier = Modifier.size(56.dp)) {
                        if (state.refreshing) {
                            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .readableWidth(),
        ) {
            PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
                TripsTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == state.selectedTab,
                        onClick = { onTabSelected(tab) },
                        modifier = Modifier
                            .heightIn(min = 56.dp)
                            .testTag("trips_tab_${tab.name}"),
                        text = {
                            Text(
                                text = stringResource(tab.labelRes(), state.tripsFor(tab).size),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                            )
                        },
                    )
                }
            }
            val trips = state.tripsFor(state.selectedTab)
            LazyColumn(
                contentPadding = PaddingValues(Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.loading) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                } else if (trips.isEmpty()) {
                    item {
                        val error = state.loadError
                        if (error != null) {
                            EmptyState(
                                icon = Icons.Filled.CloudOff,
                                title = stringResource(error),
                                body = null,
                                modifier = Modifier.testTag("trips_load_error"),
                            ) {
                                Spacer(Modifier.height(12.dp))
                                SecondaryActionButton(
                                    text = stringResource(R.string.common_retry),
                                    onClick = onRefresh,
                                    icon = Icons.Filled.Refresh,
                                    enabled = !state.refreshing,
                                )
                            }
                        } else {
                            EmptyState(
                                icon = Icons.Filled.LocalShipping,
                                title = stringResource(state.selectedTab.emptyRes()),
                                body = null,
                            )
                        }
                    }
                }
                items(trips, key = { it.id }) { trip ->
                    TripSummaryCard(trip = trip, onClick = { onOpenTrip(trip.id) })
                }
                if (state.canLoadMore) {
                    item(key = "trips_load_more") {
                        SecondaryActionButton(
                            text = stringResource(R.string.trips_load_more),
                            onClick = onLoadMore,
                            icon = Icons.Filled.ExpandMore,
                            enabled = !state.loadingMore,
                            modifier = Modifier.testTag("trips_load_more"),
                        )
                    }
                }
            }
        }
    }
}

@StringRes
private fun TripsTab.labelRes(): Int = when (this) {
    TripsTab.ACTIVE -> R.string.trips_tab_active
    TripsTab.UPCOMING -> R.string.trips_tab_upcoming
    TripsTab.COMPLETED -> R.string.trips_tab_completed
}

@StringRes
private fun TripsTab.emptyRes(): Int = when (this) {
    TripsTab.ACTIVE -> R.string.trips_empty_active
    TripsTab.UPCOMING -> R.string.trips_empty_upcoming
    TripsTab.COMPLETED -> R.string.trips_empty_completed
}
