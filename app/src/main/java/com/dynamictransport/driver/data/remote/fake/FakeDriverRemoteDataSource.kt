package com.dynamictransport.driver.data.remote.fake

import com.dynamictransport.driver.data.remote.DriverActionRequest
import com.dynamictransport.driver.data.remote.DriverRemoteDataSource
import com.dynamictransport.driver.data.remote.RemoteTripSummary
import com.dynamictransport.driver.data.remote.TripActionRequest
import com.dynamictransport.driver.domain.model.AuthTokens
import com.dynamictransport.driver.domain.model.DocumentUploadMetadata
import com.dynamictransport.driver.domain.model.Driver
import com.dynamictransport.driver.domain.model.IncidentReport
import com.dynamictransport.driver.domain.model.RemoteDocument
import com.dynamictransport.driver.domain.model.Shift
import com.dynamictransport.driver.domain.model.Trip
import java.io.File
import java.time.Instant
import javax.inject.Inject

class FakeDriverRemoteDataSource @Inject constructor(
    private val backend: FakeDriverBackend,
) : DriverRemoteDataSource {

    override suspend fun login(username: String, password: String): AuthTokens = backend.login(username, password)

    override suspend fun getProfile(): Driver = backend.profile()

    override suspend fun getCurrentShift(): Shift = backend.currentShift()

    override suspend fun startShift(request: DriverActionRequest): Shift? =
        backend.startShift(request.clientActionId, request.clientTimestamp)

    override suspend fun endShift(request: DriverActionRequest): Shift? =
        backend.endShift(request.clientActionId, request.clientTimestamp)

    override suspend fun getTrips(): List<RemoteTripSummary> = backend.trips().map { trip ->
        RemoteTripSummary(
            id = trip.id,
            tripNo = trip.tripNo,
            state = trip.state,
            pickupName = trip.pickupName,
            deliveryName = trip.deliveryName,
            plannedStartAt = trip.plannedStartAt?.let(Instant::ofEpochMilli),
            containerNo = trip.containerNo,
        )
    }

    override suspend fun getTrip(tripId: String): Trip = backend.trip(tripId)

    override suspend fun submitTripAction(tripId: String, request: TripActionRequest): Trip =
        backend.submitTripAction(tripId, request.clientActionId, request.action)

    override suspend fun getDocuments(tripId: String): List<RemoteDocument> = backend.documents(tripId)

    override suspend fun uploadDocument(metadata: DocumentUploadMetadata, file: File): RemoteDocument =
        backend.uploadDocument(metadata)


    override suspend fun createIncident(report: IncidentReport) = backend.createIncident(report)
}
