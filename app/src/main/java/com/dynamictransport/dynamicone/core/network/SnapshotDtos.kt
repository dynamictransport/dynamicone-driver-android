package com.dynamictransport.dynamicone.core.network

import kotlinx.serialization.Serializable

/** `GET trips/{tripId}/snapshot-requests/{requestId}` (docs/24 §5). Times are ISO-8601 UTC text. */
@Serializable
data class SnapshotRequestDto(
    val requestId: String? = null,
    val tripId: String? = null,
    val status: String? = null,
    val requestedAt: String? = null,
    val expiresAt: String? = null,
    val requestedByDisplayName: String? = null,
    val preferredCamera: String? = null,
)

/** `POST …/photo` answer (docs/24 §6). */
@Serializable
data class SnapshotPhotoResponseDto(
    val requestId: String? = null,
    val status: String? = null,
    val documentId: String? = null,
)

/** `POST …/result` (docs/24 §7): the tablet could not deliver a photo. */
@Serializable
data class SnapshotResultRequestDto(
    val status: String,
    val reason: String,
    val occurredAt: String,
)

/** Wire names of docs/24 (case-sensitive text). */
object SnapshotWireNames {
    const val PUSH_TYPE = "trip.snapshot"
    const val STATUS_FAILED = "Failed"
    const val DEVICE_KIND_VEHICLE_TABLET = "vehicleTablet"
    const val DEVICE_KIND_PHONE = "phone"
    const val CODE_REQUEST_NOT_FOUND = "snapshot_request_not_found"
    const val CODE_REQUEST_CLOSED = "snapshot_request_closed"
}
