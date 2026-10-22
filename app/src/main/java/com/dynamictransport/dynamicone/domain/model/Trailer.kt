package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

/**
 * Trailer choice of an accepted trip (docs/20_DRIVER_TRAILER_CONFIRMATION.md). The driver picks the
 * trailer that is actually under the truck from the office's active list: no photo, no OCR and no
 * free registration text. A trip that has started keeps its choice.
 */

/** One option of `GET trips/{tripId}/trailers`. */
data class TrailerOption(
    val id: String,
    val registrationNumber: String,
    /** Fleet code of the owning company, when the master data has one. */
    val companyCode: String?,
) {
    /** Registration and company code as one searchable line. */
    val searchText: String get() = listOfNotNull(registrationNumber, companyCode).joinToString(" ")
}

/** The trailer state of one trip: what was planned, what the driver confirmed and what can be chosen. */
data class TripTrailerState(
    val tripId: String,
    /** Opaque execution version of the same read; sent back as `expectedVersion`. */
    val version: String,
    val plannedTrailerId: String?,
    val confirmedTrailerId: String?,
    val confirmedAt: Instant?,
    /** The server accepts a (new) confirmation: an accepted trip that has not started. */
    val canConfirm: Boolean,
    /** Active trailers, in the server's order. Empty means the office has to be called. */
    val trailers: List<TrailerOption>,
) {
    val planned: TrailerOption? get() = trailers.firstOrNull { it.id == plannedTrailerId }

    /** Null when the confirmed trailer is no longer active: the driver then has to choose again. */
    val confirmed: TrailerOption? get() = trailers.firstOrNull { it.id == confirmedTrailerId }

    /** Options for a screen: the planned trailer first, then the server's order. */
    val ordered: List<TrailerOption>
        get() = trailers.sortedBy { if (it.id == plannedTrailerId) 0 else 1 }
}
