package com.dynamictransport.driver.domain.statemachine

import com.dynamictransport.driver.domain.model.BreakState
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.model.TripActionType.ACCEPT_TRIP
import com.dynamictransport.driver.domain.model.TripActionType.ARRIVE_DELIVERY
import com.dynamictransport.driver.domain.model.TripActionType.ARRIVE_PICKUP
import com.dynamictransport.driver.domain.model.TripActionType.COMPLETE_DELIVERY
import com.dynamictransport.driver.domain.model.TripActionType.COMPLETE_PICKUP
import com.dynamictransport.driver.domain.model.TripActionType.END_BREAK
import com.dynamictransport.driver.domain.model.TripActionType.FINISH_TRIP
import com.dynamictransport.driver.domain.model.TripActionType.REJECT_TRIP
import com.dynamictransport.driver.domain.model.TripActionType.START_BREAK
import com.dynamictransport.driver.domain.model.TripActionType.START_DELIVERY_LEG
import com.dynamictransport.driver.domain.model.TripActionType.START_TRIP
import com.dynamictransport.driver.domain.model.TripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStateMachineTest {

    private fun applied(snapshot: TripSnapshot, action: TripActionType): TripSnapshot {
        val result = TripStateMachine.transition(snapshot, action)
        assertTrue("$action must be legal in $snapshot", result is TransitionResult.Applied)
        return (result as TransitionResult.Applied).snapshot
    }

    @Test
    fun `happy path walks every documented state in order`() {
        val path = listOf(
            ACCEPT_TRIP to TripState.ACCEPTED,
            START_TRIP to TripState.STARTED,
            ARRIVE_PICKUP to TripState.AT_PICKUP,
            COMPLETE_PICKUP to TripState.PICKUP_COMPLETED,
            START_DELIVERY_LEG to TripState.EN_ROUTE_DELIVERY,
            ARRIVE_DELIVERY to TripState.AT_DELIVERY,
            COMPLETE_DELIVERY to TripState.DELIVERY_COMPLETED,
            FINISH_TRIP to TripState.FINISHED,
        )
        var snapshot = TripSnapshot(TripState.ASSIGNED)
        path.forEach { (action, expected) ->
            snapshot = applied(snapshot, action)
            assertEquals(expected, snapshot.state)
        }
    }

    @Test
    fun `each state allows exactly its documented actions`() {
        val expected = mapOf(
            TripState.ASSIGNED to setOf(ACCEPT_TRIP, REJECT_TRIP),
            TripState.ACCEPTED to setOf(START_TRIP),
            TripState.STARTED to setOf(ARRIVE_PICKUP, START_BREAK),
            TripState.AT_PICKUP to setOf(COMPLETE_PICKUP, START_BREAK),
            TripState.PICKUP_COMPLETED to setOf(START_DELIVERY_LEG, START_BREAK),
            TripState.EN_ROUTE_DELIVERY to setOf(ARRIVE_DELIVERY, START_BREAK),
            TripState.AT_DELIVERY to setOf(COMPLETE_DELIVERY, START_BREAK),
            TripState.DELIVERY_COMPLETED to setOf(FINISH_TRIP, START_BREAK),
            TripState.FINISHED to emptySet(),
            TripState.CANCELLED to emptySet(),
            TripState.UNKNOWN to emptySet(),
        )
        TripState.entries.forEach { state ->
            assertEquals("state $state", expected.getValue(state), TripStateMachine.legalActions(TripSnapshot(state)))
        }
    }

    @Test
    fun `every state and action combination is either applied or illegal as declared`() {
        TripState.entries.forEach { state ->
            BreakState.entries.forEach { breakState ->
                val snapshot = TripSnapshot(state, breakState)
                val legal = TripStateMachine.legalActions(snapshot)
                TripActionType.entries.forEach { action ->
                    val result = TripStateMachine.transition(snapshot, action)
                    assertEquals("$action in $snapshot", action in legal, result is TransitionResult.Applied)
                }
            }
        }
    }

    @Test
    fun `break keeps the trip state and blocks every progress action`() {
        val onBreak = applied(TripSnapshot(TripState.AT_DELIVERY), START_BREAK)
        assertEquals(TripSnapshot(TripState.AT_DELIVERY, BreakState.ON_BREAK), onBreak)
        assertEquals(setOf(END_BREAK), TripStateMachine.legalActions(onBreak))
        assertTrue(TripStateMachine.transition(onBreak, COMPLETE_DELIVERY) is TransitionResult.Illegal)

        assertEquals(TripSnapshot(TripState.AT_DELIVERY, BreakState.NONE), applied(onBreak, END_BREAK))
    }

    @Test
    fun `repeating an applied action is illegal so a double tap cannot progress twice`() {
        val accepted = applied(TripSnapshot(TripState.ASSIGNED), ACCEPT_TRIP)
        assertTrue(TripStateMachine.transition(accepted, ACCEPT_TRIP) is TransitionResult.Illegal)
        val onBreak = applied(TripSnapshot(TripState.STARTED), START_BREAK)
        assertTrue(TripStateMachine.transition(onBreak, START_BREAK) is TransitionResult.Illegal)
    }

    @Test
    fun `server allowed actions are guarded by local rules`() {
        val assigned = TripSnapshot(TripState.ASSIGNED)
        assertEquals(setOf(ACCEPT_TRIP), TripStateMachine.allowedActions(assigned, setOf(ACCEPT_TRIP, FINISH_TRIP)))
        assertEquals(emptySet<TripActionType>(), TripStateMachine.allowedActions(assigned, emptySet()))
        assertEquals(TripStateMachine.legalActions(assigned), TripStateMachine.allowedActions(assigned, null))
    }

    @Test
    fun `rejecting an assigned trip removes it from the driver's work`() {
        assertEquals(TripState.CANCELLED, applied(TripSnapshot(TripState.ASSIGNED), REJECT_TRIP).state)
        assertTrue(TripStateMachine.transition(TripSnapshot(TripState.ACCEPTED), REJECT_TRIP) is TransitionResult.Illegal)
    }

    @Test
    fun `unknown wire values never crash and are not actionable`() {
        assertEquals(TripState.UNKNOWN, TripState.fromWire("PORT_GATE_IN"))
        assertEquals(TripActionType.UNKNOWN, TripActionType.fromWire(null))
        assertEquals(BreakState.NONE, BreakState.fromWire("SOMETHING"))
        assertEquals(emptySet<TripActionType>(), TripStateMachine.legalActions(TripSnapshot(TripState.UNKNOWN)))
    }
}
