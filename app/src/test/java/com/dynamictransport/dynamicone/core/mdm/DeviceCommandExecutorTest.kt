package com.dynamictransport.dynamicone.core.mdm

import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.DeviceCommandResult
import com.dynamictransport.dynamicone.domain.model.DeviceCommandStatus
import com.dynamictransport.dynamicone.domain.model.DeviceCommandTypes
import com.dynamictransport.dynamicone.testing.FakeDeviceAlerts
import com.dynamictransport.dynamicone.testing.FakeDeviceManagementGateway
import com.dynamictransport.dynamicone.testing.FakeDevicePolicyController
import com.dynamictransport.dynamicone.testing.FakeRemoteSessionCommands
import com.dynamictransport.dynamicone.testing.InMemoryPreferencesDataStore
import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.NoOpLogger
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.command
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** docs/29 §3.2–§3.4: every command id runs once, its result is posted, and what the phone cannot do is `unsupported`. */
class DeviceCommandExecutorTest {

    private val dataStore = InMemoryPreferencesDataStore()
    private val clock = MutableClock()
    private val gateway = FakeDeviceManagementGateway()
    private val policy = FakeDevicePolicyController()
    private val alerts = FakeDeviceAlerts()
    private val remoteSessions = FakeRemoteSessionCommands()

    /** A new instance is the app after a restart: only the stored ids survive. */
    private fun executor(): DeviceCommandExecutor {
        val ledger = DeviceCommandLedger(dataStore, clock)
        return DeviceCommandExecutor(ledger, DeviceCommandReporter(ledger, gateway), policy, alerts, remoteSessions, NoOpLogger)
    }

    private fun result(id: String): DeviceCommandResult = gateway.results.last { it.first == id }.second

    @Test
    fun `a command runs once however often the server returns it`() = runBlocking {
        val executor = executor()
        val lock = command("c-1", DeviceCommandTypes.LOCK)

        assertEquals(1, executor.execute(listOf(lock)))
        assertEquals(0, executor.execute(listOf(lock)))
        assertEquals(0, executor().execute(listOf(lock)))

        assertEquals(listOf("lock"), policy.calls)
        // The stored result is posted again each time: the first post may not have arrived.
        assertEquals(3, gateway.results.size)
        assertTrue(gateway.results.all { it.second.status == DeviceCommandStatus.SUCCEEDED })
    }

    @Test
    fun `a result that could not be posted is posted with the next check-in without running the command again`() = runBlocking {
        val executor = executor()
        gateway.resultFailure = RemoteException.Offline()
        executor.execute(listOf(command("c-1", DeviceCommandTypes.RING, mapOf("seconds" to "30"))))
        assertTrue(gateway.results.isEmpty())

        gateway.resultFailure = null
        executor.execute(listOf(command("c-1", DeviceCommandTypes.RING, mapOf("seconds" to "30"))))

        assertEquals(listOf(30), alerts.rings)
        assertEquals(DeviceCommandStatus.SUCCEEDED, result("c-1").status)
    }

    @Test
    fun `an unknown command type is unsupported`() = runBlocking {
        executor().execute(listOf(command("c-1", "selfDestruct")))

        assertEquals(DeviceCommandResult.unsupported(DeviceCommandExecutor.DETAIL_UNKNOWN_TYPE), result("c-1"))
        assertTrue(policy.calls.isEmpty())
    }

    @Test
    fun `reboot and wipe are unsupported without device owner and nothing happens to the phone`() = runBlocking {
        policy.isDeviceOwner = false

        executor().execute(listOf(command("c-1", DeviceCommandTypes.REBOOT), command("c-2", DeviceCommandTypes.WIPE)))

        assertEquals(DeviceCommandResult.unsupported(DeviceCommandExecutor.DETAIL_NOT_DEVICE_OWNER), result("c-1"))
        assertEquals(DeviceCommandResult.unsupported(DeviceCommandExecutor.DETAIL_NOT_DEVICE_OWNER), result("c-2"))
        assertTrue(policy.calls.isEmpty())
    }

    @Test
    fun `lock works for a device admin and is unsupported without that role`() = runBlocking {
        policy.isDeviceOwner = false
        policy.isAdminActive = true
        executor().execute(listOf(command("c-1", DeviceCommandTypes.LOCK)))
        assertEquals(DeviceCommandStatus.SUCCEEDED, result("c-1").status)

        policy.isAdminActive = false
        executor().execute(listOf(command("c-2", DeviceCommandTypes.LOCK)))
        assertEquals(DeviceCommandResult.unsupported(DeviceCommandExecutor.DETAIL_NOT_DEVICE_ADMIN), result("c-2"))
        assertEquals(listOf("lock"), policy.calls)
    }

    @Test
    fun `a role lost between the check and the call is unsupported`() = runBlocking {
        policy.failure = SecurityException("not an active admin")

        executor().execute(listOf(command("c-1", DeviceCommandTypes.LOCK)))

        assertEquals(DeviceCommandStatus.UNSUPPORTED, result("c-1").status)
    }

    @Test
    fun `wipe posts succeeded before the phone is erased`() = runBlocking {
        val gatewayCallsAtWipe = mutableListOf<String>()
        val observingPolicy = object : com.dynamictransport.dynamicone.core.mdm.DevicePolicyController by policy {
            override fun wipeData() {
                gatewayCallsAtWipe += gateway.calls
                policy.wipeData()
            }
        }
        val ledger = DeviceCommandLedger(dataStore, clock)
        val executor = DeviceCommandExecutor(ledger, DeviceCommandReporter(ledger, gateway), observingPolicy, alerts, remoteSessions, NoOpLogger)

        assertEquals(1, executor.execute(listOf(command("c-1", DeviceCommandTypes.WIPE))))

        assertEquals(listOf("result:c-1:succeeded"), gatewayCallsAtWipe)
        assertEquals(listOf("wipe"), policy.calls)
    }

    @Test
    fun `a wipe the phone refuses is reported as failed`() = runBlocking {
        policy.failure = IllegalStateException("refused")

        executor().execute(listOf(command("c-1", DeviceCommandTypes.WIPE)))

        assertEquals(DeviceCommandStatus.FAILED, result("c-1").status)
    }

    @Test
    fun `a reboot that ended the process is answered succeeded after the restart and not repeated`() = runBlocking {
        val gatewayDown = FakeDeviceManagementGateway().apply { resultFailure = RemoteException.Offline() }
        val ledger = DeviceCommandLedger(dataStore, clock)
        // The restart takes the process down before the result reaches the server.
        DeviceCommandExecutor(ledger, DeviceCommandReporter(ledger, gatewayDown), policy, alerts, remoteSessions, NoOpLogger)
            .execute(listOf(command("c-1", DeviceCommandTypes.REBOOT)))
        assertEquals(listOf("reboot"), policy.calls)

        executor().execute(listOf(command("c-1", DeviceCommandTypes.REBOOT)))

        assertEquals(listOf("reboot"), policy.calls)
        assertEquals(DeviceCommandStatus.SUCCEEDED, result("c-1").status)
    }

    @Test
    fun `a command the process died in is answered failed and not run again`() = runBlocking {
        DeviceCommandLedger(dataStore, clock).begin("c-1", TEST_NOW.plusSeconds(600))

        assertEquals(1, executor().execute(listOf(command("c-1", DeviceCommandTypes.LOCK))))

        assertTrue(policy.calls.isEmpty())
        assertEquals(DeviceCommandResult.failed(DeviceCommandExecutor.DETAIL_INTERRUPTED), result("c-1"))
    }

    @Test
    fun `ring uses the payload seconds within sane bounds and message shows the text`() = runBlocking {
        executor().execute(
            listOf(
                command("c-1", DeviceCommandTypes.RING, mapOf("seconds" to "60")),
                command("c-2", DeviceCommandTypes.RING),
                command("c-3", DeviceCommandTypes.RING, mapOf("seconds" to "86400")),
                command("c-4", DeviceCommandTypes.MESSAGE, mapOf("title" to "Depo", "body" to "Yarın 07:00")),
                command("c-5", DeviceCommandTypes.MESSAGE),
                command("c-6", DeviceCommandTypes.SYNC),
            ),
        )

        assertEquals(listOf(60, DeviceCommandExecutor.DEFAULT_RING_SECONDS, DeviceCommandExecutor.MAX_RING_SECONDS), alerts.rings)
        assertEquals(listOf(Triple("c-4", "Depo", "Yarın 07:00")), alerts.messages)
        assertEquals(DeviceCommandResult.failed(DeviceCommandExecutor.DETAIL_INVALID_PAYLOAD), result("c-5"))
        assertEquals(DeviceCommandStatus.SUCCEEDED, result("c-6").status)
    }

    @Test
    fun `ring and message fail honestly when the phone cannot show notifications`() = runBlocking {
        alerts.canNotify = false

        executor().execute(listOf(command("c-1", DeviceCommandTypes.RING), command("c-2", DeviceCommandTypes.MESSAGE, mapOf("body" to "x"))))

        assertEquals(DeviceCommandResult.failed(DeviceCommandExecutor.DETAIL_NOTIFICATIONS_OFF), result("c-1"))
        assertEquals(DeviceCommandResult.failed(DeviceCommandExecutor.DETAIL_NOTIFICATIONS_OFF), result("c-2"))
    }

    @Test
    fun `a remote session request waits for the driver and is prompted only once`() = runBlocking {
        val executor = executor()
        val request = command(
            "c-1",
            DeviceCommandTypes.REMOTE_SESSION_REQUEST,
            mapOf("sessionId" to "s-1", "requestedBy" to "Erkan Barlaz", "expiresAt" to "2026-09-12T08:01:30Z"),
        )

        assertEquals(0, executor.execute(listOf(request)))
        assertEquals(0, executor.execute(listOf(request)))

        assertEquals(1, remoteSessions.requests.size)
        val prompted = remoteSessions.requests.single()
        assertEquals("c-1", prompted.commandId)
        assertEquals("s-1", prompted.sessionId)
        assertEquals("Erkan Barlaz", prompted.requestedBy)
        assertEquals(TEST_NOW.plusSeconds(90), prompted.expiresAt)
        // Nothing is posted until the driver answered.
        assertTrue(gateway.results.isEmpty())
    }

    @Test
    fun `a request whose prompt was lost with the process is declined, never shown again`() = runBlocking {
        val request = command("c-1", DeviceCommandTypes.REMOTE_SESSION_REQUEST, mapOf("sessionId" to "s-1", "expiresAt" to "2026-09-12T08:01:30Z"))
        executor().execute(listOf(request))

        assertEquals(1, executor().execute(listOf(request)))

        assertEquals(1, remoteSessions.requests.size)
        assertEquals(listOf("s-1"), remoteSessions.declinedUnanswered)
        assertEquals(DeviceCommandResult.succeeded(DeviceCommandExecutor.DETAIL_TIMEOUT), result("c-1"))
    }

    @Test
    fun `a request while another session runs and the end of a session are answered at once`() = runBlocking {
        remoteSessions.outcome = RemoteRequestOutcome.BUSY

        executor().execute(
            listOf(
                command("c-1", DeviceCommandTypes.REMOTE_SESSION_REQUEST, mapOf("sessionId" to "s-2", "expiresAt" to "2026-09-12T08:01:30Z")),
                command("c-2", DeviceCommandTypes.REMOTE_SESSION_END, mapOf("sessionId" to "s-1")),
                command("c-3", DeviceCommandTypes.REMOTE_SESSION_REQUEST),
            ),
        )

        assertEquals(DeviceCommandResult.succeeded(DeviceCommandExecutor.DETAIL_BUSY), result("c-1"))
        assertEquals(listOf("s-1"), remoteSessions.ended)
        assertEquals(DeviceCommandStatus.SUCCEEDED, result("c-2").status)
        assertEquals(DeviceCommandResult.failed(DeviceCommandExecutor.DETAIL_INVALID_PAYLOAD), result("c-3"))
    }

    @Test
    fun `handled ids are forgotten a while after the command expired`() = runBlocking {
        val ledger = DeviceCommandLedger(dataStore, clock)
        ledger.begin("c-old", TEST_NOW.plusSeconds(60))
        ledger.finish("c-old", DeviceCommandResult.succeeded())
        assertEquals(DeviceCommandStatus.SUCCEEDED, ledger.find("c-old")?.result?.status)

        clock.now = TEST_NOW.plus(Duration.ofDays(8))
        ledger.begin("c-new", clock.now.plusSeconds(60))

        assertNull(ledger.find("c-old"))
        assertNull(ledger.find("c-new")?.result)
    }

    @Test
    fun `a long detail is cut to the contract's 500 characters`() = runBlocking {
        val ledger = DeviceCommandLedger(dataStore, clock)

        DeviceCommandReporter(ledger, gateway).report("c-1", DeviceCommandResult.failed("x".repeat(900)))

        assertEquals(500, result("c-1").detail?.length)
    }
}
