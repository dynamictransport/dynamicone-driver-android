package com.dynamictransport.dynamicone.core.network

import kotlinx.serialization.Serializable

// Last position of the driver's assigned vehicle (docs/19_ASSIGNED_VEHICLE_POSITION.md, proposed contract).
// Every field is optional: a missing field never crashes the app, the mapping decides what is usable.

@Serializable
data class VehiclePositionDto(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val positionTimeUtc: String? = null,
    val receivedAtUtc: String? = null,
) {
    // A vehicle position is personal data of its driver: keep it out of logs.
    override fun toString(): String = "VehiclePositionDto(positionTimeUtc=$positionTimeUtc)"
}

@Serializable
data class AssignedVehiclePositionResponseDto(
    val status: String? = null,
    val vehicle: AssignedVehicleDto? = null,
    val position: VehiclePositionDto? = null,
)

/** Wire names of docs/19 (case-sensitive text, never numbers). */
object VehiclePositionWireNames {
    const val AVAILABLE = "Available"
    const val NOT_ASSIGNED = "NotAssigned"
    const val NOT_LINKED = "NotLinked"
    const val NO_DATA = "NoData"
}
