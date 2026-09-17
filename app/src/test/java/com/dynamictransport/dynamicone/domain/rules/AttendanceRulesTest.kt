package com.dynamictransport.dynamicone.domain.rules

import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection.IN
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection.OUT
import com.dynamictransport.dynamicone.domain.model.AttendancePunch
import com.dynamictransport.dynamicone.domain.model.AttendancePunchSource
import com.dynamictransport.dynamicone.domain.model.AttendanceStatus
import com.dynamictransport.dynamicone.domain.model.LocalPunch
import com.dynamictransport.dynamicone.domain.model.LocalPunchState
import com.dynamictransport.dynamicone.domain.model.PunchFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class AttendanceRulesTest {

    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val now = Instant.parse("2026-09-17T10:00:00Z")
    private val today = LocalDate.of(2026, 9, 17)

    private fun serverPunch(direction: AttendanceDirection, at: String, commandId: String? = null) =
        AttendancePunch("p-$at", commandId, direction, Instant.parse(at), null, AttendancePunchSource.MOBILE_NFC, null)

    private fun status(checkedIn: Boolean, workDate: LocalDate = today, last: AttendancePunch?) =
        AttendanceStatus(checkedIn, if (checkedIn) OUT else IN, workDate, warsaw, last)

    private fun local(
        commandId: String,
        direction: AttendanceDirection,
        scannedAt: String,
        state: LocalPunchState = LocalPunchState.PENDING,
    ) = LocalPunch(
        commandId = commandId,
        direction = direction,
        scannedAt = Instant.parse(scannedAt),
        state = state,
        failure = if (state == LocalPunchState.FAILED) PunchFailure.TAG_UNKNOWN else null,
        occurredAt = null,
        pointName = null,
    )

    @Test
    fun `tag identifiers become upper-case hex of 4 to 10 bytes`() {
        assertEquals("04A224B2C35E80", AttendanceRules.tagUid(byteArrayOf(0x04, 0xA2.toByte(), 0x24, 0xB2.toByte(), 0xC3.toByte(), 0x5E, 0x80.toByte())))
        assertEquals("00000001", AttendanceRules.tagUid(byteArrayOf(0, 0, 0, 1)))
        assertNull(AttendanceRules.tagUid(byteArrayOf(1, 2, 3)))
        assertNull(AttendanceRules.tagUid(ByteArray(11)))
        assertNull(AttendanceRules.tagUid(null as ByteArray?))

        assertEquals("04A224B2C35E80", AttendanceRules.tagUid("04:a2:24:b2:c3:5e:80"))
        assertEquals("04A224B2", AttendanceRules.tagUid(" 04 A2 24 B2 "))
        assertNull(AttendanceRules.tagUid("04A2"))
        assertNull(AttendanceRules.tagUid("04A224BZ"))
        assertNull(AttendanceRules.tagUid("04A224B"))
    }

    @Test
    fun `without any record the only action is starting work`() {
        val situation = AttendanceRules.situation(null, emptyList(), now, ZoneOffset.UTC)

        assertFalse(situation.checkedIn)
        assertEquals(IN, situation.nextDirection)
        assertNull(situation.checkedInSince)
    }

    @Test
    fun `the server status decides when the phone knows nothing newer`() {
        val checkIn = serverPunch(IN, "2026-09-17T06:57:00Z")

        val situation = AttendanceRules.situation(status(checkedIn = true, last = checkIn), emptyList(), now, ZoneOffset.UTC)

        assertTrue(situation.checkedIn)
        assertEquals(OUT, situation.nextDirection)
        assertEquals(checkIn.occurredAt, situation.checkedInSince)
        assertEquals(warsaw, situation.zone)
    }

    @Test
    fun `a check-in left open yesterday does not carry over to today`() {
        val yesterday = serverPunch(IN, "2026-09-16T07:00:00Z")

        val situation = AttendanceRules.situation(
            status(checkedIn = true, workDate = today.minusDays(1), last = yesterday),
            emptyList(),
            now,
            ZoneOffset.UTC,
        )

        assertFalse(situation.checkedIn)
        assertEquals(IN, situation.nextDirection)
    }

    @Test
    fun `a punch waiting on the phone is newer knowledge than the server status`() {
        val checkIn = serverPunch(IN, "2026-09-17T06:57:00Z", commandId = "cmd-in")
        val checkOut = local("cmd-out", OUT, "2026-09-17T09:30:00Z")

        val situation = AttendanceRules.situation(status(checkedIn = true, last = checkIn), listOf(checkOut), now, ZoneOffset.UTC)

        assertFalse(situation.checkedIn)
        assertEquals(IN, situation.nextDirection)
    }

    @Test
    fun `offline check-in without a server status offers ending work`() {
        val checkIn = local("cmd-in", IN, "2026-09-17T07:00:00Z")

        val situation = AttendanceRules.situation(null, listOf(checkIn), now, warsaw)

        assertTrue(situation.checkedIn)
        assertEquals(OUT, situation.nextDirection)
        assertEquals(checkIn.scannedAt, situation.checkedInSince)
    }

    @Test
    fun `rejected punches and punches the server already lists change nothing`() {
        val recorded = serverPunch(OUT, "2026-09-17T09:00:00Z", commandId = "cmd-out")
        val rejected = local("cmd-bad", IN, "2026-09-17T09:40:00Z", LocalPunchState.FAILED)
        val sameAsServer = local("cmd-out", OUT, "2026-09-17T09:00:00Z", LocalPunchState.SYNCED)

        val situation = AttendanceRules.situation(status(checkedIn = false, last = recorded), listOf(rejected, sameAsServer), now, ZoneOffset.UTC)

        assertFalse(situation.checkedIn)
        assertEquals(IN, situation.nextDirection)
    }

    @Test
    fun `the clock change of a DST night does not move the work day`() {
        // Warsaw goes from CEST to CET in the night of 25.10.2026: 02:59:59+02:00 is followed by 02:00:00+01:00.
        val beforeChange = Instant.parse("2026-10-25T00:30:00Z") // 02:30+02:00, still the 25th
        val afterChange = Instant.parse("2026-10-25T01:30:00Z") // 02:30+01:00, the same work day
        val checkIn = local("cmd-in", IN, "2026-10-24T22:10:00Z") // 00:10+02:00 on the 25th
        val dstDay = LocalDate.of(2026, 10, 25)
        val status = AttendanceStatus(true, OUT, dstDay, warsaw, null)

        val before = AttendanceRules.situation(status, listOf(checkIn), beforeChange, ZoneOffset.UTC)
        val after = AttendanceRules.situation(status, listOf(checkIn), afterChange, ZoneOffset.UTC)

        assertEquals(dstDay, before.today)
        assertEquals(dstDay, after.today)
        assertTrue(after.checkedIn)
        assertEquals(OUT, after.nextDirection)

        // The next morning is a new work day: an open check-in of the DST night does not carry over.
        val nextMorning = AttendanceRules.situation(status, listOf(checkIn), Instant.parse("2026-10-26T06:00:00Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 26), nextMorning.today)
        assertFalse(nextMorning.checkedIn)
        assertEquals(IN, nextMorning.nextDirection)
    }

    @Test
    fun `today is the work date of the office time zone, not of the phone`() {
        // 23:30 UTC on the 16th is already the 17th in Warsaw.
        val lateEvening = Instant.parse("2026-09-16T23:30:00Z")
        val checkIn = local("cmd-in", IN, "2026-09-16T22:10:00Z")
        val status = AttendanceStatus(false, IN, today, warsaw, null)

        val situation = AttendanceRules.situation(status, listOf(checkIn), lateEvening, ZoneOffset.UTC)

        assertEquals(today, situation.today)
        assertTrue(situation.checkedIn)
    }
}
