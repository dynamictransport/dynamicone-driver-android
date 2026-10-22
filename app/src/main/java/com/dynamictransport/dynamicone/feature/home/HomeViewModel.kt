package com.dynamictransport.dynamicone.feature.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.alarm.RestAlarmController
import com.dynamictransport.dynamicone.core.location.PlaceNameResolver
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.AssignedVehicleState
import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.domain.model.SnapshotOutcome
import com.dynamictransport.dynamicone.domain.model.VehiclePositionState
import com.dynamictransport.dynamicone.domain.repository.DriverTimesRepository
import com.dynamictransport.dynamicone.domain.repository.RoadSnapshotRepository
import com.dynamictransport.dynamicone.domain.repository.VehiclePositionRepository
import com.dynamictransport.dynamicone.feature.shift.DriverDayState
import kotlinx.coroutines.flow.flowOf
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val isDemoMode: Boolean = false,
    val driverName: String? = null,
    /** The driver's current fleet vehicle from `me`, not the vehicle of a trip. */
    val assignedVehicle: AssignedVehicleState = AssignedVehicleState.Loading,
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
    /** Tachograph summary and wake-up alarm; null without the daily shift feature (docs/16). */
    val driverDay: DriverDayState? = null,
    /** Last reported position of the assigned vehicle (docs/19); hidden while no vehicle is assigned. */
    val vehiclePosition: VehiclePositionState = VehiclePositionState.NotAssigned,
    /** Place of [vehiclePosition] as the phone names it; null until resolved or when it cannot. */
    val vehiclePlace: String? = null,
    /** The planner's latest road photo request on this tablet, while it is recent (docs/24); null otherwise. */
    val snapshot: SnapshotOutcome? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val shiftRepository: ShiftRepository,
    tripRepository: TripRepository,
    private val syncRepository: SyncRepository,
    networkMonitor: NetworkMonitor,
    appInfo: AppInfo,
    private val submitTripAction: SubmitTripActionUseCase,
    driverTimes: DriverTimesRepository,
    private val restAlarm: RestAlarmController,
    private val vehiclePositions: VehiclePositionRepository,
    private val placeNames: PlaceNameResolver,
    roadSnapshots: RoadSnapshotRepository,
    clock: Clock,
) : ViewModel() {

    /** Only a request of the last hours is worth a line on the home screen. */
    private val recentSnapshot = roadSnapshots.observeLatest().map { outcome ->
        outcome?.takeIf { Duration.between(it.updatedAt, clock.instant()) <= SNAPSHOT_SHOWN_FOR }
    }

    private val driverDay = if (appInfo.isDriverShiftEnabled) {
        combine(driverTimes.observeDriverTimes(), restAlarm.status) { times, alarm -> DriverDayState(times, alarm) }
    } else {
        flowOf(null)
    }

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

    /** Only the position of the vehicle on screen is shown; another vehicle's answer waits for the next read. */
    private val vehiclePosition = combine(vehiclePositions.observePosition(), authRepository.observeAssignedVehicle()) { position, vehicle ->
        val assigned = (vehicle as? AssignedVehicleState.Assigned)?.vehicle
        when {
            assigned == null -> VehiclePositionState.NotAssigned
            position is VehiclePositionState.Known && position.vehicleId != null && assigned.vehicleId != null &&
                position.vehicleId != assigned.vehicleId -> VehiclePositionState.Loading
            else -> position
        }
    }.distinctUntilChanged()

    /** While a new point is named, the previous name stays; a point the phone cannot name shows its coordinates. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val vehiclePlace = vehiclePosition
        .map { (it as? VehiclePositionState.Known)?.position?.point }
        .distinctUntilChanged()
        .transformLatest { point -> emit(point?.let { placeNames.placeName(it, Locale.getDefault()) }) }
        .onStart { emit(null) }

    private var positionReads: Job? = null

    private val contentWithDay = combine(
        content,
        driverDay,
        authRepository.observeAssignedVehicle(),
        vehiclePosition,
        vehiclePlace,
    ) { state, day, vehicle, position, place ->
        state.copy(driverDay = day, assignedVehicle = vehicle, vehiclePosition = position, vehiclePlace = place)
    }.combine(recentSnapshot) { state, snapshot -> state.copy(snapshot = snapshot) }

    val uiState: StateFlow<HomeUiState> =
        combine(contentWithDay, syncRepository.observeLastRefresh(), refreshing, singleFlight.busy, message) {
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

    /** No wake-up alarm for this rest (e.g. weekly rest at home). */
    fun onDismissRestAlarm(rest: RestWindow) {
        viewModelScope.launch { restAlarm.dismiss(rest) }
    }

    fun onRestoreRestAlarm() {
        viewModelScope.launch { restAlarm.restore() }
    }

    /**
     * The screen came to the front: alarm permissions may have been changed in the settings, and the office may have
     * changed the vehicle assignment.
     */
    fun onResume() {
        restAlarm.refreshPermissions()
        refreshProfile()
        readVehiclePosition()
    }

    /** Home left the screen: the vehicle position is not read in the background. */
    fun onPause() {
        positionReads?.cancel()
        positionReads = null
    }

    fun onRetryAssignedVehicle() = refreshProfile()

    fun onRetryVehiclePosition() {
        viewModelScope.launch { vehiclePositions.refresh() }
    }

    fun onMapUnavailable() {
        message.value = R.string.navigation_unavailable
    }

    private fun refreshProfile() {
        viewModelScope.launch { authRepository.refreshProfile() }
    }

    /**
     * Read at once and then every minute while home is on screen (the server's fleet sync runs about as often). Stops when
     * the server has no endpoint, no vehicle is assigned or the vehicle is not tracked; the next return to home asks again.
     */
    private fun readVehiclePosition() {
        if (positionReads?.isActive == true) return
        positionReads = viewModelScope.launch {
            while (true) {
                vehiclePositions.refresh()
                if (vehiclePositions.observePosition().first() in FINAL_POSITION_STATES) break
                delay(POSITION_READ_INTERVAL_MILLIS)
            }
        }
    }

    private companion object {
        const val POSITION_READ_INTERVAL_MILLIS = 60_000L
        val SNAPSHOT_SHOWN_FOR: Duration = Duration.ofHours(4)
        val FINAL_POSITION_STATES = setOf(
            VehiclePositionState.NotSupported,
            VehiclePositionState.NotAssigned,
            VehiclePositionState.NotLinked,
        )
    }

}
