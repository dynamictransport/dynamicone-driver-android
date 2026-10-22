package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.database.WakeUpRowState
import com.dynamictransport.dynamicone.domain.model.WakeUpDecision
import com.dynamictransport.dynamicone.domain.model.WakeUpOutcomeState
import com.dynamictransport.dynamicone.domain.model.WakeUpReason
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import com.dynamictransport.dynamicone.testing.route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

/** Wake-up calls against the Driver API contract (docs/25 §5–§7) through the production HTTP stack. */
class WakeUpCallRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    @Before
    fun setUp() {
        h = RealApiHarness(folder)
        h.signIn("driver-1")
        h.clock.now = Instant.parse("2026-10-22T05:05:10Z")
    }

    @After
    fun tearDown() = h.close()

    private val route = "/me/wake-up-calls/call-1"
    private val pushExpiry = Instant.parse("2026-10-22T05:10:00Z")

    private fun call(status: String = "Pending", expiresAt: String = "2026-10-22T05:10:00Z", reason: String = "call_office") =
        """{"callId":"call-1","status":"$status","reason":"$reason","requestedAt":"2026-10-22T05:05:00Z","expiresAt":"$expiresAt",""" +
            """"requestedByDisplayName":"Ayşe Yılmaz","tripId":"trp-1","tripNo":"DT-2026-000012-01","acknowledgedAt":null,"acknowledgedDeviceKind":null}"""

    private fun ring() = runBlocking { h.wakeUps.onRingSignal("call-1", "call_office", pushExpiry) }

    @Test
    fun `a ring push leads to the call being read and stored as ringing`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(call()) }

        val decision = ring() as WakeUpDecision.Ring

        assertEquals("call-1", decision.call.callId)
        assertEquals("Ayşe Yılmaz", decision.call.requestedByDisplayName)
        assertEquals("DT-2026-000012-01", decision.call.tripNo)
        assertEquals(WakeUpReason.CALL_OFFICE, decision.call.reason)
        assertEquals("GET /api/driver/v1$route", h.server.takeRequest().route)
        assertEquals(WakeUpRowState.RINGING, h.wakeUpDao.get("call-1")!!.state)
        assertEquals(WakeUpOutcomeState.RINGING, h.wakeUps.observeLatest().first()!!.state)
        assertEquals(listOf("call-1"), h.wakeUps.ringingCalls().map { it.callId })
    }

    @Test
    fun `a closed, unknown or expired call does not ring and nothing is invented`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(call(status = "Acknowledged")) }
        assertEquals(WakeUpDecision.Ignore, ring())

        h.routes.on("GET", route) { problemResponse(404, "wake_up_call_not_found") }
        assertEquals(WakeUpDecision.Ignore, ring())

        h.routes.on("GET", route) { jsonResponse(call(expiresAt = "2026-10-22T05:05:00Z")) }
        assertEquals(WakeUpDecision.Ignore, ring())

        assertNull(h.wakeUps.observeLatest().first())
    }

    @Test
    fun `an unknown reason code rings as a general call`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(call(reason = "fleet_meeting")) }

        val decision = ring() as WakeUpDecision.Ring

        assertEquals(WakeUpReason.OTHER, decision.call.reason)
    }

    @Test
    fun `when the server cannot be asked the push itself rings, but only with a deadline`() = runBlocking {
        h.routes.on("GET", route) { MockResponse().setResponseCode(503) }

        val decision = ring() as WakeUpDecision.Ring
        assertEquals(pushExpiry, decision.call.expiresAt)
        assertNull(decision.call.requestedByDisplayName)
        assertEquals(WakeUpRowState.RINGING, h.wakeUpDao.get("call-1")!!.state)

        assertEquals(WakeUpDecision.Ignore, h.wakeUps.onRingSignal("call-2", "other", null))
        assertNull(h.wakeUpDao.get("call-2"))
    }

    @Test
    fun `the driver's answer is stored, queued and sent once with its own clientActionId`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(call()) }
        ring()
        h.server.takeRequest()
        h.clock.now = Instant.parse("2026-10-22T05:06:40Z")

        h.wakeUps.acknowledge("call-1")
        h.wakeUps.acknowledge("call-1")

        val stored = h.wakeUpDao.get("call-1")!!
        assertEquals(WakeUpRowState.ACKNOWLEDGED_PENDING, stored.state)
        assertEquals(Instant.parse("2026-10-22T05:06:40Z"), stored.acknowledgedAt)
        assertEquals(1, h.wakeUpScheduler.requests)
        assertEquals(WakeUpOutcomeState.ACKNOWLEDGED_PENDING, h.wakeUps.observeLatest().first()!!.state)
        assertTrue(h.wakeUps.ringingCalls().isEmpty())

        h.routes.on("POST", "$route/result") { MockResponse().setResponseCode(204) }
        assertTrue(h.wakeUps.sendPending())

        val sent = h.server.takeRequest()
        assertEquals("POST /api/driver/v1$route/result", sent.route)
        val body = sent.body.readUtf8()
        assertTrue(body.contains("\"status\":\"Acknowledged\""))
        assertTrue(body.contains("\"occurredAt\":\"2026-10-22T05:06:40Z\""))
        assertTrue(body.contains("\"clientActionId\":\"call-1-ack\""))
        assertTrue(body.contains("\"installationId\":\""))
        assertEquals(WakeUpRowState.ACKNOWLEDGED, h.wakeUpDao.get("call-1")!!.state)
        assertEquals(WakeUpOutcomeState.ACKNOWLEDGED, h.wakeUps.observeLatest().first()!!.state)
        // Nothing left to send.
        assertTrue(h.wakeUps.sendPending())
        assertEquals(2, h.server.requestCount)
    }

    @Test
    fun `an answer the server cannot take now is tried again later with the same clientActionId`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(call()) }
        ring()
        h.server.takeRequest()
        h.wakeUps.acknowledge("call-1")

        h.routes.on("POST", "$route/result") { MockResponse().setResponseCode(503) }
        assertFalse(h.wakeUps.sendPending())
        assertEquals(WakeUpRowState.ACKNOWLEDGED_PENDING, h.wakeUpDao.get("call-1")!!.state)
        val first = h.server.takeRequest().body.readUtf8()

        h.routes.on("POST", "$route/result") { MockResponse().setResponseCode(204) }
        assertTrue(h.wakeUps.sendPending())
        assertEquals(first, h.server.takeRequest().body.readUtf8())
        assertEquals(WakeUpRowState.ACKNOWLEDGED, h.wakeUpDao.get("call-1")!!.state)
    }

    @Test
    fun `a call the server already closed keeps the driver's answer as given without retries`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(call()) }
        ring()
        h.server.takeRequest()
        h.wakeUps.acknowledge("call-1")

        h.routes.on("POST", "$route/result") { problemResponse(404, "wake_up_call_not_found") }
        assertTrue(h.wakeUps.sendPending())

        assertEquals(WakeUpRowState.ACKNOWLEDGED, h.wakeUpDao.get("call-1")!!.state)
        assertEquals(WakeUpOutcomeState.ACKNOWLEDGED, h.wakeUps.observeLatest().first()!!.state)
    }

    @Test
    fun `a call that rang out is reported as expired and shown as not answered`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(call()) }
        ring()
        h.server.takeRequest()
        h.clock.now = pushExpiry

        h.wakeUps.expire("call-1")

        assertEquals(WakeUpRowState.EXPIRED_PENDING, h.wakeUpDao.get("call-1")!!.state)
        assertEquals(WakeUpOutcomeState.NO_ANSWER, h.wakeUps.observeLatest().first()!!.state)
        h.routes.on("POST", "$route/result") { MockResponse().setResponseCode(204) }
        assertTrue(h.wakeUps.sendPending())
        val body = h.server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"status\":\"Expired\""))
        assertTrue(body.contains("\"clientActionId\":\"call-1-expired\""))
        assertEquals(WakeUpRowState.EXPIRED, h.wakeUpDao.get("call-1")!!.state)
        // Too late to answer: nothing changes any more.
        h.wakeUps.acknowledge("call-1")
        assertEquals(WakeUpRowState.EXPIRED, h.wakeUpDao.get("call-1")!!.state)
    }

    @Test
    fun `a stop push silences the call without a report, and a repeated ring push is ignored without reading`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(call()) }
        ring()
        h.server.takeRequest()

        h.wakeUps.onStopSignal("call-1")

        assertEquals(WakeUpRowState.STOPPED, h.wakeUpDao.get("call-1")!!.state)
        assertEquals(WakeUpOutcomeState.STOPPED, h.wakeUps.observeLatest().first()!!.state)
        assertEquals(0, h.wakeUpScheduler.requests)
        assertTrue(h.wakeUps.sendPending())
        assertEquals(WakeUpDecision.Ignore, ring())
        assertEquals(1, h.server.requestCount)
    }

    @Test
    fun `ringing is reported with the time and this installation, and a failure of that report changes nothing`() = runBlocking {
        h.routes.on("POST", "$route/ringing") { MockResponse().setResponseCode(204) }
        h.wakeUps.reportRinging("call-1")
        val sent = h.server.takeRequest()
        assertEquals("POST /api/driver/v1$route/ringing", sent.route)
        val body = sent.body.readUtf8()
        assertTrue(body.contains("\"occurredAt\":\"2026-10-22T05:05:10Z\""))
        assertTrue(body.contains("\"installationId\":\""))

        h.routes.on("POST", "$route/ringing") { MockResponse().setResponseCode(503) }
        h.wakeUps.reportRinging("call-1")
        assertEquals(0, h.wakeUpScheduler.requests)
    }

    @Test
    fun `old calls leave the device after a week`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(call()) }
        ring()
        h.wakeUps.onStopSignal("call-1")
        h.clock.now = h.clock.now.plus(Duration.ofDays(8))

        assertTrue(h.wakeUps.sendPending())

        assertNull(h.wakeUpDao.get("call-1"))
    }
}
