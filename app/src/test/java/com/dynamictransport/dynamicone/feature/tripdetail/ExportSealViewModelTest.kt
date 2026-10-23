package com.dynamictransport.dynamicone.feature.tripdetail

import androidx.lifecycle.SavedStateHandle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.DocumentSlotState
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.ExecutionBlock
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import com.dynamictransport.dynamicone.domain.usecase.SubmitTripActionUseCase
import com.dynamictransport.dynamicone.testing.ACTIVE_SHIFT
import com.dynamictransport.dynamicone.testing.FakeAppInfo
import com.dynamictransport.dynamicone.testing.FakeMessageRepository
import com.dynamictransport.dynamicone.testing.FakeNetworkMonitor
import com.dynamictransport.dynamicone.testing.FakeServerClock
import com.dynamictransport.dynamicone.testing.FakeShiftRepository
import com.dynamictransport.dynamicone.testing.FakeTripExecutionRepository
import com.dynamictransport.dynamicone.testing.FakeTripRepository
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.keepCollecting
import com.dynamictransport.dynamicone.testing.testAction
import com.dynamictransport.dynamicone.testing.testExecution
import com.dynamictransport.dynamicone.testing.testSlot
import com.dynamictransport.dynamicone.testing.testTrip
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** docs/27: the trip screen state of an export loading stop. */
class ExportSealViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val trips = FakeTripRepository()
    private val executions = FakeTripExecutionRepository()
    private val clock = MutableClock()

    private fun viewModel() = TripDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf(TRIP_ID_ARG to "trip-1")),
        tripRepository = trips,
        shiftRepository = FakeShiftRepository(ACTIVE_SHIFT),
        networkMonitor = FakeNetworkMonitor(),
        appInfo = FakeAppInfo(isDemoMode = false),
        submitTripAction = SubmitTripActionUseCase(trips, FakeShiftRepository(ACTIVE_SHIFT)),
        executionRepository = executions,
        messageRepository = FakeMessageRepository(),
        serverClock = FakeServerClock(clock),
        clock = clock,
    )

    private val confirmLoading = testAction(ExecutionActionCode.CONFIRM_LOADING, "Confirm loading completed", "s1", null, stopName = "Antwerp PSA")
    private val sealFirst = testAction(ExecutionActionCode.RECORD_SEAL_NUMBER, "Save seal number", "s1", "c1", containerNumber = "MSKU 458921-7")
    private val sealSecond = testAction(ExecutionActionCode.RECORD_SEAL_NUMBER, "Save seal number", "s1", "c2", containerNumber = "TGHU 902114-6", sealNumber = "SEAL-002")
    private val rest = testAction(ExecutionActionCode.REST, "Report a break")
    private fun completion(containerId: String, reason: ExecutionBlock?) =
        testAction(ExecutionActionCode.COMPLETE_STOP, "Container loading completed", "s1", containerId)
            .copy(canExecute = reason == null, blockingReason = reason)

    private val photoFirst = testSlot("Seal:s1:c1", DocumentType.SEAL_PHOTO, DocumentSlotState.MISSING, stopId = "s1", containerId = "c1")
    private val photoSecond = testSlot("Seal:s1:c2", DocumentType.SEAL_PHOTO, DocumentSlotState.PENDING_REVIEW, stopId = "s1", containerId = "c2", documentId = "d2")
    private val cmr = testSlot("Cmr:trip:all", DocumentType.CMR, DocumentSlotState.MISSING)

    private fun serverTrip() = testTrip(state = TripState.STARTED, workflow = TripWorkflow.TRIP_ACTIONS, serverAllowedActions = emptySet())

    @Test
    fun `before the confirmation the loading confirmation is the step and the stop completion is never offered`() = runTest {
        trips.trips.value = listOf(serverTrip())
        executions.execution.value = testExecution(
            actions = listOf(
                confirmLoading,
                completion("c1", ExecutionBlock.LOADING_CONFIRMATION_REQUIRED).copy(blockingDocumentSlots = listOf("Seal:s1:c1", "Cmr:trip:all")),
                completion("c2", ExecutionBlock.LOADING_CONFIRMATION_REQUIRED).copy(blockingDocumentSlots = listOf("Seal:s1:c2")),
                rest,
            ),
            documents = listOf(cmr, photoFirst, photoSecond),
        )
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        val state = viewModel.uiState.value
        assertEquals(confirmLoading, state.loadingConfirmation)
        assertTrue(state.exportLoadingActive)
        assertTrue(state.progressActions.isEmpty())
        assertTrue(state.sealEntries.isEmpty())
        assertFalse(state.sealHandoverComplete)
        assertEquals(rest, state.restAction)
        // The seal photos of this stop are not "needed before the next step": they belong to the seal cards later.
        assertEquals(listOf(cmr), state.blockingDocuments)

        viewModel.onConfirmLoading(confirmLoading)
        advanceUntilIdle()
        assertEquals(listOf(FakeTripExecutionRepository.Submitted("trip-1", confirmLoading, null)), executions.submitted)
        assertEquals(R.string.seal_loading_confirmed, viewModel.uiState.value.message)
    }

    @Test
    fun `after the confirmation each container has its own seal entry with separate text and photo state`() = runTest {
        trips.trips.value = listOf(serverTrip())
        executions.execution.value = testExecution(
            actions = listOf(sealFirst, sealSecond, completion("c1", ExecutionBlock.SEAL_NUMBER_REQUIRED), completion("c2", null), rest),
            documents = listOf(photoFirst, photoSecond),
        )
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        val state = viewModel.uiState.value
        assertNull(state.loadingConfirmation)
        assertTrue(state.progressActions.isEmpty())
        val (first, second) = state.sealEntries
        assertEquals("c1", first.containerId)
        assertFalse(first.sealNumberRecorded)
        assertEquals(photoFirst, first.photoSlot)
        assertFalse(first.complete)
        assertEquals("SEAL-002", second.recordedSealNumber)
        assertTrue(second.photoDone)
        assertTrue(second.complete)
        assertFalse(state.sealHandoverComplete)
        assertTrue(state.blockingDocuments.isEmpty())

        viewModel.onRecordSealNumber(sealFirst, "SEAL-001")
        advanceUntilIdle()
        assertEquals(listOf(FakeTripExecutionRepository.Submitted("trip-1", sealFirst, "SEAL-001")), executions.sealNumbers)
        assertTrue(executions.submitted.isEmpty())
        assertEquals(R.string.seal_saved, viewModel.uiState.value.message)

        executions.submitResult = ActionResult.Rejected(ActionRejection.SEAL_NUMBER_INVALID)
        viewModel.onRecordSealNumber(sealFirst, "")
        advanceUntilIdle()
        assertEquals(R.string.error_seal_number_invalid, viewModel.uiState.value.message)
    }

    @Test
    fun `with every seal number and photo on the server the stop waits for the planner until the server closes it`() = runTest {
        trips.trips.value = listOf(serverTrip())
        executions.execution.value = testExecution(
            actions = listOf(sealFirst.copy(sealNumber = "SEAL-001"), sealSecond, completion("c1", null), completion("c2", null)),
            documents = listOf(photoFirst.copy(state = DocumentSlotState.APPROVED), photoSecond),
        )
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        assertTrue(viewModel.uiState.value.sealHandoverComplete)
        assertTrue(viewModel.uiState.value.progressActions.isEmpty())

        // The planner closed the stop: the server plan moves on and the ordinary steps come back.
        val unload = testAction(ExecutionActionCode.COMPLETE_STOP, "Container unloading completed", "s2", "c1")
        executions.execution.value = testExecution(actions = listOf(unload))
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertFalse(state.exportLoadingActive)
        assertTrue(state.sealEntries.isEmpty())
        assertEquals(listOf(unload), state.progressActions)
    }

    @Test
    fun `a rejected photo keeps the entry open even with the seal number recorded`() = runTest {
        trips.trips.value = listOf(serverTrip())
        executions.execution.value = testExecution(
            actions = listOf(sealSecond, completion("c2", null)),
            documents = listOf(photoSecond.copy(state = DocumentSlotState.REJECTED, reviewNote = "Blurry")),
        )
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        val entry = viewModel.uiState.value.sealEntries.single()
        assertTrue(entry.sealNumberRecorded)
        assertFalse(entry.photoDone)
        assertFalse(viewModel.uiState.value.sealHandoverComplete)
    }
}
