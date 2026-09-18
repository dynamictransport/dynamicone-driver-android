package com.dynamictransport.dynamicone.core.alarm

import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.domain.rules.RestAlarmMode
import com.dynamictransport.dynamicone.domain.rules.RestAlarmOffReason
import com.dynamictransport.dynamicone.domain.rules.RestAlarmPlan
import com.dynamictransport.dynamicone.domain.rules.RestAlarmRules
import com.dynamictransport.dynamicone.testing.FakeShiftRepository
import com.dynamictransport.dynamicone.testing.RecordingRestAlarmScheduler
import com.dynamictransport.dynamicone.testing.RestAlarmHarness
import com.dynamictransport.dynamicone.testing.restingTimes
import com.dynamictransport.dynamicone.testing.workingTimes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RestAlarmControllerTest {

    private val restStart = Instant.parse("2026-09-11T20:40:00Z")
    private val restEnd = Instant.parse("2026-09-12T05:40:00Z")
    private val rest = RestWindow(restStart, restEnd)
    private val night = Instant.parse("2026-09-11T23:00:00Z")
    private val dailyShift = FakeShiftRepository(Shift.NotStarted.copy(requiresVehicleCheck = true))

    private fun TestScope.harness(): RestAlarmHarness =
        RestAlarmHarness(shifts = dailyShift, scope = backgroundScope + UnconfinedTestDispatcher(testScheduler)).apply { clock.now = night }

    @Test
    fun `the alarm follows the tachograph rest and is set only once`() = runTest {
        val h = harness()
        h.controller.start()
        h.times.times.value = restingTimes(end = restEnd, start = restStart)
        advanceUntilIdle()

        assertEquals(RecordingRestAlarmScheduler.Alarm(restEnd, rest, snooze = false), h.scheduler.scheduled)
        // The same plan again (e.g. another read) does not set the alarm again.
        h.times.times.value = restingTimes(end = restEnd, start = restStart, reference = night.plusSeconds(300))
        advanceUntilIdle()
        assertEquals(1, h.scheduler.history.size)

        // A later end (the tachograph moved it) moves the alarm.
        h.times.times.value = restingTimes(end = restEnd.plusSeconds(600), start = restStart)
        advanceUntilIdle()
        assertEquals(restEnd.plusSeconds(600), h.scheduler.scheduled?.triggerAt)
    }

    @Test
    fun `a fired alarm rings once with the chosen sound`() = runTest {
        val h = harness()
        h.times.times.value = restingTimes(end = restEnd, start = restStart)
        h.controller.setMode(RestAlarmMode.NOTIFICATION)
        h.clock.now = restEnd

        h.controller.onAlarmFired(rest, snooze = false)
        h.controller.onAlarmFired(rest, snooze = false)

        assertEquals(1, h.notifier.rings.size)
        assertEquals(RestAlarmMode.NOTIFICATION, h.notifier.rings.single().mode)
        assertEquals(rest, h.settings.current().rung)
    }

    @Test
    fun `snooze rings again ten minutes later`() = runTest {
        val h = harness()
        h.times.times.value = restingTimes(end = restEnd, start = restStart)
        h.clock.now = restEnd
        h.controller.onAlarmFired(rest, snooze = false)

        h.controller.snooze(rest)

        assertFalse(h.notifier.ringing)
        assertEquals(RecordingRestAlarmScheduler.Alarm(restEnd.plus(RestAlarmRules.SNOOZE), rest, snooze = true), h.scheduler.scheduled)
        h.clock.now = restEnd.plus(RestAlarmRules.SNOOZE)
        h.controller.onAlarmFired(rest, snooze = true)
        assertEquals(2, h.notifier.rings.size)
        assertNull(h.settings.current().snoozedUntil)
    }

    @Test
    fun `stop ends the ringing and nothing rings again for that rest`() = runTest {
        val h = harness()
        h.times.times.value = restingTimes(end = restEnd, start = restStart)
        h.clock.now = restEnd
        h.controller.onAlarmFired(rest, snooze = false)

        assertEquals(RingingAlarm(rest, test = false), h.controller.ringing.value)

        h.controller.stop(rest)

        assertFalse(h.notifier.ringing)
        // The alarm screen closes with the sound.
        assertNull(h.controller.ringing.value)
        h.controller.reconcile(force = true)
        assertNull(h.scheduler.scheduled)
        assertEquals(1, h.notifier.rings.size)
    }

    @Test
    fun `switched off for one rest and switched on again`() = runTest {
        val h = harness()
        h.controller.start()
        h.times.times.value = restingTimes(end = restEnd, start = restStart)
        advanceUntilIdle()

        h.controller.dismiss(rest)
        advanceUntilIdle()
        assertNull(h.scheduler.scheduled)
        assertEquals(RestAlarmPlan.Off(RestAlarmOffReason.DISMISSED, rest), h.controller.status.first().plan)
        // An alarm that goes off anyway (set before) does not ring.
        h.controller.onAlarmFired(rest, snooze = false)
        assertTrue(h.notifier.rings.isEmpty())

        h.controller.restore()
        advanceUntilIdle()
        assertEquals(restEnd, h.scheduler.scheduled?.triggerAt)
    }

    @Test
    fun `starting the shift silences a ringing alarm and cancels the next one`() = runTest {
        val h = harness()
        h.controller.start()
        h.clock.now = restEnd.plusSeconds(30)
        h.times.times.value = restingTimes(end = restEnd, start = restStart)
        advanceUntilIdle()
        // Learned about just after the end: rings at once.
        assertEquals(1, h.notifier.rings.size)
        assertTrue(h.notifier.ringing)

        dailyShift.shift.value = dailyShift.shift.value.copy(status = ShiftStatus.ACTIVE, startedAt = restEnd.plusSeconds(60))
        advanceUntilIdle()

        assertFalse(h.notifier.ringing)
        assertNull(h.scheduler.scheduled)
    }

    @Test
    fun `back at work the alarm is cancelled`() = runTest {
        val h = harness()
        h.controller.start()
        h.times.times.value = restingTimes(end = restEnd, start = restStart)
        advanceUntilIdle()
        h.times.times.value = workingTimes()
        advanceUntilIdle()
        assertNull(h.scheduler.scheduled)
        assertTrue(h.scheduler.cancels > 0)
    }

    @Test
    fun `after a restart the alarm is set again from the stored rest`() = runTest {
        val h = harness()
        h.times.times.value = restingTimes(end = restEnd, start = restStart)

        h.controller.reconcile(force = true)
        h.controller.reconcile(force = true)

        assertEquals(2, h.scheduler.history.size)
        assertEquals(restEnd, h.scheduler.scheduled?.triggerAt)
    }

    @Test
    fun `lead time and permissions are part of the status`() = runTest {
        val h = harness()
        h.times.times.value = restingTimes(end = restEnd, start = restStart)
        h.controller.setLead(Duration.ofMinutes(30))
        h.controller.setLead(Duration.ofMinutes(7))
        h.permissions.state = RestAlarmPermissionState(exactAlarms = false)
        h.controller.refreshPermissions()
        advanceUntilIdle()

        val status = h.controller.status.first()
        assertEquals(Duration.ofMinutes(30), status.lead)
        assertEquals(RestAlarmPlan.Scheduled(rest, restEnd.minus(Duration.ofMinutes(30))), status.plan)
        assertFalse(status.permissions.exactAlarms)
    }

    @Test
    fun `a test alarm rings without touching the rest memory`() = runTest {
        val h = harness()
        h.controller.ringTest()
        assertTrue(h.notifier.rings.single().test)
        assertNull(h.settings.current().rung)
        h.controller.stopTest()
        assertFalse(h.notifier.ringing)
    }
}
