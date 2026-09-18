package com.dynamictransport.dynamicone.domain.rules

import com.dynamictransport.dynamicone.domain.model.DriverTimes
import com.dynamictransport.dynamicone.domain.model.DriverTimesAvailability
import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.testing.restingTimes
import com.dynamictransport.dynamicone.testing.workingTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** When the wake-up alarm rings (docs/16 §1, decisions 18.09.2026). */
class RestAlarmRulesTest {

    private val now = Instant.parse("2026-09-18T23:00:00Z")
    private val restEnd = Instant.parse("2026-09-19T05:40:00Z")
    private val restStart = Instant.parse("2026-09-18T20:40:00Z")
    private val rest = RestWindow(restStart, restEnd)
    private val dailyShiftOff = Shift.NotStarted.copy(requiresVehicleCheck = true)

    private fun plan(
        times: DriverTimes? = restingTimes(end = restEnd, start = restStart),
        shift: Shift = dailyShiftOff,
        settings: RestAlarmSettings = RestAlarmSettings(),
        at: Instant = now,
    ) = RestAlarmRules.plan(times, shift, settings, at)

    @Test
    fun `the alarm is set for the end of the daily rest`() {
        assertEquals(RestAlarmPlan.Scheduled(rest, restEnd), plan())
    }

    @Test
    fun `the driver can be woken 15 or 30 minutes before the end`() {
        assertEquals(RestAlarmPlan.Scheduled(rest, restEnd.minus(Duration.ofMinutes(30))), plan(settings = RestAlarmSettings(lead = Duration.ofMinutes(30))))
    }

    @Test
    fun `no alarm without tachograph data or without a rest`() {
        assertEquals(RestAlarmPlan.Off(RestAlarmOffReason.NO_DATA), plan(times = null))
        assertEquals(RestAlarmPlan.Off(RestAlarmOffReason.NO_DATA), plan(times = DriverTimes(DriverTimesAvailability.NOT_LINKED)))
        assertEquals(RestAlarmPlan.Off(RestAlarmOffReason.NOT_RESTING), plan(times = workingTimes()))
    }

    @Test
    fun `a started shift means the driver is up`() {
        val active = dailyShiftOff.copy(status = ShiftStatus.ACTIVE, startedAt = Instant.parse("2026-09-19T05:20:00Z"))
        assertEquals(RestAlarmPlan.Off(RestAlarmOffReason.SHIFT_STARTED, rest), plan(shift = active))
        // Started early and ended again (the tachograph summary was not refreshed yet): still no alarm for that rest.
        val endedAfterRestStart = active.copy(status = ShiftStatus.FINISHED, endedAt = Instant.parse("2026-09-19T05:30:00Z"))
        assertEquals(RestAlarmPlan.Off(RestAlarmOffReason.SHIFT_STARTED, rest), plan(shift = endedAfterRestStart))
    }

    @Test
    fun `yesterday's shift does not stop tonight's alarm`() {
        val yesterday = dailyShiftOff.copy(
            status = ShiftStatus.FINISHED,
            startedAt = Instant.parse("2026-09-18T06:00:00Z"),
            endedAt = restStart,
        )
        assertEquals(RestAlarmPlan.Scheduled(rest, restEnd), plan(shift = yesterday))
    }

    @Test
    fun `switched off for this rest, it stays off for the same rest also when the end moves`() {
        val settings = RestAlarmSettings(dismissed = rest)
        assertEquals(RestAlarmPlan.Off(RestAlarmOffReason.DISMISSED, rest), plan(settings = settings))
        val moved = restingTimes(end = restEnd.plus(Duration.ofMinutes(20)), start = restStart.plus(Duration.ofMinutes(20)))
        assertTrue(plan(times = moved, settings = settings) is RestAlarmPlan.Off)
        // The next night's rest is a different rest and rings again.
        val nextNight = restingTimes(end = restEnd.plus(Duration.ofDays(1)), start = restStart.plus(Duration.ofDays(1)))
        assertTrue(plan(times = nextNight, settings = settings, at = now.plus(Duration.ofDays(1))) is RestAlarmPlan.Scheduled)
    }

    @Test
    fun `an alarm rings once per rest, a snooze rings again`() {
        val rung = RestAlarmSettings(rung = rest)
        assertEquals(RestAlarmPlan.Off(RestAlarmOffReason.ALREADY_RUNG, rest), plan(settings = rung, at = restEnd.plusSeconds(5)))
        val snoozeUntil = restEnd.plus(Duration.ofMinutes(10))
        assertEquals(
            RestAlarmPlan.Scheduled(rest, snoozeUntil, snoozed = true),
            plan(settings = rung.copy(snoozedUntil = snoozeUntil), at = restEnd.plusSeconds(5)),
        )
    }

    @Test
    fun `learned about shortly after the end it rings at once, much later not at all`() {
        assertEquals(RestAlarmPlan.RingNow(rest), plan(at = restEnd.plus(Duration.ofMinutes(20))))
        assertEquals(RestAlarmPlan.Off(RestAlarmOffReason.TOO_LATE, rest), plan(at = restEnd.plus(Duration.ofMinutes(61))))
    }

    @Test
    fun `a missing rest start is estimated from the minimum duration`() {
        val times = restingTimes(end = restEnd, start = null, minimum = Duration.ofHours(11))
        assertEquals(RestAlarmPlan.Scheduled(RestWindow(restEnd.minus(Duration.ofHours(11)), restEnd), restEnd), plan(times = times))
    }

    @Test
    fun `the alarm time is an instant, daylight saving does not move it`() {
        // Night of the switch to winter time in Warsaw (25/26 Oct 2026): 9 h rest ending 06:00 local.
        val warsaw = ZoneId.of("Europe/Warsaw")
        val end = ZonedDateTime.of(2026, 10, 25, 6, 0, 0, 0, warsaw).toInstant()
        val start = end.minus(Duration.ofHours(9))
        val result = plan(times = restingTimes(end = end, start = start), at = start.plusSeconds(60))
        assertEquals(RestAlarmPlan.Scheduled(RestWindow(start, end), end), result)
        assertEquals("06:00", (result as RestAlarmPlan.Scheduled).triggerAt.atZone(warsaw).toLocalTime().toString())
    }

    @Test
    fun `a fired alarm rings only while the rest still calls for it`() {
        val times = restingTimes(end = restEnd, start = restStart)
        assertTrue(RestAlarmRules.shouldRing(rest, times, dailyShiftOff, RestAlarmSettings(), snooze = false))
        assertFalse(RestAlarmRules.shouldRing(rest, times, dailyShiftOff, RestAlarmSettings(rung = rest), snooze = false))
        assertTrue(RestAlarmRules.shouldRing(rest, times, dailyShiftOff, RestAlarmSettings(rung = rest), snooze = true))
        assertFalse(RestAlarmRules.shouldRing(rest, times, dailyShiftOff, RestAlarmSettings(dismissed = rest), snooze = true))
        assertFalse(RestAlarmRules.shouldRing(rest, workingTimes(), dailyShiftOff, RestAlarmSettings(), snooze = false))
        val active = dailyShiftOff.copy(status = ShiftStatus.ACTIVE, startedAt = restEnd)
        assertFalse(RestAlarmRules.shouldRing(rest, times, active, RestAlarmSettings(), snooze = false))
        // Without data (not read yet after a restart) the phone's plan stands.
        assertTrue(RestAlarmRules.shouldRing(rest, null, dailyShiftOff, RestAlarmSettings(), snooze = false))
    }
}
