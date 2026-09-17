package com.dynamictransport.dynamicone.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Office attendance (docs/15_MOBILE_APP_API.md §5). `IN` starts the working time, `OUT` ends it. */
enum class AttendanceDirection {
    IN,
    OUT,
}

enum class AttendancePunchSource {
    /** Tag read with this app. */
    MOBILE_NFC,

    /** Card terminal or CSV import in the office. */
    TERMINAL,

    /** Approved correction in DynamicONE. */
    CORRECTION,
    UNKNOWN,
}

/** A punch recorded by the server. */
data class AttendancePunch(
    val id: String,
    /** Set for punches sent by an app; links the server record to the scan stored on the phone. */
    val commandId: String?,
    val direction: AttendanceDirection,
    val occurredAt: Instant,
    val workDate: LocalDate?,
    val source: AttendancePunchSource,
    val pointName: String?,
)

/** `GET attendance/status`. */
data class AttendanceStatus(
    val checkedIn: Boolean,
    val nextDirection: AttendanceDirection,
    val workDate: LocalDate,
    val zone: ZoneId,
    val lastPunch: AttendancePunch?,
)

enum class AttendanceDayState {
    /** Valid check-in/check-out pairs. */
    COMPLETE,

    /** Today, currently checked in. */
    OPEN,

    /** A past day with a missing check-in or check-out. */
    INCOMPLETE,

    /** Repeated direction, pending correction or similar: the office reviews it. */
    NEEDS_REVIEW,
    UNKNOWN,
}

data class AttendanceDay(
    val workDate: LocalDate,
    val state: AttendanceDayState,
    /** Server calculation; null when the day is not complete. Never 0 for missing time. */
    val workedMinutes: Int?,
    /** Oldest first. */
    val punches: List<AttendancePunch>,
)

data class AttendanceMonth(
    val month: YearMonth,
    val zone: ZoneId,
    /** Only days with punches, newest first. */
    val days: List<AttendanceDay>,
)

enum class LocalPunchState {
    /**
     * Stored on the phone, waiting to be sent (no connection, server busy, session ended, attendance access missing).
     * A [LocalPunch.failure] on a waiting punch is the reason the server did not take it yet, not a final answer.
     */
    PENDING,

    /** Being sent right now. */
    SENDING,

    /** Confirmed by the server; kept for a few days so the phone knows today's state offline. */
    SYNCED,

    /** Rejected by the server; shown with the reason until the user resends or removes it. */
    FAILED,
}

/**
 * Why the server did not take a punch. A final answer on a `FAILED` punch (400/409), the reason a `PENDING` punch is
 * still waiting on the phone (403, docs/15_MOBILE_APP_API.md §5.3).
 */
enum class PunchFailure {
    /** The tag is not registered as an attendance point (or it is disabled). */
    TAG_UNKNOWN,

    /** A check-in tag was used to end the working time or the other way round. */
    WRONG_TAG_DIRECTION,

    /** The scan time is in the future or too old to be accepted. */
    TIME_REJECTED,

    /** The account may not record office attendance (403): the punch keeps waiting and is sent again later. */
    ACCESS_REQUIRED,

    /** The same command id was used for a different punch. */
    CONFLICT,
    REJECTED,
}

/** A tag scan stored on the phone before (and shortly after) the server confirmed it. */
data class LocalPunch(
    val commandId: String,
    val direction: AttendanceDirection,
    /** Phone clock at the scan. */
    val scannedAt: Instant,
    val state: LocalPunchState,
    val failure: PunchFailure?,
    /** Server time of the punch once it is recorded. */
    val occurredAt: Instant?,
    val pointName: String?,
) {
    /** The best known time: the server's once recorded, the scan time before. */
    val time: Instant get() = occurredAt ?: scannedAt
}

sealed interface PunchResult {
    /** The server recorded the punch. */
    data class Recorded(val punch: AttendancePunch) : PunchResult

    /**
     * Stored on the phone; it is sent automatically with the same command id when the server takes it.
     * [hold] names the reason the server did not take it yet when the server answered at all (403).
     */
    data class Saved(val commandId: String, val hold: PunchFailure? = null) : PunchResult

    /** The server rejected the punch; it stays on the phone as failed. */
    data class Failed(val failure: PunchFailure) : PunchResult

    /** The tag identifier could not be read (not stored). */
    data object UnreadableTag : PunchResult

    data object NotLoggedIn : PunchResult
}
