package com.dynamictransport.dynamicone.domain.rules

import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripState

object DocumentRules {

    /**
     * Evidence can be added once the trip is accepted, while it runs and after it is completed (missing
     * documents can be uploaded later). Never to a new, cancelled or unknown trip.
     */
    fun canUpload(trip: Trip): Boolean =
        trip.state == TripState.ACCEPTED || trip.state.isInProgress || trip.state == TripState.FINISHED

    const val PDF = "application/pdf"
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    val PHOTO_CONTENT_TYPES = setOf(JPEG, PNG)
}
