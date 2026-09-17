package com.dynamictransport.driver.domain.statemachine

import com.dynamictransport.driver.domain.model.BreakState
import com.dynamictransport.driver.domain.model.Shift
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.model.TripState
import com.dynamictransport.driver.testing.ACTIVE_SHIFT
import com.dynamictransport.driver.testing.testTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class NextActionResolverTest {

    @Test
    fun `a new trip is reviewed first even before the shift is started`() {
        val trip = testTrip(state = TripState.ASSIGNED)
        val home = NextActionResolver.forHome(Shift.NotStarted, listOf(trip))
        assertEquals(PrimaryAction.ReviewNewTrip(trip.id), home.plan.primary)
        assertEquals(trip, home.newTrip)
    }

    @Test
    fun `accepted trip without an active shift asks to start the shift`() {
        val trip = testTrip(state = TripState.ACCEPTED)
        val home = NextActionResolver.forHome(Shift.NotStarted, listOf(trip))
        assertEquals(PrimaryAction.StartShift, home.plan.primary)
        assertEquals(trip, home.focusTrip)
        assertEquals(PrimaryAction.StartShift, NextActionResolver.forTrip(trip, Shift.NotStarted).primary)
    }

    @Test
    fun `with an active shift the accepted trip can be started`() {
        val trip = testTrip(state = TripState.ACCEPTED)
        val home = NextActionResolver.forHome(ACTIVE_SHIFT, listOf(trip))
        assertEquals(PrimaryAction.OnTrip(trip.id, TripActionType.START_TRIP), home.plan.primary)
    }

    @Test
    fun `primary action follows the whole trip lifecycle`() {
        val expected = mapOf(
            TripState.ASSIGNED to TripActionType.ACCEPT_TRIP,
            TripState.ACCEPTED to TripActionType.START_TRIP,
            TripState.STARTED to TripActionType.ARRIVE_PICKUP,
            TripState.AT_PICKUP to TripActionType.COMPLETE_PICKUP,
            TripState.PICKUP_COMPLETED to TripActionType.START_DELIVERY_LEG,
            TripState.EN_ROUTE_DELIVERY to TripActionType.ARRIVE_DELIVERY,
            TripState.AT_DELIVERY to TripActionType.COMPLETE_DELIVERY,
            TripState.DELIVERY_COMPLETED to TripActionType.FINISH_TRIP,
        )
        expected.forEach { (state, action) ->
            val trip = testTrip(state = state)
            assertEquals("state $state", PrimaryAction.OnTrip(trip.id, action), NextActionResolver.forTrip(trip, ACTIVE_SHIFT).primary)
        }
        assertNull(NextActionResolver.forTrip(testTrip(state = TripState.FINISHED), ACTIVE_SHIFT).primary)
        assertNull(NextActionResolver.forTrip(testTrip(state = TripState.CANCELLED), ACTIVE_SHIFT).primary)
    }

    @Test
    fun `a running trip stays dominant when another trip is assigned`() {
        val running = testTrip(id = "running", state = TripState.STARTED)
        val assigned = testTrip(id = "new", state = TripState.ASSIGNED, plannedStartAt = Instant.parse("2026-09-12T06:00:00Z"))
        val home = NextActionResolver.forHome(ACTIVE_SHIFT, listOf(assigned, running))
        assertEquals(PrimaryAction.OnTrip("running", TripActionType.ARRIVE_PICKUP), home.plan.primary)
        assertEquals(assigned, home.newTrip)
    }

    @Test
    fun `nothing left to do offers ending the shift`() {
        val home = NextActionResolver.forHome(ACTIVE_SHIFT, listOf(testTrip(state = TripState.FINISHED)))
        assertEquals(PrimaryAction.EndShift, home.plan.primary)
        assertNull(home.focusTrip)
    }

    @Test
    fun `break makes end break the only dominant action`() {
        val trip = testTrip(state = TripState.EN_ROUTE_DELIVERY, breakState = BreakState.ON_BREAK)
        val plan = NextActionResolver.forTrip(trip, ACTIVE_SHIFT)
        assertEquals(PrimaryAction.OnTrip(trip.id, TripActionType.END_BREAK), plan.primary)
        assertEquals(emptyList<PrimaryAction.OnTrip>(), plan.secondary)
    }

    @Test
    fun `break and reject are secondary actions only`() {
        val running = testTrip(state = TripState.STARTED)
        assertEquals(
            listOf(PrimaryAction.OnTrip(running.id, TripActionType.START_BREAK)),
            NextActionResolver.forTrip(running, ACTIVE_SHIFT).secondary,
        )
        val assigned = testTrip(state = TripState.ASSIGNED, serverAllowedActions = setOf(TripActionType.ACCEPT_TRIP, TripActionType.REJECT_TRIP))
        val plan = NextActionResolver.forTrip(assigned, ACTIVE_SHIFT)
        assertEquals(PrimaryAction.OnTrip(assigned.id, TripActionType.ACCEPT_TRIP), plan.primary)
        assertEquals(listOf(PrimaryAction.OnTrip(assigned.id, TripActionType.REJECT_TRIP)), plan.secondary)
    }

    @Test
    fun `reject is hidden when the backend does not allow it`() {
        val assigned = testTrip(state = TripState.ASSIGNED, serverAllowedActions = setOf(TripActionType.ACCEPT_TRIP))
        assertEquals(emptyList<PrimaryAction.OnTrip>(), NextActionResolver.forTrip(assigned, ACTIVE_SHIFT).secondary)
    }

    @Test
    fun `step blocked by the backend is reported instead of hidden`() {
        val trip = testTrip(state = TripState.DELIVERY_COMPLETED, serverAllowedActions = setOf(TripActionType.START_BREAK))
        val plan = NextActionResolver.forTrip(trip, ACTIVE_SHIFT)
        assertNull(plan.primary)
        assertEquals(TripActionType.FINISH_TRIP, plan.blockedAction)
    }

    @Test
    fun `illegal action sent by the backend is ignored`() {
        val trip = testTrip(state = TripState.ASSIGNED, serverAllowedActions = setOf(TripActionType.FINISH_TRIP))
        val plan = NextActionResolver.forTrip(trip, ACTIVE_SHIFT)
        assertNull(plan.primary)
        assertEquals(TripActionType.ACCEPT_TRIP, plan.blockedAction)
    }

    @Test
    fun `current work trip prefers the running trip over accepted ones`() {
        val accepted = testTrip(id = "accepted", state = TripState.ACCEPTED, plannedStartAt = Instant.parse("2026-09-12T05:00:00Z"))
        val running = testTrip(id = "running", state = TripState.AT_PICKUP)
        assertEquals(running, NextActionResolver.currentWorkTrip(listOf(accepted, running)))
        assertEquals(accepted, NextActionResolver.currentWorkTrip(listOf(accepted, testTrip(id = "new"))))
        assertNull(NextActionResolver.currentWorkTrip(listOf(testTrip(id = "new"))))
    }
}
