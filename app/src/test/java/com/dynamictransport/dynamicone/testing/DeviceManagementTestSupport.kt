package com.dynamictransport.dynamicone.testing

import android.content.Intent
import com.dynamictransport.dynamicone.core.mdm.DeviceAlerts
import com.dynamictransport.dynamicone.core.mdm.DeviceCheckInScheduler
import com.dynamictransport.dynamicone.core.mdm.DevicePolicyController
import com.dynamictransport.dynamicone.core.mdm.DeviceRestriction
import com.dynamictransport.dynamicone.core.mdm.DeviceStatusCollector
import com.dynamictransport.dynamicone.core.mdm.RemoteRequestOutcome
import com.dynamictransport.dynamicone.core.mdm.RemoteSessionCommands
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.push.DevicePushTokenSource
import com.dynamictransport.dynamicone.core.remote.RemoteControlAccess
import com.dynamictransport.dynamicone.core.remote.RemoteSessionNotifier
import com.dynamictransport.dynamicone.core.remote.ScreenStreamLauncher
import com.dynamictransport.dynamicone.core.security.CryptoBox
import com.dynamictransport.dynamicone.domain.model.CheckInResult
import com.dynamictransport.dynamicone.domain.model.DeviceCheckIn
import com.dynamictransport.dynamicone.domain.model.DeviceCommand
import com.dynamictransport.dynamicone.domain.model.DeviceCommandResult
import com.dynamictransport.dynamicone.domain.model.DeviceCredential
import com.dynamictransport.dynamicone.domain.model.DeviceEnrollment
import com.dynamictransport.dynamicone.domain.model.DeviceStatus
import com.dynamictransport.dynamicone.domain.model.EnrollResult
import com.dynamictransport.dynamicone.domain.model.RemoteSessionRequest
import com.dynamictransport.dynamicone.domain.repository.DeviceManagementGateway
import com.dynamictransport.dynamicone.domain.repository.DeviceManagementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Duration
import java.time.Instant

/** Invented sample values for tests; not a real device. */
const val TEST_DEVICE_ID = "6f1d2c3b-4a59-4e6f-8a7b-9c0d1e2f3a4b"
const val TEST_DEVICE_SECRET = "s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-1"

/** Stands in for the Android Keystore box: the point in tests is that nothing is stored in plain text. */
class XorCryptoBox : CryptoBox {
    override fun encrypt(plain: ByteArray): ByteArray = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

    override fun decrypt(data: ByteArray): ByteArray = encrypt(data)
}

fun deviceStatus(isDeviceOwner: Boolean = true, serialNumber: String? = "SN-0001") = DeviceStatus(
    appVersion = "0.3.0",
    appVersionCode = 4,
    osVersion = "14",
    sdkInt = 34,
    securityPatch = "2026-08-05",
    isDeviceOwner = isDeviceOwner,
    accessibilityEnabled = false,
    batteryPercent = 81,
    isCharging = false,
    networkType = "wifi",
    storageFreeMb = 41_200,
    storageTotalMb = 118_000,
    locale = "tr-TR",
    manufacturer = "HAMMER",
    model = "Iron 5",
    serialNumber = serialNumber,
)

class FixedDeviceStatusCollector(var status: DeviceStatus = deviceStatus()) : DeviceStatusCollector {
    override fun collect(): DeviceStatus = status
}

class FakeDevicePolicyController(
    override var isDeviceOwner: Boolean = true,
    override var isAdminActive: Boolean = true,
) : DevicePolicyController {
    /** Everything the device was told to do, in order. */
    val calls = mutableListOf<String>()
    val restrictions = mutableMapOf<DeviceRestriction, Boolean>()
    var uninstallBlocked = false
        private set
    val grantedPermissions = mutableListOf<String>()
    var failure: RuntimeException? = null

    private fun act(call: String) {
        failure?.let { throw it }
        calls += call
    }

    override fun lockNow() = act("lock")

    override fun reboot() = act("reboot")

    override fun wipeData() = act("wipe")

    override fun setRestriction(restriction: DeviceRestriction, blocked: Boolean) {
        failure?.let { throw it }
        restrictions[restriction] = blocked
    }

    override fun setUninstallBlocked(blocked: Boolean) {
        uninstallBlocked = blocked
    }

    override fun grantOwnPermission(permission: String): Boolean {
        grantedPermissions += permission
        return true
    }
}

class FakeDeviceAlerts(var canNotify: Boolean = true) : DeviceAlerts {
    val rings = mutableListOf<Int>()
    val messages = mutableListOf<Triple<String, String, String>>()
    var stops = 0

    override fun ensureChannels() = Unit

    override fun ring(seconds: Int): Boolean {
        if (canNotify) rings += seconds
        return canNotify
    }

    override fun stopRing() {
        stops++
    }

    override fun showMessage(commandId: String, title: String, body: String): Boolean {
        if (canNotify) messages += Triple(commandId, title, body)
        return canNotify
    }
}

class FakeRemoteSessionCommands(var outcome: RemoteRequestOutcome = RemoteRequestOutcome.PROMPTED) : RemoteSessionCommands {
    val requests = mutableListOf<RemoteSessionRequest>()
    val ended = mutableListOf<String>()
    val declinedUnanswered = mutableListOf<String>()

    override suspend fun request(request: RemoteSessionRequest): RemoteRequestOutcome {
        requests += request
        return outcome
    }

    override suspend fun end(sessionId: String) {
        ended += sessionId
    }

    override suspend fun declineUnanswered(sessionId: String) {
        declinedUnanswered += sessionId
    }
}

class FakeDeviceCheckInScheduler : DeviceCheckInScheduler {
    val periodic = mutableListOf<Pair<Long, Boolean>>()
    var requests = 0
    var stops = 0

    override fun startPeriodic(intervalSeconds: Long, replace: Boolean) {
        periodic += intervalSeconds to replace
    }

    override fun requestCheckIn() {
        requests++
    }

    override fun stop() {
        stops++
    }
}

class FakeDeviceManagementGateway : DeviceManagementGateway {
    /** Everything sent to the server, in order. */
    val calls = mutableListOf<String>()
    var enrollFailure: RemoteException? = null
    var enrollment = DeviceEnrollment(DeviceCredential(TEST_DEVICE_ID, TEST_DEVICE_SECRET), checkInSeconds = 900)
    var checkInFailure: RemoteException? = null

    /** One answer per check-in; the last one repeats. */
    val checkIns = ArrayDeque<DeviceCheckIn>()
    var resultFailure: RemoteException? = null
    val results = mutableListOf<Pair<String, DeviceCommandResult>>()
    val declined = mutableListOf<String>()
    var lastPushToken: String? = null
    var lastPolicyVersionApplied: Int? = null
    var enrolledCode: String? = null
    var enrolledStatus: DeviceStatus? = null

    override suspend fun enroll(enrollmentCode: String, installationId: String, status: DeviceStatus): DeviceEnrollment {
        calls += "enroll"
        enrollFailure?.let { throw it }
        enrolledCode = enrollmentCode
        enrolledStatus = status
        return enrollment
    }

    override suspend fun checkIn(status: DeviceStatus, pushToken: String?, policyVersionApplied: Int?): DeviceCheckIn {
        calls += "check-in"
        checkInFailure?.let { throw it }
        lastPushToken = pushToken
        lastPolicyVersionApplied = policyVersionApplied
        return (if (checkIns.size > 1) checkIns.removeFirst() else checkIns.firstOrNull()) ?: DeviceCheckIn(900, null, emptyList())
    }

    override suspend fun postCommandResult(commandId: String, result: DeviceCommandResult) {
        calls += "result:$commandId:${result.status.wireName}"
        resultFailure?.let { throw it }
        results += commandId to result
    }

    override suspend fun declineRemoteSession(sessionId: String) {
        calls += "decline:$sessionId"
        declined += sessionId
    }
}

class FakeDevicePushTokenSource(var token: String? = "fcm-token-1") : DevicePushTokenSource {
    override suspend fun current(): String? = token
}

class FakeRemoteControlAccess(
    override var isEnabled: Boolean = false,
    override var canSelfEnable: Boolean = false,
) : RemoteControlAccess {
    override fun selfEnable(): Boolean {
        if (canSelfEnable) isEnabled = true
        return isEnabled
    }
}

class FakeDeviceManagementRepository : DeviceManagementRepository {
    override val enrolled = MutableStateFlow(false)
    override val lastCheckInAt = MutableStateFlow<Instant?>(null)
    var enrollResult = EnrollResult.ENROLLED
    var checkInResult = CheckInResult.DONE
    val codes = mutableListOf<String>()
    var checkIns = 0

    override suspend fun isEnrolled(): Boolean = enrolled.value

    override suspend fun enroll(code: String): EnrollResult {
        codes += code
        if (enrollResult == EnrollResult.ENROLLED) {
            enrolled.value = true
            lastCheckInAt.value = TEST_NOW
        }
        return enrollResult
    }

    override suspend fun checkIn(): CheckInResult {
        checkIns++
        return checkInResult
    }
}

class FakeRemoteSessionNotifier : RemoteSessionNotifier {
    val shown = mutableListOf<RemoteSessionRequest>()
    var cancels = 0

    override fun ensureChannels() = Unit

    override fun showRequest(request: RemoteSessionRequest, remaining: Duration) {
        shown += request
    }

    override fun cancelRequest() {
        cancels++
    }
}

class FakeScreenStreamLauncher : ScreenStreamLauncher {
    val started = mutableListOf<String>()
    var stops = 0

    override fun start(sessionId: String, resultCode: Int, permission: Intent) {
        started += sessionId
    }

    override fun stop() {
        stops++
    }
}

fun command(id: String, type: String, payload: Map<String, String> = emptyMap(), expiresAt: Instant? = TEST_NOW.plusSeconds(3_600)) =
    DeviceCommand(id, type, payload, expiresAt)
