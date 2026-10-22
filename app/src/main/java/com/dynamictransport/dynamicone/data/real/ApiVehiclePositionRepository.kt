package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.network.AssignedVehiclePositionResponseDto
import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.VehiclePositionDto
import com.dynamictransport.dynamicone.core.network.VehiclePositionWireNames
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.VehiclePosition
import com.dynamictransport.dynamicone.domain.model.VehiclePositionState
import com.dynamictransport.dynamicone.domain.repository.VehiclePositionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last position of the driver's assigned vehicle (docs/19). A vehicle position is personal data of its driver, so it is
 * kept in memory only: it is gone after a restart, on sign-out and when another driver signs in.
 */
@Singleton
class ApiVehiclePositionRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
    private val sessionPreferences: SessionPreferences,
) : VehiclePositionRepository {

    /** The last answer and read outcome of one driver; another driver's entry is never shown. */
    private data class Entry(val driverId: String, val answer: VehiclePositionState?, val failed: Boolean)

    private val entry = MutableStateFlow<Entry?>(null)
    private val reading = Mutex()

    override fun observePosition(): Flow<VehiclePositionState> =
        combine(sessionPreferences.driverIdChanges, entry) { driverId, current ->
            val own = current?.takeIf { driverId != null && it.driverId == driverId }
            own?.answer ?: if (own?.failed == true) VehiclePositionState.Unavailable else VehiclePositionState.Loading
        }.distinctUntilChanged()

    override suspend fun refresh(): RefreshResult {
        val driverId = sessionPreferences.driverId() ?: return RefreshResult.SESSION_EXPIRED
        // The server has no endpoint: asked again only after the app starts anew (docs/19 §3).
        if (entry.value?.takeIf { it.driverId == driverId }?.answer == VehiclePositionState.NotSupported) {
            return RefreshResult.NOT_SUPPORTED
        }
        if (!reading.tryLock()) return RefreshResult.SUCCESS
        try {
            var answer: VehiclePositionState? = null
            val result = driverRead(tracker) {
                answer = VehiclePositionMapping.state(tracker.call { api.assignedVehiclePosition() })
            }
            // An answer that arrives after another driver signed in belongs to nobody on screen.
            if (sessionPreferences.driverId() != driverId) return result
            when (result) {
                RefreshResult.SUCCESS -> entry.value = Entry(driverId, answer, failed = false)
                RefreshResult.NOT_SUPPORTED -> entry.value = Entry(driverId, VehiclePositionState.NotSupported, failed = false)
                // The last answer of this driver stays; without one the failure is shown.
                else -> entry.update { current ->
                    current?.takeIf { it.driverId == driverId }?.copy(failed = true) ?: Entry(driverId, null, failed = true)
                }
            }
            return result
        } finally {
            reading.unlock()
        }
    }

    fun clear() {
        entry.value = null
    }
}

object VehiclePositionMapping {

    /** A status this app version does not know shows as "no position", never as a failure the driver could retry. */
    fun state(dto: AssignedVehiclePositionResponseDto): VehiclePositionState = when (dto.status) {
        VehiclePositionWireNames.NOT_ASSIGNED -> VehiclePositionState.NotAssigned
        VehiclePositionWireNames.NOT_LINKED -> VehiclePositionState.NotLinked
        VehiclePositionWireNames.AVAILABLE -> dto.position?.let(::position)
            ?.let { VehiclePositionState.Known(dto.vehicle?.vehicleId?.takeIf(String::isNotBlank), it) }
            ?: VehiclePositionState.NoData
        else -> VehiclePositionState.NoData
    }

    /** Null without a time or with coordinates outside WGS84: a point is never invented. */
    fun position(dto: VehiclePositionDto): VehiclePosition? {
        val latitude = dto.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val longitude = dto.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        val time = dto.positionTimeUtc?.toInstantOrNull() ?: return null
        return VehiclePosition(GeoPoint(latitude, longitude), time, dto.receivedAtUtc?.toInstantOrNull())
    }
}
