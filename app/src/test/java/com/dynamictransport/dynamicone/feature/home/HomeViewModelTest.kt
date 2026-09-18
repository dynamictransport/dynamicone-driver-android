package com.dynamictransport.dynamicone.feature.home

import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.FailedAction
import com.dynamictransport.dynamicone.domain.model.PendingActionType
import com.dynamictransport.dynamicone.domain.model.SyncErrorCode
import com.dynamictransport.dynamicone.domain.model.SyncOverview
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
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
import com.dynamictransport.dynamicone.testing.FakeTripRepository
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.RestAlarmHarness
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.keepCollecting
import com.dynamictransport.dynamicone.testing.testTrip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
    )

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
}
