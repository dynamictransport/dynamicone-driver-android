package com.dynamictransport.driver.domain.statemachine

import com.dynamictransport.driver.domain.model.BreakState
import com.dynamictransport.driver.domain.model.Shift
import com.dynamictransport.driver.domain.model.Trip
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.model.TripState
import java.time.Instant

/** The single dominant action offered to the driver. */
sealed interface PrimaryAction {
    data object StartShift : PrimaryAction

    /** Opens the shift summary where ending is confirmed. */
    data object EndShift : PrimaryAction

    /** A new trip must be reviewed before it can be accepted. */
    data class ReviewNewTrip(val tripId: String) : PrimaryAction

    data class OnTrip(val tripId: String, val action: TripActionType) : PrimaryAction

    /** The trip follows the server plan: its options (e.g. one per container) are chosen on the trip screen. */
    data class ContinueTrip(val tripId: String) : PrimaryAction
}

data class ActionPlan(
    val primary: PrimaryAction?,
    /** Non-dominant trip actions (break, reject). */
    val secondary: List<PrimaryAction.OnTrip> = emptyList(),
    /** Progress step the backend currently does not allow (e.g. mandatory documents missing). */
    val blockedAction: TripActionType? = null,
)

data class HomePlan(
    val focusTrip: Trip?,
    val plan: ActionPlan,
    /** Newest unreviewed assignment, shown as an alert even while another trip is running. */
    val newTrip: Trip?,
)

/**
 * Derives the primary action from `nextAllowedActions` + local state machine.
 * UI never decides the next step with its own if/else chains.
 */
object NextActionResolver {

    private val primaryPriority = listOf(
        TripActionType.END_BREAK,
        TripActionType.ACCEPT_TRIP,
        TripActionType.START_TRIP,
        TripActionType.ARRIVE_PICKUP,
        TripActionType.COMPLETE_PICKUP,
        TripActionType.START_DELIVERY_LEG,
        TripActionType.ARRIVE_DELIVERY,
        TripActionType.COMPLETE_DELIVERY,
        TripActionType.FINISH_TRIP,
    )

    private val secondaryOrder = listOf(
        TripActionType.START_BREAK,
        TripActionType.REJECT_TRIP,
    )

    private val planOrder = compareBy<Trip, Instant?>(nullsLast()) { it.plannedStartAt }.thenBy { it.tripNo }

    fun forTrip(trip: Trip, shift: Shift): ActionPlan {
        if (trip.workflow.usesServerActions && trip.state != TripState.ASSIGNED) {
            // Accepting stays a local decision; every later step comes from the trip's execution plan.
            val open = trip.state == TripState.ACCEPTED || trip.state.isInProgress
            return ActionPlan(primary = PrimaryAction.ContinueTrip(trip.id).takeIf { open })
        }
        val snapshot = TripSnapshot(trip.state, trip.breakState)
        val allowed = TripStateMachine.allowedActions(snapshot, trip.serverAllowedActions)
        val secondary = secondaryOrder.filter { it in allowed }.map { PrimaryAction.OnTrip(trip.id, it) }
        val primaryType = primaryPriority.firstOrNull { it in allowed }

        if (primaryType == TripActionType.START_TRIP && shift.isTracked && !shift.isActive) {
            // A trip is started within an active shift (PROMPT_START_HERE.md order).
            return ActionPlan(primary = PrimaryAction.StartShift, secondary = secondary)
        }

        val progression = TripStateMachine.progressionAction(trip.state)
        val blocked = progression?.takeIf { primaryType == null && trip.breakState == BreakState.NONE && it !in allowed }
        return ActionPlan(
            primary = primaryType?.let { PrimaryAction.OnTrip(trip.id, it) },
            secondary = secondary,
            blockedAction = blocked,
        )
    }

    /** The trip the driver is working on (documents, incidents): the running one, else the next accepted one. */
    fun currentWorkTrip(trips: List<Trip>): Trip? =
        trips.filter { it.state.isInProgress }.sortedWith(planOrder).firstOrNull()
            ?: trips.filter { it.state == TripState.ACCEPTED }.sortedWith(planOrder).firstOrNull()

    fun forHome(shift: Shift, trips: List<Trip>): HomePlan {
        val inProgress = trips.filter { it.state.isInProgress }.sortedWith(planOrder).firstOrNull()
        val newTrip = trips.filter { it.state == TripState.ASSIGNED }.sortedWith(planOrder).firstOrNull()
        val accepted = trips.filter { it.state == TripState.ACCEPTED }.sortedWith(planOrder).firstOrNull()

        return when {
            inProgress != null -> HomePlan(inProgress, forTrip(inProgress, shift), newTrip)
            newTrip != null -> HomePlan(
                focusTrip = newTrip,
                plan = ActionPlan(primary = PrimaryAction.ReviewNewTrip(newTrip.id)),
                newTrip = newTrip,
            )
            shift.isTracked && !shift.isActive -> HomePlan(accepted, ActionPlan(primary = PrimaryAction.StartShift), null)
            accepted != null -> HomePlan(accepted, forTrip(accepted, shift), null)
            // Without backend shift support there is nothing to end: no fake shift action.
            else -> HomePlan(null, ActionPlan(primary = PrimaryAction.EndShift.takeIf { shift.isTracked }), null)
        }
    }
}
