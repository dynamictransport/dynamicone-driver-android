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
    }
}
