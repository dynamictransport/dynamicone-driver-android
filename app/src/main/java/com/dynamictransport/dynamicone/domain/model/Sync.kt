package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

/** What the driver sees for any locally recorded data. Failures are never shown as success. */
enum class SyncState {
    SYNCED,
    PENDING,
    FAILED,
}

enum class PendingActionType {
    TRIP_ACTION,
    SHIFT_START,
    SHIFT_END,
    INCIDENT_REPORT,
    DOCUMENT_UPLOAD,
}

/** docs/04_OFFLINE_SYNC.md PendingAction.state */
enum class PendingActionState {
    PENDING,
    SENDING,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    SYNCED,
}

/** Driver-friendly error categories. Raw technical errors are never stored or shown. */
enum class SyncErrorCode {
    OFFLINE,
    SERVER_UNAVAILABLE,
    CONFLICT,
    REJECTED_BY_SERVER,
    SESSION_EXPIRED,
    PRECEDING_ACTION_FAILED,
    NOT_SUPPORTED_YET,
    UNKNOWN,
}

data class FailedAction(
    val clientActionId: String,
    val type: PendingActionType,
    val entityId: String?,
    val tripAction: TripActionType?,
    val errorCode: SyncErrorCode,
    val createdAt: Instant,
)

data class SyncOverview(
    val pendingCount: Int,
    val failures: List<FailedAction>,
    val lastSuccessfulSyncAt: Instant?,
) {
    val failedCount: Int get() = failures.size

    companion object {
        val Empty = SyncOverview(pendingCount = 0, failures = emptyList(), lastSuccessfulSyncAt = null)
    }
}

enum class RefreshResult {
    SUCCESS,
    OFFLINE,
    SESSION_EXPIRED,
    SERVICE_UNAVAILABLE,
    RATE_LIMITED,

    /** The connected backend has no endpoint for this data. */
    NOT_SUPPORTED,

    /** The account may not see this data (any more): `403` with the endpoint's access code. */
    ACCESS_REQUIRED,
    FAILED,
}
