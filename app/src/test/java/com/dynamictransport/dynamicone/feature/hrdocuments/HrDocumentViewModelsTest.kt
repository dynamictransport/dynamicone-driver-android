package com.dynamictransport.dynamicone.feature.hrdocuments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.domain.model.HrContentProblem
import com.dynamictransport.dynamicone.domain.model.HrContentResult
import com.dynamictransport.dynamicone.domain.model.HrDocumentLookup
import com.dynamictransport.dynamicone.domain.model.HrDocumentStatus
import com.dynamictransport.dynamicone.domain.model.HrSignResult
import com.dynamictransport.dynamicone.domain.model.LocalSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SignatureFailure
import com.dynamictransport.dynamicone.domain.rules.HrDocumentRules
import com.dynamictransport.dynamicone.testing.FakeHrDocumentRepository
import com.dynamictransport.dynamicone.testing.FakePdfOpener
import com.dynamictransport.dynamicone.testing.FakePdfPages
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.VALID_STROKE
import com.dynamictransport.dynamicone.testing.hrDocument
import com.dynamictransport.dynamicone.testing.hrLanguage
import com.dynamictransport.dynamicone.testing.keepCollecting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class HrDocumentViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeHrDocumentRepository()
    private val opener = FakePdfOpener()
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    }

    @After
    fun tearDown() = Locale.setDefault(defaultLocale)

    private fun detail(documentId: String = "doc-1") =
        HrDocumentDetailViewModel(SavedStateHandle(mapOf(HR_DOCUMENT_ID_ARG to documentId)), repository, opener)

    private fun HrDocumentDetailViewModel.readyToSign() {
        onDeclarationChanged(true)
        onStroke(VALID_STROKE)
    }

    @Test
    fun `the app language version is shown first and another language asks for the declaration again`() {
        repository.pending(hrDocument())
        val viewModel = detail()

        val shown = viewModel.uiState.value
        assertEquals("tr", shown.selectedLanguage?.code)
        assertNotNull(shown.pages)
        assertEquals(listOf("doc-1" to "tr"), repository.contentRequests)
        assertEquals(listOf("doc-1"), repository.refreshedDocuments)

        viewModel.onDeclarationChanged(true)
        viewModel.onSelectLanguage("pl")

        val switched = viewModel.uiState.value
        assertEquals("pl", switched.selectedLanguage?.code)
        assertFalse(switched.declarationAccepted)
        assertEquals(listOf("doc-1" to "tr", "doc-1" to "pl"), repository.contentRequests)
        // The pages of the earlier language are released.
        assertTrue((opener.sources.first() as FakePdfPages).closed)
    }

    @Test
    fun `signing needs the declaration and a signature and a double tap signs once`() {
        repository.pending(hrDocument())
        val gate = CompletableDeferred<Unit>()
        repository.signGate = gate
        val viewModel = detail()

        viewModel.onSign()
        assertEquals(R.string.hr_sign_needs, viewModel.uiState.value.message)
        assertTrue(repository.signed.isEmpty())

        viewModel.onDeclarationChanged(true)
        assertFalse(viewModel.uiState.value.readyToSign)
        viewModel.onStroke(VALID_STROKE)
        assertTrue(viewModel.uiState.value.readyToSign)

        viewModel.onSign()
        viewModel.onSign()
        assertTrue(viewModel.uiState.value.submitting)
        gate.complete(Unit)

        val signed = repository.signed.single()
        assertEquals("tr", signed.languageCode)
        assertEquals("B".repeat(64), signed.contentSha256)
        assertEquals(listOf(VALID_STROKE), signed.signature.strokes)
        assertEquals(HrDocumentRules.PAD_ASPECT_RATIO, signed.signature.aspectRatio)
        val after = viewModel.uiState.value
        assertEquals(SignOutcomeUi(R.string.hr_saved_offline, StatusTone.Info), after.outcome)
        assertTrue(after.strokes.isEmpty())
        assertFalse(after.submitting)
    }

    @Test
    fun `each answer of the server has its own message`() {
        repository.pending(hrDocument())
        val viewModel = detail()
        fun signWith(result: HrSignResult): SignOutcomeUi? {
            repository.signResult = { result }
            viewModel.readyToSign()
            viewModel.onSign()
            return viewModel.uiState.value.outcome
        }

        assertEquals(SignOutcomeUi(R.string.hr_signed_success, StatusTone.Positive), signWith(HrSignResult.Signed(hrDocument(status = HrDocumentStatus.SIGNED))))
        assertEquals(
            SignOutcomeUi(R.string.hr_state_waiting_access, StatusTone.Info),
            signWith(HrSignResult.Saved("sig-1", SignatureFailure.ACCESS_REQUIRED)),
        )
        assertEquals(SignOutcomeUi(R.string.hr_failure_signature, StatusTone.Danger), signWith(HrSignResult.Failed(SignatureFailure.SIGNATURE_INVALID)))
        assertTrue(viewModel.uiState.value.declarationAccepted)
        assertEquals(SignOutcomeUi(R.string.hr_failure_changed, StatusTone.Danger), signWith(HrSignResult.Failed(SignatureFailure.DOCUMENT_CHANGED)))
        // Changed content has to be read again before the declaration counts.
        assertFalse(viewModel.uiState.value.declarationAccepted)
        assertEquals(SignOutcomeUi(R.string.hr_already_signed, StatusTone.Positive), signWith(HrSignResult.AlreadySigned))
        assertEquals(SignOutcomeUi(R.string.hr_withdrawn, StatusTone.Neutral), signWith(HrSignResult.Withdrawn))
    }

    @Test
    fun `a signature waiting on the phone cannot be signed again but a rejected one can`() {
        val document = hrDocument()
        repository.pending(document)
        val viewModel = detail()
        fun local(state: LocalSignatureState) = LocalSignature("sig-1", document.id, "tr", TEST_NOW, state, null)

        repository.lists.update { it.copy(localSignatures = listOf(local(LocalSignatureState.PENDING))) }
        viewModel.readyToSign()
        assertFalse(viewModel.uiState.value.canBeSigned)
        assertFalse(viewModel.uiState.value.readyToSign)

        repository.lists.update { it.copy(localSignatures = listOf(local(LocalSignatureState.FAILED))) }
        assertTrue(viewModel.uiState.value.readyToSign)
    }

    @Test
    fun `content the phone cannot get or show says so and can be tried again`() {
        repository.pending(hrDocument())
        repository.contentResult = { _, _ -> HrContentResult.Unavailable(HrContentProblem.OFFLINE) }
        val viewModel = detail()
        assertEquals(R.string.hr_content_offline, viewModel.uiState.value.contentError)
        assertNull(viewModel.uiState.value.pages)

        repository.contentResult = { _, language -> HrContentResult.Available("/files/$language.pdf", language, "B".repeat(64)) }
        opener.open = { null }
        viewModel.onRetryContent()
        assertEquals(R.string.hr_content_open_failed, viewModel.uiState.value.contentError)

        opener.open = { FakePdfPages() }
        viewModel.onRetryContent()
        assertNull(viewModel.uiState.value.contentError)
        assertNotNull(viewModel.uiState.value.pages)
        assertEquals(3, repository.contentRequests.size)
    }

    @Test
    fun `new content from the office is shown and the declaration asked again`() {
        repository.pending(hrDocument())
        val viewModel = detail()
        viewModel.onDeclarationChanged(true)

        val replaced = hrDocument(languages = listOf(hrLanguage("pl", "RODO v2", "E"), hrLanguage("tr", "RODO v2", "F")))
        repository.pending(replaced)

        assertEquals("F".repeat(64), viewModel.uiState.value.selectedLanguage?.contentSha256)
        assertFalse(viewModel.uiState.value.declarationAccepted)
        assertEquals(listOf("doc-1" to "tr", "doc-1" to "tr"), repository.contentRequests)
        // An unchanged list does not load the same pages again.
        repository.pending(replaced)
        assertEquals(2, repository.contentRequests.size)
    }

    @Test
    fun `a signed document does not load its pages again`() {
        repository.single.value = mapOf("doc-1" to hrDocument(status = HrDocumentStatus.SIGNED, signedAt = TEST_NOW, signedLanguage = "tr", receiptAvailable = true))
        val viewModel = detail()

        // The signed proof is opened by the in-app viewer (DocumentViewerViewModel), not downloaded here.
        assertTrue(repository.contentRequests.isEmpty())
        assertNull(viewModel.uiState.value.pages)
        assertTrue(viewModel.uiState.value.document?.receiptAvailable == true)
    }

    @Test
    fun `a document that is not on the phone shows the read error, a removed one says so`() {
        repository.documentRefreshResult = RefreshResult.OFFLINE
        val offline = detail("doc-9")
        assertEquals(HrDocumentLookup.Loading, offline.uiState.value.lookup)
        assertEquals(R.string.refresh_offline, offline.uiState.value.loadError)

        repository.missing.value = setOf("doc-9")
        assertEquals(HrDocumentLookup.Missing, offline.uiState.value.lookup)
    }

    @Test
    fun `leaving the screen releases the pages`() {
        repository.pending(hrDocument())
        val store = ViewModelStore()
        val factory = viewModelFactory { initializer { detail() } }
        val viewModel = ViewModelProvider(store, factory)[HrDocumentDetailViewModel::class.java]
        assertNotNull(viewModel.uiState.value.pages)

        store.clear()

        assertTrue((opener.sources.single() as FakePdfPages).closed)
    }

    @Test
    fun `the list shows what waits for a signature, what was signed, and a stale list after a failed read`() = runTest {
        val document = hrDocument()
        repository.pending(document)
        repository.lists.update {
            it.copy(
                signed = listOf(hrDocument(id = "doc-0", status = HrDocumentStatus.SIGNED)),
                localSignatures = listOf(LocalSignature("sig-1", document.id, "tr", TEST_NOW, LocalSignatureState.PENDING, null)),
            )
        }
        repository.refreshResult = RefreshResult.OFFLINE
        val viewModel = HrDocumentsViewModel(repository)
        keepCollecting(viewModel.uiState)

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertEquals(listOf("doc-1"), state.pending?.map { it.document.id })
        assertEquals(LocalSignatureState.PENDING, state.pending?.single()?.local?.state)
        assertEquals(listOf("doc-0"), state.signed?.map { it.id })
        assertTrue(state.stale)
        assertNull(state.loadError)
    }

    @Test
    fun `the list tells when the server has no signing yet or the account lost access`() = runTest {
        repository.refreshResult = RefreshResult.NOT_SUPPORTED
        val viewModel = HrDocumentsViewModel(repository)
        keepCollecting(viewModel.uiState)

        viewModel.refresh()
        assertTrue(viewModel.uiState.value.unavailable)
        assertFalse(viewModel.uiState.value.stale)

        repository.refreshResult = RefreshResult.ACCESS_REQUIRED
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.accessRemoved)

        repository.refreshResult = RefreshResult.OFFLINE
        viewModel.refresh()
        assertEquals(R.string.refresh_offline, viewModel.uiState.value.loadError)
    }

    @Test
    fun `the reminder counts documents that still need a signature and reads an old list again`() = runTest {
        val clock = MutableClock()
        val first = hrDocument(id = "doc-1")
        val second = hrDocument(id = "doc-2", dueAt = TEST_NOW.plusSeconds(3_600))
        repository.pending(first, second)
        repository.lists.update {
            it.copy(localSignatures = listOf(LocalSignature("sig-1", "doc-1", "tr", TEST_NOW, LocalSignatureState.PENDING, null)))
        }
        val viewModel = PendingSignaturesViewModel(repository, clock)
        keepCollecting(viewModel.pending)

        assertEquals(PendingSignatures(count = 1, soonestDue = TEST_NOW.plusSeconds(3_600), singleDocumentId = "doc-2"), viewModel.pending.value)

        viewModel.onResume()
        assertEquals(0, repository.refreshes)
        clock.now = TEST_NOW.plusSeconds(11 * 60)
        viewModel.onResume()
        assertEquals(1, repository.refreshes)

        repository.lists.update {
            it.copy(localSignatures = it.localSignatures + LocalSignature("sig-2", "doc-2", "tr", TEST_NOW, LocalSignatureState.PENDING, null))
        }
        assertNull(viewModel.pending.value)
    }
}
