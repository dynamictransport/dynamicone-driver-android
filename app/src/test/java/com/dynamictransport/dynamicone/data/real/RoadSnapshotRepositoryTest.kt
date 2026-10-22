package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.database.SnapshotRowState
import com.dynamictransport.dynamicone.domain.model.SnapshotCamera
import com.dynamictransport.dynamicone.domain.model.SnapshotDecision
import com.dynamictransport.dynamicone.domain.model.SnapshotFailure
import com.dynamictransport.dynamicone.domain.model.SnapshotOutcomeState
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
import java.io.File
import java.time.Duration
import java.time.Instant

/** Remote road snapshots against the Driver API contract (docs/24 §5–§7) through the production HTTP stack. */
class RoadSnapshotRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    @Before
    fun setUp() {
        h = RealApiHarness(folder)
        h.signIn("driver-1")
        h.clock.now = Instant.parse("2026-10-21T13:00:30Z")
    }

    @After
    fun tearDown() = h.close()

    private val route = "/trips/trp-1/snapshot-requests/req-1"

    private fun request(status: String = "Pending", expiresAt: String = "2026-10-21T13:10:00Z", camera: String = "External") =
        """{"requestId":"req-1","tripId":"trp-1","status":"$status","requestedAt":"2026-10-21T13:00:00Z","expiresAt":"$expiresAt",""" +
            """"requestedByDisplayName":"Ayşe Yılmaz","preferredCamera":"$camera"}"""

    private fun photoFile(): File = folder.newFile("capture.jpg").apply { writeBytes(ByteArray(2_000) { 7 }) }

    @Test
    fun `a push leads to the request being read and stored for the capture`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(request()) }

        val decision = h.snapshots.onRequestSignal("trp-1", "req-1")

        val capture = decision as SnapshotDecision.Capture
        assertEquals("req-1", capture.request.requestId)
        assertEquals("Ayşe Yılmaz", capture.request.requestedByDisplayName)
        assertEquals(SnapshotCamera.EXTERNAL, capture.request.preferredCamera)
        assertEquals("GET /api/driver/v1$route", h.server.takeRequest().route)
        assertEquals(SnapshotRowState.REQUESTED, h.snapshotDao.get("req-1")!!.state)
        assertEquals(SnapshotOutcomeState.WAITING_FOR_DRIVER, h.snapshots.observeLatest().first()!!.state)
    }

    @Test
    fun `a closed, unknown or expired request is ignored and nothing is invented`() = runBlocking {
        h.routes.on("GET", route) { problemResponse(409, "snapshot_request_closed") }
        assertEquals(SnapshotDecision.Ignore, h.snapshots.onRequestSignal("trp-1", "req-1"))

        h.routes.on("GET", route) { problemResponse(404, "snapshot_request_not_found") }
        assertEquals(SnapshotDecision.Ignore, h.snapshots.onRequestSignal("trp-1", "req-1"))

        h.routes.on("GET", route) { jsonResponse(request(expiresAt = "2026-10-21T13:00:00Z")) }
        assertEquals(SnapshotDecision.Ignore, h.snapshots.onRequestSignal("trp-1", "req-1"))

        h.routes.on("GET", route) { jsonResponse(request(status = "Captured")) }
        assertEquals(SnapshotDecision.Ignore, h.snapshots.onRequestSignal("trp-1", "req-1"))

        assertNull(h.snapshots.observeLatest().first())
    }

    @Test
    fun `the photo is prepared, stored and uploaded with the capture time and camera, then removed from the tablet`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(request()) }
        h.snapshots.onRequestSignal("trp-1", "req-1")
        h.server.takeRequest()
        val capturedAt = Instant.parse("2026-10-21T13:01:12Z")

        assertTrue(h.snapshots.storePhoto("req-1", photoFile(), capturedAt, SnapshotCamera.BACK))

        val stored = h.snapshotDao.get("req-1")!!
        assertEquals(SnapshotRowState.CAPTURED, stored.state)
        assertTrue(File(stored.photoPath!!).isFile)
        assertEquals(1, h.snapshotScheduler.requests)
        assertEquals(SnapshotOutcomeState.CAPTURED, h.snapshots.observeLatest().first()!!.state)

        h.routes.on("POST", "$route/photo") { jsonResponse("""{"requestId":"req-1","status":"Captured","documentId":"doc-9"}""") }
        assertTrue(h.snapshots.sendPending())

        val upload = h.server.takeRequest()
        assertEquals("POST /api/driver/v1$route/photo", upload.route)
        val body = upload.body.readUtf8()
        assertTrue(body.contains("name=\"capturedAt\""))
        assertTrue(body.contains("2026-10-21T13:01:12Z"))
        assertTrue(body.contains("name=\"camera\""))
        assertTrue(body.contains("Back"))
        assertTrue(body.contains("name=\"photo\"; filename=\"req-1.jpg\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        val after = h.snapshotDao.get("req-1")!!
        assertEquals(SnapshotRowState.UPLOADED, after.state)
        assertNull(after.photoPath)
        assertFalse(File(stored.photoPath!!).exists())
        assertEquals(SnapshotOutcomeState.UPLOADED, h.snapshots.observeLatest().first()!!.state)
    }

    @Test
    fun `an upload the server cannot take now is tried again later with the same request id`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(request()) }
        h.snapshots.onRequestSignal("trp-1", "req-1")
        h.server.takeRequest()
        h.snapshots.storePhoto("req-1", photoFile(), h.clock.now, SnapshotCamera.EXTERNAL)

        h.routes.on("POST", "$route/photo") { MockResponse().setResponseCode(503) }
        assertFalse(h.snapshots.sendPending())
        assertEquals(SnapshotRowState.CAPTURED, h.snapshotDao.get("req-1")!!.state)

        h.routes.on("POST", "$route/photo") { jsonResponse("""{"requestId":"req-1","status":"Captured","documentId":"doc-9"}""") }
        assertTrue(h.snapshots.sendPending())
        assertEquals(SnapshotRowState.UPLOADED, h.snapshotDao.get("req-1")!!.state)
    }

    @Test
    fun `a request closed on the server while the photo waited is dropped without an error`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(request()) }
        h.snapshots.onRequestSignal("trp-1", "req-1")
        h.server.takeRequest()
        h.snapshots.storePhoto("req-1", photoFile(), h.clock.now, SnapshotCamera.EXTERNAL)
        val path = h.snapshotDao.get("req-1")!!.photoPath!!

        h.routes.on("POST", "$route/photo") { problemResponse(409, "snapshot_request_closed") }
        assertTrue(h.snapshots.sendPending())

        assertEquals(SnapshotRowState.CLOSED, h.snapshotDao.get("req-1")!!.state)
        assertFalse(File(path).exists())
        assertNull(h.snapshots.observeLatest().first())
    }

    @Test
    fun `a photo the server refuses for good becomes a failure report instead of a retry loop`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(request()) }
        h.snapshots.onRequestSignal("trp-1", "req-1")
        h.server.takeRequest()
        h.snapshots.storePhoto("req-1", photoFile(), h.clock.now, SnapshotCamera.EXTERNAL)
        h.routes.on("POST", "$route/photo") { problemResponse(413, "photo_too_large") }
        h.routes.on("POST", "$route/result") { MockResponse().setResponseCode(204) }

        assertTrue(h.snapshots.sendPending())

        h.server.takeRequest()
        val result = h.server.takeRequest()
        assertEquals("POST /api/driver/v1$route/result", result.route)
        assertTrue(result.body.readUtf8().contains("\"reason\":\"camera_unavailable\""))
        assertEquals(SnapshotRowState.FAILED, h.snapshotDao.get("req-1")!!.state)
    }

    @Test
    fun `a failure of the tablet reaches the server with its reason and time`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(request()) }
        h.snapshots.onRequestSignal("trp-1", "req-1")
        h.server.takeRequest()
        h.clock.now = Instant.parse("2026-10-21T13:02:00Z")

        h.snapshots.reportFailure("req-1", SnapshotFailure.DRIVER_DISMISSED)
        assertEquals(SnapshotOutcomeState.FAILED, h.snapshots.observeLatest().first()!!.state)
        assertEquals(SnapshotFailure.DRIVER_DISMISSED, h.snapshots.observeLatest().first()!!.failure)

        h.routes.on("POST", "$route/result") { MockResponse().setResponseCode(204) }
        assertTrue(h.snapshots.sendPending())

        val sent = h.server.takeRequest()
        assertEquals("POST /api/driver/v1$route/result", sent.route)
        assertEquals("""{"status":"Failed","reason":"driver_dismissed","occurredAt":"2026-10-21T13:02:00Z"}""", sent.body.readUtf8())
        assertEquals(SnapshotRowState.FAILED, h.snapshotDao.get("req-1")!!.state)
        // Reporting twice changes nothing: the request is no longer open on the tablet.
        h.snapshots.reportFailure("req-1", SnapshotFailure.EXPIRED)
        assertEquals(SnapshotFailure.DRIVER_DISMISSED, h.snapshots.observeLatest().first()!!.failure)
    }

    @Test
    fun `a request opened from the notification after its deadline is reported as expired`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(request()) }
        h.snapshots.onRequestSignal("trp-1", "req-1")
        h.clock.now = h.clock.now.plus(Duration.ofMinutes(15))

        assertNull(h.snapshots.pendingRequest("req-1"))

        val row = h.snapshotDao.get("req-1")!!
        assertEquals(SnapshotRowState.FAILED_PENDING, row.state)
        assertEquals("expired", row.failureReason)
    }

    @Test
    fun `a second push for an answered request is ignored and nothing is read again`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(request()) }
        h.snapshots.onRequestSignal("trp-1", "req-1")
        h.server.takeRequest()
        h.snapshots.storePhoto("req-1", photoFile(), h.clock.now, SnapshotCamera.BACK)

        assertEquals(SnapshotDecision.Ignore, h.snapshots.onRequestSignal("trp-1", "req-1"))

        assertEquals(0, h.server.requestCount - 1)
    }

    @Test
    fun `a photo the tablet cannot encode is reported as a camera failure`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(request()) }
        h.snapshots.onRequestSignal("trp-1", "req-1")
        h.snapshotPreparer.usable = false

        assertFalse(h.snapshots.storePhoto("req-1", photoFile(), h.clock.now, SnapshotCamera.BACK))

        val row = h.snapshotDao.get("req-1")!!
        assertEquals(SnapshotRowState.FAILED_PENDING, row.state)
        assertEquals("camera_unavailable", row.failureReason)
    }
}
