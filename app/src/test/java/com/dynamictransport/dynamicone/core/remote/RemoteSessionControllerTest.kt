package com.dynamictransport.dynamicone.core.remote

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.mdm.DeviceCommandExecutor
import com.dynamictransport.dynamicone.core.mdm.DeviceCommandLedger
import com.dynamictransport.dynamicone.core.mdm.DeviceCommandReporter
import com.dynamictransport.dynamicone.core.mdm.RemoteRequestOutcome
import com.dynamictransport.dynamicone.domain.model.DeviceCommandResult
import com.dynamictransport.dynamicone.domain.model.RemoteSessionRequest
import com.dynamictransport.dynamicone.testing.FakeDeviceCheckInScheduler
import com.dynamictransport.dynamicone.testing.FakeDeviceManagementGateway
import com.dynamictransport.dynamicone.testing.FakeRemoteSessionNotifier
import com.dynamictransport.dynamicone.testing.FakeScreenStreamLauncher
import com.dynamictransport.dynamicone.testing.FakeServerClock
import com.dynamictransport.dynamicone.testing.InMemoryPreferencesDataStore
import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.TEST_NOW
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** docs/29 §4: nothing is streamed before the driver accepted; decline and timeout tell the server, then the command. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RemoteSessionControllerTest {

    private val clock = MutableClock()
    private val gateway = FakeDeviceManagementGateway()
    private val notifier = FakeRemoteSessionNotifier()
    private val streams = FakeScreenStreamLauncher()
    private val scheduler = FakeDeviceCheckInScheduler()

    private fun TestScope.controller() = RemoteSessionController(
        gateway = gateway,
        reporter = DeviceCommandReporter(DeviceCommandLedger(InMemoryPreferencesDataStore(), clock), gateway),
        notifier = notifier,
        streams = streams,
        scheduler = scheduler,
        clock = FakeServerClock(clock),
        scope = backgroundScope,
    )

    private fun request(sessionId: String = "s-1", commandId: String = "c-1") =
        RemoteSessionRequest(commandId, sessionId, "Erkan Barlaz", TEST_NOW.plusSeconds(90))

    @Test
    fun `a request shows the prompt and starts nothing`() = runTest {
        val controller = controller()

        assertEquals(RemoteRequestOutcome.PROMPTED, controller.request(request()))

        assertEquals(request(), controller.pending.value)
        assertEquals(listOf(request()), notifier.shown)
        assertTrue(streams.started.isEmpty())
        assertNull(controller.activeSessionId.value)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `decline tells the server first and then answers the command`() = runTest {
        val controller = controller()
        controller.request(request())

        controller.decline("s-1")

        assertNull(controller.pending.value)
        assertEquals(listOf("decline:s-1", "result:c-1:succeeded"), gateway.calls)
        assertEquals(DeviceCommandResult.succeeded(DeviceCommandExecutor.DETAIL_DECLINED), gateway.results.single().second)
        assertTrue(streams.started.isEmpty())
        assertEquals(1, notifier.cancels)
        assertEquals(1, scheduler.requests)

        // A second tap, or the timeout arriving later, changes nothing.
        controller.decline("s-1")
        advanceTimeBy(120_000)
        assertEquals(2, gateway.calls.size)
    }

    @Test
    fun `an unanswered request is declined when it expires`() = runTest {
        val controller = controller()
        controller.request(request())

        advanceTimeBy(89_000)
        runCurrent()
        assertEquals(request(), controller.pending.value)

        advanceTimeBy(1_001)
        runCurrent()
        assertNull(controller.pending.value)
        assertEquals(listOf("decline:s-1", "result:c-1:succeeded"), gateway.calls)
        assertEquals(DeviceCommandResult.succeeded(DeviceCommandExecutor.DETAIL_TIMEOUT), gateway.results.single().second)
        assertTrue(streams.started.isEmpty())
    }

    @Test
    fun `accept starts the stream for that session and answers the command without a decline`() = runTest {
        val controller = controller()
        controller.request(request())

        assertTrue(controller.accept("s-1", Activity.RESULT_OK, Intent()))
        runCurrent()

        assertEquals(listOf("s-1"), streams.started)
        assertEquals("s-1", controller.activeSessionId.value)
        assertNull(controller.pending.value)
        assertEquals(listOf("result:c-1:succeeded"), gateway.calls)
        // The expiry of the request no longer matters.
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(gateway.declined.isEmpty())

        controller.onStreamEnded("s-1")
        assertNull(controller.activeSessionId.value)
    }

    @Test
    fun `accepting a request that is no longer waiting starts nothing`() = runTest {
        val controller = controller()
        controller.request(request())
        controller.decline("s-1")

        assertFalse(controller.accept("s-1", Activity.RESULT_OK, Intent()))
        assertFalse(controller.accept("s-9", Activity.RESULT_OK, Intent()))

        assertTrue(streams.started.isEmpty())
        assertNull(controller.activeSessionId.value)
    }

    @Test
    fun `a second request while one waits or streams is declined as busy`() = runTest {
        val controller = controller()
        controller.request(request())

        assertEquals(RemoteRequestOutcome.BUSY, controller.request(request(sessionId = "s-2", commandId = "c-2")))
        assertEquals(listOf("s-2"), gateway.declined)
        assertEquals(request(), controller.pending.value)

        controller.accept("s-1", Activity.RESULT_OK, Intent())
        assertEquals(RemoteRequestOutcome.BUSY, controller.request(request(sessionId = "s-3", commandId = "c-3")))
        assertEquals(listOf("s-2", "s-3"), gateway.declined)
    }

    @Test
    fun `a request that arrives after its expiry is declined without a prompt`() = runTest {
        val controller = controller()
        clock.now = TEST_NOW.plusSeconds(91)

        assertEquals(RemoteRequestOutcome.EXPIRED, controller.request(request()))

        assertNull(controller.pending.value)
        assertTrue(notifier.shown.isEmpty())
        assertEquals(listOf("s-1"), gateway.declined)
    }

    @Test
    fun `the office ending the session stops this session's stream only`() = runTest {
        val controller = controller()
        controller.request(request())
        controller.accept("s-1", Activity.RESULT_OK, Intent())

        controller.end("s-other")
        assertEquals(0, streams.stops)

        controller.end("s-1")
        assertEquals(1, streams.stops)
    }

    @Test
    fun `the office withdrawing a request closes the prompt`() = runTest {
        val controller = controller()
        controller.request(request())

        controller.end("s-1")

        assertNull(controller.pending.value)
        assertEquals(1, notifier.cancels)
        assertEquals(0, streams.stops)
        assertEquals(listOf("result:c-1:succeeded"), gateway.calls)
    }
}
