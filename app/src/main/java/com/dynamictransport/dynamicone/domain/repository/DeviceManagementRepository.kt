package com.dynamictransport.dynamicone.domain.repository

import com.dynamictransport.dynamicone.domain.model.CheckInResult
import com.dynamictransport.dynamicone.domain.model.DeviceCheckIn
import com.dynamictransport.dynamicone.domain.model.DeviceCommandResult
import com.dynamictransport.dynamicone.domain.model.DeviceEnrollment
import com.dynamictransport.dynamicone.domain.model.DeviceStatus
import com.dynamictransport.dynamicone.domain.model.EnrollResult
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Device management (docs/29): the phone is managed with its own credential, with or without a signed-in account.
 * Off in the demo and when `app.deviceManagement=false`.
 */
interface DeviceManagementRepository {
    val enrolled: Flow<Boolean>

    val lastCheckInAt: Flow<Instant?>

    suspend fun isEnrolled(): Boolean

    /** Exchanges the office's enrolment code for the device credential and sends the first check-in. */
    suspend fun enroll(code: String): EnrollResult

    /** Reports the device state, applies the policy and runs the commands the server returns. */
    suspend fun checkIn(): CheckInResult
}

/** The MDM endpoints (docs/29 §3). Failures are `RemoteException`s; nothing is retried here. */
interface DeviceManagementGateway {
    suspend fun enroll(enrollmentCode: String, installationId: String, status: DeviceStatus): DeviceEnrollment

    suspend fun checkIn(status: DeviceStatus, pushToken: String?, policyVersionApplied: Int?): DeviceCheckIn

    suspend fun postCommandResult(commandId: String, result: DeviceCommandResult)

    suspend fun declineRemoteSession(sessionId: String)
}
