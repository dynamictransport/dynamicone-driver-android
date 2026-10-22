package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DrawnSignature
import com.dynamictransport.dynamicone.domain.model.HrContentProblem
import com.dynamictransport.dynamicone.domain.model.HrContentResult
import com.dynamictransport.dynamicone.domain.model.HrDocumentLookup
import com.dynamictransport.dynamicone.domain.model.HrDocumentStatus
import com.dynamictransport.dynamicone.domain.model.HrSignResult
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SignatureFailure
import com.dynamictransport.dynamicone.domain.rules.HrDocumentRules
import com.dynamictransport.dynamicone.testing.DriverApiJson
import com.dynamictransport.dynamicone.testing.HrDocumentJson
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.RoutingDispatcher.Companion.MOBILE_API
import com.dynamictransport.dynamicone.testing.SequenceIdGenerator
import com.dynamictransport.dynamicone.testing.TEST_JSON
import com.dynamictransport.dynamicone.testing.VALID_STROKE
import com.dynamictransport.dynamicone.testing.drainRequests
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import com.dynamictransport.dynamicone.testing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Personnel document signing against the docs/17 contract with the production HTTP stack and a MockWebServer. */
class RealHrDocumentRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    private val signature = DrawnSignature(listOf(VALID_STROKE), HrDocumentRules.PAD_ASPECT_RATIO)
    private val shaPl = HrDocumentJson.sha256(HrDocumentJson.PDF_PL)
    private val soon = HrDocumentJson.document("doc-soon", dueAtUtc = "2026-09-14T21:59:00Z")
    private val later = HrDocumentJson.document("doc-later", dueAtUtc = "2026-09-20T21:59:00Z")

    @Before
    fun setUp() {
        h = RealApiHarness(folder, mobileAccount = true)
        h.signInOffice()
    }

    @After
    fun tearDown() = h.close()

    private fun pendingRoute(vararg documents: String) =
        h.routes.on("GET", "$MOBILE_API/hr-documents", mapOf("state" to "Pending")) { jsonResponse(HrDocumentJson.page(documents.toList())) }

    private fun signedRoute(vararg documents: String, hasMore: Boolean = false) =
        h.routes.on("GET", "$MOBILE_API/hr-documents", mapOf("state" to "Signed")) {
            jsonResponse(HrDocumentJson.page(documents.toList(), pageSize = 25, hasMore = hasMore))
        }

    private fun signedDocument(id: String, language: String = "pl") = HrDocumentJson.document(
        id,
        status = "Signed",
        signedAtUtc = "2026-09-12T07:59:58Z",
        signedLanguageCode = language,
        receiptAvailable = true,
    )

    private fun pdf(bytes: ByteArray) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/pdf")
        .setHeader("ETag", "\"${HrDocumentJson.sha256(bytes)}\"")
        .setBody(Buffer().write(bytes))

    private fun offline() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)

    private fun signatureRoute(response: () -> MockResponse) = h.routes.on("POST", "$MOBILE_API/hr-documents/doc-soon/signature", response = response)

    /** Reads the documents so the repository knows them, then forgets the requests. */
    private fun loaded() = runBlocking {
        pendingRoute(soon, later)
        signedRoute()
        assertEquals(RefreshResult.SUCCESS, h.hrDocuments.refresh())
        h.server.drainRequests()
    }

    /** The same stores after the app process was restarted. */
    private fun restartedRepository() = RealHrDocumentRepository(
        h.mobileApi, h.tracker, h.hrDao, h.hrFiles, h.downloader, h.sessionPreferences, h.hrScheduler,
        SequenceIdGenerator("restart"), h.clock, h.uptime, TEST_JSON,
    )

    private fun signatureBodies() = h.server.drainRequests()
        .filter { it.route.endsWith("/signature") }
        .map { TEST_JSON.parseToJsonElement(it.body.readUtf8()).jsonObject }

    @Test
    fun `documents to sign are read soonest first and kept on the phone for a start without network`() = runBlocking {
        pendingRoute(later, soon)
        signedRoute(signedDocument("doc-old"), hasMore = true)

        assertEquals(RefreshResult.SUCCESS, h.hrDocuments.refresh())

        val requests = h.server.drainRequests().map { it.requestUrl!! }
        assertEquals(listOf("100", "25"), requests.map { it.queryParameter("pageSize") })
        val lists = h.hrDocuments.observeLists().first()
        assertEquals(listOf("doc-soon", "doc-later"), lists.pending?.map { it.id })
        assertEquals(listOf("doc-old"), lists.signed?.map { it.id })
        assertTrue(lists.canLoadMoreSigned)
        val first = lists.pending!!.first()
        assertEquals(Instant.parse("2026-09-14T21:59:00Z"), first.dueAt)
        assertEquals(listOf("pl", "tr"), first.languages.map { it.code })
        assertEquals(shaPl, first.language("pl")?.contentSha256)

        // App restart without network: the list comes from the phone, marked with the time of the last read.
        // No network at all: the connection is refused.
        h.server.shutdown()
        val restarted = restartedRepository()
        val cached = withTimeout(5_000) { restarted.observeLists().first { it.pending != null } }
        assertEquals(listOf("doc-soon", "doc-later"), cached.pending?.map { it.id })
        assertEquals(h.clock.now, cached.updatedAt)
        assertEquals(RefreshResult.OFFLINE, restarted.refresh())
        assertEquals(listOf("doc-soon", "doc-later"), restarted.observeLists().first().pending?.map { it.id })
    }

    @Test
    fun `more signed documents are read page by page`() = runBlocking {
        pendingRoute()
        signedRoute(signedDocument("doc-1"), hasMore = true)
        h.hrDocuments.refresh()
        h.routes.on("GET", "$MOBILE_API/hr-documents", mapOf("state" to "Signed", "page" to "2")) {
            jsonResponse(HrDocumentJson.page(listOf(signedDocument("doc-2"), signedDocument("doc-1")), page = 2, pageSize = 25))
        }

        assertEquals(RefreshResult.SUCCESS, h.hrDocuments.loadMoreSigned())

        val lists = h.hrDocuments.observeLists().first()
        assertEquals(listOf("doc-1", "doc-2"), lists.signed?.map { it.id })
        assertFalse(lists.canLoadMoreSigned)
    }

    @Test
    fun `the PDF is downloaded once, checked against its checksum and read from the phone again`() = runBlocking {
        loaded()
        h.routes.on("GET", "$MOBILE_API/hr-documents/doc-soon/content", mapOf("languageCode" to "pl")) { pdf(HrDocumentJson.PDF_PL) }

        val first = h.hrDocuments.content("doc-soon", "pl") as HrContentResult.Available
        val second = h.hrDocuments.content("doc-soon", "pl") as HrContentResult.Available

        assertArrayEquals(HrDocumentJson.PDF_PL, File(first.filePath).readBytes())
        assertEquals(first.filePath, second.filePath)
        assertEquals(shaPl, first.contentSha256)
        val downloads = h.server.drainRequests()
        assertEquals(1, downloads.size)
        assertEquals("Bearer access-1", downloads.single().getHeader("Authorization"))
        // App-private storage, never the shared viewing cache.
        assertTrue(first.filePath.startsWith(h.directories.filesDir.path))
    }

    @Test
    fun `bytes that do not match the checksum are never shown`() = runBlocking {
        loaded()
        h.routes.on("GET", "$MOBILE_API/hr-documents/doc-soon/content") { pdf("%PDF-1.7 something else".toByteArray()) }

        assertEquals(HrContentResult.Unavailable(HrContentProblem.INTEGRITY), h.hrDocuments.content("doc-soon", "pl"))

        assertTrue(File(h.directories.filesDir, "hr_documents").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a phone without room does not start the download`() = runBlocking {
        loaded()
        h.freeSpace.value = 10L * 1024 * 1024

        assertEquals(HrContentResult.Unavailable(HrContentProblem.LOW_STORAGE), h.hrDocuments.content("doc-soon", "pl"))
        assertEquals(0, h.server.drainRequests().size)
    }

    @Test
    fun `a withdrawn document cannot be downloaded and leaves the list`() = runBlocking {
        loaded()
        h.routes.on("GET", "$MOBILE_API/hr-documents/doc-soon/content") { problemResponse(409, "hr_document_withdrawn") }

        assertEquals(HrContentResult.Unavailable(HrContentProblem.WITHDRAWN), h.hrDocuments.content("doc-soon", "pl"))

        assertEquals(listOf("doc-later"), h.hrDocuments.observeLists().first().pending?.map { it.id })
        val lookup = h.hrDocuments.observeDocument("doc-soon").first() as HrDocumentLookup.Found
        assertEquals(HrDocumentStatus.WITHDRAWN, lookup.document.status)
        // The stored list follows: a restart does not bring it back.
        assertEquals(listOf("doc-later"), restartedRepository().observeLists().first { it.pending != null }.pending?.map { it.id })
    }

    @Test
    fun `a signature is stored first and sent with its command id, language, checksum, strokes and times`() = runBlocking {
        loaded()
        h.uptime.elapsedMillis = 5_000_000L
        signatureRoute { jsonResponse(HrDocumentJson.signed(signedDocument("doc-soon")), code = 201) }

        val result = h.hrDocuments.sign("doc-soon", "pl", shaPl, signature)

        assertTrue(result is HrSignResult.Signed)
        val request = h.server.drainRequests().single()
        assertEquals("POST /api/mobile/v1/hr-documents/doc-soon/signature", request.route)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
        val body = TEST_JSON.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("sig-1", body.getValue("commandId").jsonPrimitive.content)
        assertEquals("pl", body.getValue("languageCode").jsonPrimitive.content)
        assertEquals(shaPl, body.getValue("contentSha256").jsonPrimitive.content)
        assertEquals("true", body.getValue("declarationAccepted").jsonPrimitive.content)
        assertEquals("2026-09-12T08:00:00Z", body.getValue("signedAtUtc").jsonPrimitive.content)
        assertEquals(0L, body.getValue("elapsedSinceSignMs").jsonPrimitive.long)
        assertEquals(h.sessionPreferences.installationId(), body.getValue("installationId").jsonPrimitive.content)
        val drawing = body.getValue("signature").jsonObject
        assertEquals(2.5f, drawing.getValue("aspectRatio").jsonPrimitive.float)
        val points = drawing.getValue("strokes").jsonArray.single().jsonArray
        assertEquals(3, points.size)
        assertEquals(0.1f, points.first().jsonObject.getValue("x").jsonPrimitive.float)
        assertEquals(0.5f, points.first().jsonObject.getValue("y").jsonPrimitive.float)

        // Confirmed: nothing of the drawing stays on the phone, and the document moved to the signed ones.
        assertTrue(h.hrDao.signatures.isEmpty())
        val lists = h.hrDocuments.observeLists().first()
        assertEquals(listOf("doc-later"), lists.pending?.map { it.id })
        assertEquals("doc-soon", lists.signed?.first()?.id)
        assertEquals(HrDocumentStatus.SIGNED, lists.signed?.first()?.status)
    }

    @Test
    fun `without a connection the signature waits and is sent later with the same command id`() = runBlocking {
        loaded()
        signatureRoute { offline() }

        assertEquals(HrSignResult.Saved("sig-1"), h.hrDocuments.sign("doc-soon", "pl", shaPl, signature))

        assertEquals(LocalSignatureState.PENDING, h.hrDao.signatures.single().state)
        assertEquals(1, h.hrScheduler.requests)
        val local = h.hrDocuments.observeLists().first().localFor("doc-soon")
        assertEquals(LocalSignatureState.PENDING, local?.state)
        // Still a document to sign on the server: it is shown as waiting, not as signed.
        assertEquals(listOf("doc-soon", "doc-later"), h.hrDocuments.observeLists().first().pending?.map { it.id })

        // Back online 90 seconds later on the same boot: the server can place the signature in time.
        h.server.drainRequests()
        h.uptime.elapsedMillis += 90_000
        signatureRoute { jsonResponse(HrDocumentJson.signed(signedDocument("doc-soon")), code = 201) }

        assertTrue(h.hrDocuments.sendPending())

        val resent = signatureBodies().single()
        assertEquals("sig-1", resent.getValue("commandId").jsonPrimitive.content)
        assertEquals("2026-09-12T08:00:00Z", resent.getValue("signedAtUtc").jsonPrimitive.content)
        assertEquals(90_000L, resent.getValue("elapsedSinceSignMs").jsonPrimitive.long)
        assertTrue(h.hrDao.signatures.isEmpty())
    }

    @Test
    fun `leaving the screen while the signature is being sent hands it to the background send`() = runBlocking {
        loaded()
        // A slow server: the answer is still on its way when the screen is closed.
        signatureRoute { jsonResponse(HrDocumentJson.signed(signedDocument("doc-soon")), code = 201).setHeadersDelay(10, TimeUnit.SECONDS) }

        val signing = launch(Dispatchers.IO) { h.hrDocuments.sign("doc-soon", "pl", shaPl, signature) }
        assertNotNull(h.server.takeRequest(5, TimeUnit.SECONDS))
        signing.cancelAndJoin()

        val waiting = h.hrDao.signatures.single()
        assertEquals(LocalSignatureState.PENDING, waiting.state)
        assertEquals(1, h.hrScheduler.requests)
    }

    @Test
    fun `reading the list hands a signature still waiting on the phone to the background send`() = runBlocking {
        loaded()
        signatureRoute { offline() }
        h.hrDocuments.sign("doc-soon", "pl", shaPl, signature)
        assertEquals(1, h.hrScheduler.requests)

        h.hrDocuments.refresh()

        assertEquals(2, h.hrScheduler.requests)
        // Nothing waiting: reading the list schedules nothing.
        h.hrDao.deleteSignature("sig-1")
        h.hrDocuments.refresh()
        assertEquals(2, h.hrScheduler.requests)
    }

    @Test
    fun `a second tap while a signature waits sends that one again instead of a new one`() = runBlocking {
        loaded()
        signatureRoute { offline() }

        val first = h.hrDocuments.sign("doc-soon", "pl", shaPl, signature)
        val second = h.hrDocuments.sign("doc-soon", "tr", HrDocumentJson.sha256(HrDocumentJson.PDF_TR), signature)

        assertEquals(HrSignResult.Saved("sig-1"), first)
        assertEquals(HrSignResult.Saved("sig-1"), second)
        assertEquals(1, h.hrDao.signatures.size)
        assertEquals(listOf("sig-1", "sig-1"), signatureBodies().map { it.getValue("commandId").jsonPrimitive.content })
    }

    @Test
    fun `after an app and phone restart the signature is sent without elapsed time`() = runBlocking {
        loaded()
        signatureRoute { offline() }
        h.hrDocuments.sign("doc-soon", "pl", shaPl, signature)
        h.server.drainRequests()

        // The phone restarted: the boot-relative clock cannot be compared any more.
        h.uptime.boot = 8
        h.uptime.elapsedMillis = 20_000
        signatureRoute { jsonResponse(HrDocumentJson.signed(signedDocument("doc-soon")), code = 200) }

        assertTrue(restartedRepository().sendPending())

        val body = signatureBodies().single()
        assertEquals("sig-1", body.getValue("commandId").jsonPrimitive.content)
        assertTrue(body["elapsedSinceSignMs"].let { it == null || it == JsonNull })
        assertTrue(h.hrDao.signatures.isEmpty())
    }

    @Test
    fun `a document signed from another phone counts as signed`() = runBlocking {
        loaded()
        signatureRoute { problemResponse(409, "hr_document_already_signed") }
        h.routes.on("GET", "$MOBILE_API/hr-documents/doc-soon") { jsonResponse(signedDocument("doc-soon", language = "tr")) }

        assertEquals(HrSignResult.AlreadySigned, h.hrDocuments.sign("doc-soon", "pl", shaPl, signature))

        assertTrue(h.hrDao.signatures.isEmpty())
        val lookup = h.hrDocuments.observeDocument("doc-soon").first() as HrDocumentLookup.Found
        assertEquals(HrDocumentStatus.SIGNED, lookup.document.status)
        assertEquals("tr", lookup.document.signedLanguage)
        assertEquals(listOf("doc-later"), h.hrDocuments.observeLists().first().pending?.map { it.id })
    }

    @Test
    fun `changed content is signed again with a new command id`() = runBlocking {
        loaded()
        signatureRoute { problemResponse(409, "hr_document_changed") }
        h.routes.on("GET", "$MOBILE_API/hr-documents/doc-soon") { jsonResponse(soon) }

        assertEquals(HrSignResult.Failed(SignatureFailure.DOCUMENT_CHANGED), h.hrDocuments.sign("doc-soon", "pl", shaPl, signature))

        val failed = h.hrDao.signatures.single()
        assertEquals(LocalSignatureState.FAILED, failed.state)
        assertEquals(SignatureFailure.DOCUMENT_CHANGED, failed.failure)
        assertTrue(h.server.drainRequests().any { it.route == "GET /api/mobile/v1/hr-documents/doc-soon" })
        // A rejected signature is never sent again on its own.
        assertTrue(h.hrDocuments.sendPending())
        assertTrue(h.server.drainRequests().isEmpty())

        signatureRoute { jsonResponse(HrDocumentJson.signed(signedDocument("doc-soon")), code = 201) }
        assertTrue(h.hrDocuments.sign("doc-soon", "pl", shaPl, signature) is HrSignResult.Signed)
        assertEquals("sig-2", signatureBodies().single().getValue("commandId").jsonPrimitive.content)
        assertTrue(h.hrDao.signatures.isEmpty())
    }

    @Test
    fun `a withdrawn document needs no signature any more`() = runBlocking {
        loaded()
        signatureRoute { problemResponse(409, "hr_document_withdrawn") }

        assertEquals(HrSignResult.Withdrawn, h.hrDocuments.sign("doc-soon", "pl", shaPl, signature))

        assertTrue(h.hrDao.signatures.isEmpty())
        assertEquals(listOf("doc-later"), h.hrDocuments.observeLists().first().pending?.map { it.id })
    }

    @Test
    fun `missing access keeps the signature waiting with the reason and Retry-After is honoured`() = runBlocking {
        loaded()
        signatureRoute { problemResponse(403, "hr_documents_access_required") }

        assertEquals(HrSignResult.Saved("sig-1", SignatureFailure.ACCESS_REQUIRED), h.hrDocuments.sign("doc-soon", "pl", shaPl, signature))
        val waiting = h.hrDao.signatures.single()
        assertEquals(LocalSignatureState.PENDING, waiting.state)
        assertEquals(SignatureFailure.ACCESS_REQUIRED, waiting.failure)

        signatureRoute { problemResponse(429, "rate_limited", retryAfterSeconds = 120) }
        assertFalse(restartedRepository().sendPending())
        // The worker's own backoff applies to a background send; a new signature asks for the server's delay.
        h.clock.now = h.clock.now.plusSeconds(300)
        h.hrDao.deleteSignature("sig-1")
        h.hrDocuments.sign("doc-soon", "pl", shaPl, signature)
        assertEquals(120L, h.hrScheduler.delaysSeconds.last())
    }

    @Test
    fun `a server without the signing endpoints keeps the signature and says not connected`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/hr-documents") { MockResponse().setResponseCode(404) }
        h.routes.on("GET", "$MOBILE_API/hr-documents/doc-soon") { MockResponse().setResponseCode(404) }

        assertEquals(RefreshResult.NOT_SUPPORTED, h.hrDocuments.refresh())
        assertEquals(RefreshResult.NOT_SUPPORTED, h.hrDocuments.refreshDocument("doc-soon"))

        loaded()
        signatureRoute { MockResponse().setResponseCode(404) }
        assertEquals(HrSignResult.Saved("sig-1"), h.hrDocuments.sign("doc-soon", "pl", shaPl, signature))
        assertEquals(LocalSignatureState.PENDING, h.hrDao.signatures.single().state)
    }

    @Test
    fun `an expired access token is refreshed and the same signature sent once more`() = runBlocking {
        loaded()
        val attempts = AtomicInteger()
        h.routes.onRequest("POST", "$MOBILE_API/hr-documents/doc-soon/signature") { request ->
            attempts.incrementAndGet()
            if (request.getHeader("Authorization") == "Bearer access-2") {
                jsonResponse(HrDocumentJson.signed(signedDocument("doc-soon")), code = 201)
            } else {
                problemResponse(401, "unauthorized")
            }
        }
        h.routes.on("POST", "$MOBILE_API/auth/refresh") { jsonResponse(DriverApiJson.tokens(access = "access-2", refresh = "refresh-2")) }

        assertTrue(h.hrDocuments.sign("doc-soon", "pl", shaPl, signature) is HrSignResult.Signed)

        assertEquals(2, attempts.get())
        assertEquals(listOf("sig-1", "sig-1"), signatureBodies().map { it.getValue("commandId").jsonPrimitive.content })
        assertEquals("access-2", h.tokenStore.tokens?.accessToken)
    }

    @Test
    fun `an incomplete signature or a missing session stores nothing`() = runBlocking {
        loaded()
        val dot = DrawnSignature(listOf(listOf(VALID_STROKE.first())), HrDocumentRules.PAD_ASPECT_RATIO)

        assertEquals(HrSignResult.Incomplete, h.hrDocuments.sign("doc-soon", "pl", shaPl, dot))
        h.sessionPreferences.clearLoggedInUser(expired = false)
        assertEquals(HrSignResult.NotLoggedIn, h.hrDocuments.sign("doc-soon", "pl", shaPl, signature))

        assertTrue(h.hrDao.signatures.isEmpty())
        assertEquals(0, h.server.drainRequests().size)
    }

    @Test
    fun `a notification for a document not in the list reads it on its own`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/hr-documents/doc-new") { jsonResponse(HrDocumentJson.document("doc-new")) }
        h.routes.on("GET", "$MOBILE_API/hr-documents/doc-gone") { problemResponse(404, "hr_document_not_found") }

        assertEquals(RefreshResult.SUCCESS, h.hrDocuments.refreshDocument("doc-new"))
        assertEquals(RefreshResult.SUCCESS, h.hrDocuments.refreshDocument("doc-gone"))

        assertTrue(h.hrDocuments.observeDocument("doc-new").first() is HrDocumentLookup.Found)
        assertEquals(HrDocumentLookup.Missing, h.hrDocuments.observeDocument("doc-gone").first())
    }

    @Test
    fun `the signed PDF opens through the protected download`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/hr-documents/doc-old/receipt") { pdf(HrDocumentJson.PDF_PL) }

        val receipt = h.hrDocuments.openReceipt("doc-old") as DocumentContentResult.Available

        assertEquals("application/pdf", receipt.contentType)
        assertArrayEquals(HrDocumentJson.PDF_PL, File(receipt.filePath).readBytes())
    }

    @Test
    fun `signing out keeps waiting signatures, another account removes them`() = runBlocking {
        loaded()
        h.routes.on("GET", "$MOBILE_API/hr-documents/doc-soon/content") { pdf(HrDocumentJson.PDF_PL) }
        h.hrDocuments.content("doc-soon", "pl")
        signatureRoute { offline() }
        h.hrDocuments.sign("doc-soon", "pl", shaPl, signature)

        h.hrDocuments.clear()

        // The server data, the stored list and the PDFs are gone; the signature is not lost with the session.
        assertTrue(h.hrDao.cache.isEmpty())
        assertTrue(File(h.directories.filesDir, "hr_documents").listFiles().isNullOrEmpty())
        assertNull(h.hrDocuments.observeLists().first().pending)
        assertEquals(1, h.hrDao.signatures.size)

        h.hrDocuments.keepOnly("user-9")
        assertEquals(1, h.hrDao.signatures.size)
        h.hrDocuments.keepOnly("user-2")
        assertTrue(h.hrDao.signatures.isEmpty())
    }
}
