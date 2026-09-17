package com.dynamictransport.dynamicone.feature

import androidx.lifecycle.SavedStateHandle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.push.OpenConversationTracker
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DriverMessage
import com.dynamictransport.dynamicone.domain.model.MessageDirection
import com.dynamictransport.dynamicone.domain.model.MonthlyPayroll
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SignedDocument
import com.dynamictransport.dynamicone.domain.model.SignedDocumentType
import com.dynamictransport.dynamicone.domain.rules.DriverInputRules
import com.dynamictransport.dynamicone.feature.messages.MessagesViewModel
import com.dynamictransport.dynamicone.feature.payroll.PayrollViewModel
import com.dynamictransport.dynamicone.feature.personnel.SignedDocumentsViewModel
import com.dynamictransport.dynamicone.feature.tripdetail.TRIP_ID_ARG
import com.dynamictransport.dynamicone.testing.FakeAppInfo
import com.dynamictransport.dynamicone.testing.FakeMessageNotifier
import com.dynamictransport.dynamicone.testing.FakeMessageRepository
import com.dynamictransport.dynamicone.testing.FakePayrollRepository
import com.dynamictransport.dynamicone.testing.FakeSignedDocumentRepository
import com.dynamictransport.dynamicone.testing.FakeTripRepository
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.keepCollecting
import com.dynamictransport.dynamicone.testing.testTrip
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth

class PayrollViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val payroll = FakePayrollRepository()
    private val september = YearMonth.of(2026, 9)

    private fun viewModel(demo: Boolean = false) = PayrollViewModel(payroll, MutableClock(), FakeAppInfo(isDemoMode = demo))

    @Test
    fun `the current month is read and an unpublished month is shown as such, never as zero`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        val state = viewModel.uiState.value
        assertEquals(september, state.month)
        assertEquals(MonthlyPayroll.Unpublished(september), state.payroll)
        assertNull(state.loadError)
        assertTrue(state.canGoBack)
        assertFalse(state.canGoForward)
        assertEquals(listOf(september), payroll.refreshed)
    }

    @Test
    fun `earlier months can be opened, months after the current one cannot`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onNextMonth()
        assertEquals(listOf(september), payroll.refreshed)

        viewModel.onPreviousMonth()
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 8), viewModel.uiState.value.month)
        assertTrue(viewModel.uiState.value.canGoForward)
        assertEquals(listOf(september, YearMonth.of(2026, 8)), payroll.refreshed)
    }

    @Test
    fun `a failed read without data is an error, with earlier data it is stale`() = runTest {
        payroll.refreshResult = RefreshResult.OFFLINE
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        assertEquals(R.string.refresh_offline, viewModel.uiState.value.loadError)
        assertNull(viewModel.uiState.value.payroll)

        payroll.payrolls.value = mapOf(september to MonthlyPayroll.Published(september, TEST_NOW, emptyList(), null))
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.loadError)
        assertTrue(viewModel.uiState.value.stale)
    }

    @Test
    fun `demo mode shows payroll as unavailable`() = runTest {
        payroll.refreshResult = RefreshResult.NOT_SUPPORTED
        val viewModel = viewModel(demo = true)
        keepCollecting(viewModel.uiState)

        assertTrue(viewModel.uiState.value.unavailable)
        assertTrue(viewModel.uiState.value.isDemoMode)
        assertNull(viewModel.uiState.value.loadError)
    }
}

class SignedDocumentsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeSignedDocumentRepository()
    private val document = SignedDocument("sd1", SignedDocumentType.COMPENSATION, TEST_NOW)

    @Test
    fun `signed documents are listed and opened as the stored PDF`() = runTest {
        repository.server = listOf(document)
        repository.openResult = DocumentContentResult.Available("/cache/document_view/signed-sd1.pdf", "application/pdf")
        val viewModel = SignedDocumentsViewModel(repository, FakeAppInfo())
        keepCollecting(viewModel.uiState)

        assertEquals(listOf(document), viewModel.uiState.value.documents)

        viewModel.onOpen(document)
        advanceUntilIdle()

        assertEquals(listOf("sd1"), repository.opened)
        assertEquals("application/pdf", viewModel.uiState.value.openRequest?.contentType)
        viewModel.onOpenRequestHandled()
        assertNull(viewModel.uiState.value.openRequest)
    }

    @Test
    fun `failed and unsupported reads are shown as such`() = runTest {
        repository.refreshResult = RefreshResult.FAILED
        val failed = SignedDocumentsViewModel(repository, FakeAppInfo())
        keepCollecting(failed.uiState)
        assertEquals(R.string.refresh_failed, failed.uiState.value.loadError)
        assertNull(failed.uiState.value.documents)

        repository.refreshResult = RefreshResult.NOT_SUPPORTED
        val demo = SignedDocumentsViewModel(repository, FakeAppInfo(isDemoMode = true))
        keepCollecting(demo.uiState)
        assertTrue(demo.uiState.value.unavailable)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val messages = FakeMessageRepository()
    private val trips = FakeTripRepository(listOf(testTrip(id = "t1")))
    private val openConversations = OpenConversationTracker()
    private val notifier = FakeMessageNotifier()

    private fun viewModel(tripId: String? = null, demo: Boolean = false) = MessagesViewModel(
        savedStateHandle = SavedStateHandle(if (tripId == null) emptyMap<String, Any?>() else mapOf(TRIP_ID_ARG to tripId)),
        messageRepository = messages,
        tripRepository = trips,
        appInfo = FakeAppInfo(isDemoMode = demo),
        openConversations = openConversations,
        notifier = notifier,
    )

    @Test
    fun `the open conversation is polled while it is visible and backs off after failures`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        runCurrent()
        assertEquals(listOf<String?>(null), messages.refreshedTrips)

        advanceTimeBy(MessagesViewModel.POLL_INTERVAL_MILLIS + 1)
        assertEquals(2, messages.refreshedTrips.size)

        messages.refreshResult = RefreshResult.OFFLINE
        advanceTimeBy(MessagesViewModel.POLL_INTERVAL_MILLIS + 1)
        assertEquals(3, messages.refreshedTrips.size)
        // Messages were read before: the failure keeps them on screen as stale instead of an empty error.
        assertTrue(viewModel.uiState.value.stale)
        assertNull(viewModel.uiState.value.loadError)

        // Backed off: the next read waits twice as long.
        advanceTimeBy(MessagesViewModel.POLL_INTERVAL_MILLIS + 1)
        assertEquals(3, messages.refreshedTrips.size)
        advanceTimeBy(MessagesViewModel.POLL_INTERVAL_MILLIS)
        assertEquals(4, messages.refreshedTrips.size)
    }

    @Test
    fun `the general conversation has no trip, a trip conversation shows its trip`() = runTest {
        val general = viewModel()
        keepCollecting(general.uiState)
        runCurrent()
        assertNull(general.uiState.value.tripId)

        val trip = viewModel(tripId = "t1")
        keepCollecting(trip.uiState)
        runCurrent()

        assertEquals(listOf(null, "t1"), messages.refreshedTrips.take(2))
        assertEquals("TRP-T1", trip.uiState.value.tripNo)
    }

    @Test
    fun `an open conversation is not announced and its notification is removed`() = runTest {
        val viewModel = viewModel(tripId = "t1")

        viewModel.onVisible()
        assertTrue(openConversations.isOpen("t1"))
        assertEquals(listOf<String?>("t1"), notifier.cancelled)

        viewModel.onHidden()
        assertFalse(openConversations.isOpen("t1"))
    }

    @Test
    fun `the draft is cleared only when the message was stored`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onDraftChanged("At the gate")
        viewModel.onSend()
        assertEquals(listOf<Pair<String?, String>>(null to "At the gate"), messages.sent)
        assertEquals("", viewModel.uiState.value.draft)

        messages.sendResult = ActionResult.Rejected(ActionRejection.CONFLICT)
        viewModel.onDraftChanged("Again")
        viewModel.onSend()
        assertEquals("Again", viewModel.uiState.value.draft)
        assertEquals(R.string.error_conflict, viewModel.uiState.value.message)

        messages.sendResult = ActionResult.Rejected(ActionRejection.OUTCOME_UNKNOWN)
        viewModel.onSend()
        assertEquals("", viewModel.uiState.value.draft)
        assertEquals(R.string.error_outcome_unknown, viewModel.uiState.value.message)
    }

    @Test
    fun `blank and too long drafts are never sent`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onDraftChanged("   ")
        assertFalse(viewModel.uiState.value.canSend)
        viewModel.onSend()
        assertTrue(messages.sent.isEmpty())
        assertEquals(R.string.error_note_required, viewModel.uiState.value.message)

        viewModel.onDraftChanged("x".repeat(DriverInputRules.MESSAGE_MAX_LENGTH + 500))
        assertEquals(DriverInputRules.MESSAGE_MAX_LENGTH, viewModel.uiState.value.draft.length)
    }

    @Test
    fun `read receipts only for incoming messages that are not read yet`() = runTest {
        val viewModel = viewModel(tripId = "t1")
        keepCollecting(viewModel.uiState)

        viewModel.onMessageVisible(DriverMessage("in", "t1", "Gate 4", MessageDirection.TO_DRIVER, TEST_NOW, null))
        viewModel.onMessageVisible(DriverMessage("out", "t1", "OK", MessageDirection.FROM_DRIVER, TEST_NOW, null))
        viewModel.onMessageVisible(DriverMessage("seen", "t1", "Old", MessageDirection.TO_DRIVER, TEST_NOW, TEST_NOW))

        assertEquals(listOf<Pair<String?, String>>("t1" to "in"), messages.markedRead)
    }

    @Test
    fun `stored messages are resent or deleted by their command id`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onResend("msg-1")
        viewModel.onDiscard("msg-2")

        assertEquals(listOf("msg-1"), messages.resent)
        assertEquals(listOf("msg-2"), messages.discarded)
    }

    @Test
    fun `demo mode shows messages as unavailable and stops reading`() = runTest {
        messages.refreshResult = RefreshResult.NOT_SUPPORTED
        val viewModel = viewModel(demo = true)
        keepCollecting(viewModel.uiState)
        runCurrent()

        assertTrue(viewModel.uiState.value.unavailable)
        assertFalse(viewModel.uiState.value.canSend)
        advanceTimeBy(5 * MessagesViewModel.POLL_INTERVAL_MILLIS)
        assertEquals(1, messages.refreshedTrips.size)
    }
}
