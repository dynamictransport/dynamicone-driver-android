package com.dynamictransport.dynamicone.domain.rules

import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.CheckAnswer
import com.dynamictransport.dynamicone.domain.model.CheckPhoto
import com.dynamictransport.dynamicone.domain.model.CheckResult
import com.dynamictransport.dynamicone.domain.model.CheckTemplate
import com.dynamictransport.dynamicone.domain.model.LocalShiftCommand
import com.dynamictransport.dynamicone.domain.model.ServerShiftState
import com.dynamictransport.dynamicone.domain.model.ShiftCommandState
import com.dynamictransport.dynamicone.domain.model.ShiftCommandType
import com.dynamictransport.dynamicone.domain.model.ShiftEndedBy
import com.dynamictransport.dynamicone.domain.model.ShiftRecord
import com.dynamictransport.dynamicone.domain.model.ShiftStartDraft
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.model.VehicleIdentification
import com.dynamictransport.dynamicone.domain.model.VehicleInfo
import com.dynamictransport.dynamicone.testing.restingTimes
import com.dynamictransport.dynamicone.testing.workingTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DailyShiftRulesTest {

    private val morning = Instant.parse("2026-09-19T05:50:00Z")

    private fun record(id: String = "shift-1", start: Instant = morning, end: Instant? = null, endedBy: ShiftEndedBy? = null) = ShiftRecord(
        id = id,
        commandId = "start-$id",
        startedAt = start,
        endedAt = end,
        endedBy = endedBy,
        vehicle = VehicleInfo("veh-1", "WX 12345"),
        identifiedBy = VehicleIdentification.NFC,
        trailerPlate = "WGM 1234A",
        startedBeforeRestEnd = false,
        vehicleCheck = null,
    )

    private fun command(id: String, type: ShiftCommandType, at: Instant, state: ShiftCommandState = ShiftCommandState.PENDING) =
        LocalShiftCommand(id, type, at, state, null, "WX 12345", VehicleIdentification.NFC, defectCount = 1)

    @Test
    fun `the server state is the base`() {
        val active = DailyShiftRules.resolve(ServerShiftState(record(), null, null), emptyList(), null)
        assertEquals(ShiftStatus.ACTIVE, active.status)
        assertEquals("WX 12345", active.vehiclePlate)
        assertEquals("start-shift-1", active.startCommandId)
        assertTrue(active.requiresVehicleCheck)
        assertEquals(SyncState.SYNCED, active.syncState)

        val ended = DailyShiftRules.resolve(ServerShiftState(null, record(end = morning.plusSeconds(3600)), null), emptyList(), null)
        assertEquals(ShiftStatus.FINISHED, ended.status)
        assertEquals(ShiftStatus.NOT_STARTED, DailyShiftRules.resolve(null, emptyList(), null).status)
    }

    @Test
    fun `a start on the phone shows the shift running and waiting to be sent`() {
        val shift = DailyShiftRules.resolve(null, listOf(command("c1", ShiftCommandType.START, morning)), null)
        assertEquals(ShiftStatus.ACTIVE, shift.status)
        assertEquals(SyncState.PENDING, shift.syncState)
        assertEquals("c1", shift.startCommandId)
        assertEquals(1, shift.vehicleCheck?.defectCount)
    }

    @Test
    fun `an end on the phone finishes the running shift, a rejected start changes nothing`() {
        val started = listOf(
            command("c1", ShiftCommandType.START, morning),
            command("c2", ShiftCommandType.END, morning.plusSeconds(3600)),
        )
        val ended = DailyShiftRules.resolve(null, started, null)
        assertEquals(ShiftStatus.FINISHED, ended.status)
        assertEquals(morning.plusSeconds(3600), ended.endedAt)

        val rejected = DailyShiftRules.resolve(null, listOf(command("c1", ShiftCommandType.START, morning, ShiftCommandState.FAILED)), null)
        assertEquals(ShiftStatus.NOT_STARTED, rejected.status)
    }

    @Test
    fun `a start the server already recorded is not applied twice`() {
        val server = ServerShiftState(record(start = morning.minusSeconds(5)).copy(commandId = "c1"), null, null)
        val shift = DailyShiftRules.resolve(server, listOf(command("c1", ShiftCommandType.START, morning, ShiftCommandState.SENDING)), null)
        assertEquals("shift-1", shift.id)
        assertEquals(SyncState.SYNCED, shift.syncState)
    }

    @Test
    fun `a daily rest after the start ends the shift (docs 16 section 9_1)`() {
        val restStart = morning.plus(Duration.ofHours(12))
        val times = restingTimes(end = restStart.plus(Duration.ofHours(11)), start = restStart)
        val shift = DailyShiftRules.resolve(ServerShiftState(record(), null, null), emptyList(), times)
        assertEquals(ShiftStatus.FINISHED, shift.status)
        assertEquals(restStart, shift.endedAt)
        assertTrue(shift.endedByTachograph)
        assertTrue(shift.startRequired)

        // Last night's rest does not end this morning's shift.
        val lastNight = restingTimes(end = morning.minusSeconds(600), start = morning.minus(Duration.ofHours(9)))
        assertEquals(ShiftStatus.ACTIVE, DailyShiftRules.resolve(ServerShiftState(record(), null, null), emptyList(), lastNight).status)
    }

    @Test
    fun `the rest is not over while its end is ahead`() {
        val times = restingTimes(end = morning.plus(Duration.ofMinutes(25)))
        assertEquals(Duration.ofMinutes(25), DailyShiftRules.restNotOver(times, morning))
        assertNull(DailyShiftRules.restNotOver(times, morning.plus(Duration.ofMinutes(25))))
        assertNull(DailyShiftRules.restNotOver(workingTimes(), morning))
        assertNull(DailyShiftRules.restNotOver(null, morning))
    }

    private val template = CheckTemplate.Default
    private fun allOk() = template.items.map { CheckAnswer(it.key, CheckResult.OK) }

    @Test
    fun `a complete check needs every item exactly once`() {
        assertNull(VehicleCheckRules.validate(template, allOk()))
        assertEquals(ActionRejection.NOT_ALLOWED, VehicleCheckRules.validate(template, allOk().drop(1)))
        assertEquals(ActionRejection.NOT_ALLOWED, VehicleCheckRules.validate(template, allOk() + CheckAnswer("tyres", CheckResult.OK)))
        assertEquals(ActionRejection.NOT_ALLOWED, VehicleCheckRules.validate(template, allOk() + CheckAnswer("unknown", CheckResult.OK)))
    }

    @Test
    fun `a defect needs its photo, not applicable only where allowed`() {
        val defectWithoutPhoto = allOk().map { if (it.key == "lights") CheckAnswer("lights", CheckResult.DEFECT) else it }
        assertEquals(ActionRejection.PHOTO_REQUIRED, VehicleCheckRules.validate(template, defectWithoutPhoto))
        val defectWithPhoto = allOk().map { if (it.key == "lights") CheckAnswer("lights", CheckResult.DEFECT, "Left brake light", listOf(CheckPhoto("p1", "/x.jpg"))) else it }
        assertNull(VehicleCheckRules.validate(template, defectWithPhoto))

        val containerNa = allOk().map { if (it.key == "container_locks") CheckAnswer(it.key, CheckResult.NOT_APPLICABLE) else it }
        assertNull(VehicleCheckRules.validate(template, containerNa))
        val tyresNa = allOk().map { if (it.key == "tyres") CheckAnswer(it.key, CheckResult.NOT_APPLICABLE) else it }
        assertEquals(ActionRejection.NOT_ALLOWED, VehicleCheckRules.validate(template, tyresNa))

        val longNote = allOk().map { if (it.key == "lights") CheckAnswer("lights", CheckResult.DEFECT, "x".repeat(501), listOf(CheckPhoto("p1", "/x.jpg"))) else it }
        assertEquals(ActionRejection.NOTE_TOO_LONG, VehicleCheckRules.validate(template, longNote))
    }

    @Test
    fun `a start names its vehicle one way`() {
        val draft = ShiftStartDraft("c1", "04A224B2C35E80", null, morning, null, false, template, allOk())
        assertNull(VehicleCheckRules.validate(draft))
        assertEquals(ActionRejection.NOT_ALLOWED, VehicleCheckRules.validate(draft.copy(tagUid = null)))
        assertNull(VehicleCheckRules.validate(draft.copy(tagUid = null, manualVehicle = VehicleInfo("veh-1", "WX 12345"))))
        assertEquals(ActionRejection.NOT_ALLOWED, VehicleCheckRules.validate(draft.copy(manualVehicle = VehicleInfo("veh-1", "WX 12345"))))
    }

    @Test
    fun `an item is complete when it may move on`() {
        val lights = template.items.first { it.key == "lights" }
        assertFalse(VehicleCheckRules.isItemComplete(lights, null))
        assertTrue(VehicleCheckRules.isItemComplete(lights, CheckAnswer("lights", CheckResult.OK)))
        assertFalse(VehicleCheckRules.isItemComplete(lights, CheckAnswer("lights", CheckResult.DEFECT)))
        assertTrue(VehicleCheckRules.isItemComplete(lights, CheckAnswer("lights", CheckResult.DEFECT, photos = listOf(CheckPhoto("p", "/p.jpg")))))
        assertFalse(VehicleCheckRules.isItemComplete(lights, CheckAnswer("tyres", CheckResult.OK)))
        assertEquals("Işıklar ve sinyaller çalışıyor", lights.title("tr"))
        assertEquals("Lights and indicators work", lights.title("de"))
    }
}
