package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import com.dynamictransport.dynamicone.domain.model.PendingTripAction
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.testing.DriverApiJson
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.drainRequests
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import com.dynamictransport.dynamicone.testing.route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * docs/27 (backend TH-2026-0519) through the real repositories and HTTP stack: loading confirmation and seal
 * numbers of an export loading stop, two containers, and the stop that is never completed from the app.
 */
class ExportSealRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    @Before
    fun setUp() {
        h = RealApiHarness(folder)
        h.signIn()
    }

    @After
    fun tearDown() = h.close()

    private val stops = """[{"id":"s1","sequence":1,"activity":"Load","status":"Arrived","location":"Antwerp PSA","latitude":51.3,"longitude":4.3}]"""
    private val containers = """[{"id":"c1","number":"MSKU 458921-7","sealNumber":null},{"id":"c2","number":"TGHU 902114-6","sealNumber":null}]"""
    private val sealSlots = listOf(
        DriverApiJson.slot("Seal:s1:c1", "Seal", "Missing", true, "s1", "c1"),
        DriverApiJson.slot("Seal:s1:c2", "Seal", "Missing", true, "s1", "c2"),
    )

    /** The plan before the loading confirmation. */
    private fun beforeConfirmation(version: String) = DriverApiJson.execution(
        version = version,
        actions = listOf(
            DriverApiJson.action("ConfirmLoading", "Confirm loading completed", "s1", null, blockingSlots = emptyList(), stopName = "Antwerp PSA"),
            DriverApiJson.action("CompleteStop", "Container loading completed", "s1", "c1", blockingSlots = listOf("Seal:s1:c1"), blockingReason = "loading_confirmation_required"),
            DriverApiJson.action("CompleteStop", "Container loading completed", "s1", "c2", blockingSlots = listOf("Seal:s1:c2"), blockingReason = "loading_confirmation_required"),
            DriverApiJson.action("Rest", "Report a break"),
        ),
        documents = sealSlots,
    )

    /** The plan after the confirmation; [sealOf1] is the seal the server already holds for the first container. */
    private fun afterConfirmation(version: String, sealOf1: String? = null) = DriverApiJson.execution(
        version = version,
        actions = listOf(
            DriverApiJson.action("RecordSealNumber", "Save seal number", "s1", "c1", blockingSlots = emptyList(), sealNumber = sealOf1, containerNumber = "MSKU 458921-7", stopName = "Antwerp PSA"),
            DriverApiJson.action("RecordSealNumber", "Save seal number", "s1", "c2", blockingSlots = emptyList(), containerNumber = "TGHU 902114-6", stopName = "Antwerp PSA"),
            DriverApiJson.action("CompleteStop", "Container loading completed", "s1", "c1", blockingSlots = listOf("Seal:s1:c1"), blockingReason = if (sealOf1 == null) "seal_number_required" else null),
            DriverApiJson.action("CompleteStop", "Container loading completed", "s1", "c2", blockingSlots = emptyList(), blockingReason = "seal_number_required"),
        ),
        documents = sealSlots,
    )

    private fun routeTrip(execution: () -> String) {
        val summary = DriverApiJson.summary("t1", "Active", "InProgress")
        h.routes.on("GET", "/trips") { jsonResponse(DriverApiJson.page(listOf(summary))) }
        h.routes.on("GET", "/trips/t1") { jsonResponse(DriverApiJson.detail(summary, stops = stops, containers = containers)) }
        h.routes.on("GET", "/trips/t1/execution") { jsonResponse(execution()) }
        h.routes.on("GET", "/trips/t1/actions") { jsonResponse(DriverApiJson.page(emptyList())) }
    }

    private fun loadTrip() = runBlocking {
        assertEquals(RefreshResult.SUCCESS, h.trips.refreshAll())
        assertEquals(RefreshResult.SUCCESS, h.trips.refreshTrip("t1"))
    }

    private fun postBodies() = h.server.drainRequests().filter { it.route == "POST /api/driver/v1/trips/t1/actions" }.map { it.body.readUtf8() }

    @Test
    fun `the loading confirmation is sent for the stop without a container and the stop is never completed from the app`() = runBlocking {
        val confirmed = AtomicInteger()
        routeTrip { if (confirmed.get() == 0) beforeConfirmation("v7") else afterConfirmation("v8") }
        h.routes.on("POST", "/trips/t1/actions") {
            confirmed.incrementAndGet()
            jsonResponse(DriverApiJson.result(version = "v8"))
        }
        loadTrip()

        val plan = h.trips.currentExecution("t1")!!
        val loading = plan.loadingConfirmation!!
        val completion = plan.availableActions.first { it.code == ExecutionActionCode.COMPLETE_STOP }
        // The completion of an export loading stop is the planner's, even if it looked executable.
        assertEquals(ActionResult.Rejected(ActionRejection.NOT_ALLOWED), h.executions.submit("t1", completion, null))
        assertEquals(ActionResult.Rejected(ActionRejection.NOT_ALLOWED), h.executions.submit("t1", completion.copy(canExecute = true, blockingDocumentSlots = emptyList(), blockingReason = null), null))
        assertTrue(postBodies().isEmpty())

        assertEquals(ActionResult.Queued, h.executions.submit("t1", loading, null))
        assertEquals(
            // A null container is omitted (explicitNulls = false): the backend binds an absent Guid? as null.
            listOf("""{"commandId":"cmd-1","expectedVersion":"v7","action":"ConfirmLoading","stopId":"s1"}"""),
            postBodies(),
        )
        assertTrue(h.commandDao.all.isEmpty())
        // The plan was read again: the seal entries replace the confirmation.
        val next = h.trips.currentExecution("t1")!!
        assertEquals("v8", next.version)
        assertNull(next.loadingConfirmation)
        assertEquals(listOf("c1", "c2"), next.sealEntries.map { it.containerId })
        assertEquals("s1", next.exportLoadingStopId)
    }

    @Test
    fun `each container's seal is its own command with the current version, and the second one uses the version read after the first`() = runBlocking {
        val saved = AtomicInteger()
        routeTrip {
            when (saved.get()) {
                0 -> afterConfirmation("v8")
                1 -> afterConfirmation("v9", sealOf1 = "SEAL-001")
                else -> afterConfirmation("v10", sealOf1 = "SEAL-001")
            }
        }
        h.routes.on("POST", "/trips/t1/actions") {
            saved.incrementAndGet()
            jsonResponse(DriverApiJson.result(version = "v${8 + saved.get()}"))
        }
        loadTrip()
        val (first, second) = h.trips.currentExecution("t1")!!.sealEntries

        // The seal entry never goes through the plain action path, and the photo slot stays as the server says.
        assertEquals(ActionResult.Rejected(ActionRejection.NOT_ALLOWED), h.executions.submit("t1", first.action, null))
        assertEquals(ActionResult.Queued, h.executions.recordSealNumber("t1", first.action, " SEAL-001 "))
        assertEquals("SEAL-001", h.trips.currentExecution("t1")!!.sealEntries[0].recordedSealNumber)
        assertNull(h.trips.currentExecution("t1")!!.sealEntries[1].recordedSealNumber)
        assertTrue(h.trips.currentExecution("t1")!!.sealEntries.none { it.photoDone })

        assertEquals(ActionResult.Queued, h.executions.recordSealNumber("t1", second.action, "seal-002"))
        assertEquals(
            listOf(
                """{"commandId":"cmd-1","expectedVersion":"v8","action":"RecordSealNumber","stopId":"s1","containerId":"c1","sealNumber":"SEAL-001"}""",
                """{"commandId":"cmd-2","expectedVersion":"v9","action":"RecordSealNumber","stopId":"s1","containerId":"c2","sealNumber":"seal-002"}""",
            ),
            postBodies(),
        )
        assertTrue(h.commandDao.all.isEmpty())
    }

    @Test
    fun `invalid seal numbers are refused before any request and the server's refusal is explained`() = runBlocking {
        routeTrip { afterConfirmation("v8") }
        h.routes.on("POST", "/trips/t1/actions") { problemResponse(400, "invalid_seal_number") }
        loadTrip()
        val entry = h.trips.currentExecution("t1")!!.sealEntries.first()

        listOf("", "   ", "SEAL\nOTHER", "A".repeat(101)).forEach { value ->
            assertEquals(ActionResult.Rejected(ActionRejection.SEAL_NUMBER_INVALID), h.executions.recordSealNumber("t1", entry.action, value))
        }
        assertTrue(postBodies().isEmpty())

        assertEquals(ActionResult.Rejected(ActionRejection.SEAL_NUMBER_INVALID), h.executions.recordSealNumber("t1", entry.action, "SEAL-001"))
        assertEquals(1, postBodies().size)
        // Not kept: the driver corrects the value and the next attempt is a new command.
        assertTrue(h.commandDao.all.isEmpty())
        assertNull(h.executions.observePendingAction("t1").first())
    }

    @Test
    fun `seal gate conflicts are explained and the plan is read again without a replay`() = runBlocking {
        routeTrip { afterConfirmation("v8") }
        val answers = ArrayDeque(listOf("seal_number_required", "loading_confirmation_required", "conflict"))
        h.routes.on("POST", "/trips/t1/actions") { problemResponse(409, answers.removeFirst()) }
        loadTrip()
        val entry = h.trips.currentExecution("t1")!!.sealEntries.first()

        assertEquals(ActionResult.Rejected(ActionRejection.SEAL_NUMBER_REQUIRED), h.executions.recordSealNumber("t1", entry.action, "SEAL-001"))
        assertEquals(ActionResult.Rejected(ActionRejection.LOADING_CONFIRMATION_REQUIRED), h.executions.recordSealNumber("t1", entry.action, "SEAL-001"))
        assertEquals(ActionResult.Rejected(ActionRejection.PLAN_CHANGED), h.executions.recordSealNumber("t1", entry.action, "SEAL-001"))
        val requests = h.server.drainRequests()
        assertEquals(3, requests.count { it.route == "POST /api/driver/v1/trips/t1/actions" })
        // Each refusal read the plan again (one read at load time, one after each 409); nothing was replayed.
        assertEquals(4, requests.count { it.route == "GET /api/driver/v1/trips/t1/execution" })
        assertTrue(h.commandDao.all.isEmpty())
    }

    @Test
    fun `a lost response resends the seal command with the same id and the same seal number`() = runBlocking {
        routeTrip { afterConfirmation("v8") }
        val attempts = AtomicInteger()
        h.routes.on("POST", "/trips/t1/actions") {
            if (attempts.incrementAndGet() == 1) {
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            } else {
                jsonResponse(DriverApiJson.result(version = "v9"))
            }
        }
        loadTrip()
        val (first, second) = h.trips.currentExecution("t1")!!.sealEntries

        assertEquals(ActionResult.Rejected(ActionRejection.OUTCOME_UNKNOWN), h.executions.recordSealNumber("t1", first.action, "SEAL-001"))
        val pending = h.executions.observePendingAction("t1").first()!!
        assertEquals(PendingCommandState.OUTCOME_UNKNOWN, pending.state)
        assertEquals(ExecutionActionCode.RECORD_SEAL_NUMBER, pending.action.code)
        assertEquals("c1", pending.action.containerId)
        assertEquals("SEAL-001", pending.sealNumber)
        // The second container waits until the first command is resolved; nothing else is sent meanwhile.
        assertEquals(ActionResult.Rejected(ActionRejection.PENDING_ACTION), h.executions.recordSealNumber("t1", second.action, "SEAL-002"))

        assertEquals(ActionResult.Queued, h.executions.resendPending("t1"))
        val bodies = postBodies()
        assertEquals(2, bodies.size)
        assertEquals(bodies[0], bodies[1])
        assertTrue(bodies[0].endsWith(""","sealNumber":"SEAL-001"}"""))
        assertNull(h.executions.observePendingAction("t1").first())
    }

    @Test
    fun `a seal command stored before an app restart is resent with its stored seal number`() = runBlocking {
        routeTrip { afterConfirmation("v8") }
        h.routes.on("POST", "/trips/t1/actions") { jsonResponse(DriverApiJson.result(version = "v9")) }
        loadTrip()
        val entry = h.trips.currentExecution("t1")!!.sealEntries.first()
        assertTrue(
            h.store.save(
                PendingTripAction(
                    commandId = "cmd-restart",
                    tripId = "t1",
                    expectedVersion = "v8",
                    action = entry.action,
                    note = null,
                    location = null,
                    createdAt = h.clock.instant(),
                    state = PendingCommandState.OUTCOME_UNKNOWN,
                    sealNumber = "SEAL-R",
                ),
            ),
        )

        assertEquals("SEAL-R", h.executions.observePendingAction("t1").first()?.sealNumber)
        assertEquals(ActionResult.Queued, h.executions.resendPending("t1"))
        assertEquals(
            listOf("""{"commandId":"cmd-restart","expectedVersion":"v8","action":"RecordSealNumber","stopId":"s1","containerId":"c1","sealNumber":"SEAL-R"}"""),
            postBodies(),
        )
    }
}
