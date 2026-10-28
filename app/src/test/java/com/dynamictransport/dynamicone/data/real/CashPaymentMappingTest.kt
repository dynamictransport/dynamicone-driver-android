package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.ApiProblem
import com.dynamictransport.dynamicone.core.network.CashPaymentDto
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.CashPaymentKind
import com.dynamictransport.dynamicone.domain.model.CashPaymentState
import com.dynamictransport.dynamicone.domain.model.CashSignFailure
import com.dynamictransport.dynamicone.testing.CashPaymentJson
import com.dynamictransport.dynamicone.testing.TEST_JSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** docs/28 wire model → domain, and the server's answers to a signature (ASSUMPTIONS P7, P10). */
class CashPaymentMappingTest {

    private fun decode(json: String) = TEST_JSON.decodeFromString(CashPaymentDto.serializer(), json)

    private fun problem(status: Int, code: String?) = ApiProblem(status, code, null, null)

    @Test
    fun `an approved payment keeps the exact amount, the form text and the hash`() {
        val payment = CashPaymentMapping.payment(decode(CashPaymentJson.payment("pay-1", amount = "1250.5", currency = "pln")))!!

        assertEquals("pay-1", payment.id)
        assertEquals("Mehmet Yıldız", payment.employeeName)
        assertEquals("1042", payment.registrationNumber)
        assertEquals(CashPaymentKind.ADVANCE, payment.kind)
        assertEquals(BigDecimal("1250.5"), payment.amount)
        assertEquals("PLN", payment.currency)
        assertEquals(LocalDate.of(2026, 10, 30), payment.plannedPaymentDate)
        assertEquals(CashPaymentState.APPROVED, payment.state)
        assertEquals(Instant.parse("2026-10-27T09:15:00Z"), payment.approvedAt)
        assertNull(payment.receivedAt)
        assertEquals("Avans ödeme formu", payment.title)
        assertEquals(listOf("Avans / Zaliczka", "Tutar: 100,00 EUR"), payment.lines)
        assertEquals("Yukarıda yazılı tutarı nakit olarak eksiksiz teslim aldım.", payment.declaration)
        assertEquals("Finans Müdürü", payment.approvedByName)
        assertEquals(CashPaymentJson.FORM_HASH, payment.formHash)
        assertTrue(payment.canSign)
        assertFalse(payment.receiptAvailable)
    }

    @Test
    fun `a received payment carries the hand-over time and the receipt`() {
        val payment = CashPaymentMapping.payment(decode(CashPaymentJson.received("pay-2")))!!

        assertEquals(CashPaymentState.RECEIVED, payment.state)
        assertEquals(Instant.parse("2026-10-28T10:05:00Z"), payment.receivedAt)
        assertTrue(payment.receiptAvailable)
        assertFalse(payment.canSign)
    }

    @Test
    fun `form lines are read as strings or as label and value objects`() {
        val lines = """["Avans","",{"label":"Tutar","value":"100,00 EUR"},{"text":"Not"},{"a":"x","b":2},["Ek","satır"],null]"""
        val payment = CashPaymentMapping.payment(decode(CashPaymentJson.payment("pay-3", lines = lines)))!!

        assertEquals(listOf("Avans", "Tutar: 100,00 EUR", "Not", "x 2", "Ek satır"), payment.lines)
    }

    @Test
    fun `an unknown kind or state is kept as text instead of being guessed`() {
        val payment = CashPaymentMapping.payment(decode(CashPaymentJson.payment("pay-4", kind = "bonus", state = "Held")))!!

        assertEquals(CashPaymentKind.UNKNOWN, payment.kind)
        assertEquals("bonus", payment.kindName)
        assertEquals(CashPaymentState.UNKNOWN, payment.state)
    }

    @Test
    fun `a payment without id, name, positive amount or currency is skipped`() {
        assertNull(CashPaymentMapping.payment(decode(CashPaymentJson.payment("", amount = "100"))))
        assertNull(CashPaymentMapping.payment(decode(CashPaymentJson.payment("pay-5", amount = "0"))))
        assertNull(CashPaymentMapping.payment(decode(CashPaymentJson.payment("pay-6", amount = "-5"))))
        assertNull(CashPaymentMapping.payment(decode(CashPaymentJson.payment("pay-7", currency = " "))))
        assertNull(CashPaymentMapping.payment(decode(CashPaymentJson.payment("pay-8", employeeName = ""))))
    }

    @Test
    fun `the server's answers to a signature follow the contract`() {
        assertEquals(
            CashSignatureOutcome.Rejected(CashSignFailure.SIGNATURE_INVALID),
            CashPaymentMapping.signatureOutcome(RemoteException.Validation(problem(400, "invalid_cash_signature"))),
        )
        assertEquals(CashSignatureOutcome.Rejected(CashSignFailure.REJECTED), CashPaymentMapping.signatureOutcome(RemoteException.Validation(null)))
        assertEquals(CashSignatureOutcome.AlreadyReceived, CashPaymentMapping.signatureOutcome(RemoteException.Conflict(problem(409, "cash_payment_already_received"))))
        assertEquals(CashSignatureOutcome.Cancelled, CashPaymentMapping.signatureOutcome(RemoteException.Conflict(problem(409, "cash_payment_cancelled"))))
        assertEquals(
            CashSignatureOutcome.Rejected(CashSignFailure.FORM_CHANGED, reread = true),
            CashPaymentMapping.signatureOutcome(RemoteException.Conflict(problem(409, "cash_form_changed"))),
        )
        assertEquals(
            CashSignatureOutcome.Rejected(CashSignFailure.EMPLOYEE_INACTIVE),
            CashPaymentMapping.signatureOutcome(RemoteException.Conflict(problem(409, "cash_employee_inactive"))),
        )
        assertEquals(
            CashSignatureOutcome.Rejected(CashSignFailure.CONFLICT, reread = true),
            CashPaymentMapping.signatureOutcome(RemoteException.Conflict(problem(409, "cash_payment_conflict"))),
        )
        assertEquals(
            CashSignatureOutcome.Rejected(CashSignFailure.CONFLICT),
            CashPaymentMapping.signatureOutcome(RemoteException.Conflict(problem(409, "command_conflict"))),
        )
        assertEquals(CashSignatureOutcome.Missing, CashPaymentMapping.signatureOutcome(RemoteException.NotFound(problem(404, "cash_payment_not_found"))))
        // A 404 without the contract's code: the endpoint is not there, the signature waits.
        assertEquals(CashSignatureOutcome.Retry(), CashPaymentMapping.signatureOutcome(RemoteException.NotFound(null)))
        assertEquals(CashSignatureOutcome.AccessRequired, CashPaymentMapping.signatureOutcome(RemoteException.Forbidden(problem(403, "cash_delivery_required"))))
        assertEquals(CashSignatureOutcome.AccessRequired, CashPaymentMapping.signatureOutcome(RemoteException.Forbidden(problem(403, "cash_access_required"))))
        assertEquals(CashSignatureOutcome.Retry(), CashPaymentMapping.signatureOutcome(RemoteException.Forbidden(problem(403, "password_change_required"))))
        assertEquals(CashSignatureOutcome.Retry(delaySeconds = 30), CashPaymentMapping.signatureOutcome(RemoteException.RateLimited(30, problem(429, null))))
        assertEquals(CashSignatureOutcome.Retry(), CashPaymentMapping.signatureOutcome(RemoteException.Unauthorized(null)))
        assertEquals(CashSignatureOutcome.Retry(), CashPaymentMapping.signatureOutcome(RemoteException.ServerError(500)))
        assertEquals(CashSignatureOutcome.Retry(), CashPaymentMapping.signatureOutcome(RemoteException.Offline()))
        assertEquals(CashSignatureOutcome.Retry(), CashPaymentMapping.signatureOutcome(RemoteException.InvalidResponse()))
    }

    @Test
    fun `financial data stays out of toString`() {
        val dto = decode(CashPaymentJson.payment("pay-9"))
        assertFalse(dto.toString().contains("Mehmet"))
        assertFalse(dto.toString().contains("100"))
    }
}
