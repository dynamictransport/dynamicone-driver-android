package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DocumentSlotState
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.IncidentCategory
import com.dynamictransport.dynamicone.domain.model.MessageDirection
import com.dynamictransport.dynamicone.domain.model.MonthlyPayroll
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SignedDocumentType
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger

/** Driver App operations contract (TH-2026-0439) through the real repositories and HTTP stack. */
class RealOperationsRepositoriesTest {

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

    private val stops = """[
        {"id":"s1","sequence":1,"activity":"Pickup","status":"Planned","location":"Rotterdam ECT Delta","latitude":51.95,"longitude":4.05},
        {"id":"s2","sequence":2,"activity":"Unload","status":"Planned","location":"Venlo","latitude":null,"longitude":null},
        {"id":"s3","sequence":3,"activity":"EmptyReturn","status":"Planned","location":"Rotterdam Depot"}
    ]"""
    private val containers = """[{"id":"c1","number":"MSKU 458921-7","sealNumber":"781392"},{"id":"c2","number":null,"sealNumber":null}]"""
    private val pickupOptions = listOf(
        DriverApiJson.action("CompleteStop", "I picked up the full container", "s1", "c1"),
        DriverApiJson.action("CompleteStop", "I picked up the full container", "s1", "c2"),
        DriverApiJson.action("Rest", "Report a break"),
        DriverApiJson.action("Problem", "Report a problem"),
    )

    private fun routeTrip(assignment: String = "Active", status: String = "InProgress", execution: () -> String) {
        val summary = DriverApiJson.summary("t1", assignment, status)
        h.routes.on("GET", "/trips") { jsonResponse(DriverApiJson.page(listOf(summary))) }
        h.routes.on("GET", "/trips/t1") { jsonResponse(DriverApiJson.detail(summary, stops = stops, containers = containers)) }
        h.routes.on("GET", "/trips/t1/execution") { jsonResponse(execution()) }
        h.routes.on("GET", "/trips/t1/actions") {
            jsonResponse(DriverApiJson.page(listOf(DriverApiJson.receipt("cmd-1", "CompleteStop", "2026-09-12T08:05:00Z", "s1", "c2"))))
        }
    }

    private fun loadTrip() = runBlocking {
        assertEquals(RefreshResult.SUCCESS, h.trips.refreshAll())
        assertEquals(RefreshResult.SUCCESS, h.trips.refreshTrip("t1"))
    }

    private fun postBodies(path: String) = h.server.drainRequests().filter { it.route == "POST /api/driver/v1$path" }.map { it.body.readUtf8() }

    // --- Trip action workflow ---

    @Test
    fun `only options of the server plan are sent, unchanged, with a stored command id`() = runBlocking {
        routeTrip { DriverApiJson.execution(version = "v7", actions = pickupOptions) }
        h.routes.on("POST", "/trips/t1/actions") { jsonResponse(DriverApiJson.result()) }
        loadTrip()

        val execution = h.executions.observeExecution("t1").first()!!
        assertEquals(listOf("c1", "c2"), execution.availableActions.filter { it.code == ExecutionActionCode.COMPLETE_STOP }.map { it.containerId })
        val secondContainer = execution.availableActions[1]

        // An option that is not in the current plan is never sent.
        assertEquals(
            ActionResult.Rejected(ActionRejection.PLAN_CHANGED),
            h.executions.submit("t1", secondContainer.copy(stopId = "s2"), null),
        )
        assertEquals(ActionResult.Queued, h.executions.submit("t1", secondContainer, null))

        assertEquals(
            listOf("""{"commandId":"cmd-1","expectedVersion":"v7","action":"CompleteStop","stopId":"s1","containerId":"c2"}"""),
            postBodies("/trips/t1/actions"),
        )
        assertTrue(h.commandDao.all.isEmpty())
        val receipt = h.executions.observeReceipts("t1").first().single()
        assertEquals(Instant.parse("2026-09-12T08:05:00Z"), receipt.receivedAt)
        assertNull(receipt.observedAt)
    }

    @Test
    fun `a lost response keeps the command and resends the same id and payload`() = runBlocking {
        routeTrip { DriverApiJson.execution(version = "v7", actions = pickupOptions) }
        val attempts = AtomicInteger()
        h.routes.on("POST", "/trips/t1/actions") {
            if (attempts.incrementAndGet() == 1) {
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            } else {
                // The server recognizes the command id and answers with the recorded result.
                jsonResponse(DriverApiJson.result())
            }
        }
        loadTrip()
        val action = h.trips.currentExecution("t1")!!.availableActions.first()

        assertEquals(ActionResult.Rejected(ActionRejection.OUTCOME_UNKNOWN), h.executions.submit("t1", action, null))
        assertEquals(PendingCommandState.OUTCOME_UNKNOWN, h.executions.observePendingAction("t1").first()?.state)
        assertEquals(1, h.sync.observeOverview().first().pendingCount)
        // No new action can be sent while the earlier one is not confirmed.
        assertEquals(
            ActionResult.Rejected(ActionRejection.PENDING_ACTION),
            h.executions.submit("t1", h.trips.currentExecution("t1")!!.availableActions[1], null),
        )

        assertEquals(ActionResult.Queued, h.executions.resendPending("t1"))

        val bodies = postBodies("/trips/t1/actions")
        assertEquals(2, bodies.size)
        assertEquals(bodies[0], bodies[1])
        assertTrue(bodies[0].contains("\"commandId\":\"cmd-1\""))
        assertNull(h.executions.observePendingAction("t1").first())
        assertEquals(0, h.sync.observeOverview().first().pendingCount)
    }

    @Test
    fun `a conflict drops the stored command and reads the changed plan without replaying it`() = runBlocking {
        val changed = AtomicInteger()
        routeTrip {
            if (changed.get() == 0) {
                DriverApiJson.execution(version = "v7", actions = pickupOptions)
            } else {
                DriverApiJson.execution(version = "v8", actions = listOf(DriverApiJson.action("CompleteStop", "Container unloading completed", "s2", "c1")))
            }
        }
        h.routes.on("POST", "/trips/t1/actions") {
            changed.incrementAndGet()
            problemResponse(409, "conflict")
        }
        loadTrip()

        assertEquals(
            ActionResult.Rejected(ActionRejection.PLAN_CHANGED),
            h.executions.submit("t1", h.trips.currentExecution("t1")!!.availableActions.first(), null),
        )

        assertTrue(h.commandDao.all.isEmpty())
        val plan = h.trips.currentExecution("t1")!!
        assertEquals("v8", plan.version)
        assertEquals("s2", plan.availableActions.single().stopId)
        assertEquals(1, postBodies("/trips/t1/actions").size)
    }

    @Test
    fun `a command interrupted by an app restart is shown as unknown and resent unchanged`() = runBlocking {
        routeTrip { DriverApiJson.execution(version = "v9", actions = pickupOptions) }
        h.routes.on("POST", "/trips/t1/actions") { jsonResponse(DriverApiJson.result()) }
        h.commandDao.insert(h.storedCommand("cmd-9", state = PendingCommandState.SENDING, updatedAt = h.clock.now.minusSeconds(60)))

        assertEquals(PendingCommandState.OUTCOME_UNKNOWN, h.executions.observePendingAction("t1").first()?.state)
        loadTrip()
        assertEquals(ActionResult.Queued, h.executions.resendPending("t1"))

        // The stored version is sent, not the newer one: a queued command is never rewritten.
        assertEquals(
            listOf("""{"commandId":"cmd-9","expectedVersion":"v7","action":"CompleteStop","stopId":"s1","containerId":"c1"}"""),
            postBodies("/trips/t1/actions"),
        )
    }

    @Test
    fun `problem reports use the server's Problem option and always carry a note`() = runBlocking {
        routeTrip { DriverApiJson.execution(version = "v7", actions = pickupOptions) }
        h.routes.on("POST", "/trips/t1/actions") { jsonResponse(DriverApiJson.result()) }
        loadTrip()
        val problem = h.trips.currentExecution("t1")!!.availableActions.single { it.code == ExecutionActionCode.PROBLEM }

        assertEquals(ActionResult.Rejected(ActionRejection.NOTE_REQUIRED), h.executions.submit("t1", problem, "  "))
        assertEquals(ActionResult.Rejected(ActionRejection.NOTE_TOO_LONG), h.executions.submit("t1", problem, "x".repeat(1001)))
        assertEquals(ActionResult.Queued, h.incidents.report("t1", IncidentCategory.DELAY, " Stuck at gate "))

        assertEquals(
            listOf("""{"commandId":"cmd-1","expectedVersion":"v7","action":"Problem","note":"[DELAY] Stuck at gate"}"""),
            postBodies("/trips/t1/actions"),
        )
    }

    @Test
    fun `a legacy trip never uses trip actions`() = runBlocking {
        routeTrip { DriverApiJson.execution(workflow = "LegacyStatuses", actions = pickupOptions) }
        h.routes.on("GET", "/trips/t1/statuses") { jsonResponse(DriverApiJson.page(emptyList())) }
        loadTrip()

        assertTrue(h.trips.currentExecution("t1")!!.availableActions.isEmpty())
        assertEquals(
            ActionResult.Rejected(ActionRejection.PLAN_CHANGED),
            h.executions.submit("t1", com.dynamictransport.dynamicone.domain.model.ExecutionAction(ExecutionActionCode.REST, null, null, null), null),
        )
        assertTrue(h.server.drainRequests().none { it.method == "POST" })
    }

    // --- Step evidence (docs/18) ---

    private val evidence = listOf(
        DriverApiJson.slot("Cmr:trip:all", "Cmr", "Missing", required = true),
        DriverApiJson.slot("Interchange:s1:c1", "Interchange", "Rejected", true, "s1", "c1", documentId = "d2", reviewNote = "Unreadable"),
        DriverApiJson.slot("Interchange:s1:c2", "Interchange", "PendingReview", true, "s1", "c2", documentId = "d3"),
    )

    @Test
    fun `a step blocked by missing evidence is never sent, and is sent unchanged once the plan allows it`() = runBlocking {
        val uploaded = AtomicInteger()
        routeTrip {
            val first = if (uploaded.get() == 0) listOf("Interchange:s1:c1") else emptyList()
            DriverApiJson.execution(
                version = "v${7 + uploaded.get()}",
                actions = listOf(
                    DriverApiJson.action("CompleteStop", "I picked up the full container", "s1", "c1", blockingSlots = first),
                    DriverApiJson.action("CompleteStop", "I picked up the full container", "s1", "c2", blockingSlots = emptyList()),
                ),
                documents = evidence,
            )
        }
        h.routes.on("POST", "/trips/t1/actions") { jsonResponse(DriverApiJson.result()) }
        loadTrip()
        val blocked = h.trips.currentExecution("t1")!!.availableActions.first()
        assertTrue(blocked.isBlocked)
        assertEquals(listOf("Interchange:s1:c1"), h.trips.currentExecution("t1")!!.blockingSlots.map { it.key })

        assertEquals(ActionResult.Rejected(ActionRejection.DOCUMENTS_REQUIRED), h.executions.submit("t1", blocked, null))
        assertTrue(postBodies("/trips/t1/actions").isEmpty())
        assertTrue(h.commandDao.all.isEmpty())

        // A replacement was uploaded: the plan read again no longer blocks the step. The step shown earlier
        // is looked up in the current plan and sent with its current version.
        uploaded.set(1)
        assertEquals(RefreshResult.SUCCESS, h.trips.refreshTrip("t1"))
        assertEquals(ActionResult.Queued, h.executions.submit("t1", blocked, null))
        assertEquals(
            listOf("""{"commandId":"cmd-1","expectedVersion":"v8","action":"CompleteStop","stopId":"s1","containerId":"c1"}"""),
            postBodies("/trips/t1/actions"),
        )
    }

    @Test
    fun `a step the server refuses for missing evidence is dropped, not done, and the checklist is read again`() = runBlocking {
        val refused = AtomicInteger()
        routeTrip {
            val blocking = if (refused.get() == 0) emptyList() else listOf("Cmr:trip:all")
            DriverApiJson.execution(
                version = "v7",
                actions = listOf(DriverApiJson.action("CompleteStop", "I picked up the full container", "s1", "c1", blockingSlots = blocking)),
                documents = evidence,
            )
        }
        h.routes.on("POST", "/trips/t1/actions") {
            refused.incrementAndGet()
            problemResponse(409, "required_documents_missing")
        }
        loadTrip()

        assertEquals(
            ActionResult.Rejected(ActionRejection.DOCUMENTS_REQUIRED),
            h.executions.submit("t1", h.trips.currentExecution("t1")!!.availableActions.single(), null),
        )

        assertTrue(h.commandDao.all.isEmpty())
        assertNull(h.executions.observePendingAction("t1").first())
        val plan = h.trips.currentExecution("t1")!!
        assertTrue(plan.availableActions.single().isBlocked)
        assertEquals(listOf("Cmr:trip:all"), plan.blockingSlots.map { it.key })
        assertEquals(1, postBodies("/trips/t1/actions").size)
    }

    @Test
    fun `a resent command the server now refuses for missing evidence is dropped`() = runBlocking {
        routeTrip { DriverApiJson.execution(version = "v9", actions = pickupOptions, documents = evidence) }
        h.routes.on("POST", "/trips/t1/actions") { problemResponse(409, "required_documents_missing") }
        h.commandDao.insert(h.storedCommand("cmd-9", state = PendingCommandState.OUTCOME_UNKNOWN))
        loadTrip()

        assertEquals(ActionResult.Rejected(ActionRejection.DOCUMENTS_REQUIRED), h.executions.resendPending("t1"))

        assertTrue(h.commandDao.all.isEmpty())
        assertEquals(1, postBodies("/trips/t1/actions").size)
    }

    // --- Trailer confirmation (docs/20) ---

    private val trailerOptions = listOf(
        DriverApiJson.trailerOption("tr1", "WGM 45T2", "DYNA-120"),
        DriverApiJson.trailerOption("tr2", "WGM 88X1"),
        DriverApiJson.trailerOption("tr3", null),
    )

    private fun routeAcceptedTripWithTrailers(trailers: () -> String) {
        routeTrip(status = "Assigned") {
            DriverApiJson.execution(
                version = "v7",
                status = "Assigned",
                actions = listOf(DriverApiJson.action("Start", "I started the trip", blockingReason = "trailer_confirmation_required")),
                trailerConfirmationRequired = true,
            )
        }
        h.routes.on("GET", "/trips/t1/trailers") { jsonResponse(trailers()) }
    }

    @Test
    fun `the driver chooses from the active list and the choice carries the version of that read`() = runBlocking {
        routeAcceptedTripWithTrailers {
            DriverApiJson.trailers(version = "v7", plannedTrailerId = "tr2", trailers = trailerOptions)
        }
        h.routes.on("POST", "/trips/t1/trailer-confirmation") { jsonResponse(DriverApiJson.result(version = "v8")) }
        loadTrip()
        assertEquals(RefreshResult.SUCCESS, h.executions.refreshTrailers("t1"))

        val state = h.executions.observeTrailers("t1").first()!!
        // A trailer without a registration is dropped; the planned one is offered first.
        assertEquals(listOf("tr1", "tr2"), state.trailers.map { it.id })
        assertEquals(listOf("tr2", "tr1"), state.ordered.map { it.id })
        assertEquals("DYNA-120", state.trailers.first().companyCode)
        assertTrue(state.canConfirm)

        // A trailer that is not in the list never reaches the server.
        assertEquals(ActionResult.Rejected(ActionRejection.NOT_FOUND), h.executions.confirmTrailer("t1", "tr9"))
        assertEquals(ActionResult.Queued, h.executions.confirmTrailer("t1", "tr1"))
        assertEquals(
            listOf("""{"trailerId":"tr1","expectedVersion":"v7"}"""),
            postBodies("/trips/t1/trailer-confirmation"),
        )
    }

    @Test
    fun `a trip that cannot choose any more is never sent a confirmation`() = runBlocking {
        routeAcceptedTripWithTrailers {
            DriverApiJson.trailers(version = "v7", canConfirm = false, confirmedTrailerId = "tr1", trailers = trailerOptions)
        }
        loadTrip()

        assertEquals(RefreshResult.SUCCESS, h.executions.refreshTrailers("t1"))
        assertEquals(ActionResult.Rejected(ActionRejection.NOT_ALLOWED), h.executions.confirmTrailer("t1", "tr1"))

        assertTrue(h.server.drainRequests().none { it.method == "POST" })
    }

    @Test
    fun `a changed plan makes the driver choose again from the current list`() = runBlocking {
        val reads = AtomicInteger(0)
        routeAcceptedTripWithTrailers {
            if (reads.getAndIncrement() == 0) {
                DriverApiJson.trailers(version = "v7", trailers = trailerOptions)
            } else {
                DriverApiJson.trailers(version = "v9", trailers = listOf(DriverApiJson.trailerOption("tr2", "WGM 88X1")))
            }
        }
        h.routes.on("POST", "/trips/t1/trailer-confirmation") { problemResponse(409, "conflict") }
        loadTrip()
        assertEquals(RefreshResult.SUCCESS, h.executions.refreshTrailers("t1"))

        assertEquals(ActionResult.Rejected(ActionRejection.PLAN_CHANGED), h.executions.confirmTrailer("t1", "tr1"))
        val state = h.executions.observeTrailers("t1").first()!!
        assertEquals("v9", state.version)
        assertEquals(listOf("tr2"), state.trailers.map { it.id })
    }

    @Test
    fun `starting is not offered and never sent while the trailer is not confirmed`() = runBlocking {
        routeAcceptedTripWithTrailers { DriverApiJson.trailers(version = "v7", trailers = trailerOptions) }
        loadTrip()

        val execution = h.trips.currentExecution("t1")!!
        assertTrue(execution.trailerConfirmationRequired)
        val start = execution.availableActions.single { it.code == ExecutionActionCode.START }
        assertTrue(start.blockedByTrailer)

        assertEquals(ActionResult.Rejected(ActionRejection.NOT_ALLOWED), h.executions.submit("t1", start, null))
        assertTrue(h.server.drainRequests().none { it.method == "POST" })
    }

    // --- Documents ---

    private fun routeChecklist() {
        h.routes.on("GET", "/trips/t1/document-requirements") {
            jsonResponse(
                "[" + listOf(
                    DriverApiJson.slot("Cmr:trip:all", "Cmr", "Missing", required = true),
                    DriverApiJson.slot("Customs:trip:all", "Customs", "NotRequired", required = false),
                    DriverApiJson.slot("Interchange:s1:c1", "Interchange", "Rejected", true, "s1", "c1", documentId = "d2", reviewNote = "Unreadable"),
                    DriverApiJson.slot("Interchange:s1:c2", "Interchange", "PendingReview", true, "s1", "c2", documentId = "d3"),
                    DriverApiJson.slot("Container:s1:c1", "Container", "Approved", true, "s1", "c1", documentId = "d4"),
                    DriverApiJson.slot("Seal:s1:c1", "Seal", "Missing", true, "s1", "c1"),
                    DriverApiJson.slot("", "Seal", "Missing", true),
                    DriverApiJson.slot("Reefer:trip:all", "Reefer", "Inspected", false),
                ).joinToString(",") + "]",
            )
        }
        h.routes.on("GET", "/trips/t1/documents") {
            jsonResponse(
                DriverApiJson.page(
                    listOf(
                        """{"id":"d2","type":"Interchange","fileName":"eir-2.jpg","contentType":"image/jpeg","submittedAtUtc":"2026-09-12T09:00:00Z"}""",
                        """{"id":"d1","type":"Interchange","fileName":"eir-1.jpg","contentType":"image/jpeg","submittedAtUtc":"2026-09-12T08:00:00Z"}""",
                    ),
                ),
            )
        }
    }

    @Test
    fun `every slot of the server checklist is kept separately with its own state`() = runBlocking {
        routeChecklist()

        assertNull(h.documents.observeSlots("t1").first())
        assertEquals(RefreshResult.SUCCESS, h.documents.refreshDocuments("t1"))

        val slots = h.documents.observeSlots("t1").first()!!
        assertEquals(7, slots.size)
        assertEquals(
            listOf(DocumentType.CMR, DocumentType.CUSTOMS, DocumentType.INTERCHANGE, DocumentType.INTERCHANGE, DocumentType.CONTAINER_PHOTO, DocumentType.SEAL_PHOTO, null),
            slots.map { it.type },
        )
        val rejected = slots[2]
        assertEquals(DocumentSlotState.REJECTED, rejected.state)
        assertEquals("Unreadable", rejected.reviewNote)
        assertEquals("d2", rejected.documentId)
        assertTrue(rejected.needsUpload)
        assertEquals(DocumentSlotState.PENDING_REVIEW, slots[3].state)
        assertEquals(false, slots[1].required)
        assertFalse(slots[1].needsUpload)
        assertEquals(DocumentSlotState.UNKNOWN, slots[6].state)
        assertEquals(listOf("d2", "d1"), h.documents.observeFiles("t1").first().map { it.id })
        assertEquals("/api/driver/v1/trips/t1/document-requirements", h.server.takeRequest().path)
    }

    @Test
    fun `upload sends the opaque slot key and the document and reads the checklist again`() = runBlocking {
        routeTrip { DriverApiJson.execution() }
        routeChecklist()
        h.routes.on("POST", "/trips/t1/document-slots") { jsonResponse(DriverApiJson.result(code = "uploaded", version = null)) }
        loadTrip()
        h.documents.refreshDocuments("t1")
        h.server.drainRequests()
        val cmr = h.documents.observeSlots("t1").first()!!.first()
        val capture = mediaFile("doc_1.pdf").apply { writeText("%PDF-1.7\n1 0 obj\n") }

        assertEquals(ActionResult.Queued, h.documents.upload("t1", cmr, capture.path))

        val requests = h.server.drainRequests()
        val upload = requests.single { it.method == "POST" }
        assertTrue(upload.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertEquals("Bearer access-1", upload.getHeader("Authorization"))
        val body = upload.body.readUtf8()
        assertTrue(body.contains("name=\"slot\""))
        assertTrue(body.contains("\r\n\r\nCmr:trip:all\r\n"))
        assertTrue(body.contains("name=\"document\"; filename=\"doc_1.pdf\""))
        assertTrue(body.contains("Content-Type: application/pdf"))
        assertEquals(
            listOf(
                "GET /api/driver/v1/trips/t1/document-requirements",
                "GET /api/driver/v1/trips/t1/documents",
                "GET /api/driver/v1/trips/t1",
                "GET /api/driver/v1/trips/t1/execution",
            ),
            requests.drop(1).map { it.route },
        )
        assertFalse(capture.exists())
    }

    @Test
    fun `container and seal slots accept photos only, PNG is uploaded as it is`() = runBlocking {
        routeTrip { DriverApiJson.execution() }
        routeChecklist()
        h.routes.on("POST", "/trips/t1/document-slots") { jsonResponse(DriverApiJson.result(code = "uploaded", version = null)) }
        loadTrip()
        h.documents.refreshDocuments("t1")
        h.server.drainRequests()
        val seal = h.documents.observeSlots("t1").first()!!.single { it.key == "Seal:s1:c1" }

        val pdf = mediaFile("seal.pdf").apply { writeText("%PDF-1.7\n") }
        assertEquals(ActionResult.Rejected(ActionRejection.PHOTO_REQUIRED), h.documents.upload("t1", seal, pdf.path))
        assertTrue(h.server.drainRequests().isEmpty())

        val png = mediaFile("seal.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32))
        }
        assertEquals(ActionResult.Queued, h.documents.upload("t1", seal, png.path))
        assertTrue(h.server.drainRequests().single { it.method == "POST" }.body.readUtf8().contains("Content-Type: image/png"))
    }

    @Test
    fun `evidence can be added to a completed trip but not to a new assignment`() = runBlocking {
        routeTrip(assignment = "Completed", status = "Completed") { DriverApiJson.execution(status = "Completed") }
        routeChecklist()
        h.routes.on("POST", "/trips/t1/document-slots") { jsonResponse(DriverApiJson.result(code = "uploaded", version = null)) }
        loadTrip()
        h.documents.refreshDocuments("t1")
        val cmr = h.documents.observeSlots("t1").first()!!.first()

        assertEquals(ActionResult.Queued, h.documents.upload("t1", cmr, mediaFile("late.pdf").apply { writeText("%PDF-1.7\n") }.path))

        h.server.drainRequests()
        routeTrip(assignment = "Confirmed", status = "Assigned") { DriverApiJson.execution() }
        h.trips.refreshTrip("t1")
        assertEquals(
            ActionResult.Rejected(ActionRejection.TRIP_REQUIRED),
            h.documents.upload("t1", cmr, mediaFile("early.pdf").apply { writeText("%PDF-1.7\n") }.path),
        )
        assertTrue(h.server.drainRequests().none { it.method == "POST" })
    }

    @Test
    fun `a lost upload response reads the checklist again and is never uploaded twice`() = runBlocking {
        routeTrip { DriverApiJson.execution() }
        routeChecklist()
        h.routes.on("POST", "/trips/t1/document-slots") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        loadTrip()
        h.documents.refreshDocuments("t1")
        h.server.drainRequests()
        val cmr = h.documents.observeSlots("t1").first()!!.first()

        assertEquals(
            ActionResult.Rejected(ActionRejection.OFFLINE),
            h.documents.upload("t1", cmr, mediaFile("doc.pdf").apply { writeText("%PDF-1.7\n") }.path),
        )

        val routes = h.server.drainRequests().map { it.route }
        assertEquals(1, routes.count { it.startsWith("POST") })
        assertEquals("GET /api/driver/v1/trips/t1/document-requirements", routes[1])
    }

    @Test
    fun `refused uploads are explained and files are checked before any request`() = runBlocking {
        routeTrip { DriverApiJson.execution() }
        routeChecklist()
        var next: () -> MockResponse = { problemResponse(400, "invalid_slot") }
        h.routes.on("POST", "/trips/t1/document-slots") { next() }
        loadTrip()
        h.documents.refreshDocuments("t1")
        val cmr = h.documents.observeSlots("t1").first()!!.first()

        assertEquals(ActionResult.Rejected(ActionRejection.PLAN_CHANGED), h.documents.upload("t1", cmr, pdf("a")))
        next = { problemResponse(400, "document_rejected") }
        assertEquals(ActionResult.Rejected(ActionRejection.UNSUPPORTED_FILE), h.documents.upload("t1", cmr, pdf("b")))
        next = { MockResponse().setResponseCode(413) }
        assertEquals(ActionResult.Rejected(ActionRejection.FILE_TOO_LARGE), h.documents.upload("t1", cmr, pdf("c")))

        h.server.drainRequests()
        val large = folder.newFile("large.pdf")
        RandomAccessFile(large, "rw").use {
            it.write("%PDF-1.7".toByteArray())
            it.setLength(DocumentUploadPreparer.MAX_UPLOAD_BYTES + 1)
        }
        val text = folder.newFile("notes.txt").apply { writeText("not a document") }
        assertEquals(ActionResult.Rejected(ActionRejection.FILE_TOO_LARGE), h.documents.upload("t1", cmr, large.path))
        assertEquals(ActionResult.Rejected(ActionRejection.UNSUPPORTED_FILE), h.documents.upload("t1", cmr, text.path))
        assertTrue(h.server.drainRequests().isEmpty())
        assertTrue(large.exists())
    }

    private fun mediaFile(name: String) = File(File(h.directories.filesDir, "document_media").apply { mkdirs() }, name)

    private fun pdf(name: String) = mediaFile("$name.pdf").apply { writeText("%PDF-1.7\n") }.path

    // --- Payroll ---

    @Test
    fun `an unpublished month is shown as unpublished, never as zero`() = runBlocking {
        h.routes.on("GET", "/me/payroll") { jsonResponse(DriverApiJson.PAYROLL_UNPUBLISHED) }
        val month = YearMonth.of(2026, 9)

        assertNull(h.payroll.observePayroll(month).first())
        assertEquals(RefreshResult.SUCCESS, h.payroll.refresh(month))

        assertEquals(MonthlyPayroll.Unpublished(month), h.payroll.observePayroll(month).first())
        assertEquals("/api/driver/v1/me/payroll?year=2026&month=9", h.server.takeRequest().path)
    }

    @Test
    fun `a published snapshot keeps currencies, categories, unknown types and exact decimals`() = runBlocking {
        h.routes.on("GET", "/me/payroll") { jsonResponse(DriverApiJson.payrollPublished()) }
        val month = YearMonth.of(2026, 9)

        assertEquals(RefreshResult.SUCCESS, h.payroll.refresh(month))

        val payroll = h.payroll.observePayroll(month).first() as MonthlyPayroll.Published
        assertEquals(Instant.parse("2026-10-02T09:30:00Z"), payroll.publishedAt)
        assertEquals(listOf("PLN", "EUR"), payroll.currencies.map { it.currencyCode })
        val pln = payroll.currencies[0]
        assertEquals(BigDecimal("5000.10"), pln.baseGross)
        assertEquals(listOf("Bonus", "FutureAllowance"), pln.additions.map { it.type })
        assertEquals(BigDecimal("0.20"), pln.additions[0].amount)
        assertEquals(BigDecimal("12.345"), pln.additions[1].amount)
        assertEquals(LocalDate.of(2026, 9, 15), pln.additions[1].effectiveFrom)
        assertEquals(listOf("TrafficFine"), pln.deductions.map { it.type })
        assertEquals(BigDecimal("4700.30"), pln.balanceBeforeStatutoryDeductions)
        assertEquals(BigDecimal("0"), payroll.currencies[1].baseGross)
        assertEquals(BigDecimal("7.5"), payroll.finalized?.overtimeHours)
        assertNull(payroll.finalized?.overtimeGross)
        assertEquals("PLN", payroll.finalized?.currency)
    }

    @Test
    fun `an inconsistent or missing payroll answer is an error and nothing is shown`() = runBlocking {
        var next: () -> MockResponse = { jsonResponse("""{"payroll":null,"publishedAtUtc":null,"state":"Published"}""") }
        h.routes.on("GET", "/me/payroll") { next() }
        val month = YearMonth.of(2026, 9)

        assertEquals(RefreshResult.FAILED, h.payroll.refresh(month))
        next = { jsonResponse(DriverApiJson.payrollPublished(month = 8)) }
        assertEquals(RefreshResult.FAILED, h.payroll.refresh(month))
        next = { problemResponse(404, "not_found") }
        assertEquals(RefreshResult.FAILED, h.payroll.refresh(month))
        assertEquals(RefreshResult.FAILED, h.payroll.refresh(YearMonth.of(1999, 12)))

        assertNull(h.payroll.observePayroll(month).first())
        assertEquals(3, h.server.requestCount)
    }

    // --- Signed documents ---

    @Test
    fun `signed documents are paged and opened as the stored PDF with the bearer token`() = runBlocking {
        h.routes.on("GET", "/me/signed-documents", mapOf("page" to "1")) {
            jsonResponse(
                DriverApiJson.page(
                    listOf(
                        DriverApiJson.signedDocument("sd1", "Compensation", "2026-09-01T10:00:00Z"),
                        DriverApiJson.signedDocument("sd2", "Leave", "2026-08-01T10:00:00Z"),
                    ),
                    hasMore = true,
                ),
            )
        }
        h.routes.on("GET", "/me/signed-documents", mapOf("page" to "2")) {
            jsonResponse(DriverApiJson.page(listOf(DriverApiJson.signedDocument("sd3", "Contract", "2026-07-01T10:00:00Z")), page = 2))
        }
        h.routes.on("GET", "/me/signed-documents/sd1/content") {
            MockResponse().setHeader("Content-Type", "application/pdf").setBody("%PDF-1.7 signed")
        }

        assertEquals(RefreshResult.SUCCESS, h.signedDocuments.refresh())
        assertEquals(RefreshResult.SUCCESS, h.signedDocuments.loadMore())
        val documents = h.signedDocuments.observeDocuments().first()!!
        assertEquals(
            listOf(SignedDocumentType.COMPENSATION, SignedDocumentType.LEAVE, SignedDocumentType.UNKNOWN),
            documents.map { it.type },
        )
        assertFalse(h.signedDocuments.observeCanLoadMore().first())

        val opened = h.signedDocuments.open("sd1") as DocumentContentResult.Available

        assertEquals("%PDF-1.7 signed", File(opened.filePath).readText())
        assertEquals("application/pdf", opened.contentType)
        val content = h.server.drainRequests().last()
        assertEquals("/api/driver/v1/me/signed-documents/sd1/content", content.path)
        assertEquals("Bearer access-1", content.getHeader("Authorization"))
    }

    // --- Messages ---

    @Test
    fun `general and trip conversations are separate reads`() = runBlocking {
        h.routes.on("GET", "/messages") { jsonResponse(DriverApiJson.page(emptyList())) }

        h.messages.refresh(null)
        h.messages.refresh("t1")

        // Without a filter the server returns the whole inbox (TH-2026-0440): the general conversation asks for general messages only.
        assertEquals("/api/driver/v1/messages?generalOnly=true&page=1&pageSize=25", h.server.takeRequest().path)
        assertEquals("/api/driver/v1/messages?tripId=t1&page=1&pageSize=25", h.server.takeRequest().path)
    }

    @Test
    fun `pages are merged oldest first without duplicates`() = runBlocking {
        h.routes.on("GET", "/messages", mapOf("page" to "1")) {
            jsonResponse(
                DriverApiJson.page(
                    listOf(
                        DriverApiJson.message("m3", "ToDriver", "Gate 4", "2026-09-12T09:00:00Z", senderDisplayName = "Ayşe Yılmaz"),
                        DriverApiJson.message("m2", "FromDriver", "Arrived", "2026-09-12T08:30:00Z", readAtUtc = "2026-09-12T08:31:00Z"),
                    ),
                    hasMore = true,
                ),
            )
        }
        h.routes.on("GET", "/messages", mapOf("page" to "2")) {
            jsonResponse(
                DriverApiJson.page(
                    listOf(
                        DriverApiJson.message("m2", "FromDriver", "Arrived", "2026-09-12T08:30:00Z"),
                        DriverApiJson.message("m1", "ToDriver", "Good morning", "2026-09-12T07:00:00Z"),
                        DriverApiJson.message("m0", "Sideways", "Unknown direction", "2026-09-12T06:00:00Z"),
                    ),
                    page = 2,
                ),
            )
        }

        h.messages.refresh(null)
        assertTrue(h.messages.observeConversation(null).first().canLoadOlder)
        h.messages.loadOlder(null)

        val conversation = h.messages.observeConversation(null).first()
        assertEquals(listOf("m1", "m2", "m3"), conversation.messages.map { it.id })
        assertEquals(MessageDirection.FROM_DRIVER, conversation.messages[1].direction)
        assertEquals(listOf(null, null, "Ayşe Yılmaz"), conversation.messages.map { it.senderName })
        assertFalse(conversation.canLoadOlder)
    }

    @Test
    fun `a message with a lost response is resent with the same id and shown once`() = runBlocking {
        val sent = AtomicInteger()
        h.routes.on("POST", "/messages") {
            if (sent.incrementAndGet() == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else jsonResponse(DriverApiJson.result(version = null))
        }
        h.routes.on("GET", "/messages") {
            val items = if (sent.get() >= 2) listOf(DriverApiJson.message("msg-1", "FromDriver", "At the terminal", "2026-09-12T08:00:00Z", tripId = "t1")) else emptyList()
            jsonResponse(DriverApiJson.page(items))
        }

        assertEquals(ActionResult.Rejected(ActionRejection.OUTCOME_UNKNOWN), h.messages.send("t1", "  At the terminal  "))
        val pending = h.messages.observeConversation("t1").first().pending.single()
        assertEquals(PendingCommandState.OUTCOME_UNKNOWN, pending.state)
        assertTrue(h.messages.observeConversation(null).first().pending.isEmpty())

        assertEquals(ActionResult.Queued, h.messages.resend(pending.commandId))

        val bodies = postBodies("/messages")
        assertEquals(listOf("""{"commandId":"msg-1","body":"At the terminal","tripId":"t1"}"""), bodies.distinct())
        assertEquals(2, bodies.size)
        val conversation = h.messages.observeConversation("t1").first()
        assertEquals(listOf("msg-1"), conversation.messages.map { it.id })
        assertTrue(conversation.pending.isEmpty())
        assertTrue(h.commandDao.all.isEmpty())
    }

    @Test
    fun `invalid messages are refused before storing or sending`() = runBlocking {
        assertEquals(ActionResult.Rejected(ActionRejection.NOTE_REQUIRED), h.messages.send(null, "   "))
        assertEquals(ActionResult.Rejected(ActionRejection.NOTE_TOO_LONG), h.messages.send(null, "x".repeat(2001)))
        assertTrue(h.commandDao.all.isEmpty())
        assertEquals(0, h.server.requestCount)
    }

    @Test
    fun `read receipts are sent only for unread incoming messages with the conversation in the body`() = runBlocking {
        h.routes.on("GET", "/messages") {
            jsonResponse(
                DriverApiJson.page(
                    listOf(
                        DriverApiJson.message("in-general", "ToDriver", "Hello", "2026-09-12T08:00:00Z"),
                        DriverApiJson.message("out", "FromDriver", "Hi", "2026-09-12T08:01:00Z"),
                        DriverApiJson.message("in-read", "ToDriver", "Read", "2026-09-12T08:02:00Z", readAtUtc = "2026-09-12T08:03:00Z"),
                    ),
                ),
            )
        }
        h.routes.on("GET", "/messages", mapOf("tripId" to "t1")) {
            jsonResponse(DriverApiJson.page(listOf(DriverApiJson.message("in-trip", "ToDriver", "Gate 4", "2026-09-12T08:00:00Z", tripId = "t1"))))
        }
        h.routes.on("POST", "/messages/in-general/read") { MockResponse().setResponseCode(204) }
        h.routes.on("POST", "/messages/in-trip/read") { MockResponse().setResponseCode(204) }
        h.messages.refresh(null)
        h.messages.refresh("t1")
        h.server.drainRequests()

        assertTrue(h.messages.markRead(null, "in-general"))
        assertFalse(h.messages.markRead(null, "in-general"))
        assertFalse(h.messages.markRead(null, "out"))
        assertFalse(h.messages.markRead(null, "in-read"))
        assertTrue(h.messages.markRead("t1", "in-trip"))

        val reads = h.server.drainRequests()
        assertEquals(listOf("POST /api/driver/v1/messages/in-general/read", "POST /api/driver/v1/messages/in-trip/read"), reads.map { it.route })
        assertEquals("{}", reads[0].body.readUtf8())
        assertEquals("{}", reads[1].body.readUtf8())
    }

    @Test
    fun `the conversation list groups the inbox by trip with unread counts and trip numbers`() = runBlocking {
        h.routes.on("GET", "/messages") {
            jsonResponse(
                DriverApiJson.page(
                    listOf(
                        DriverApiJson.message("t2-in", "ToDriver", "Seal photo please", "2026-09-12T10:00:00Z", tripId = "t2"),
                        DriverApiJson.message("t1-out", "FromDriver", "At the terminal", "2026-09-12T09:05:00Z", tripId = "t1"),
                        DriverApiJson.message("t1-in", "ToDriver", "Gate 4", "2026-09-12T09:00:00Z", tripId = "t1"),
                        DriverApiJson.message("t1-read", "ToDriver", "Hello", "2026-09-12T08:30:00Z", tripId = "t1", readAtUtc = "2026-09-12T08:31:00Z"),
                    ),
                ),
            )
        }
        h.routes.on("GET", "/trips/t1") { jsonResponse(DriverApiJson.detail(DriverApiJson.summary("t1", "Active", "InProgress"))) }
        h.routes.on("GET", "/trips/t2") { MockResponse().setResponseCode(404) }

        h.messages.refreshConversations()

        val list = h.messages.observeConversations().first()
        assertTrue(list.loaded)
        // The general conversation is offered first even without messages, then the trip with the newest message.
        assertEquals(listOf(null, "t2", "t1"), list.conversations.map { it.tripId })
        assertEquals(null, list.conversations[0].lastMessage)
        val t1 = list.conversations[2]
        assertEquals("TRP-t1", t1.tripNo)
        assertEquals("t1-out", t1.lastMessage?.id)
        assertEquals(1, t1.unreadCount)
        assertEquals("t1-in", t1.lastUnread?.id)
        // A trip that cannot be read is still listed, without its number.
        assertEquals(null, list.conversations[1].tripNo)
        assertEquals(2, list.totalUnread)
    }

    @Test
    fun `the general conversation is read with generalOnly and a read receipt clears its unread count`() = runBlocking {
        h.routes.on("GET", "/messages") {
            jsonResponse(
                DriverApiJson.page(
                    listOf(
                        DriverApiJson.message("t1-in", "ToDriver", "Gate 4", "2026-09-12T09:00:00Z", tripId = "t1"),
                        DriverApiJson.message("in", "ToDriver", "Hello", "2026-09-12T08:00:00Z"),
                    ),
                ),
            )
        }
        h.routes.on("GET", "/messages", mapOf("generalOnly" to "true")) {
            jsonResponse(DriverApiJson.page(listOf(DriverApiJson.message("in", "ToDriver", "Hello", "2026-09-12T08:00:00Z"))))
        }
        h.routes.on("GET", "/trips/t1") { MockResponse().setResponseCode(404) }
        h.routes.on("POST", "/messages/in/read") { MockResponse().setResponseCode(204) }

        h.messages.refreshConversations()
        h.messages.refresh(null)
        val reads = h.server.drainRequests().filter { it.route == "GET /api/driver/v1/messages" }
        assertEquals(listOf(null, "true"), reads.map { it.requestUrl?.queryParameter("generalOnly") })
        assertEquals(listOf("in"), h.messages.observeConversation(null).first().messages.map { it.id })
        assertEquals(listOf(1, 1), h.messages.observeConversations().first().conversations.map { it.unreadCount })

        assertTrue(h.messages.markRead(null, "in"))

        assertEquals(listOf(0, 1), h.messages.observeConversations().first().conversations.map { it.unreadCount })
    }
}
