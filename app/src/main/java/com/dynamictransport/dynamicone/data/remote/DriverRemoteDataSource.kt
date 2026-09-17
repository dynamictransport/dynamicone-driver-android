package com.dynamictransport.dynamicone.data.remote

import com.dynamictransport.dynamicone.domain.model.AuthTokens
import com.dynamictransport.dynamicone.domain.model.DocumentUploadMetadata
import com.dynamictransport.dynamicone.domain.model.Driver
import com.dynamictransport.dynamicone.domain.model.IncidentReport
import com.dynamictransport.dynamicone.domain.model.RemoteDocument
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import java.io.File
import java.time.Instant

data class DriverActionRequest(
    val clientActionId: String,
    val clientTimestamp: Instant,
)

data class TripActionRequest(
    val clientActionId: String,
    val clientTimestamp: Instant,
    val action: TripActionType,
    val reasonCode: String?,
    val note: String?,
)

/** `GET /trips` item: no break state and no allowed actions. */
data class RemoteTripSummary(
    val id: String,
    val tripNo: String,
    val state: TripState,
    val pickupName: String,
    val deliveryName: String,
    val plannedStartAt: Instant?,
    val containerNo: String?,
)

/**
 * The DynamicONE Driver API as seen by the data layer. Implemented by the Retrofit client and by
 * the in-app fake backend. All failures are reported as [com.dynamictransport.dynamicone.core.network.RemoteException].
 */
interface DriverRemoteDataSource {
    suspend fun login(username: String, password: String): AuthTokens

    suspend fun getProfile(): Driver

    suspend fun getCurrentShift(): Shift

    /** Returns the updated shift when the server includes it (openapi currently returns no body). */
    suspend fun startShift(request: DriverActionRequest): Shift?

    suspend fun endShift(request: DriverActionRequest): Shift?

    suspend fun getTrips(): List<RemoteTripSummary>

    suspend fun getTrip(tripId: String): Trip

    suspend fun submitTripAction(tripId: String, request: TripActionRequest): Trip

    suspend fun getDocuments(tripId: String): List<RemoteDocument>

    suspend fun uploadDocument(metadata: DocumentUploadMetadata, file: File): RemoteDocument?

    suspend fun createIncident(report: IncidentReport)
}
