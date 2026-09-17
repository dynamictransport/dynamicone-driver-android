package com.dynamictransport.driver.feature.tripdetail

import androidx.lifecycle.SavedStateHandle
import com.dynamictransport.driver.R
import com.dynamictransport.driver.domain.model.ActionRejection
import com.dynamictransport.driver.domain.model.ActionResult
import com.dynamictransport.driver.domain.model.BreakState
import com.dynamictransport.driver.domain.model.ConversationList
import com.dynamictransport.driver.domain.model.ConversationSummary
import com.dynamictransport.driver.domain.model.DocumentSlotState
import com.dynamictransport.driver.domain.model.DocumentType
import com.dynamictransport.driver.domain.model.ExecutionActionCode
import com.dynamictransport.driver.domain.model.PendingCommandState
import com.dynamictransport.driver.domain.model.PendingTripAction
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.model.RejectReason
import com.dynamictransport.driver.domain.model.Shift
import com.dynamictransport.driver.domain.model.SyncState
import com.dynamictransport.driver.domain.model.TripActionRecord
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.model.TripState
import com.dynamictransport.driver.domain.model.TripWorkflow
import com.dynamictransport.driver.domain.repository.TripActionCommand
import com.dynamictransport.driver.domain.rules.DriverInputRules
import com.dynamictransport.driver.domain.statemachine.PrimaryAction
import com.dynamictransport.driver.domain.usecase.SubmitTripActionUseCase
import com.dynamictransport.driver.testing.ACTIVE_SHIFT
import com.dynamictransport.driver.testing.FakeAppInfo
import com.dynamictransport.driver.testing.FakeMessageRepository
import com.dynamictransport.driver.testing.FakeNetworkMonitor
import com.dynamictransport.driver.testing.FakeShiftRepository
import com.dynamictransport.driver.testing.FakeTripExecutionRepository
import com.dynamictransport.driver.testing.FakeTripRepository
import com.dynamictransport.driver.testing.MainDispatcherRule
import com.dynamictransport.driver.testing.TEST_NOW
import com.dynamictransport.driver.testing.keepCollecting
import com.dynamictransport.driver.testing.testAction
import com.dynamictransport.driver.testing.testExecution
import com.dynamictransport.driver.testing.testSlot
import com.dynamictransport.driver.testing.testTrip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TripDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val trips = FakeTripRepository()
    private val shifts = FakeShiftRepository(ACTIVE_SHIFT)
    private val network = FakeNetworkMonitor()
    private val executions = FakeTripExecutionRepository()
    private val messages = FakeMessageRepository()

    private fun viewModel(demo: Boolean = true) = TripDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf(TRIP_ID_ARG to "trip-1")),
        tripRepository = trips,
        shiftRepository = shifts,
        networkMonitor = network,
        appInfo = FakeAppInfo(isDemoMode = demo),
        submitTripAction = SubmitTripActionUseCase(trips, shifts),
        executionRepository = executions,
        messageRepository = messages,
    )

    @Test
    fun `on break only end break is offered and the break start is shown`() = runTest {
        trips.trips.value = listOf(testTrip(state = TripState.EN_ROUTE_DELIVERY, breakState = BreakState.ON_BREAK))
        trips.history.value = mapOf("trip-1" to listOf(TripActionRecord(TripActionType.START_BREAK, TEST_NOW, SyncState.PENDING)))
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        val state = viewModel.uiState.value
        assertEquals(PrimaryAction.OnTrip("trip-1", TripActionType.END_BREAK), state.plan.primary)
        assertTrue(state.plan.secondary.isEmpty())
        assertEquals(TEST_NOW, state.breakStartedAt)
    }

    @Test
    fun `the messages tile counts only the unread messages of this trip`() = runTest {
        trips.trips.value = listOf(testTrip(state = TripState.EN_ROUTE_DELIVERY))
        messages.conversations.value = ConversationList(
            listOf(ConversationSummary(null, null, null, 4), ConversationSummary("trip-1", "TRP-1", null, 2)),
            loaded = true,
        )
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        assertEquals(2, viewModel.uiState.value.unreadMessages)
    }

    @Test
    fun `reject is offered only when the backend allows it`() = runTest {
        trips.trips.value = listOf(testTrip(state = TripState.ASSIGNED, serverAllowedActions = setOf(TripActionType.ACCEPT_TRIP)))
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        assertTrue(viewModel.uiState.value.plan.secondary.isEmpty())

        trips.trips.value = listOf(
            testTrip(state = TripState.ASSIGNED, serverAllowedActions = setOf(TripActionType.ACCEPT_TRIP, TripActionType.REJECT_TRIP)),
        )
        assertEquals(TripActionType.REJECT_TRIP, viewModel.uiState.value.plan.secondary.single().action)
    }

    @Test
    fun `start break is submitted as a trip action and confirmed`() = runTest {
        trips.trips.value = listOf(testTrip(state = TripState.STARTED))
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onStartBreak()
        advanceUntilIdle()

        assertEquals(TripActionCommand("trip-1", TripActionType.START_BREAK), trips.submitted.single())
        assertEquals(R.string.trip_break_reported, viewModel.uiState.value.message)
    }

    @Test
    fun `accepted trip without shift starts the shift first`() = runTest {
        shifts.shift.value = Shift.NotStarted
        trips.trips.value = listOf(testTrip(state = TripState.ACCEPTED))
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        assertEquals(PrimaryAction.StartShift, viewModel.uiState.value.plan.primary)

        viewModel.onPrimaryAction(PrimaryAction.StartShift)
        advanceUntilIdle()

        assertEquals(1, shifts.startCalls)
        assertEquals(PrimaryAction.OnTrip("trip-1", TripActionType.START_TRIP), viewModel.uiState.value.plan.primary)
    }

    @Test
    fun `without backend shift support the trip starts directly`() = runTest {
        shifts.shift.value = Shift.Unavailable
        trips.trips.value = listOf(testTrip(state = TripState.ACCEPTED))
        val viewModel = viewModel(demo = false)
        keepCollecting(viewModel.uiState)

        assertEquals(PrimaryAction.OnTrip("trip-1", TripActionType.START_TRIP), viewModel.uiState.value.plan.primary)
        assertFalse(viewModel.uiState.value.offlineActionsQueued)
    }

    @Test
    fun `double tap on a trip action submits once`() = runTest {
        trips.trips.value = listOf(testTrip(state = TripState.AT_PICKUP))
        val gate = CompletableDeferred<Unit>()
        trips.gate = gate
        val viewModel = viewModel()
        val action = PrimaryAction.OnTrip("trip-1", TripActionType.COMPLETE_PICKUP)

        viewModel.onPrimaryAction(action)
        viewModel.onPrimaryAction(action)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, trips.submitted.size)
    }

    @Test
    fun `opening the screen reloads the trip and a failed first load offers retry`() = runTest {
        trips.refreshResult = RefreshResult.SERVICE_UNAVAILABLE
        val viewModel = viewModel(demo = false)
        keepCollecting(viewModel.uiState)

        assertEquals(R.string.refresh_service_unavailable, viewModel.uiState.value.loadError)
        assertFalse(viewModel.uiState.value.loading)

        trips.refreshResult = RefreshResult.SUCCESS
        trips.trips.value = listOf(testTrip(state = TripState.ACCEPTED))
        viewModel.onRetry()
        advanceUntilIdle()

        assertEquals(listOf("trip-1", "trip-1"), trips.refreshedTrips)
        assertNull(viewModel.uiState.value.loadError)
        assertEquals("trip-1", viewModel.uiState.value.trip?.id)
    }

    // --- Trip action workflow ---

    private val pickupFirstContainer = testAction(ExecutionActionCode.COMPLETE_STOP, "I picked up the full container", "s1", "c1")
    private val pickupSecondContainer = testAction(ExecutionActionCode.COMPLETE_STOP, "I picked up the full container", "s1", "c2")
    private val rest = testAction(ExecutionActionCode.REST, "Report a break")
    private val problem = testAction(ExecutionActionCode.PROBLEM, "Report a problem")

    private fun serverTrip() = testTrip(state = TripState.STARTED, workflow = TripWorkflow.TRIP_ACTIONS, serverAllowedActions = emptySet())

    @Test
    fun `trip action steps are exactly the options of the server plan`() = runTest {
        trips.trips.value = listOf(serverTrip())
        executions.execution.value = testExecution(
            actions = listOf(pickupFirstContainer, pickupSecondContainer, rest, problem),
            documents = listOf(
                testSlot("Cmr:trip:all", DocumentType.CMR, DocumentSlotState.MISSING),
                testSlot("Customs:trip:all", DocumentType.CUSTOMS, DocumentSlotState.NOT_REQUIRED, required = false),
            ),
        )
        val viewModel = viewModel(demo = false)
        keepCollecting(viewModel.uiState)

        val state = viewModel.uiState.value
        assertTrue(state.usesServerActions)
        assertEquals(listOf(pickupFirstContainer, pickupSecondContainer), state.progressActions)
        assertEquals(rest, state.restAction)
        assertTrue(state.problemAvailable)
        assertEquals(1, state.openDocumentCount)
        assertTrue(state.timeline.isEmpty())
        assertEquals(1, executions.receiptRefreshes)
    }

    @Test
    fun `a server option is submitted unchanged and a double tap sends it once`() = runTest {
        trips.trips.value = listOf(serverTrip())
        executions.execution.value = testExecution(actions = listOf(pickupFirstContainer, pickupSecondContainer))
        val gate = CompletableDeferred<Unit>()
        executions.gate = gate
        val viewModel = viewModel(demo = false)
        keepCollecting(viewModel.uiState)

        viewModel.onExecutionAction(pickupSecondContainer)
        viewModel.onExecutionAction(pickupSecondContainer)
        assertTrue(viewModel.uiState.value.submitting)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(FakeTripExecutionRepository.Submitted("trip-1", pickupSecondContainer, null)), executions.submitted)
        assertEquals(R.string.exec_recorded, viewModel.uiState.value.message)
    }

    @Test
    fun `a break is confirmed and a changed plan is explained`() = runTest {
        trips.trips.value = listOf(serverTrip())
        executions.execution.value = testExecution(actions = listOf(pickupFirstContainer, rest))
        val viewModel = viewModel(demo = false)
        keepCollecting(viewModel.uiState)

        viewModel.onExecutionAction(rest)
        advanceUntilIdle()
        assertEquals(R.string.trip_break_reported, viewModel.uiState.value.message)

        executions.submitResult = ActionResult.Rejected(ActionRejection.PLAN_CHANGED)
        viewModel.onExecutionAction(pickupFirstContainer)
        advanceUntilIdle()
        assertEquals(R.string.error_plan_changed, viewModel.uiState.value.message)
    }

    @Test
    fun `a stored action is resent or discarded before anything else`() = runTest {
        trips.trips.value = listOf(serverTrip())
        executions.execution.value = testExecution(actions = listOf(pickupFirstContainer))
        val stored = PendingTripAction("cmd-1", "trip-1", "v1", pickupFirstContainer, null, null, TEST_NOW, PendingCommandState.SENDING)
        executions.pending.value = stored
        val viewModel = viewModel(demo = false)
        keepCollecting(viewModel.uiState)
        assertTrue(viewModel.uiState.value.submitting)

        executions.pending.value = stored.copy(state = PendingCommandState.OUTCOME_UNKNOWN)
        assertFalse(viewModel.uiState.value.submitting)
        assertEquals(PendingCommandState.OUTCOME_UNKNOWN, viewModel.uiState.value.pendingAction?.state)

        viewModel.onResendPending()
        advanceUntilIdle()
        assertEquals(1, executions.resendCalls)
        assertEquals(R.string.exec_recorded, viewModel.uiState.value.message)

        viewModel.onDiscardPending()
        advanceUntilIdle()
        assertEquals(1, executions.discardCalls)
        assertNull(viewModel.uiState.value.pendingAction)
    }

    @Test
    fun `a plan that could not be read offers no guessed step`() = runTest {
        trips.trips.value = listOf(serverTrip())
        trips.refreshResult = RefreshResult.SERVICE_UNAVAILABLE
        val viewModel = viewModel(demo = false)
        keepCollecting(viewModel.uiState)

        val state = viewModel.uiState.value
        assertTrue(state.planUnavailable)
        assertFalse(state.planLoading)
        assertTrue(state.progressActions.isEmpty())
        assertNull(state.loadError)
    }

    @Test
    fun `legacy trips keep the status plan and read no receipts`() = runTest {
        trips.trips.value = listOf(
            testTrip(
                state = TripState.AT_PICKUP,
                workflow = TripWorkflow.LEGACY_STATUSES,
                serverAllowedActions = setOf(TripActionType.COMPLETE_PICKUP, TripActionType.START_BREAK),
            ),
        )
        val viewModel = viewModel(demo = false)
        keepCollecting(viewModel.uiState)

        val state = viewModel.uiState.value
        assertFalse(state.usesServerActions)
        assertTrue(state.isLegacyWorkflow)
        assertEquals(PrimaryAction.OnTrip("trip-1", TripActionType.COMPLETE_PICKUP), state.plan.primary)
        assertEquals(0, executions.receiptRefreshes)
    }
}

class RejectTripViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val trips = FakeTripRepository(
        listOf(testTrip(state = TripState.ASSIGNED, serverAllowedActions = setOf(TripActionType.ACCEPT_TRIP, TripActionType.REJECT_TRIP))),
    )
    private val shifts = FakeShiftRepository()

    private fun viewModel() = RejectTripViewModel(
        savedStateHandle = SavedStateHandle(mapOf(TRIP_ID_ARG to "trip-1")),
        tripRepository = trips,
        submitTripAction = SubmitTripActionUseCase(trips, shifts),
    )

    @Test
    fun `other reason requires a short note`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        assertFalse(viewModel.uiState.value.canSubmit)
        assertEquals("TRP-TRIP-1", viewModel.uiState.value.tripNo)

        viewModel.onReasonSelected(RejectReason.OTHER)
        assertTrue(viewModel.uiState.value.noteRequired)
        assertFalse(viewModel.uiState.value.canSubmit)

        viewModel.onNoteChanged("   ")
        assertFalse(viewModel.uiState.value.canSubmit)

        viewModel.onNoteChanged("Trailer damaged")
        assertTrue(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `preset reason rejects without a note`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onReasonSelected(RejectReason.WRONG_ASSIGNMENT)
        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(
            TripActionCommand("trip-1", TripActionType.REJECT_TRIP, RejectReason.WRONG_ASSIGNMENT, null),
            trips.submitted.single(),
        )
        assertTrue(viewModel.uiState.value.completed)
    }

    @Test
    fun `submitting without a reason is refused with a message`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(R.string.error_reason_required, viewModel.uiState.value.error)
        assertTrue(trips.submitted.isEmpty())
    }

    @Test
    fun `note never exceeds the maximum length`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onNoteChanged("x".repeat(DriverInputRules.REJECT_NOTE_MAX_LENGTH + 50))

        assertEquals(DriverInputRules.REJECT_NOTE_MAX_LENGTH, viewModel.uiState.value.note.length)
    }
}
