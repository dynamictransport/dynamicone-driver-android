package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.domain.model.UserProfile
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the real data layer keeps for the signed-in account, cleared in one place. */
@Singleton
class RealSessionData @Inject constructor(
    private val trips: RealTripRepository,
    private val executions: RealTripExecutionRepository,
    private val documents: RealDocumentRepository,
    private val payroll: RealPayrollRepository,
    private val signedDocuments: RealSignedDocumentRepository,
    private val messages: RealMessageRepository,
    private val sync: RealSyncRepository,
    private val downloader: ProtectedFileDownloader,
    private val commands: PendingCommandStore,
    private val attendance: RealAttendanceRepository,
    private val vehiclePosition: ApiVehiclePositionRepository,
    private val myDocuments: RealMyDocumentRepository,
    private val vehicleDocuments: RealVehicleDocumentRepository,
    private val driverDay: DriverDaySessionData = DriverDaySessionData.None,
    private val hrDocuments: HrDocumentSessionData = HrDocumentSessionData.None,
) {
    /** Server data held in memory plus temporary viewing files. */
    suspend fun clearServerData() {
        trips.clear()
        executions.clear()
        documents.clear()
        payroll.clear()
        signedDocuments.clear()
        messages.clear()
        sync.reset()
        downloader.clear()
        attendance.clear()
        vehiclePosition.clear()
        myDocuments.clear()
        vehicleDocuments.clear()
        driverDay.clear()
        hrDocuments.clear()
    }

    /** Signing out or changing the password removes the driver's unconfirmed commands too. */
    suspend fun clearStoredCommands() = commands.clear()

    /**
     * An account signed in on this device: earlier accounts' driver commands and attendance punches must never be sent
     * with this session. An account without the driver module keeps no driver commands.
     */
    suspend fun keepStoredDataOf(profile: UserProfile) {
        val driverId = profile.driverId
        if (driverId == null) commands.clear() else commands.keepOnly(driverId)
        attendance.keepOnly(profile.id)
        driverDay.keepOnly(driverId)
        hrDocuments.keepOnly(profile.id)
    }
}

/**
 * The daily shift's data of the signed-in driver (docs/16). Waiting starts and ends stay on the phone when the session
 * ends, like attendance punches; another driver signing in removes them.
 */
interface DriverDaySessionData {
    fun clear()

    suspend fun keepOnly(driverId: String?)

    object None : DriverDaySessionData {
        override fun clear() = Unit

        override suspend fun keepOnly(driverId: String?) = Unit
    }
}

class ApiDriverDaySessionData @Inject constructor(
    private val shift: ApiShiftRepository,
    private val driverTimes: ApiDriverTimesRepository,
    private val vehicleCheck: ApiVehicleCheckRepository,
    private val cache: DriverCacheStore,
) : DriverDaySessionData {
    override fun clear() {
        shift.clear()
        driverTimes.clear()
        vehicleCheck.clear()
    }

    override suspend fun keepOnly(driverId: String?) {
        if (driverId == null) {
            // An account without the driver module keeps no driver data: no driver id matches the empty one.
            cache.clear()
            shift.keepOnly("")
        } else {
            cache.keepOnly(driverId)
            shift.keepOnly(driverId)
        }
    }
}

/**
 * The personnel documents of the signed-in account (docs/17). Signatures waiting on the phone stay when the session
 * ends, like attendance punches; another account signing in removes them.
 */
interface HrDocumentSessionData {
    suspend fun clear()

    suspend fun keepOnly(userId: String)

    object None : HrDocumentSessionData {
        override suspend fun clear() = Unit

        override suspend fun keepOnly(userId: String) = Unit
    }
}
