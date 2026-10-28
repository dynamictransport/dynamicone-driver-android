package com.dynamictransport.dynamicone.feature.cashpayments

import androidx.lifecycle.SavedStateHandle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.domain.model.CashPaymentLookup
import com.dynamictransport.dynamicone.domain.model.CashPaymentScope
import com.dynamictransport.dynamicone.domain.model.CashPaymentState
import com.dynamictransport.dynamicone.domain.model.CashSignFailure
import com.dynamictransport.dynamicone.domain.model.CashSignResult
import com.dynamictransport.dynamicone.domain.model.LocalCashSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.rules.HrDocumentRules
import com.dynamictransport.dynamicone.testing.FakeAuthRepository
import com.dynamictransport.dynamicone.testing.FakeCashPaymentRepository
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.VALID_STROKE
import com.dynamictransport.dynamicone.testing.cashPayment
import com.dynamictransport.dynamicone.testing.keepCollecting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CashPaymentViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCashPaymentRepository()
    private val auth = FakeAuthRepository()
    private val officer = CashPaymentScope.DISBURSEMENTS

    private fun detail(scope: CashPaymentScope = officer, paymentId: String = "pay-1") = CashPaymentDetailViewModel(
        SavedStateHandle(mapOf(CASH_SCOPE_ARG to scope.wireName, CASH_PAYMENT_ID_ARG to paymentId)),
        repository,
        auth,
    )

    private fun CashPaymentDetailViewModel.readyToSign() {
        onDeclarationChanged(true)
        onStroke(VALID_STROKE)
    }

    private fun waiting(paymentId: String = "pay-1", state: LocalSignatureState = LocalSignatureState.PENDING, failure: CashSignFailure? = null) =
        LocalCashSignature("cash-1", officer, paymentId, TEST_NOW, state, failure)

    // --- Detail ---

    @Test
    fun `the detail is read from the server when it opens and only then may it be signed`() = runTest {
        repository.list(officer, CashPaymentState.APPROVED, cashPayment())
        repository.paymentRefreshResult = RefreshResult.OFFLINE
        val viewModel = detail()

        val fromList = viewModel.uiState.value
        assertEquals("pay-1", fromList.payment?.id)
        assertEquals(listOf(officer to "pay-1"), repository.refreshedPayments)
        assertFalse(fromList.fresh)
        assertFalse(fromList.canSign)
        assertNull(fromList.loadError)

        viewModel.readyToSign()
        viewModel.onSign()
        assertTrue(repository.signed.isEmpty())
        assertEquals(R.string.cash_sign_not_ready, viewModel.uiState.value.message)

        repository.paymentRefreshResult = RefreshResult.SUCCESS
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.fresh)
        assertTrue(viewModel.uiState.value.canSign)
        assertTrue(viewModel.uiState.value.readyToSign)
    }

    @Test
    fun `an unknown payment shows the read error until the server answers`() = runTest {
        repository.paymentRefreshResult = RefreshResult.OFFLINE
        val viewModel = detail()
        assertEquals(R.string.refresh_offline, viewModel.uiState.value.loadError)
        assertTrue(viewModel.uiState.value.lookup is CashPaymentLookup.Loading)

        repository.paymentRefreshResult = RefreshResult.SUCCESS
        repository.payment(officer, cashPayment())
        viewModel.refresh()
        assertNull(viewModel.uiState.value.loadError)
        assertEquals("pay-1", viewModel.uiState.value.payment?.id)
    }

    @Test
    fun `I received the money sends the declaration and drawing once and opens the receipt on the server's answer`() = runTest {
        repository.payment(officer, cashPayment())
        val gate = CompletableDeferred<Unit>()
        repository.signGate = gate
        repository.signResult = { CashSignResult.Signed(cashPayment(state = CashPaymentState.RECEIVED, receivedAt = TEST_NOW)) }
        val viewModel = detail()
        viewModel.readyToSign()

        viewModel.onSign()
        viewModel.onSign()
        assertTrue(viewModel.uiState.value.submitting)
        gate.complete(Unit)

        val sent = repository.signed.single()
        assertEquals(officer, sent.scope)
        assertEquals("pay-1", sent.paymentId)
        assertEquals("F".repeat(64), sent.formHash)
        assertEquals(listOf(VALID_STROKE), sent.signature.strokes)
        assertEquals(HrDocumentRules.PAD_ASPECT_RATIO, sent.signature.aspectRatio)
        val state = viewModel.uiState.value
        assertFalse(state.submitting)
        assertEquals(R.string.cash_signed_success, state.outcome?.message)
        assertEquals(StatusTone.Positive, state.outcome?.tone)
        assertTrue(state.strokes.isEmpty())
        assertTrue(state.openReceipt)
        viewModel.onReceiptOpened()
        assertFalse(viewModel.uiState.value.openReceipt)
    }

    @Test
    fun `without the declaration or a drawing nothing is sent`() = runTest {
        repository.payment(officer, cashPayment())
        val viewModel = detail()

        viewModel.onStroke(VALID_STROKE)
        viewModel.onSign()
        assertEquals(R.string.cash_sign_needs, viewModel.uiState.value.message)
        viewModel.onMessageShown()
        viewModel.onClearSignature()
        viewModel.onDeclarationChanged(true)
        viewModel.onSign()
        assertEquals(R.string.cash_sign_needs, viewModel.uiState.value.message)
        assertTrue(repository.signed.isEmpty())
    }

    @Test
    fun `a signature the server did not confirm waits and is sent again unchanged`() = runTest {
        repository.payment(officer, cashPayment())
        val viewModel = detail()
        viewModel.readyToSign()

        viewModel.onSign()
        assertEquals(R.string.cash_saved_offline, viewModel.uiState.value.outcome?.message)
        assertEquals(StatusTone.Warning, viewModel.uiState.value.outcome?.tone)
        assertFalse(viewModel.uiState.value.openReceipt)

        // The repository reports the stored signature: the screen offers to send it again, not to sign again.
        repository.local.update { listOf(waiting()) }
        assertTrue(viewModel.uiState.value.waitingToSend)
        assertFalse(viewModel.uiState.value.canSign)
        repository.signResult = { CashSignResult.Signed(cashPayment(state = CashPaymentState.RECEIVED, receivedAt = TEST_NOW)) }
        viewModel.onResend()
        assertEquals(2, repository.signed.size)
        assertTrue(repository.signed.last().signature.strokes.isEmpty())
        assertTrue(viewModel.uiState.value.openReceipt)
    }

    @Test
    fun `a rejected signature is reported and a changed form asks for the declaration again`() = runTest {
        repository.payment(officer, cashPayment())
        repository.signResult = { CashSignResult.Failed(CashSignFailure.FORM_CHANGED) }
        val viewModel = detail()
        viewModel.readyToSign()

        viewModel.onSign()

        val state = viewModel.uiState.value
        assertEquals(R.string.cash_failure_form_changed, state.outcome?.message)
        assertEquals(StatusTone.Danger, state.outcome?.tone)
        assertFalse(state.declarationAccepted)
        assertTrue(state.strokes.isEmpty())
        assertFalse(state.openReceipt)

        repository.signResult = { CashSignResult.Failed(CashSignFailure.SIGNATURE_INVALID) }
        viewModel.readyToSign()
        viewModel.onSign()
        assertEquals(R.string.cash_failure_signature, viewModel.uiState.value.outcome?.message)
        assertTrue(viewModel.uiState.value.declarationAccepted)
    }

    @Test
    fun `a lost permission reads the account again and closes the screen`() = runTest {
        repository.payment(officer, cashPayment())
        repository.signResult = { CashSignResult.AccessRequired }
        val viewModel = detail()
        viewModel.readyToSign()

        viewModel.onSign()

        assertEquals(R.string.cash_access_removed, viewModel.uiState.value.outcome?.message)
        assertTrue(viewModel.uiState.value.closeRequested)
        assertEquals(1, auth.profileRefreshes)
    }

    @Test
    fun `already received, cancelled and missing show the server's state`() = runTest {
        repository.payment(officer, cashPayment())
        repository.signResult = { CashSignResult.AlreadyReceived }
        val viewModel = detail()
        viewModel.readyToSign()
        viewModel.onSign()
        assertEquals(R.string.cash_already_received, viewModel.uiState.value.outcome?.message)

        repository.signResult = { CashSignResult.Cancelled }
        viewModel.readyToSign()
        viewModel.onSign()
        assertEquals(R.string.cash_cancelled, viewModel.uiState.value.outcome?.message)
        assertEquals(StatusTone.Neutral, viewModel.uiState.value.outcome?.tone)

        repository.signResult = { CashSignResult.Missing }
        repository.single.update { emptyMap() }
        repository.missing.update { it + (officer to "pay-1") }
        viewModel.readyToSign()
        viewModel.onSign()
        assertTrue(viewModel.uiState.value.lookup is CashPaymentLookup.Missing)
        assertFalse(viewModel.uiState.value.canSign)
    }

    @Test
    fun `the own account signs through its own scope`() = runTest {
        repository.payment(CashPaymentScope.OWN, cashPayment())
        repository.signResult = { CashSignResult.Signed(cashPayment(state = CashPaymentState.RECEIVED, receivedAt = TEST_NOW)) }
        val viewModel = detail(CashPaymentScope.OWN)
        viewModel.readyToSign()

        viewModel.onSign()

        assertEquals(CashPaymentScope.OWN, repository.signed.single().scope)
        assertEquals(listOf(CashPaymentScope.OWN to "pay-1"), repository.refreshedPayments)
    }

    // --- List ---

    @Test
    fun `the list reads the state it shows and switching the filter reads that state`() = runTest {
        repository.list(officer, CashPaymentState.APPROVED, cashPayment(), cashPayment(id = "pay-2"))
        repository.list(officer, CashPaymentState.RECEIVED, cashPayment(id = "pay-0", state = CashPaymentState.RECEIVED, receivedAt = TEST_NOW), hasMore = true)
        repository.local.update { listOf(waiting("pay-2")) }
        val viewModel = CashPaymentsViewModel(officer, repository)
        keepCollecting(viewModel.uiState)

        viewModel.refresh()
        assertEquals(listOf(officer to CashPaymentState.APPROVED), repository.refreshed)
        val approved = viewModel.uiState.value
        assertEquals(CashPaymentState.APPROVED, approved.filter)
        assertEquals(listOf("pay-1", "pay-2"), approved.items?.map { it.payment.id })
        assertEquals(LocalSignatureState.PENDING, approved.items?.get(1)?.local?.state)
        assertFalse(approved.canLoadMore)

        viewModel.onFilterSelected(CashPaymentState.RECEIVED)
        assertEquals(listOf(officer to CashPaymentState.APPROVED, officer to CashPaymentState.RECEIVED), repository.refreshed)
        val received = viewModel.uiState.value
        assertEquals(listOf("pay-0"), received.items?.map { it.payment.id })
        assertTrue(received.canLoadMore)
    }

    @Test
    fun `the list tells when the server is unreachable, has no cash endpoints or the account lost access`() = runTest {
        repository.list(officer, CashPaymentState.APPROVED, cashPayment())
        repository.refreshResult = RefreshResult.OFFLINE
        val viewModel = CashPaymentsViewModel(officer, repository)
        keepCollecting(viewModel.uiState)

        viewModel.refresh()
        assertTrue(viewModel.uiState.value.stale)
        assertNull(viewModel.uiState.value.loadError)

        repository.refreshResult = RefreshResult.NOT_SUPPORTED
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.unavailable)
        assertFalse(viewModel.uiState.value.stale)

        repository.refreshResult = RefreshResult.ACCESS_REQUIRED
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.accessRemoved)

        // A failed read of another state does not mark this one.
        repository.refreshResult = RefreshResult.FAILED
        viewModel.onFilterSelected(CashPaymentState.CANCELLED)
        assertEquals(R.string.refresh_failed, viewModel.uiState.value.loadError)
        repository.refreshResult = RefreshResult.SUCCESS
        viewModel.onFilterSelected(CashPaymentState.APPROVED)
        assertNull(viewModel.uiState.value.loadError)
        assertFalse(viewModel.uiState.value.stale)
    }
}
