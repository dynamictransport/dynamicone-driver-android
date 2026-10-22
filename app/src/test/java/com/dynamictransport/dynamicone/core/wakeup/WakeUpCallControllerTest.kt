package com.dynamictransport.dynamicone.core.wakeup

import com.dynamictransport.dynamicone.core.push.WakeUpCallSignalHandler
import com.dynamictransport.dynamicone.core.push.WakeUpSignal
import com.dynamictransport.dynamicone.domain.model.WakeUpDecision
import com.dynamictransport.dynamicone.testing.FakeAlarmVolumeBooster
import com.dynamictransport.dynamicone.testing.FakeWakeUpCallRepository
import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.RecordingWakeUpCallNotifier
import com.dynamictransport.dynamicone.testing.wakeUpCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Ringing and silencing on the device (docs/25 §2): answer, stop push, deadline, restart. */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeUpCallControllerTest {

    private val repository = FakeWakeUpCallRepository()
    private val notifier = RecordingWakeUpCallNotifier()
    private val volume = FakeAlarmVolumeBooster()
    private val clock = MutableClock(Instant.parse("2026-10-22T05:05:10Z"))

    private fun controller(scope: CoroutineScope) = WakeUpCallController(repository, notifier, volume, clock, scope)

    @Test
    fun `a call rings loud with its deadline and the cockpit learns that it rings`() = runTest {
        val controller = controller(this)

        controller.ring(wakeUpCall())
        runCurrent()

        assertEquals(listOf("call-1"), notifier.rung)
        assertEquals(1, volume.raised)
        assertEquals("call-1", controller.ringing.value?.callId)
        assertEquals(listOf("call-1"), repository.ringingReports)

        controller.acknowledge("call-1")
    }

    @Test
    fun `the driver's answer stops the sound, restores the volume and closes the screen`() = runTest {
        val controller = controller(this)
        controller.ring(wakeUpCall())
        runCurrent()

        controller.acknowledge("call-1")

        assertEquals(listOf("call-1"), repository.acknowledged)
        assertEquals(1, notifier.cancels)
        assertEquals(1, volume.restored)
        assertNull(controller.ringing.value)
        // The deadline no longer fires for an answered call.
        advanceTimeBy(Duration.ofMinutes(6).toMillis())
        runCurrent()
        assertTrue(repository.expired.isEmpty())
    }

    @Test
    fun `a stop push silences the call without an answer`() = runTest {
        val controller = controller(this)
        controller.ring(wakeUpCall())
        runCurrent()

        controller.stop("call-1")

        assertEquals(listOf("call-1"), repository.stops)
        assertTrue(repository.acknowledged.isEmpty())
        assertEquals(1, notifier.cancels)
        assertNull(controller.ringing.value)
    }

    @Test
    fun `a stop for another call leaves the ringing one alone`() = runTest {
        val controller = controller(this)
        controller.ring(wakeUpCall())
        runCurrent()

        controller.stop("call-9")

        assertEquals(0, notifier.cancels)
        assertEquals("call-1", controller.ringing.value?.callId)
        controller.acknowledge("call-1")
    }

    @Test
    fun `without an answer the call rings out at its deadline and is reported as missed`() = runTest {
        val controller = controller(this)
        controller.ring(wakeUpCall())
        runCurrent()

        advanceTimeBy(Duration.ofMinutes(4).toMillis())
        runCurrent()
        assertTrue(repository.expired.isEmpty())

        advanceTimeBy(Duration.ofMinutes(1).toMillis())
        runCurrent()

        assertEquals(listOf("call-1"), repository.expired)
        assertEquals(1, notifier.cancels)
        assertEquals(1, volume.restored)
        assertNull(controller.ringing.value)
    }

    @Test
    fun `a call whose deadline passed does not ring at all`() = runTest {
        val controller = controller(this)
        clock.now = Instant.parse("2026-10-22T05:10:00Z")

        controller.ring(wakeUpCall())
        runCurrent()

        assertTrue(notifier.rung.isEmpty())
        assertEquals(listOf("call-1"), repository.expired)
        assertNull(controller.ringing.value)
    }

    @Test
    fun `a newer call replaces the ringing one`() = runTest {
        val controller = controller(this)
        controller.ring(wakeUpCall("call-1"))
        runCurrent()

        controller.ring(wakeUpCall("call-2", expiresAt = Instant.parse("2026-10-22T05:12:00Z")))
        runCurrent()

        assertEquals(listOf("call-1", "call-2"), notifier.rung)
        assertEquals("call-2", controller.ringing.value?.callId)
        // The first call's deadline no longer silences the second one.
        advanceTimeBy(Duration.ofMinutes(5).toMillis())
        runCurrent()
        assertEquals("call-2", controller.ringing.value?.callId)
        controller.acknowledge("call-2")
    }

    @Test
    fun `after a restart a stored call rings again while its deadline holds, otherwise it is reported as missed`() = runTest {
        repository.stored = listOf(
            wakeUpCall("old", expiresAt = Instant.parse("2026-10-22T05:05:00Z")),
            wakeUpCall("live"),
        )
        val controller = controller(this)

        controller.start()
        controller.start()
        runCurrent()

        assertEquals(1, notifier.channels)
        assertEquals(listOf("live"), notifier.rung)
        assertEquals(listOf("old"), repository.expired)
        controller.acknowledge("live")
    }

    @Test
    fun `the push side rings only what the repository confirms and stops on a stop event`() = runTest {
        val controller = controller(this)
        val handler = WakeUpCallSignalHandler(repository, controller)

        handler.onSignal(WakeUpSignal.from(mapOf("type" to "driver.wakeup", "event" to "ring", "callId" to "call-1", "reason" to "call_office")))
        assertEquals(listOf(Triple("call-1", "call_office", null)), repository.signals)
        assertTrue(notifier.rung.isEmpty())

        repository.decision = WakeUpDecision.Ring(wakeUpCall())
        handler.onSignal(WakeUpSignal.from(mapOf("callId" to "call-1", "expiresAt" to "2026-10-22T05:10:00Z")))
        runCurrent()
        assertEquals(listOf("call-1"), notifier.rung)
        assertEquals(Instant.parse("2026-10-22T05:10:00Z"), repository.signals.last().third)

        handler.onSignal(WakeUpSignal.from(mapOf("event" to "stop", "callId" to "call-1")))
        assertEquals(listOf("call-1"), repository.stops)
        assertNull(controller.ringing.value)

        handler.onSignal(WakeUpSignal.from(mapOf("event" to "ring")))
        assertEquals(2, repository.signals.size)
    }
}
