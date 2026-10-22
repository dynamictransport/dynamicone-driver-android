package com.dynamictransport.dynamicone.domain.model

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Terminal appointment reference (TAR) the planner recorded for a port terminal stop (docs/26). Read-only for the
 * driver. [code] is text and keeps its leading zeros; the window is `[startAt, endAt)` in UTC and is shown in the
 * terminal's own [timeZone]. It is the planner's record, not a confirmation by the terminal.
 */
data class TerminalAppointment(
    val code: String,
    val startAt: Instant,
    val endAt: Instant,
    val timeZone: ZoneId,
) {
    /** The window is compared on UTC instants only; the stop's own status decides whether it still matters. */
    fun statusAt(now: Instant, stopStatus: StopStatus): TarStatus = when {
        stopStatus == StopStatus.COMPLETED || stopStatus == StopStatus.SKIPPED -> TarStatus(TarPhase.PAST, null)
        now.isBefore(startAt) -> TarStatus(TarPhase.UPCOMING, Duration.between(now, startAt))
        now.isBefore(endAt) -> {
            val remaining = Duration.between(now, endAt)
            TarStatus(if (remaining <= ENDING_SOON) TarPhase.ENDING_SOON else TarPhase.ACTIVE, remaining)
        }
        else -> TarStatus(TarPhase.EXPIRED, null)
    }

    /** The end of the window in the terminal's time zone falls on a later calendar day than its start. */
    val crossesMidnight: Boolean
        get() = startAt.atZone(timeZone).toLocalDate() != endAt.atZone(timeZone).toLocalDate()

    companion object {
        /** The last part of an active window is shown as a warning. */
        val ENDING_SOON: Duration = Duration.ofMinutes(30)
    }
}

enum class TarPhase {
    /** Before the window: the countdown runs to its start. */
    UPCOMING,

    /** Inside the window: the countdown runs to its end. */
    ACTIVE,

    /** Inside the window with at most [TerminalAppointment.ENDING_SOON] left. */
    ENDING_SOON,

    /** The window has ended and the stop is still open. */
    EXPIRED,

    /** The stop is completed or skipped: the TAR is history, without an alarm or a countdown. */
    PAST,
    ;

    /** A countdown is only shown while the window can still be met. */
    val counting: Boolean get() = this == UPCOMING || this == ACTIVE || this == ENDING_SOON
}

/** [remaining] is the time to the start (UPCOMING) or to the end (ACTIVE, ENDING_SOON); null otherwise. */
data class TarStatus(val phase: TarPhase, val remaining: Duration?)
