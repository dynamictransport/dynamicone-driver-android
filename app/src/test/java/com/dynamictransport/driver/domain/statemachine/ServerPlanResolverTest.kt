package com.dynamictransport.driver.domain.statemachine

import com.dynamictransport.driver.domain.model.Shift
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.model.TripState
import com.dynamictransport.driver.domain.model.TripWorkflow
import com.dynamictransport.driver.testing.testTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Trips whose steps come from the server plan: the home screen never decides a step locally. */
class ServerPlanResolverTest {

    @Test
    fun `accepted and running trip action trips continue on the trip screen`() {
        listOf(TripState.ACCEPTED, TripState.STARTED).forEach { state ->
            listOf(TripWorkflow.TRIP_ACTIONS, TripWorkflow.UNRESOLVED).forEach { workflow ->
                val trip = testTrip(state = state, workflow = workflow, serverAllowedActions = emptySet())
                assertEquals(ActionPlan(primary = PrimaryAction.ContinueTrip(trip.id)), NextActionResolver.forTrip(trip, Shift.Unavailable))
            }
        }
    }

    @Test
    fun `a new assignment is still accepted first`() {
        val trip = testTrip(state = TripState.ASSIGNED, workflow = TripWorkflow.UNRESOLVED, serverAllowedActions = setOf(TripActionType.ACCEPT_TRIP))
        assertEquals(PrimaryAction.OnTrip(trip.id, TripActionType.ACCEPT_TRIP), NextActionResolver.forTrip(trip, Shift.Unavailable).primary)
    }

    @Test
    fun `closed or unknown trips offer nothing`() {
        listOf(TripState.FINISHED, TripState.CANCELLED, TripState.UNKNOWN).forEach { state ->
            assertNull(NextActionResolver.forTrip(testTrip(state = state, workflow = TripWorkflow.TRIP_ACTIONS), Shift.Unavailable).primary)
        }
    }

    @Test
    fun `legacy trips keep the status plan and home shows the running trip`() {
        val legacy = testTrip(state = TripState.AT_PICKUP, workflow = TripWorkflow.LEGACY_STATUSES, serverAllowedActions = setOf(TripActionType.COMPLETE_PICKUP))
        assertEquals(PrimaryAction.OnTrip(legacy.id, TripActionType.COMPLETE_PICKUP), NextActionResolver.forTrip(legacy, Shift.Unavailable).primary)

        val running = testTrip(id = "running", state = TripState.STARTED, workflow = TripWorkflow.TRIP_ACTIONS)
        val home = NextActionResolver.forHome(Shift.Unavailable, listOf(running))
        assertEquals(running, home.focusTrip)
        assertEquals(PrimaryAction.ContinueTrip("running"), home.plan.primary)
    }
}
