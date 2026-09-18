package com.dynamictransport.dynamicone.domain.rules

import com.dynamictransport.dynamicone.domain.model.DriverTimes
import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.domain.model.Shift
import java.time.Duration
import java.time.Instant

enum class RestAlarmMode {
    /** Full screen on the lock screen and the alarm sound until the driver answers (default, docs/16 §1). */
    ALARM,

    /** A normal notification with the notification sound. */
    NOTIFICATION,
}

/** The driver's choices and what the alarm already did for which rest. Stored on the phone. */
data class RestAlarmSettings(
    val mode: RestAlarmMode = RestAlarmMode.ALARM,
    /** How long before the end of the rest the alarm rings (0, 15 or 30 minutes). */
    val lead: Duration = Duration.ZERO,
    /** The driver switched the alarm off for this rest (e.g. weekly rest at home). */
    val dismissed: RestWindow? = null,
    /** The alarm rang for this rest. */
    val rung: RestWindow? = null,
    /** Snoozed alarm of [rung]: rings again at this time. */
    val snoozedUntil: Instant? = null,
) {
    companion object {
        val LEAD_OPTIONS: List<Duration> = listOf(Duration.ZERO, Duration.ofMinutes(15), Duration.ofMinutes(30))
    }
}

enum class RestAlarmOffReason {
    /** No tachograph data (not linked, no data, not read yet). */
    NO_DATA,

    /** The tachograph shows no daily rest. */
    NOT_RESTING,

    /** A shift started after this rest began: the driver is up. */
    SHIFT_STARTED,
    DISMISSED,
    ALREADY_RUNG,

    /** The rest ended too long ago to wake anybody. */
    TOO_LATE,
}

sealed interface RestAlarmPlan {
    data class Off(val reason: RestAlarmOffReason, val rest: RestWindow? = null) : RestAlarmPlan

    data class Scheduled(val rest: RestWindow, val triggerAt: Instant, val snoozed: Boolean = false) : RestAlarmPlan

    /** The rest ended a moment ago (or the lead time already passed) and nothing rang for it yet. */
    data class RingNow(val rest: RestWindow) : RestAlarmPlan
}

/**
 * When the wake-up alarm rings (docs/16 §1, decision 18.09.2026: at the end of every daily rest; the driver can switch
 * it off for one rest). Pure: the caller schedules and cancels.
 */
object RestAlarmRules {

    /** An alarm learned about later than this after the end of the rest does not ring any more. */
    val LATE_LIMIT: Duration = Duration.ofMinutes(60)
    val SNOOZE: Duration = Duration.ofMinutes(10)

    fun plan(times: DriverTimes?, shift: Shift, settings: RestAlarmSettings, now: Instant): RestAlarmPlan {
        if (times == null || !times.isAvailable) return RestAlarmPlan.Off(RestAlarmOffReason.NO_DATA)
        val rest = times.restingRest?.window() ?: return RestAlarmPlan.Off(RestAlarmOffReason.NOT_RESTING)
        val shiftStart = shift.startedAt
        if (shift.isActive || (shift.requiresVehicleCheck && shiftStart != null && shiftStart.isAfter(rest.start))) {
            return RestAlarmPlan.Off(RestAlarmOffReason.SHIFT_STARTED, rest)
        }
        if (settings.dismissed?.isSameRest(rest) == true) return RestAlarmPlan.Off(RestAlarmOffReason.DISMISSED, rest)
        if (settings.rung?.isSameRest(rest) == true) {
            val snooze = settings.snoozedUntil
            return if (snooze != null && snooze.isAfter(now)) {
                RestAlarmPlan.Scheduled(rest, snooze, snoozed = true)
            } else {
                RestAlarmPlan.Off(RestAlarmOffReason.ALREADY_RUNG, rest)
            }
        }
        val trigger = rest.end.minus(settings.lead)
        return when {
            trigger.isAfter(now) -> RestAlarmPlan.Scheduled(rest, trigger)
            Duration.between(rest.end, now) <= LATE_LIMIT -> RestAlarmPlan.RingNow(rest)
            else -> RestAlarmPlan.Off(RestAlarmOffReason.TOO_LATE, rest)
        }
    }

    /**
     * A fired alarm still rings only for a rest the driver neither switched off nor ended by starting a shift. A snoozed
     * alarm rings again for the rest that already rang.
     */
    fun shouldRing(rest: RestWindow, times: DriverTimes?, shift: Shift, settings: RestAlarmSettings, snooze: Boolean): Boolean {
        val shiftStart = shift.startedAt
        if (shift.isActive || (shift.requiresVehicleCheck && shiftStart != null && shiftStart.isAfter(rest.start))) return false
        if (settings.dismissed?.isSameRest(rest) == true) return false
        if (!snooze && settings.rung?.isSameRest(rest) == true) return false
        // Newer tachograph data may show that the driver is working again; without data the phone's plan stands.
        val current = times?.takeIf { it.isAvailable } ?: return true
        val currentRest = current.restingRest?.window() ?: return false
        return currentRest.isSameRest(rest)
    }
}
