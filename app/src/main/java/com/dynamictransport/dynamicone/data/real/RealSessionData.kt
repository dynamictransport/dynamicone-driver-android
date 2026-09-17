package com.dynamictransport.dynamicone.data.real

import javax.inject.Inject
import javax.inject.Singleton

/** Everything the real data layer keeps for the signed-in driver, cleared in one place. */
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
    }

    /** Signing out or changing the password removes the driver's unconfirmed commands too. */
    suspend fun clearStoredCommands() = commands.clear()

    /** Another driver signed in on this device: earlier drivers' commands must never be sent with this session. */
    suspend fun keepStoredCommandsOf(driverId: String) = commands.keepOnly(driverId)
}
