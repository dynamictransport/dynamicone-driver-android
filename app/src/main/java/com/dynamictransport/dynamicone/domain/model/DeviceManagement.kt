package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

/**
 * The managed device's own credential (docs/29 §2). It belongs to the phone, not to an account, and is never logged.
 */
class DeviceCredential(val deviceId: String, val deviceSecret: String) {

    /** `Device {deviceId as 32 hex digits}.{deviceSecret}`. */
    val authorization: String
        get() = "Device ${deviceId.filter { it != '-' }.lowercase()}.$deviceSecret"

    override fun toString(): String = "DeviceCredential(deviceId=$deviceId)"
}

/** What the phone reports about itself at enrolment and at every check-in (docs/29 §3.1, §3.2). */
data class DeviceStatus(
    val appVersion: String,
    val appVersionCode: Int,
    val osVersion: String,
    val sdkInt: Int,
    val securityPatch: String?,
    val isDeviceOwner: Boolean,
    val accessibilityEnabled: Boolean,
    val batteryPercent: Int?,
    val isCharging: Boolean,
    /** `wifi`, `cellular` or `none`. */
    val networkType: String,
    val storageFreeMb: Long,
    val storageTotalMb: Long,
    val locale: String,
    val manufacturer: String,
    val model: String,
    /** Readable by the device owner only. */
    val serialNumber: String?,
)

/** The restrictions the office wants on this phone (docs/29 §3.5). */
data class DevicePolicy(
    val version: Int,
    val blockFactoryReset: Boolean,
    val blockUnknownSources: Boolean,
    val blockUsbFileTransfer: Boolean,
    val blockSafeBoot: Boolean,
    val blockAddUser: Boolean,
)

/** A command of the office (docs/29 §3.4). [payload] holds the payload's plain values as text. */
data class DeviceCommand(
    val id: String,
    val type: String,
    val payload: Map<String, String> = emptyMap(),
    val expiresAt: Instant? = null,
)

enum class DeviceCommandStatus(val wireName: String) {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    UNSUPPORTED("unsupported"),
    ;

    companion object {
        fun fromWire(value: String?): DeviceCommandStatus? = entries.firstOrNull { it.wireName == value }
    }
}

data class DeviceCommandResult(val status: DeviceCommandStatus, val detail: String? = null) {
    companion object {
        const val MAX_DETAIL_LENGTH = 500

        fun succeeded(detail: String? = null) = DeviceCommandResult(DeviceCommandStatus.SUCCEEDED, detail)

        fun failed(detail: String? = null) = DeviceCommandResult(DeviceCommandStatus.FAILED, detail)

        fun unsupported(detail: String? = null) = DeviceCommandResult(DeviceCommandStatus.UNSUPPORTED, detail)
    }
}

/** Wire names of docs/29 (case-sensitive text). */
object DeviceCommandTypes {
    const val SYNC = "sync"
    const val RING = "ring"
    const val MESSAGE = "message"
    const val LOCK = "lock"
    const val REBOOT = "reboot"
    const val WIPE = "wipe"
    const val REMOTE_SESSION_REQUEST = "remoteSession.request"
    const val REMOTE_SESSION_END = "remoteSession.end"
}

data class DeviceEnrollment(val credential: DeviceCredential, val checkInSeconds: Long?)

data class DeviceCheckIn(
    val checkInSeconds: Long?,
    val policy: DevicePolicy?,
    val commands: List<DeviceCommand>,
)

enum class EnrollResult {
    ENROLLED,

    /** Unknown, used, expired or mistyped code. */
    INVALID_CODE,
    OFFLINE,
    FAILED,
}

enum class CheckInResult {
    DONE,
    NOT_ENROLLED,

    /** The server retired the device: the credential is gone and the restrictions are lifted. */
    RETIRED,
    OFFLINE,
    FAILED,
}

/** The office asks to see this phone's screen (docs/29 §4); the driver answers on the phone. */
data class RemoteSessionRequest(
    val commandId: String,
    val sessionId: String,
    val requestedBy: String?,
    val expiresAt: Instant,
)

/** The 8-character enrolment code (docs/29 §3.1): dash, spaces and letter case do not matter. */
object EnrollmentCode {
    const val LENGTH = 8
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** `K7Q2-M9XD`, or null when the text cannot be a code. */
    fun normalize(input: String): String? {
        val letters = input.uppercase().filter { it != '-' && !it.isWhitespace() }
        if (letters.length != LENGTH || letters.any { it !in ALPHABET }) return null
        return letters.substring(0, LENGTH / 2) + "-" + letters.substring(LENGTH / 2)
    }
}
