package com.dynamictransport.dynamicone.feature.home

import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.AssignedVehicle
import com.dynamictransport.dynamicone.domain.model.AssignedVehicleState
import com.dynamictransport.dynamicone.domain.model.FailedAction
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import com.dynamictransport.dynamicone.domain.model.PendingActionType
import com.dynamictransport.dynamicone.domain.model.SyncErrorCode
import com.dynamictransport.dynamicone.domain.model.SyncOverview
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.VehiclePosition
import com.dynamictransport.dynamicone.domain.model.VehiclePositionState
import com.dynamictransport.dynamicone.domain.repository.TripActionCommand
import com.dynamictransport.dynamicone.domain.statemachine.PrimaryAction
import com.dynamictransport.dynamicone.domain.usecase.SubmitTripActionUseCase
import com.dynamictransport.dynamicone.testing.ACTIVE_SHIFT
import com.dynamictransport.dynamicone.testing.FakeAppInfo
import com.dynamictransport.dynamicone.testing.FakeAuthRepository
import com.dynamictransport.dynamicone.testing.FakeDriverTimesRepository
import com.dynamictransport.dynamicone.testing.FakeNetworkMonitor
import com.dynamictransport.dynamicone.testing.FakeShiftRepository
import com.dynamictransport.dynamicone.testing.FakeSyncRepository
import com.dynamictransport.dynamicone.testing.FakePlaceNameResolver
import com.dynamictransport.dynamicone.testing.FakeTripRepository
import com.dynamictransport.dynamicone.testing.FakeVehiclePositionRepository
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.RestAlarmHarness
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.keepCollecting
import com.dynamictransport.dynamicone.testing.testTrip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import com.dynamictransport.dynamicone.data.demo.UnavailableRoadSnapshotRepository
import com.dynamictransport.dynamicone.data.demo.UnavailableWakeUpCallRepository
import com.dynamictransport.dynamicone.testing.FakeWakeUpCallPermissions
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val auth = FakeAuthRepository()
    private val shifts = FakeShiftRepository()
    private val trips = FakeTripRepository()
    private val sync = FakeSyncRepository()
    private val network = FakeNetworkMonitor()
    private val driverTimes = FakeDriverTimesRepository()
    private val positions = FakeVehiclePositionRepository()
    private var places = FakePlaceNameResolver()

    private fun viewModel() = HomeViewModel(
        authRepository = auth,
        shiftRepository = shifts,
        tripRepository = trips,
        syncRepository = sync,
        networkMonitor = network,
        appInfo = FakeAppInfo(isDemoMode = true),
        submitTripAction = SubmitTripActionUseCase(trips, shifts),
        driverTimes = driverTimes,
        restAlarm = RestAlarmHarness(driverTimes, shifts, scope = CoroutineScope(mainDispatcherRule.dispatcher)).controller,
        vehiclePositions = positions,
        placeNames = places,
        roadSnapshots = UnavailableRoadSnapshotRepository,
        wakeUpCalls = UnavailableWakeUpCallRepository,
        wakeUpPermissions = FakeWakeUpCallPermissions(),
        clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC),
    )

    private val dyna042 = AssignedVehicleState.Assigned(AssignedVehicle("veh-42", "Dyna042", "WX 12345"))
    private val gliwice = GeoPoint(50.2945, 18.6714)
    private val position = VehiclePosition(gliwice, TEST_NOW, TEST_NOW)

    @Test
    fun `new assigned trip makes review the dominant action`() = runTest {
        trips.trips.value = listOf(testTrip(state = TripState.ASSIGNED))
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        val state = viewModel.uiState.value
        assertEquals(PrimaryAction.ReviewNewTrip("trip-1"), state.primaryAction)
        assertEquals("trip-1", state.newTrip?.id)
        assertEquals("Mehmet Demir", state.driverName)
        assertTrue(state.isDemoMode)
    }

    @Test
    fun `start shift is performed in place and the next action appears`() = runTest {
        trips.trips.value = listOf(testTrip(state = TripState.ACCEPTED))
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        assertEquals(PrimaryAction.StartShift, viewModel.uiState.value.primaryAction)

        viewModel.onPrimaryAction(PrimaryAction.StartShift)
        advanceUntilIdle()

        assertEquals(1, shifts.startCalls)
        assertEquals(PrimaryAction.OnTrip("trip-1", TripActionType.START_TRIP), viewModel.uiState.value.primaryAction)
    }

    @Test
    fun `double tap on the primary action submits a single business action`() = runTest {
        shifts.shift.value = ACTIVE_SHIFT
        trips.trips.value = listOf(testTrip(state = TripState.STARTED))
        val gate = CompletableDeferred<Unit>()
        trips.gate = gate
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        val action = checkNotNull(viewModel.uiState.value.primaryAction)

        viewModel.onPrimaryAction(action)
        viewModel.onPrimaryAction(action)
        assertTrue(viewModel.uiState.value.submitting)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(TripActionCommand("trip-1", TripActionType.ARRIVE_PICKUP)), trips.submitted)
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `navigation-only actions are not executed by the view model`() = runTest {
        shifts.shift.value = ACTIVE_SHIFT
        val viewModel = viewModel()

        viewModel.onPrimaryAction(PrimaryAction.EndShift)
        viewModel.onPrimaryAction(PrimaryAction.ReviewNewTrip("trip-1"))
        advanceUntilIdle()

        assertEquals(0, shifts.endCalls)
        assertTrue(trips.submitted.isEmpty())
    }

    @Test
    fun `refused action is explained to the driver`() = runTest {
        shifts.shift.value = ACTIVE_SHIFT
        trips.trips.value = listOf(testTrip(state = TripState.STARTED))
        trips.nextResult = ActionResult.Rejected(ActionRejection.NOT_ALLOWED)
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onPrimaryAction(PrimaryAction.OnTrip("trip-1", TripActionType.ARRIVE_PICKUP))
        advanceUntilIdle()
        assertEquals(R.string.error_not_allowed, viewModel.uiState.value.message)

        viewModel.onMessageShown()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `connectivity and queue status are visible and retryable`() = runTest {
        network.online.value = false
        val failure = FailedAction("c-1", PendingActionType.TRIP_ACTION, "trip-1", TripActionType.ACCEPT_TRIP, SyncErrorCode.CONFLICT, TEST_NOW)
        sync.overview.value = SyncOverview(pendingCount = 3, failures = listOf(failure), lastSuccessfulSyncAt = TEST_NOW)
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        val state = viewModel.uiState.value
        assertFalse(state.isOnline)
        assertEquals(3, state.pendingCount)
        assertEquals(1, state.failedCount)
        assertEquals(TEST_NOW, state.lastSyncAt)

        viewModel.onRetrySync()
        advanceUntilIdle()
        assertEquals(1, sync.retryCalls)
    }

    @Test
    fun `the assigned vehicle comes from the profile, never from the trip`() = runTest {
        trips.trips.value = listOf(testTrip(state = TripState.STARTED).copy(vehicleCompanyCode = "Dyna007"))
        auth.assignedVehicle.value = AssignedVehicleState.NotAssigned
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        assertEquals(AssignedVehicleState.NotAssigned, viewModel.uiState.value.assignedVehicle)

        val assigned = AssignedVehicleState.Assigned(AssignedVehicle("veh-42", "Dyna042", "WX 12345"))
        auth.assignedVehicle.value = assigned
        assertEquals(assigned, viewModel.uiState.value.assignedVehicle)

        // Assignment ended on the server: the old vehicle is not kept.
        auth.assignedVehicle.value = AssignedVehicleState.NotAssigned
        assertEquals(AssignedVehicleState.NotAssigned, viewModel.uiState.value.assignedVehicle)
    }

    @Test
    fun `coming back to home and retrying read the profile again`() = runTest {
        auth.assignedVehicle.value = AssignedVehicleState.Unavailable
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        assertEquals(AssignedVehicleState.Unavailable, viewModel.uiState.value.assignedVehicle)

        viewModel.onResume()
        runCurrent()
        assertEquals(1, auth.profileRefreshes)
        viewModel.onPause()

        viewModel.onRetryAssignedVehicle()
        advanceUntilIdle()
        assertEquals(2, auth.profileRefreshes)
    }

    @Test
    fun `the vehicle position is read on return and every minute until home leaves the screen`() = runTest {
        auth.assignedVehicle.value = dyna042
        positions.nextAnswer = VehiclePositionState.Known("veh-42", position)
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onResume()
        runCurrent()
        assertEquals(1, positions.refreshes)
        assertEquals(VehiclePositionState.Known("veh-42", position), viewModel.uiState.value.vehiclePosition)

        advanceTimeBy(60_001)
        assertEquals(2, positions.refreshes)

        // Coming back again while reads run does not start a second loop.
        viewModel.onResume()
        runCurrent()
        assertEquals(2, positions.refreshes)

        viewModel.onPause()
        advanceTimeBy(600_000)
        assertEquals(2, positions.refreshes)
    }

    @Test
    fun `reads stop when the server has no position endpoint, no vehicle or no tracking`() = runTest {
        auth.assignedVehicle.value = dyna042
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        listOf(VehiclePositionState.NotSupported, VehiclePositionState.NotAssigned, VehiclePositionState.NotLinked).forEach { answer ->
            val before = positions.refreshes
            positions.nextAnswer = answer
            viewModel.onResume()
            advanceTimeBy(600_000)
            assertEquals("$answer", before + 1, positions.refreshes)
            viewModel.onPause()
        }
    }

    @Test
    fun `only the position of the vehicle on screen is shown`() = runTest {
        auth.assignedVehicle.value = dyna042
        positions.position.value = VehiclePositionState.Known("veh-7", position)
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        // Another vehicle's answer (the assignment changed between the two reads) waits for the next read.
        assertEquals(VehiclePositionState.Loading, viewModel.uiState.value.vehiclePosition)

        positions.position.value = VehiclePositionState.Known("veh-42", position)
        assertEquals(VehiclePositionState.Known("veh-42", position), viewModel.uiState.value.vehiclePosition)

        // Without an assigned vehicle there is no position row at all.
        auth.assignedVehicle.value = AssignedVehicleState.NotAssigned
        assertEquals(VehiclePositionState.NotAssigned, viewModel.uiState.value.vehiclePosition)
    }

    @Test
    fun `the phone names the place and a point it cannot name has no name`() = runTest {
        places = FakePlaceNameResolver(mapOf(gliwice to "Gliwice, PL"))
        auth.assignedVehicle.value = dyna042
        positions.position.value = VehiclePositionState.Known("veh-42", position)
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        assertEquals("Gliwice, PL", viewModel.uiState.value.vehiclePlace)

        val elsewhere = GeoPoint(52.4064, 16.9252)
        positions.position.value = VehiclePositionState.Known("veh-42", position.copy(point = elsewhere))
        assertNull(viewModel.uiState.value.vehiclePlace)

        positions.position.value = VehiclePositionState.NoData
        assertNull(viewModel.uiState.value.vehiclePlace)
        assertEquals(listOf(gliwice, elsewhere), places.lookups)
    }

    @Test
    fun `retrying the position reads it once and a missing map app is told`() = runTest {
        auth.assignedVehicle.value = dyna042
        positions.position.value = VehiclePositionState.Unavailable
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onRetryVehiclePosition()
        advanceUntilIdle()
        assertEquals(1, positions.refreshes)

        viewModel.onMapUnavailable()
        assertEquals(R.string.navigation_unavailable, viewModel.uiState.value.message)
    }
}
