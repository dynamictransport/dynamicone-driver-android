package com.dynamictransport.driver.domain.statemachine

import com.dynamictransport.driver.domain.model.BreakState
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.model.TripState

data class TripSnapshot(
    val state: TripState,
    val breakState: BreakState = BreakState.NONE,
)

sealed interface TransitionResult {
    data class Applied(val snapshot: TripSnapshot) : TransitionResult
    data class Illegal(val from: TripSnapshot, val action: TripActionType) : TransitionResult
}

/**
 * Single source of truth for trip transitions on the client (docs/03_STATE_MACHINE.md).
 *
 * Used to (1) guard against illegal actions coming from the backend, (2) project pending offline
 * actions on top of the last server snapshot and (3) reject duplicate taps.
 */
object TripStateMachine {

    private val progression: Map<TripState, Pair<TripActionType, TripState>> = mapOf(
        TripState.ASSIGNED to (TripActionType.ACCEPT_TRIP to TripState.ACCEPTED),
        TripState.ACCEPTED to (TripActionType.START_TRIP to TripState.STARTED),
        TripState.STARTED to (TripActionType.ARRIVE_PICKUP to TripState.AT_PICKUP),
        TripState.AT_PICKUP to (TripActionType.COMPLETE_PICKUP to TripState.PICKUP_COMPLETED),
        TripState.PICKUP_COMPLETED to (TripActionType.START_DELIVERY_LEG to TripState.EN_ROUTE_DELIVERY),
        TripState.EN_ROUTE_DELIVERY to (TripActionType.ARRIVE_DELIVERY to TripState.AT_DELIVERY),
        TripState.AT_DELIVERY to (TripActionType.COMPLETE_DELIVERY to TripState.DELIVERY_COMPLETED),
        TripState.DELIVERY_COMPLETED to (TripActionType.FINISH_TRIP to TripState.FINISHED),
    )

    /** The action that moves [state] forward, if any. */
    fun progressionAction(state: TripState): TripActionType? = progression[state]?.first

    fun legalActions(snapshot: TripSnapshot): Set<TripActionType> {
        if (snapshot.breakState == BreakState.ON_BREAK) {
            // While on break only ending the break is possible: no accidental "delivery completed".
            return setOf(TripActionType.END_BREAK)
        }
        return buildSet {
            progression[snapshot.state]?.let { add(it.first) }
            if (snapshot.state == TripState.ASSIGNED) add(TripActionType.REJECT_TRIP)
            if (snapshot.state.isInProgress) add(TripActionType.START_BREAK)
        }
    }

    fun transition(snapshot: TripSnapshot, action: TripActionType): TransitionResult {
        if (action !in legalActions(snapshot)) return TransitionResult.Illegal(snapshot, action)
        val next = when (action) {
            TripActionType.START_BREAK -> snapshot.copy(breakState = BreakState.ON_BREAK)
            TripActionType.END_BREAK -> snapshot.copy(breakState = BreakState.NONE)
            // The contract has no REJECTED state; a rejected trip leaves the driver's work (ASSUMPTIONS.md).
            TripActionType.REJECT_TRIP -> snapshot.copy(state = TripState.CANCELLED)
            else -> snapshot.copy(state = progression.getValue(snapshot.state).second)
        }
        return TransitionResult.Applied(next)
    }

    /**
     * Actions the driver may perform now: the server list guarded by local rules. When the server
     * list is unknown (or superseded by a pending local action) the local rules alone apply.
     */
    fun allowedActions(snapshot: TripSnapshot, serverAllowed: Set<TripActionType>?): Set<TripActionType> {
        val legal = legalActions(snapshot)
        return if (serverAllowed == null) legal else legal intersect serverAllowed
    }
}
