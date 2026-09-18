package com.dynamictransport.dynamicone.domain.model

import java.time.Duration
import java.time.Instant

/** Tachograph activity reported by Webfleet (docs/16_DRIVER_SHIFT_REST_AND_VEHICLE_CHECK.md §3). */
enum class DriverActivity {
    DRIVING,
    OTHER_WORK,
    STANDBY,
    BREAK,
    UNKNOWN,
}

enum class DailyRestState {
    /** The last shift ended and no new one started. The rest may already be long enough ([DailyRest.endsAt] passed). */
    RESTING,

    /** A shift is running: driving, other work, a short break or availability. */
    WORKING,
    UNKNOWN,
}

/** The driver's daily rest according to the tachograph. */
data class DailyRest(
    val state: DailyRestState,
    val startedAt: Instant?,
    /** Earliest legal start of the next shift (Webfleet `earliest_start_next_shift`). */
    val endsAt: Instant?,
    val minimumDuration: Duration?,
) {
    val isResting: Boolean get() = state == DailyRestState.RESTING && endsAt != null

    /** The time span of this rest; null without an end. A missing start is estimated from the minimum duration. */
    fun window(): RestWindow? {
        val end = endsAt ?: return null
        val start = startedAt ?: end.minus(minimumDuration ?: ASSUMED_MINIMUM)
        return RestWindow(minOf(start, end), end)
    }

    private companion object {
        /** The shortest daily rest (reduced, 9 h) when the provider gave neither start nor minimum duration. */
        val ASSUMED_MINIMUM: Duration = Duration.ofHours(9)
    }
}

/** One daily rest in time. Two different daily rests never overlap, so overlapping windows are the same rest. */
data class RestWindow(val start: Instant, val end: Instant) {
    fun isSameRest(other: RestWindow): Boolean = start < other.end && other.start < end || this == other
}

enum class DriverTimesAvailability {
    AVAILABLE,

    /** The driver is not linked to a Webfleet driver. */
    NOT_LINKED,

    /** Linked, but no tachograph data (none received yet, or the account is not licensed for it). */
    NO_DATA,
}

/** The driver's latest tachograph summary. Indicative values; the legal check stays with the operator. */
data class DriverTimes(
    val availability: DriverTimesAvailability,
    /** When the values were valid. */
    val referenceTime: Instant? = null,
    val activity: DriverActivity = DriverActivity.UNKNOWN,
    val dailyRest: DailyRest? = null,
    /** Negative: the limit is exceeded. Null: unknown (never shown as zero). */
    val remainingDrivingToday: Duration? = null,
    val remainingWorkingToday: Duration? = null,
    val nextBreakDueAt: Instant? = null,
    val nextBreakMinimum: Duration? = null,
    val latestShiftEnd: Instant? = null,
    val hasInfringement: Boolean = false,
) {
    val isAvailable: Boolean get() = availability == DriverTimesAvailability.AVAILABLE

    /** The rest the driver is in, when the tachograph says so. */
    val restingRest: DailyRest? get() = dailyRest?.takeIf { isAvailable && it.isResting }

    /** Older than the planning threshold: shown as the last known values. */
    fun isStale(now: Instant): Boolean = referenceTime == null || Duration.between(referenceTime, now) > STALE_AFTER

    companion object {
        /** Same threshold as the backend's Planning Intelligence (docs/06 Webfleet). */
        val STALE_AFTER: Duration = Duration.ofMinutes(15)
    }
}
