package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

/**
 * Remote road snapshot (docs/24): the planner asks the vehicle tablet for a photo of the road right now.
 * Only real requests of the server are handled; the app never invents one.
 */
enum class SnapshotCamera(val wire: String) {
    /** Dock camera of the tablet (Webfleet video dock), sees the road regardless of the tablet's position. */
    EXTERNAL("External"),
    BACK("Back"),
    ;

    companion object {
        fun fromWire(value: String?): SnapshotCamera = entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: EXTERNAL
    }
}

enum class SnapshotRequestStatus(val wire: String) {
    PENDING("Pending"),
    CAPTURED("Captured"),
    FAILED("Failed"),
    EXPIRED("Expired"),
    CANCELLED("Cancelled"),
    ;

    companion object {
        fun fromWire(value: String?): SnapshotRequestStatus? = entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

/** Why the tablet could not deliver a photo (docs/24 §7). */
enum class SnapshotFailure(val wire: String) {
    CAMERA_UNAVAILABLE("camera_unavailable"),
    PERMISSION_DENIED("permission_denied"),
    EXPIRED("expired"),
    DRIVER_DISMISSED("driver_dismissed"),
}

/** A request as the server describes it. */
data class SnapshotRequest(
    val requestId: String,
    val tripId: String,
    val status: SnapshotRequestStatus,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val requestedByDisplayName: String?,
    val preferredCamera: SnapshotCamera,
) {
    fun isExpiredAt(now: Instant): Boolean = !now.isBefore(expiresAt)
}

/** What happened to a request on this tablet, in the order it moves. */
enum class SnapshotOutcomeState {
    /** The driver has to open the app (notification shown). */
    WAITING_FOR_DRIVER,

    /** Taken; the upload waits for the connection. */
    CAPTURED,

    /** The server has the photo. */
    UPLOADED,

    /** Not taken; the reason is on its way to the server or already there. */
    FAILED,
}

/** The latest request of the signed-in driver, for the banners on the screens. */
data class SnapshotOutcome(
    val requestId: String,
    val tripId: String,
    val requestedByDisplayName: String?,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val state: SnapshotOutcomeState,
    val capturedAt: Instant?,
    val failure: SnapshotFailure?,
    val updatedAt: Instant,
)

/** The tablet's answer to a push: what to do now. */
sealed interface SnapshotDecision {
    /** Take the photo now; the request is pending on the server. */
    data class Capture(val request: SnapshotRequest) : SnapshotDecision

    /** Nothing to do: closed, unknown, another driver's, already handled. */
    data object Ignore : SnapshotDecision
}
