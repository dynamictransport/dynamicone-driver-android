package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.AppLogger
import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.mdm.DeviceCheckInScheduler
import com.dynamictransport.dynamicone.core.mdm.DeviceCommandExecutor
import com.dynamictransport.dynamicone.core.mdm.DeviceCommandLedger
import com.dynamictransport.dynamicone.core.mdm.DeviceCredentialStore
import com.dynamictransport.dynamicone.core.mdm.DeviceManagementStateStore
import com.dynamictransport.dynamicone.core.mdm.DevicePolicyApplier
import com.dynamictransport.dynamicone.core.mdm.DeviceStatusCollector
import com.dynamictransport.dynamicone.core.network.MdmApi
import com.dynamictransport.dynamicone.core.network.MdmCheckInRequestDto
import com.dynamictransport.dynamicone.core.network.MdmCheckInResponseDto
import com.dynamictransport.dynamicone.core.network.MdmCommandResultRequestDto
import com.dynamictransport.dynamicone.core.network.MdmEnrollRequestDto
import com.dynamictransport.dynamicone.core.network.MdmErrorCodes
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.network.apiCall
import com.dynamictransport.dynamicone.core.network.requireSuccess
import com.dynamictransport.dynamicone.core.push.DevicePushTokenSource
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.domain.model.CheckInResult
import com.dynamictransport.dynamicone.domain.model.DeviceCheckIn
import com.dynamictransport.dynamicone.domain.model.DeviceCommand
import com.dynamictransport.dynamicone.domain.model.DeviceCommandResult
import com.dynamictransport.dynamicone.domain.model.DeviceCredential
import com.dynamictransport.dynamicone.domain.model.DeviceEnrollment
import com.dynamictransport.dynamicone.domain.model.DevicePolicy
import com.dynamictransport.dynamicone.domain.model.DeviceStatus
import com.dynamictransport.dynamicone.domain.model.EnrollResult
import com.dynamictransport.dynamicone.domain.model.EnrollmentCode
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.DeviceManagementGateway
import com.dynamictransport.dynamicone.domain.repository.DeviceManagementRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The MDM endpoints over Retrofit (docs/29 §3). DTOs stop here. */
@Singleton
class ApiDeviceManagementGateway @Inject constructor(
    private val api: MdmApi,
) : DeviceManagementGateway {

    override suspend fun enroll(enrollmentCode: String, installationId: String, status: DeviceStatus): DeviceEnrollment {
        val response = apiCall {
            api.enroll(
                MdmEnrollRequestDto(
                    enrollmentCode = enrollmentCode,
                    installationId = installationId,
                    manufacturer = status.manufacturer,
                    model = status.model,
                    serialNumber = status.serialNumber,
                    osVersion = status.osVersion,
                    sdkInt = status.sdkInt,
                    appVersion = status.appVersion,
                    appVersionCode = status.appVersionCode,
                    isDeviceOwner = status.isDeviceOwner,
                ),
            )
        }
        val deviceId = response.deviceId?.takeIf { it.isNotBlank() } ?: throw RemoteException.InvalidResponse()
        val deviceSecret = response.deviceSecret?.takeIf { it.isNotBlank() } ?: throw RemoteException.InvalidResponse()
        return DeviceEnrollment(DeviceCredential(deviceId, deviceSecret), response.checkInSeconds)
    }

    override suspend fun checkIn(status: DeviceStatus, pushToken: String?, policyVersionApplied: Int?): DeviceCheckIn = apiCall {
        api.checkIn(
            MdmCheckInRequestDto(
                appVersion = status.appVersion,
                appVersionCode = status.appVersionCode,
                osVersion = status.osVersion,
                sdkInt = status.sdkInt,
                securityPatch = status.securityPatch,
                isDeviceOwner = status.isDeviceOwner,
                accessibilityEnabled = status.accessibilityEnabled,
                batteryPercent = status.batteryPercent,
                isCharging = status.isCharging,
                networkType = status.networkType,
                storageFreeMb = status.storageFreeMb,
                storageTotalMb = status.storageTotalMb,
                pushToken = pushToken,
                locale = status.locale,
                policyVersionApplied = policyVersionApplied,
            ),
        )
    }.toDomain()

    override suspend fun postCommandResult(commandId: String, result: DeviceCommandResult) {
        apiCall { api.postCommandResult(commandId, MdmCommandResultRequestDto(result.status.wireName, result.detail)).requireSuccess() }
    }

    override suspend fun declineRemoteSession(sessionId: String) {
        apiCall { api.declineRemoteSession(sessionId).requireSuccess() }
    }

    private fun MdmCheckInResponseDto.toDomain() = DeviceCheckIn(
        checkInSeconds = checkInSeconds,
        policy = policy?.let { dto ->
            // A policy without a version cannot be reported back as applied: it is left alone.
            dto.version?.let { version ->
                DevicePolicy(version, dto.blockFactoryReset, dto.blockUnknownSources, dto.blockUsbFileTransfer, dto.blockSafeBoot, dto.blockAddUser)
            }
        },
        commands = commands.orEmpty().mapNotNull { dto ->
            val id = dto.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DeviceCommand(id = id, type = dto.type.orEmpty(), payload = dto.payload.plainValues(), expiresAt = dto.expiresAt?.toInstantOrNull())
        },
    )

    /** The payloads of docs/29 §3.4 are flat: texts and numbers. Nested values are not part of the contract. */
    private fun JsonObject?.plainValues(): Map<String, String> = this.orEmpty()
        .filterValues { it is JsonPrimitive && it !is JsonNull }
        .mapValues { (_, value) -> (value as JsonPrimitive).content }
}

/**
 * Device management against the real backend (docs/29). One check-in runs at a time: push, worker and screen may all
 * ask for one.
 */
@Singleton
class RealDeviceManagementRepository @Inject constructor(
    private val gateway: DeviceManagementGateway,
    private val credentials: DeviceCredentialStore,
    private val state: DeviceManagementStateStore,
    private val ledger: DeviceCommandLedger,
    private val sessionPreferences: SessionPreferences,
    private val statusCollector: DeviceStatusCollector,
    private val policyApplier: DevicePolicyApplier,
    private val executor: DeviceCommandExecutor,
    private val pushTokens: DevicePushTokenSource,
    private val scheduler: DeviceCheckInScheduler,
    private val appInfo: AppInfo,
    private val clock: Clock,
    private val logger: AppLogger,
) : DeviceManagementRepository {

    private val checkInMutex = Mutex()

    private val enabled: Boolean get() = appInfo.isDeviceManagementEnabled && !appInfo.isDemoMode

    override val enrolled: Flow<Boolean> = if (enabled) credentials.enrolled else flowOf(false)

    override val lastCheckInAt: Flow<Instant?> = if (enabled) state.lastCheckInAt else flowOf(null)

    override suspend fun isEnrolled(): Boolean = enabled && credentials.read() != null

    override suspend fun enroll(code: String): EnrollResult {
        if (!enabled) return EnrollResult.FAILED
        val enrollmentCode = EnrollmentCode.normalize(code) ?: return EnrollResult.INVALID_CODE
        // A device owner reads its serial number for the office's device list.
        policyApplier.grantOwnerPermissions()
        val enrollment = try {
            gateway.enroll(enrollmentCode, sessionPreferences.installationId(), statusCollector.collect())
        } catch (_: RemoteException.NotFound) {
            return EnrollResult.INVALID_CODE
        } catch (_: RemoteException.Validation) {
            return EnrollResult.INVALID_CODE
        } catch (_: RemoteException.Offline) {
            return EnrollResult.OFFLINE
        } catch (_: RemoteException.ServerUnavailable) {
            return EnrollResult.OFFLINE
        } catch (e: RemoteException) {
            logger.warn("device enrol", e)
            return EnrollResult.FAILED
        }
        // Re-enrolment replaces the secret: what the old enrolment remembered no longer applies.
        ledger.clear()
        state.clearEnrolment()
        credentials.write(enrollment.credential)
        val seconds = interval(enrollment.checkInSeconds)
        state.setCheckInSeconds(seconds)
        scheduler.startPeriodic(seconds, replace = true)
        logger.event("device_enrolled")
        // The first check-in tells the office the phone is there; when it fails now, the worker sends it.
        if (checkIn() != CheckInResult.DONE) scheduler.requestCheckIn()
        return EnrollResult.ENROLLED
    }

    override suspend fun checkIn(): CheckInResult {
        if (!enabled) return CheckInResult.NOT_ENROLLED
        return checkInMutex.withLock {
            var result = CheckInResult.DONE
            // A finished command is followed by one more check-in (docs/29 §3.2); the rounds are bounded.
            for (round in 1..MAX_ROUNDS) {
                val (outcome, finished) = checkInOnce()
                result = outcome
                if (outcome != CheckInResult.DONE || finished == 0) break
            }
            result
        }
    }

    private suspend fun checkInOnce(): Pair<CheckInResult, Int> {
        if (credentials.read() == null) return CheckInResult.NOT_ENROLLED to 0
        policyApplier.onEnrolled()
        val reply = try {
            gateway.checkIn(statusCollector.collect(), pushTokens.current(), state.policyVersionApplied())
        } catch (e: RemoteException.Unauthorized) {
            if (e.problem?.code != MdmErrorCodes.DEVICE_NOT_ENROLLED) return CheckInResult.FAILED to 0
            retire()
            return CheckInResult.RETIRED to 0
        } catch (_: RemoteException.Offline) {
            return CheckInResult.OFFLINE to 0
        } catch (_: RemoteException.ServerUnavailable) {
            return CheckInResult.OFFLINE to 0
        } catch (e: RemoteException) {
            logger.warn("device check-in", e)
            return CheckInResult.FAILED to 0
        }
        state.setLastCheckIn(clock.instant())
        reply.checkInSeconds?.let { seconds ->
            val wanted = interval(seconds)
            if (wanted != state.checkInSeconds()) {
                state.setCheckInSeconds(wanted)
                scheduler.startPeriodic(wanted, replace = true)
            }
        }
        reply.policy?.let { policyApplier.apply(it) }
        return CheckInResult.DONE to executor.execute(reply.commands)
    }

    /** docs/29 §2: the credential goes, the worker stops and the restrictions are lifted. */
    private suspend fun retire() {
        credentials.clear()
        scheduler.stop()
        policyApplier.clear()
        ledger.clear()
        state.clearEnrolment()
        logger.event("device_retired")
    }

    private fun interval(seconds: Long?): Long =
        (seconds ?: DeviceManagementStateStore.MIN_CHECK_IN_SECONDS).coerceAtLeast(DeviceManagementStateStore.MIN_CHECK_IN_SECONDS)

    private companion object {
        const val MAX_ROUNDS = 3
    }
}

/** Demo and `app.deviceManagement=false`: the phone is not managed. */
object UnavailableDeviceManagementRepository : DeviceManagementRepository {
    override val enrolled: Flow<Boolean> = flowOf(false)
    override val lastCheckInAt: Flow<Instant?> = flowOf(null)

    override suspend fun isEnrolled(): Boolean = false

    override suspend fun enroll(code: String): EnrollResult = EnrollResult.FAILED

    override suspend fun checkIn(): CheckInResult = CheckInResult.NOT_ENROLLED
}
