package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

/** openapi IncidentRequest.category */
enum class IncidentCategory {
    DELAY,
    TRAFFIC,
    TERMINAL_QUEUE,
    VEHICLE_BREAKDOWN,
    ACCIDENT,
    DOCUMENT,
    CONTAINER_SEAL,
    CUSTOMER,
    OTHER,
}

data class IncidentReport(
    val clientActionId: String,
    val tripId: String?,
    val category: IncidentCategory,
    val note: String?,
    val createdAt: Instant,
)
