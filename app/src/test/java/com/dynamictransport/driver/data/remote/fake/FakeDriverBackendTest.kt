package com.dynamictransport.driver.data.remote.fake

import com.dynamictransport.driver.core.network.RemoteException
import com.dynamictransport.driver.domain.model.ShiftStatus
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.model.TripState
import com.dynamictransport.driver.testing.MutableClock
import com.dynamictransport.driver.testing.TEST_NOW
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset

class FakeDriverBackendTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val trip = "trp-261245"

    private fun backend(file: java.io.File? = null) =
        FakeDriverBackend(MutableClock(), json, stateFile = file, zone = ZoneOffset.UTC)

    @Test
    fun `the same clientActionId is applied exactly once`() = runTest {
        val backend = backend()
        backend.submitTripAction(trip, "action-1", TripActionType.ACCEPT_TRIP)
        val again = backend.submitTripAction(trip, "action-1", TripActionType.ACCEPT_TRIP)

        assertEquals(TripState.ACCEPTED, again.state)
        assertEquals(setOf("action-1"), backend.processedActionIds())
    }

    @Test
    fun `illegal transitions are answered with a conflict`() = runTest {
        val backend = backend()
        val error = runCatching { backend.submitTripAction(trip, "a", TripActionType.FINISH_TRIP) }.exceptionOrNull()
        assertTrue(error is RemoteException.Conflict)
    }

    @Test
    fun `a trip can only be started within an active shift`() = runTest {
        val backend = backend()
        backend.submitTripAction(trip, "accept", TripActionType.ACCEPT_TRIP)
        val error = runCatching { backend.submitTripAction(trip, "start", TripActionType.START_TRIP) }.exceptionOrNull()
        assertTrue(error is RemoteException.Conflict)

        backend.startShift("shift", TEST_NOW)
        assertEquals(TripState.STARTED, backend.submitTripAction(trip, "start-2", TripActionType.START_TRIP).state)
        assertEquals(ShiftStatus.ACTIVE, backend.currentShift().status)
    }

    @Test
    fun `detail exposes next allowed actions of the state machine`() = runTest {
        assertEquals(setOf(TripActionType.ACCEPT_TRIP, TripActionType.REJECT_TRIP), backend().trip(trip).serverAllowedActions)
    }

    @Test
    fun `simulated offline and wrong password fail like the real API`() = runTest {
        val backend = backend()
        assertTrue(runCatching { backend.login("driver", "wrong") }.exceptionOrNull() is RemoteException.Unauthorized)
        backend.simulateOffline.value = true
        assertTrue(runCatching { backend.trips() }.exceptionOrNull() is RemoteException.Offline)
    }

    @Test
    fun `demo state survives an app restart`() = runTest {
        val file = tempFolder.newFile("backend.json")
        file.delete()
        backend(file).submitTripAction(trip, "accept", TripActionType.ACCEPT_TRIP)

        assertEquals(TripState.ACCEPTED, backend(file).trip(trip).state)
    }
}
