package com.dynamictransport.dynamicone.domain.statemachine

import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import com.dynamictransport.dynamicone.domain.repository.ShiftRepository
import com.dynamictransport.dynamicone.domain.usecase.EndShiftUseCase
import com.dynamictransport.dynamicone.testing.ACTIVE_SHIFT
import com.dynamictransport.dynamicone.testing.FakeShiftRepository
import com.dynamictransport.dynamicone.testing.FakeTripRepository
import com.dynamictransport.dynamicone.testing.testTrip
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Daily shift (docs/16): after the daily rest the shift is started at the vehicle before trip work continues. */
class DailyShiftActionTest {

    private val dailyOff = Shift.NotStarted.copy(requiresVehicleCheck = true)
    private val dailyActive = ACTIVE_SHIFT.copy(requiresVehicleCheck = true)

    @Test
    fun `after the rest the running trip waits for the shift start`() {
        val running = testTrip(state = TripState.STARTED, workflow = TripWorkflow.TRIP_ACTIONS)
        val home = NextActionResolver.forHome(dailyOff, listOf(running))
        assertEquals(PrimaryAction.StartShift, home.plan.primary)
        assertEquals(running, home.focusTrip)
        assertEquals(PrimaryAction.StartShift, NextActionResolver.forTrip(running, dailyOff).primary)
    }

    @Test
    fun `with the daily shift running the trip plan applies again`() {
        val running = testTrip(state = TripState.STARTED, workflow = TripWorkflow.TRIP_ACTIONS)
        assertEquals(PrimaryAction.ContinueTrip(running.id), NextActionResolver.forHome(dailyActive, listOf(running)).plan.primary)
    }

    @Test
    fun `the shift start comes first, a new assignment stays visible`() {
        val newTrip = testTrip(id = "new", state = TripState.ASSIGNED)
        val home = NextActionResolver.forHome(dailyOff, listOf(newTrip))
        assertEquals(PrimaryAction.StartShift, home.plan.primary)
        assertEquals(newTrip, home.newTrip)
        // Accepting is independent of the shift (A3).
        assertEquals(PrimaryAction.OnTrip(newTrip.id, TripActionType.ACCEPT_TRIP), NextActionResolver.forTrip(newTrip, dailyOff).primary)
    }

    @Test
    fun `without the daily shift nothing changes`() {
        val running = testTrip(state = TripState.STARTED, workflow = TripWorkflow.TRIP_ACTIONS)
        assertEquals(PrimaryAction.ContinueTrip(running.id), NextActionResolver.forHome(Shift.Unavailable, listOf(running)).plan.primary)
    }

    @Test
    fun `a daily shift ends for the rest also in the middle of a trip`() = runBlocking {
        val trips = FakeTripRepository(listOf(testTrip(state = TripState.STARTED)))
        val daily = object : ShiftRepository by FakeShiftRepository(dailyActive) {
            override val requiresVehicleCheck: Boolean = true
        }
        assertEquals(ActionResult.Queued, EndShiftUseCase(daily, trips)())

        val simple = FakeShiftRepository(ACTIVE_SHIFT)
        assertEquals(ActionResult.Rejected(ActionRejection.TRIP_IN_PROGRESS), EndShiftUseCase(simple, trips)())
        assertEquals(ShiftStatus.ACTIVE, simple.shift.value.status)
    }
}
