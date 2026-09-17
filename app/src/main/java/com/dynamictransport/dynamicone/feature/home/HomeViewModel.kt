package com.dynamictransport.dynamicone.feature.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.AuthRepository
import com.dynamictransport.dynamicone.domain.repository.NetworkMonitor
import com.dynamictransport.dynamicone.domain.repository.ShiftRepository
import com.dynamictransport.dynamicone.domain.repository.SyncRepository
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import com.dynamictransport.dynamicone.domain.statemachine.NextActionResolver
import com.dynamictransport.dynamicone.domain.statemachine.PrimaryAction
import com.dynamictransport.dynamicone.domain.usecase.SubmitTripActionUseCase
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val isDemoMode: Boolean = false,
    val driverName: String? = null,
    val isOnline: Boolean = true,
    val shift: Shift = Shift.NotStarted,
    val lastSyncAt: Instant? = null,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val focusTrip: Trip? = null,
    val newTrip: Trip? = null,
    val primaryAction: PrimaryAction? = null,
    val blockedAction: TripActionType? = null,
    /** The latest server read succeeded, so an empty trip list really means "no trip". */
    val tripsLoaded: Boolean = true,
    val refreshing: Boolean = false,
    @param:StringRes val refreshError: Int? = null,
    val submitting: Boolean = false,
    @param:StringRes val message: Int? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val shiftRepository: ShiftRepository,
    tripRepository: TripRepository,
    private val syncRepository: SyncRepository,
    networkMonitor: NetworkMonitor,
    appInfo: AppInfo,
    private val submitTripAction: SubmitTripActionUseCase,
) : ViewModel() {

    private val singleFlight = SingleFlight()
    private val message = MutableStateFlow<Int?>(null)
    private val refreshing = MutableStateFlow(false)

    private val content = combine(
        authRepository.observeProfile(),
        shiftRepository.observeShift(),
        tripRepository.observeTrips(),
        syncRepository.observeOverview(),
        networkMonitor.isOnline,
    ) { driver, shift, trips, overview, online ->
        val home = NextActionResolver.forHome(shift, trips)
        HomeUiState(
            loading = false,
            isDemoMode = appInfo.isDemoMode,
            driverName = driver?.displayName?.takeIf { it.isNotBlank() },
            isOnline = online,
            shift = shift,
            lastSyncAt = overview.lastSuccessfulSyncAt,
            pendingCount = overview.pendingCount,
            failedCount = overview.failedCount,
            focusTrip = home.focusTrip,
            newTrip = home.newTrip,
            primaryAction = home.plan.primary,
            blockedAction = home.plan.blockedAction,
        )
    }

    val uiState: StateFlow<HomeUiState> =
        combine(content, syncRepository.observeLastRefresh(), refreshing, singleFlight.busy, message) {
                state, lastRefresh, isRefreshing, busy, currentMessage ->
            state.copy(
                // Until the first server read of the session is done there is nothing to decide on.
                loading = lastRefresh == null && state.focusTrip == null,
                tripsLoaded = lastRefresh == RefreshResult.SUCCESS,
                refreshing = isRefreshing,
                // Offline is already shown by the connectivity banner.
                refreshError = lastRefresh?.takeIf { it != RefreshResult.OFFLINE }?.messageRes(),
                submitting = busy,
                message = currentMessage,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(isDemoMode = appInfo.isDemoMode))

    /** Actions performed in place. Navigation-only actions (review trip, end shift summary) are handled by the UI. */
    fun onPrimaryAction(action: PrimaryAction) {
        if (action !is PrimaryAction.OnTrip && action != PrimaryAction.StartShift) return
        singleFlight.launch(viewModelScope) {
            val result = when (action) {
                is PrimaryAction.OnTrip -> submitTripAction(action.tripId, action.action)
                else -> shiftRepository.startShift()
            }
            (result as? ActionResult.Rejected)?.let { message.value = it.reason.messageRes() }
        }
    }

    fun onRetrySync() {
        viewModelScope.launch { syncRepository.retryFailed() }
    }

    /** Reads are safe to repeat; writes are never resent from here. */
    fun onRefresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                syncRepository.refreshFromServer()
            } finally {
                refreshing.value = false
            }
        }
    }

    fun onMessageShown() {
        message.value = null
    }
}
