package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

/** The last position fleet tracking reported for a vehicle (docs/19). Not live tracking of the phone. */
data class VehiclePosition(
    val point: GeoPoint,
    /** When the vehicle was at [point]; a parked vehicle keeps an old time. */
    val positionTime: Instant,
    val receivedAt: Instant?,
)

/** What the app knows about the position of the signed-in driver's assigned vehicle. */
sealed interface VehiclePositionState {
    /** Not read yet in this session. */
    data object Loading : VehiclePositionState

    /** The server has no position endpoint (yet): nothing is shown. */
    data object NotSupported : VehiclePositionState

    /** The read failed and no earlier answer of this session is known. */
    data object Unavailable : VehiclePositionState

    data object NotAssigned : VehiclePositionState

    /** The vehicle is not connected to fleet tracking. */
    data object NotLinked : VehiclePositionState

    /** Connected, but fleet tracking has not reported a position with coordinates. */
    data object NoData : VehiclePositionState

    /** [vehicleId] identifies the vehicle the server answered for (it must match the one on screen). */
    data class Known(val vehicleId: String?, val position: VehiclePosition) : VehiclePositionState
}
