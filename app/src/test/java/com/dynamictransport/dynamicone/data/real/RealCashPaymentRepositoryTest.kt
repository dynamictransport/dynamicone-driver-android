package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.domain.model.CashPaymentLookup
import com.dynamictransport.dynamicone.domain.model.CashPaymentScope
import com.dynamictransport.dynamicone.domain.model.CashPaymentState
import com.dynamictransport.dynamicone.domain.model.CashSignFailure
import com.dynamictransport.dynamicone.domain.model.CashSignResult
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DrawnSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SignaturePoint
import com.dynamictransport.dynamicone.domain.rules.HrDocumentRules
import com.dynamictransport.dynamicone.testing.CashPaymentJson
import com.dynamictransport.dynamicone.testing.DriverApiJson
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.RoutingDispatcher.Companion.MOBILE_API
import com.dynamictransport.dynamicone.testing.SequenceIdGenerator
import com.dynamictransport.dynamicone.testing.TEST_JSON
import com.dynamictransport.dynamicone.testing.VALID_STROKE
import com.dynamictransport.dynamicone.testing.drainRequests
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import com.dynamictransport.dynamicone.testing.route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Cash hand-over against the docs/28 contract with the production HTTP stack and a MockWebServer (ASSUMPTIONS P4–P9). */
class RealCashPaymentRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    private val officer = CashPaymentScope.DISBURSEMENTS
    private val signature = DrawnSignature(listOf(VALID_STROKE), HrDocumentRules.PAD_ASPECT_RATIO)
    private val approved = CashPaymentJson.payment("pay-1")
    private val salary = CashPaymentJson.payment("pay-2", kind = "salary", amount = "4500.00", currency = "PLN")
    private val receiptBytes = "%PDF-1.7 Nakit teslim makbuzu".toByteArray()

    @Before
    fun setUp() {
        h = RealApiHarness(folder, mobileAccount = true)
        h.signInOffice()
    }

    @After
    fun tearDown() = h.close()

    private fun listRoute(state: String = "Approved", vararg payments: String, hasMore: Boolean = false, page: Int = 1) =
        h.routes.on("GET", "$MOBILE_API/cash-payments", mapOf("state" to state, "page" to page.toString())) {
            jsonResponse(CashPaymentJson.page(payments.toList(), page = page, hasMore = hasMore))
        }

    private fun detailRoute(payment: String = approved, id: String = "pay-1") =
        h.routes.on("GET", "$MOBILE_API/cash-payments/$id") { jsonResponse(payment) }

    private fun signatureRoute(response: () -> MockResponse) = h.routes.on("POST", "$MOBILE_API/cash-payments/pay-1/signature", response = response)

    private fun offline() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)

    private fun pdf() = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/pdf").setBody(Buffer().write(receiptBytes))

    /** Reads the queue and the detail so the repository knows the payment, then forgets the requests. */
    private fun loaded() = runBlocking {
        listRoute("Approved", approved, salary)
        detailRoute()
        assertEquals(RefreshResult.SUCCESS, h.cashPayments.refresh(officer, CashPaymentState.APPROVED))
        assertEquals(RefreshResult.SUCCESS, h.cashPayments.refreshPayment(officer, "pay-1"))
        h.server.drainRequests()
    }

    /** The same stores after the app process was restarted. */
    private fun restartedRepository() = RealCashPaymentRepository(
        h.mobileApi, h.tracker, h.cashDao, h.downloader, h.sessionPreferences, h.cashScheduler, SequenceIdGenerator("restart"), h.clock, TEST_JSON,
    )

    private fun signatureBodies() = h.server.drainRequests()
        .filter { it.route.endsWith("/signature") }
        .map { TEST_JSON.parseToJsonElement(it.body.readUtf8()).jsonObject }

    private suspend fun found(id: String = "pay-1", scope: CashPaymentScope = officer) =
        h.cashPayments.observePayment(scope, id).first { it !is CashPaymentLookup.Loading } as CashPaymentLookup.Found

    @Test
    fun `the queue reads the approved payments first, fifty per page, and more pages on demand`() = runBlocking {
        listRoute("Approved", approved, salary, hasMore = true)
        listRoute("Approved", CashPaymentJson.payment("pay-3"), page = 2)

        assertEquals(RefreshResult.SUCCESS, h.cashPayments.refresh(officer, CashPaymentState.APPROVED))
        val first = h.server.drainRequests().single().requestUrl!!
        assertEquals("Approved", first.queryParameter("state"))
        assertEquals("50", first.queryParameter("pageSize"))
        assertEquals("/api/mobile/v1/cash-payments", first.encodedPath)

        val list = h.cashPayments.observeList(officer, CashPaymentState.APPROVED).first()!!
        assertEquals(listOf("pay-1", "pay-2"), list.items.map { it.id })
        assertTrue(list.hasMore)
        assertEquals(BigDecimal("4500.00"), list.items[1].amount)
        assertEquals("PLN", list.items[1].currency)

        assertEquals(RefreshResult.SUCCESS, h.cashPayments.loadMore(officer, CashPaymentState.APPROVED))
        val more = h.cashPayments.observeList(officer, CashPaymentState.APPROVED).first()!!
        assertEquals(listOf("pay-1", "pay-2", "pay-3"), more.items.map { it.id })
        assertFalse(more.hasMore)
        // Nothing is kept for offline use: a second repository over the same phone knows no list.
        assertNull(restartedRepository().observeList(officer, CashPaymentState.APPROVED).first())
    }

    @Test
    fun `the own account reads its payments from the me endpoints`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/cash-payments", mapOf("state" to "Received")) { jsonResponse(CashPaymentJson.page(listOf(CashPaymentJson.received("pay-9")))) }
        h.routes.on("GET", "$MOBILE_API/me/cash-payments/pay-9") { jsonResponse(CashPaymentJson.received("pay-9")) }
        h.routes.on("GET", "$MOBILE_API/me/cash-payments/pay-9/receipt") { pdf() }

        assertEquals(RefreshResult.SUCCESS, h.cashPayments.refresh(CashPaymentScope.OWN, CashPaymentState.RECEIVED))
        assertEquals(RefreshResult.SUCCESS, h.cashPayments.refreshPayment(CashPaymentScope.OWN, "pay-9"))
        val receipt = h.cashPayments.openReceipt(CashPaymentScope.OWN, "pay-9") as DocumentContentResult.Available

        assertEquals(
            listOf("GET /api/mobile/v1/me/cash-payments", "GET /api/mobile/v1/me/cash-payments/pay-9", "GET /api/mobile/v1/me/cash-payments/pay-9/receipt"),
            h.server.drainRequests().map { it.route },
        )
        assertArrayEquals(receiptBytes, File(receipt.filePath).readBytes())
        assertEquals(CashPaymentState.RECEIVED, found("pay-9", CashPaymentScope.OWN).payment.state)
        // The officer's scope knows nothing of it.
        assertTrue(h.cashPayments.observePayment(officer, "pay-9").first() is CashPaymentLookup.Loading)
    }

    @Test
    fun `a confirmed hand-over sends the contract body once, moves the payment and opens the receipt with the token`() = runBlocking {
        loaded()
        listRoute("Received")
        assertEquals(RefreshResult.SUCCESS, h.cashPayments.refresh(officer, CashPaymentState.RECEIVED))
        signatureRoute { jsonResponse(CashPaymentJson.received("pay-1"), code = 201) }
        h.routes.on("GET", "$MOBILE_API/cash-payments/pay-1/receipt") { pdf() }
        h.server.drainRequests()

        val result = h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature)

        assertTrue(result is CashSignResult.Signed)
        val body = signatureBodies().single()
        assertEquals("cash-1", body.getValue("commandId").jsonPrimitive.content)
        assertEquals(CashPaymentJson.FORM_HASH, body.getValue("formHash").jsonPrimitive.content)
        assertTrue(body.getValue("confirmed").jsonPrimitive.boolean)
        assertEquals(HrDocumentRules.PAD_ASPECT_RATIO, body.getValue("aspectRatio").jsonPrimitive.float, 0.0001f)
        val strokes = body.getValue("strokes").jsonArray
        assertEquals(1, strokes.size)
        assertEquals(VALID_STROKE.size, strokes[0].jsonArray.size)
        assertEquals(0.1f, strokes[0].jsonArray[0].jsonObject.getValue("x").jsonPrimitive.float, 0.0001f)
        // Confirmed: the drawn strokes are gone from the phone and the payment moved to the received list.
        assertTrue(h.cashDao.signatures.isEmpty())
        assertEquals(listOf("pay-2"), h.cashPayments.observeList(officer, CashPaymentState.APPROVED).first()!!.items.map { it.id })
        assertEquals(listOf("pay-1"), h.cashPayments.observeList(officer, CashPaymentState.RECEIVED).first()!!.items.map { it.id })
        val seen = found()
        assertEquals(CashPaymentState.RECEIVED, seen.payment.state)
        assertEquals(Instant.parse("2026-10-28T10:05:00Z"), seen.payment.receivedAt)
        assertNull(seen.local)

        val receipt = h.cashPayments.openReceipt(officer, "pay-1") as DocumentContentResult.Available
        val request = h.server.drainRequests().single()
        assertEquals("GET /api/mobile/v1/cash-payments/pay-1/receipt", request.route)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
        assertNull(request.requestUrl!!.query)
        assertArrayEquals(receiptBytes, File(receipt.filePath).readBytes())
        assertTrue(receipt.filePath.contains(ProtectedFileDownloader.PRIVATE_VIEW_DIRECTORY))
    }

    @Test
    fun `without a connection the signature waits on the phone and a second tap sends the same command again`() = runBlocking {
        loaded()
        signatureRoute { offline() }

        val saved = h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature)
        assertEquals(CashSignResult.Saved("cash-1"), saved)
        assertEquals(LocalSignatureState.PENDING, h.cashDao.signatures.single().state)
        assertEquals(1, h.cashScheduler.requests)
        val waiting = found()
        assertEquals(CashPaymentState.APPROVED, waiting.payment.state)
        assertEquals(LocalSignatureState.PENDING, waiting.local?.state)

        // The officer taps again with a different drawing: the stored signature is what goes out, unchanged.
        val other = DrawnSignature(listOf(VALID_STROKE.map { SignaturePoint(it.x + 0.2f, it.y) }), HrDocumentRules.PAD_ASPECT_RATIO)
        assertEquals(CashSignResult.Saved("cash-1"), h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, other))
        assertEquals(1, h.cashDao.signatures.size)
        assertEquals(2, h.cashScheduler.requests)
        h.server.drainRequests()

        // The connection is back: the background send delivers the first drawing under the same command id; the
        // server answers 200 for a repeat.
        signatureRoute { jsonResponse(CashPaymentJson.received("pay-1"), code = 200) }
        assertTrue(h.cashPayments.sendPending())
        val body = signatureBodies().single()
        assertEquals("cash-1", body.getValue("commandId").jsonPrimitive.content)
        assertEquals(0.1f, body.getValue("strokes").jsonArray[0].jsonArray[0].jsonObject.getValue("x").jsonPrimitive.float, 0.0001f)
        assertTrue(h.cashDao.signatures.isEmpty())
        assertEquals(CashPaymentState.RECEIVED, found().payment.state)
    }

    @Test
    fun `after a restart the stored signature is sent with its own command id`() = runBlocking {
        loaded()
        signatureRoute { offline() }
        h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature)
        h.server.drainRequests()
        signatureRoute { jsonResponse(CashPaymentJson.received("pay-1"), code = 201) }

        val restarted = restartedRepository()
        assertTrue(restarted.sendPending())

        val body = signatureBodies().single()
        assertEquals("cash-1", body.getValue("commandId").jsonPrimitive.content)
        assertEquals(CashPaymentJson.FORM_HASH, body.getValue("formHash").jsonPrimitive.content)
        assertTrue(h.cashDao.signatures.isEmpty())
    }

    @Test
    fun `an expired access token is refreshed and the same signature sent once more`() = runBlocking {
        loaded()
        val attempts = AtomicInteger()
        h.routes.onRequest("POST", "$MOBILE_API/cash-payments/pay-1/signature") { request ->
            attempts.incrementAndGet()
            if (request.getHeader("Authorization") == "Bearer access-2") {
                jsonResponse(CashPaymentJson.received("pay-1"), code = 201)
            } else {
                problemResponse(401, "unauthorized")
            }
        }
        h.routes.on("POST", "$MOBILE_API/auth/refresh") { jsonResponse(DriverApiJson.tokens(access = "access-2", refresh = "refresh-2")) }

        assertTrue(h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature) is CashSignResult.Signed)

        assertEquals(2, attempts.get())
        assertEquals(listOf("cash-1", "cash-1"), signatureBodies().map { it.getValue("commandId").jsonPrimitive.content })
        assertEquals("access-2", h.tokenStore.tokens?.accessToken)
    }

    @Test
    fun `already received or cancelled by finance removes the local signature and shows the real state`() = runBlocking {
        loaded()
        signatureRoute { problemResponse(409, "cash_payment_already_received") }
        detailRoute(CashPaymentJson.received("pay-1"))

        assertEquals(CashSignResult.AlreadyReceived, h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature))
        assertTrue(h.cashDao.signatures.isEmpty())
        assertEquals(CashPaymentState.RECEIVED, found().payment.state)
        assertEquals(listOf("pay-2"), h.cashPayments.observeList(officer, CashPaymentState.APPROVED).first()!!.items.map { it.id })

        // A second payment that finance cancelled in the meantime.
        detailRoute(salary, id = "pay-2")
        h.cashPayments.refreshPayment(officer, "pay-2")
        h.routes.on("POST", "$MOBILE_API/cash-payments/pay-2/signature") { problemResponse(409, "cash_payment_cancelled") }
        detailRoute(CashPaymentJson.payment("pay-2", state = "Cancelled", canSign = false), id = "pay-2")
        assertEquals(CashSignResult.Cancelled, h.cashPayments.sign(officer, "pay-2", CashPaymentJson.FORM_HASH, signature))
        assertTrue(h.cashDao.signatures.isEmpty())
        assertEquals(CashPaymentState.CANCELLED, found("pay-2").payment.state)
        assertTrue(h.cashPayments.observeList(officer, CashPaymentState.APPROVED).first()!!.items.isEmpty())
    }

    @Test
    fun `a changed form is rejected, read again and signed with a new command id`() = runBlocking {
        loaded()
        signatureRoute { problemResponse(409, "cash_form_changed") }
        val newHash = "A".repeat(64)
        detailRoute(CashPaymentJson.payment("pay-1", amount = "150", formHash = newHash))

        assertEquals(CashSignResult.Failed(CashSignFailure.FORM_CHANGED), h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature))
        val rejected = h.cashDao.signatures.single()
        assertEquals(LocalSignatureState.FAILED, rejected.state)
        assertEquals(CashSignFailure.FORM_CHANGED, rejected.failure)
        val current = found()
        assertEquals(newHash, current.payment.formHash)
        assertEquals(BigDecimal("150"), current.payment.amount)
        assertEquals(LocalSignatureState.FAILED, current.local?.state)
        h.server.drainRequests()

        signatureRoute { jsonResponse(CashPaymentJson.received("pay-1"), code = 201) }
        assertTrue(h.cashPayments.sign(officer, "pay-1", newHash, signature) is CashSignResult.Signed)
        val body = signatureBodies().single()
        assertEquals("cash-2", body.getValue("commandId").jsonPrimitive.content)
        assertEquals(newHash, body.getValue("formHash").jsonPrimitive.content)
        assertTrue(h.cashDao.signatures.isEmpty())
    }

    @Test
    fun `a lost permission is final for the stored signature and is never resent in the background`() = runBlocking {
        loaded()
        signatureRoute { problemResponse(403, "cash_delivery_required") }

        assertEquals(CashSignResult.AccessRequired, h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature))

        val row = h.cashDao.signatures.single()
        assertEquals(LocalSignatureState.FAILED, row.state)
        assertEquals(CashSignFailure.ACCESS_REQUIRED, row.failure)
        assertEquals(0, h.cashScheduler.requests)
        h.server.drainRequests()
        assertTrue(h.cashPayments.sendPending())
        assertTrue(h.server.drainRequests().isEmpty())
        // Reading the queue says the same.
        h.routes.on("GET", "$MOBILE_API/cash-payments") { problemResponse(403, "cash_delivery_required") }
        assertEquals(RefreshResult.ACCESS_REQUIRED, h.cashPayments.refresh(officer, CashPaymentState.APPROVED))
    }

    @Test
    fun `a payment the server no longer knows is removed from the phone`() = runBlocking {
        loaded()
        signatureRoute { problemResponse(404, "cash_payment_not_found") }

        assertEquals(CashSignResult.Missing, h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature))

        assertTrue(h.cashDao.signatures.isEmpty())
        assertTrue(h.cashPayments.observePayment(officer, "pay-1").first() is CashPaymentLookup.Missing)
        assertEquals(listOf("pay-2"), h.cashPayments.observeList(officer, CashPaymentState.APPROVED).first()!!.items.map { it.id })

        detailRoute(CashPaymentJson.payment("pay-2"), id = "pay-2")
        h.routes.on("GET", "$MOBILE_API/cash-payments/pay-2") { problemResponse(404, "cash_payment_not_found") }
        assertEquals(RefreshResult.SUCCESS, h.cashPayments.refreshPayment(officer, "pay-2"))
        assertTrue(h.cashPayments.observePayment(officer, "pay-2").first() is CashPaymentLookup.Missing)
    }

    @Test
    fun `the server's retry-after and a validation error are followed`() = runBlocking {
        loaded()
        signatureRoute { problemResponse(429, "rate_limited", retryAfterSeconds = 30) }
        assertEquals(CashSignResult.Saved("cash-1"), h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature))
        assertEquals(listOf(30L), h.cashScheduler.delaysSeconds)
        assertEquals(LocalSignatureState.PENDING, h.cashDao.signatures.single().state)

        // Until the server's 30 seconds are over nothing is sent at all; afterwards the same command goes again.
        assertEquals(CashSignResult.Saved("cash-1"), h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature))
        h.clock.now = h.clock.now.plusSeconds(31)
        signatureRoute { problemResponse(400, "invalid_cash_signature") }
        h.server.drainRequests()
        assertEquals(CashSignResult.Failed(CashSignFailure.SIGNATURE_INVALID), h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature))
        assertEquals(listOf("cash-1"), signatureBodies().map { it.getValue("commandId").jsonPrimitive.content })
        assertEquals(LocalSignatureState.FAILED, h.cashDao.signatures.single().state)
        assertEquals(CashPaymentState.APPROVED, found().payment.state)
    }

    @Test
    fun `an incomplete drawing stores nothing and a server without the endpoints is reported`() = runBlocking {
        loaded()
        val dot = DrawnSignature(listOf(listOf(VALID_STROKE.first())), HrDocumentRules.PAD_ASPECT_RATIO)
        assertEquals(CashSignResult.Incomplete, h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, dot))
        assertTrue(h.cashDao.signatures.isEmpty())
        assertTrue(h.server.drainRequests().isEmpty())

        h.routes.on("GET", "$MOBILE_API/cash-payments") { MockResponse().setResponseCode(404) }
        assertEquals(RefreshResult.NOT_SUPPORTED, h.cashPayments.refresh(officer, CashPaymentState.APPROVED))
    }

    @Test
    fun `signing out removes waiting signatures, another account's rows never travel with this session`() = runBlocking {
        loaded()
        signatureRoute { offline() }
        h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature)
        assertEquals(1, h.cashDao.signatures.size)

        h.cashPayments.keepOnly("user-9")
        assertEquals(1, h.cashDao.signatures.size)
        h.cashPayments.keepOnly("someone-else")
        assertTrue(h.cashDao.signatures.isEmpty())

        h.server.drainRequests()
        h.cashPayments.sign(officer, "pay-1", CashPaymentJson.FORM_HASH, signature)
        assertEquals(1, h.cashDao.signatures.size)
        h.cashPayments.clear()
        // The session's memory is gone, the signature stays until an explicit sign-out.
        assertNull(h.cashPayments.observeList(officer, CashPaymentState.APPROVED).first())
        assertEquals(1, h.cashDao.signatures.size)
        h.cashPayments.clearStoredSignatures()
        assertTrue(h.cashDao.signatures.isEmpty())
    }
}
