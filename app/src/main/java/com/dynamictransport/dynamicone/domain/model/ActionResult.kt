package com.dynamictransport.dynamicone.domain.model

/** Outcome of a driver action. */
sealed interface ActionResult {
    /** Demo mode: stored locally and queued. Real API: confirmed by the server. */
    data object Queued : ActionResult
    data class Rejected(val reason: ActionRejection) : ActionResult
}

enum class ActionRejection {
    /** Illegal for the current state, which also covers duplicate taps. */
    NOT_ALLOWED,
    SHIFT_NOT_ACTIVE,
    TRIP_IN_PROGRESS,
    REASON_REQUIRED,
    NOTE_REQUIRED,
    NOTE_TOO_LONG,
    NOT_FOUND,
    NOT_LOGGED_IN,
    LOW_STORAGE,
    FILE_ERROR,

    /** The connected backend does not support this feature yet. */
    NOT_SUPPORTED,

    /** The action needs an accepted or running trip. */
    TRIP_REQUIRED,
    FILE_TOO_LARGE,
    UNSUPPORTED_FILE,

    /** 409: the server state changed; the current state was reloaded. */
    CONFLICT,

    /** 409 on a trip action: the plan or next action changed. The driver has to choose again. */
    PLAN_CHANGED,

    /** The connection broke after sending: kept on the device and resent only with the same command id. */
    OUTCOME_UNKNOWN,

    /** An earlier action of this trip still has to be resent or discarded. */
    PENDING_ACTION,

    /** The step needs its required evidence uploaded first; it was not recorded (docs/18). */
    DOCUMENTS_REQUIRED,

    /** Container and seal evidence must be a JPEG or PNG photo. */
    PHOTO_REQUIRED,

    /** docs/27: the seal number is empty, longer than 100 characters or contains control characters. */
    SEAL_NUMBER_INVALID,

    /** docs/27, 409: the server still waits for the seal number of the container; nothing was recorded. */
    SEAL_NUMBER_REQUIRED,

    /** docs/27, 409: the server still waits for the loading confirmation of the stop; nothing was recorded. */
    LOADING_CONFIRMATION_REQUIRED,

    /** No connection or the outcome is unknown; nothing is resent automatically. */
    OFFLINE,
    SERVICE_UNAVAILABLE,
    RATE_LIMITED,
    SESSION_EXPIRED,
    FORBIDDEN,
    FAILED,
}
