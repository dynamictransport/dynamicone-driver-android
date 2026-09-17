package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

/** Trip lifecycle as defined in docs/03_STATE_MACHINE.md and backend/openapi.yaml (TripState). */
enum class TripState {
    ASSIGNED,
    ACCEPTED,
    STARTED,
    AT_PICKUP,
    PICKUP_COMPLETED,
    EN_ROUTE_DELIVERY,
    AT_DELIVERY,
    DELIVERY_COMPLETED,
    FINISHED,
    CANCELLED,

    /** A state this app version does not know (e.g. future backend-configured container flows). Never actionable. */
    UNKNOWN,
    ;

    val isTerminal: Boolean get() = this == FINISHED || this == CANCELLED

    val isInProgress: Boolean get() = this in IN_PROGRESS

    companion object {
        private val IN_PROGRESS = setOf(
            STARTED,
            AT_PICKUP,
            PICKUP_COMPLETED,
            EN_ROUTE_DELIVERY,
            AT_DELIVERY,
            DELIVERY_COMPLETED,
        )

        fun fromWire(value: String?): TripState = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** Driver actions on a trip (openapi TripActionType). */
enum class TripActionType {
    ACCEPT_TRIP,
    REJECT_TRIP,
    START_TRIP,
    ARRIVE_PICKUP,
    COMPLETE_PICKUP,
    START_DELIVERY_LEG,
    ARRIVE_DELIVERY,
    COMPLETE_DELIVERY,
    FINISH_TRIP,
    START_BREAK,
    END_BREAK,

    /** An action this app version cannot perform. Filtered out before it reaches the UI. */
    UNKNOWN,
    ;

    companion object {
        fun fromWire(value: String?): TripActionType = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** Who decides the trip's next step. */
enum class TripWorkflow {
    /** In-app demo backend: the local state machine (docs/03_STATE_MACHINE.md). */
    LOCAL_STATE_MACHINE,

    /** Driver API: the execution plan of this trip has not been read yet. Nothing is guessed meanwhile. */
    UNRESOLVED,

    /** Driver API: the steps are exactly the server's `availableActions`. */
    TRIP_ACTIONS,

    /** Driver API: a trip started before trip actions existed; it finishes through the status workflow. */
    LEGACY_STATUSES,
    ;

    /** The next step is read from the server plan instead of the local state machine. */
    val usesServerActions: Boolean get() = this == UNRESOLVED || this == TRIP_ACTIONS
}

data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * A planned location of a trip (master-data coordinates, not live vehicle tracking).
 * Missing coordinates stay null; a point is never invented.
 */
data class TripLocation(
    val stopId: String?,
    val name: String?,
    val coordinates: GeoPoint?,
)

data class TripContainer(
    val id: String?,
    val number: String?,
    val sealNumber: String?,
)

/** Break runs in parallel to the trip state and never changes it. */
enum class BreakState {
    NONE,
    ON_BREAK,
    ;

    companion object {
        fun fromWire(value: String?): BreakState = if (value == ON_BREAK.name) ON_BREAK else NONE
    }
}

/** Preset reject reasons. A reason is mandatory; OTHER additionally requires a short note. */
enum class RejectReason {
    VEHICLE_PROBLEM,
    LEGAL_DRIVING_TIME,
    HEALTH,
    WRONG_ASSIGNMENT,
    OTHER,
}

data class Trip(
    val id: String,
    val tripNo: String,
    val state: TripState,
    val breakState: BreakState,
    /** Null when the source has no stop information (e.g. Driver API trip list items). */
    val pickupName: String?,
    val deliveryName: String?,
    val plannedStartAt: Instant?,
    val containerNo: String?,
    val pickupAddress: String? = null,
    val deliveryAddress: String? = null,
    val vehiclePlate: String? = null,
    val trailerPlate: String? = null,
    val sealNo: String? = null,
    val instructions: String? = null,
    /**
     * Actions the server allows for [state]. `null` when unknown (list endpoint) or when a local
     * pending action has moved the trip past the server snapshot; local rules apply in that case.
     */
    val serverAllowedActions: Set<TripActionType>? = null,
    val syncState: SyncState = SyncState.SYNCED,
    val orderNumber: String? = null,
    /** Ordered stops with persisted planned/actual times, when loaded. */
    val stops: List<TripStop> = emptyList(),
    val workflow: TripWorkflow = TripWorkflow.LOCAL_STATE_MACHINE,
    /** First stop of the plan; null when unknown or the trip has no stop. */
    val origin: TripLocation? = null,
    /** Last stop of the plan. */
    val destination: TripLocation? = null,
    /** First stop that is not completed or skipped; null when unknown or the trip is completed. */
    val nextStop: TripLocation? = null,
    /** Every container of the trip, in backend order. */
    val containers: List<TripContainer> = emptyList(),
)

/** What happens at a stop (backend TripStopActivity). Unknown values are shown as OTHER. */
enum class StopActivity { PICKUP, LOAD, UNLOAD, DELIVER, EMPTY_RETURN, FUEL, REST, OTHER }

/** Backend TripStopStatus. UNKNOWN for values this app version does not know. */
enum class StopStatus { PLANNED, EN_ROUTE, ARRIVED, COMPLETED, SKIPPED, UNKNOWN }

/** A trip stop. Times are UTC instants from the backend and are shown in the device time zone. */
data class TripStop(
    val id: String,
    val sequence: Int,
    val activity: StopActivity,
    val status: StopStatus,
    val locationName: String?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val plannedArrivalAt: Instant?,
    val plannedDepartureAt: Instant?,
    val actualArrivalAt: Instant?,
    val actualDepartureAt: Instant?,
    val reference: String?,
) {
    /** Both coordinates of the planned location, or null when the master data has none. */
    val coordinates: GeoPoint?
        get() = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null
}

/** A driver action recorded on this device for a trip (used for the timeline). */
data class TripActionRecord(
    val action: TripActionType,
    val occurredAt: Instant,
    val syncState: SyncState,
)
