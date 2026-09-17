package com.dynamictransport.dynamicone.feature.tripdetail

import androidx.annotation.StringRes
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionRecord
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import java.time.Instant

enum class TimelineStepKind(@param:StringRes val label: Int) {
    ASSIGNED(R.string.timeline_assigned),
    ACCEPTED(R.string.timeline_accepted),
    STARTED(R.string.timeline_started),
    PICKUP(R.string.timeline_pickup),
    DELIVERY(R.string.timeline_delivery),
    COMPLETED(R.string.timeline_completed),
}

enum class StepStatus { DONE, CURRENT, UPCOMING }

data class TimelineStep(
    val kind: TimelineStepKind,
    val status: StepStatus,
    val occurredAt: Instant?,
    val syncState: SyncState?,
)

/** docs/02_SCREEN_SPEC.md §5 timeline, derived from the trip state and locally recorded actions. */
object TripTimeline {

    private val progressOrder = listOf(
        TripState.ASSIGNED,
        TripState.ACCEPTED,
        TripState.STARTED,
        TripState.AT_PICKUP,
        TripState.PICKUP_COMPLETED,
        TripState.EN_ROUTE_DELIVERY,
        TripState.AT_DELIVERY,
        TripState.DELIVERY_COMPLETED,
        TripState.FINISHED,
    )

    private data class StepDefinition(val kind: TimelineStepKind, val doneFrom: TripState, val action: TripActionType?)

    private val definitions = listOf(
        StepDefinition(TimelineStepKind.ASSIGNED, TripState.ASSIGNED, null),
        StepDefinition(TimelineStepKind.ACCEPTED, TripState.ACCEPTED, TripActionType.ACCEPT_TRIP),
        StepDefinition(TimelineStepKind.STARTED, TripState.STARTED, TripActionType.START_TRIP),
        StepDefinition(TimelineStepKind.PICKUP, TripState.PICKUP_COMPLETED, TripActionType.COMPLETE_PICKUP),
        StepDefinition(TimelineStepKind.DELIVERY, TripState.DELIVERY_COMPLETED, TripActionType.COMPLETE_DELIVERY),
        StepDefinition(TimelineStepKind.COMPLETED, TripState.FINISHED, TripActionType.FINISH_TRIP),
    )

    fun build(trip: Trip, history: List<TripActionRecord>): List<TimelineStep> {
        val reached = progressOrder.indexOf(trip.state)
        if (reached < 0) return emptyList() // cancelled or unknown flow
        var currentAssigned = false
        return definitions.map { definition ->
            val done = reached >= progressOrder.indexOf(definition.doneFrom)
            val record = definition.action?.let { action -> history.lastOrNull { it.action == action } }
            val status = when {
                done -> StepStatus.DONE
                !currentAssigned -> StepStatus.CURRENT.also { currentAssigned = true }
                else -> StepStatus.UPCOMING
            }
            TimelineStep(definition.kind, status, record?.occurredAt, record?.syncState)
        }
    }
}
