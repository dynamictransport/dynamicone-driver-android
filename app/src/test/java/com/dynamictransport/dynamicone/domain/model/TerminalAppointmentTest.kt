package com.dynamictransport.dynamicone.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class TerminalAppointmentTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")
    private val start = Instant.parse("2026-10-22T08:00:00Z")
    private val end = Instant.parse("2026-10-22T10:00:00Z")
    private val tar = TerminalAppointment("001234", start, end, amsterdam)

    @Test
    fun `before the window the countdown runs to the start`() {
        val status = tar.statusAt(start.minusSeconds(3_600), StopStatus.PLANNED)
        assertEquals(TarPhase.UPCOMING, status.phase)
        assertEquals(Duration.ofHours(1), status.remaining)
    }

    @Test
    fun `the start itself is inside the window and counts down to the end`() {
        val status = tar.statusAt(start, StopStatus.EN_ROUTE)
        assertEquals(TarPhase.ACTIVE, status.phase)
        assertEquals(Duration.ofHours(2), status.remaining)
    }

    @Test
    fun `the last thirty minutes are a warning`() {
        assertEquals(TarPhase.ACTIVE, tar.statusAt(end.minusSeconds(30 * 60 + 1), StopStatus.ARRIVED).phase)
        val status = tar.statusAt(end.minusSeconds(30 * 60), StopStatus.ARRIVED)
        assertEquals(TarPhase.ENDING_SOON, status.phase)
        assertEquals(Duration.ofMinutes(30), status.remaining)
        assertEquals(TarPhase.ENDING_SOON, tar.statusAt(end.minusSeconds(1), StopStatus.ARRIVED).phase)
    }

    @Test
    fun `the end is exclusive and an open stop past it is expired`() {
        val status = tar.statusAt(end, StopStatus.ARRIVED)
        assertEquals(TarPhase.EXPIRED, status.phase)
        assertNull(status.remaining)
        assertFalse(status.phase.counting)
    }

    @Test
    fun `a completed or skipped stop shows its TAR as history without a countdown`() {
        for (stopStatus in listOf(StopStatus.COMPLETED, StopStatus.SKIPPED)) {
            val status = tar.statusAt(start.plusSeconds(60), stopStatus)
            assertEquals(stopStatus.name, TarPhase.PAST, status.phase)
            assertNull(status.remaining)
        }
        // An open stop with the same clock is active: the stop status, not the time, makes it history.
        assertTrue(tar.statusAt(start.plusSeconds(60), StopStatus.PLANNED).phase.counting)
    }

    @Test
    fun `phases are decided on UTC instants whatever the terminal zone`() {
        val tokyo = tar.copy(timeZone = ZoneId.of("Asia/Tokyo"))
        assertEquals(tar.statusAt(start.minusSeconds(1), StopStatus.PLANNED), tokyo.statusAt(start.minusSeconds(1), StopStatus.PLANNED))
        assertEquals(tar.statusAt(end, StopStatus.PLANNED), tokyo.statusAt(end, StopStatus.PLANNED))
    }

    @Test
    fun `midnight is crossed in the terminal zone, not in UTC`() {
        // 23:30 – 01:00 Amsterdam (summer time) is 21:30 – 23:00 UTC: one UTC day, two local days.
        val night = TerminalAppointment("7", Instant.parse("2026-10-22T21:30:00Z"), Instant.parse("2026-10-22T23:00:00Z"), amsterdam)
        assertTrue(night.crossesMidnight)
        assertFalse(night.copy(timeZone = ZoneId.of("UTC")).crossesMidnight)
        assertFalse(tar.crossesMidnight)
    }
}
