package com.dynamictransport.dynamicone.core.network

import kotlinx.serialization.Serializable

/** `GET me/wake-up-calls/{callId}` (docs/25 §5). Times are ISO-8601 UTC text. */
@Serializable
data class WakeUpCallDto(
    val callId: String? = null,
    val status: String? = null,
    val reason: String? = null,
    val requestedAt: String? = null,
    val expiresAt: String? = null,
    val requestedByDisplayName: String? = null,
    val tripId: String? = null,
    val tripNo: String? = null,
    val acknowledgedAt: String? = null,
    val acknowledgedDeviceKind: String? = null,
)

/** `POST …/ringing` (docs/25 §6): this device started to ring. */
@Serializable
data class WakeUpRingingRequestDto(
    val occurredAt: String,
    val installationId: String,
)

/** `POST …/result` (docs/25 §7): the driver answered, or the call rang out on this device. */
@Serializable
data class WakeUpResultRequestDto(
    val status: String,
    val occurredAt: String,
    val installationId: String,
    val clientActionId: String,
)

/** Wire names of docs/25 (case-sensitive text). */
object WakeUpWireNames {
    const val PUSH_TYPE = "driver.wakeup"
    const val EVENT_RING = "ring"
    const val EVENT_STOP = "stop"
    const val STATUS_ACKNOWLEDGED = "Acknowledged"
    const val STATUS_EXPIRED = "Expired"
    const val CODE_CALL_NOT_FOUND = "wake_up_call_not_found"
}
