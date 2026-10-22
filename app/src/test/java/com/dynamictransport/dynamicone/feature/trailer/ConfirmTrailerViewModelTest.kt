package com.dynamictransport.dynamicone.feature.trailer

import androidx.lifecycle.SavedStateHandle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import com.dynamictransport.dynamicone.feature.tripdetail.TRIP_ID_ARG
import com.dynamictransport.dynamicone.testing.FakeNetworkMonitor
import com.dynamictransport.dynamicone.testing.FakeTripExecutionRepository
import com.dynamictransport.dynamicone.testing.FakeTripRepository
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.keepCollecting
import com.dynamictransport.dynamicone.testing.testTrailer
import com.dynamictransport.dynamicone.testing.testTrailerState
import com.dynamictransport.dynamicone.testing.testTrip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConfirmTrailerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val trips = FakeTripRepository(
        listOf(testTrip(state = TripState.ACCEPTED, workflow = TripWorkflow.TRIP_ACTIONS)),
    )
    private val executions = FakeTripExecutionRepository()
    private val network = FakeNetworkMonitor()

    private val options = listOf(
        testTrailer("tr1", "WGM 45T2", "DYNA-120"),
        testTrailer("tr2", "WGM 88X1"),
        testTrailer("tr3", "WPR 10A9", "DYNA-77"),
    )

    private fun viewModel() = ConfirmTrailerViewModel(
        savedStateHandle = SavedStateHandle(mapOf(TRIP_ID_ARG to "trip-1")),
        tripRepository = trips,
        networkMonitor = network,
        executionRepository = executions,
    )

    @Test
    fun `the planned trailer is only marked and never confirmed for the driver`() = runTest {
        executions.trailers.value = testTrailerState(plannedTrailerId = "tr2", trailers = options)
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        val state = viewModel.uiState.value
        assertEquals("tr2", state.plannedId)
        assertNull(state.selectedId)
        assertFalse(state.canSubmit)
        // The planned trailer is offered first, the rest keeps the server's order.
        assertEquals(listOf("tr2", "tr1", "tr3"), state.options.map { it.id })
        assertEquals(1, executions.trailerRefreshes)

        viewModel.onSelect("tr1")
        assertTrue(viewModel.uiState.value.canSubmit)

        viewModel.onConfirm()
        assertEquals(listOf("trip-1" to "tr1"), executions.confirmedTrailers)
        assertTrue(viewModel.uiState.value.completed)
        assertEquals(R.string.trailer_confirmed_message, viewModel.uiState.value.message)
    }

    @Test
    fun `a recorded choice is shown and confirming it again is not offered`() = runTest {
        executions.trailers.value = testTrailerState(plannedTrailerId = "tr2", confirmedTrailerId = "tr1", trailers = options)
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        assertEquals("tr1", viewModel.uiState.value.selectedId)
        assertEquals("WGM 45T2", viewModel.uiState.value.confirmedOption?.registrationNumber)
        assertFalse(viewModel.uiState.value.canSubmit)

        // Changing the choice is possible until the trip starts.
        viewModel.onSelect("tr3")
        assertTrue(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `a trip that has started cannot choose any more`() = runTest {
        executions.trailers.value = testTrailerState(confirmedTrailerId = "tr1", canConfirm = false, trailers = options)
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onSelect("tr3")
        assertFalse(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `an empty list keeps the confirmation closed`() = runTest {
        executions.trailers.value = testTrailerState(trailers = emptyList())
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        assertTrue(viewModel.uiState.value.listEmpty)
        assertFalse(viewModel.uiState.value.canSubmit)
        assertFalse(viewModel.uiState.value.hasSearch)
    }

    @Test
    fun `search filters on registration and company code`() = runTest {
        executions.trailers.value = testTrailerState(trailers = options)
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onQueryChanged(" 88x ")
        assertEquals(listOf("tr2"), viewModel.uiState.value.visibleOptions.map { it.id })

        viewModel.onQueryChanged("dyna-77")
        assertEquals(listOf("tr3"), viewModel.uiState.value.visibleOptions.map { it.id })

        viewModel.onQueryChanged("WXX")
        assertTrue(viewModel.uiState.value.visibleOptions.isEmpty())
    }

    @Test
    fun `a refused confirmation is reported and the screen stays open`() = runTest {
        executions.trailers.value = testTrailerState(trailers = options)
        executions.confirmTrailerResult = ActionResult.Rejected(ActionRejection.PLAN_CHANGED)
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onSelect("tr1")
        viewModel.onConfirm()

        assertFalse(viewModel.uiState.value.completed)
        assertEquals(R.string.error_plan_changed, viewModel.uiState.value.message)
    }
}
