package com.dynamictransport.dynamicone.feature.personnelfiles

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.PersonnelDocumentHistory
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentsSnapshot
import com.dynamictransport.dynamicone.testing.FakeAuthRepository
import com.dynamictransport.dynamicone.testing.FakeCashPaymentRepository
import com.dynamictransport.dynamicone.testing.FakeHrDocumentRepository
import com.dynamictransport.dynamicone.testing.FakeImageDecoder
import com.dynamictransport.dynamicone.testing.FakeMyDocumentRepository
import com.dynamictransport.dynamicone.testing.FakePdfOpener
import com.dynamictransport.dynamicone.testing.FakePdfPages
import com.dynamictransport.dynamicone.testing.FakeSignedDocumentRepository
import com.dynamictransport.dynamicone.testing.FakeVehicleDocumentRepository
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.RecordingFileCleaner
import com.dynamictransport.dynamicone.testing.keepCollecting
import com.dynamictransport.dynamicone.testing.personnelDocument
import com.dynamictransport.dynamicone.testing.vehicleDocuments
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonnelFileViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val myDocuments = FakeMyDocumentRepository()
    private val vehicleDocuments = FakeVehicleDocumentRepository()
    private val signedDocuments = FakeSignedDocumentRepository()
    private val hrDocuments = FakeHrDocumentRepository()
    private val cashPayments = FakeCashPaymentRepository()
    private val auth = FakeAuthRepository()
    private val pdfOpener = FakePdfOpener()
    private val imageDecoder = FakeImageDecoder()
    private val cleaner = RecordingFileCleaner()

    private fun loggedIn(vararg modules: AppModule) {
        auth.sessionState.value = SessionState.LoggedIn("user-7", modules.toSet())
    }

    // --- My documents ---

    @Test
    fun `a driver with the hr module reads both parts, an office account only the history`() = runTest {
        loggedIn(AppModule.DRIVER, AppModule.HR_DOCUMENTS)
        val viewModel = MyDocumentsViewModel(myDocuments, auth)
        keepCollecting(viewModel.uiState)
        viewModel.refresh()

        assertTrue(viewModel.uiState.value.showRequirements)
        assertTrue(viewModel.uiState.value.showHistory)
        assertEquals(1, myDocuments.requirementReads)
        assertEquals(1, myDocuments.historyReads)

        loggedIn(AppModule.OFFICE_ATTENDANCE, AppModule.HR_DOCUMENTS)
        val office = MyDocumentsViewModel(myDocuments, auth)
        keepCollecting(office.uiState)
        office.refresh()

        assertFalse(office.uiState.value.showRequirements)
        assertTrue(office.uiState.value.showHistory)
        assertEquals(1, myDocuments.requirementReads)
        assertEquals(2, myDocuments.historyReads)
    }

    @Test
    fun `a driver without the hr module never asks for the history`() = runTest {
        loggedIn(AppModule.DRIVER)
        val viewModel = MyDocumentsViewModel(myDocuments, auth)
        keepCollecting(viewModel.uiState)
        viewModel.refresh()

        assertFalse(viewModel.uiState.value.showHistory)
        assertEquals(0, myDocuments.historyReads)
    }

    @Test
    fun `a failed read without data is an error, with data it marks the list as old`() = runTest {
        loggedIn(AppModule.HR_DOCUMENTS)
        myDocuments.historyResult = RefreshResult.ACCESS_REQUIRED
        val viewModel = MyDocumentsViewModel(myDocuments, auth)
        keepCollecting(viewModel.uiState)
        viewModel.refresh()

        assertEquals(R.string.feature_access_required, viewModel.uiState.value.history.error)

        myDocuments.history.value = PersonnelDocumentHistory(listOf(personnelDocument("d-1")), canLoadMore = false)
        myDocuments.historyResult = RefreshResult.RATE_LIMITED
        viewModel.refresh()

        assertNull(viewModel.uiState.value.history.error)
        assertTrue(viewModel.uiState.value.history.stale)
    }

    @Test
    fun `loading more reports a failure once and never runs twice at the same time`() = runTest {
        loggedIn(AppModule.HR_DOCUMENTS)
        myDocuments.history.value = PersonnelDocumentHistory(listOf(personnelDocument("d-1")), canLoadMore = true)
        myDocuments.loadMoreResult = RefreshResult.SERVICE_UNAVAILABLE
        val viewModel = MyDocumentsViewModel(myDocuments, auth)
        keepCollecting(viewModel.uiState)

        assertTrue(viewModel.uiState.value.canLoadMore)
        viewModel.onLoadMore()

        assertEquals(R.string.refresh_service_unavailable, viewModel.uiState.value.message)
        viewModel.onMessageShown()
        assertNull(viewModel.uiState.value.message)
        assertEquals(1, myDocuments.loadMoreCalls)
    }

    // --- Vehicle documents ---

    @Test
    fun `every visit reads the vehicle files and me again`() = runTest {
        loggedIn(AppModule.DRIVER)
        vehicleDocuments.snapshot.value = VehicleDocumentsSnapshot(vehicleDocuments("veh-1"), stale = false)
        val viewModel = VehicleDocumentsViewModel(vehicleDocuments, auth)
        keepCollecting(viewModel.uiState)

        viewModel.refresh()
        viewModel.refresh()

        assertEquals(2, vehicleDocuments.reads)
        assertEquals(2, auth.profileRefreshes)
        assertEquals("veh-1", viewModel.uiState.value.documents.data?.vehicle?.vehicleId)
    }

    @Test
    fun `an answer kept from an earlier read is shown as old`() = runTest {
        loggedIn(AppModule.DRIVER)
        vehicleDocuments.snapshot.value = VehicleDocumentsSnapshot(vehicleDocuments("veh-1"), stale = true)
        val viewModel = VehicleDocumentsViewModel(vehicleDocuments, auth)
        keepCollecting(viewModel.uiState)

        assertTrue(viewModel.uiState.value.documents.stale)
    }

    @Test
    fun `a dropped answer after an assignment change reads as a failure, not the old vehicle`() = runTest {
        loggedIn(AppModule.DRIVER)
        vehicleDocuments.refreshResult = RefreshResult.OFFLINE
        val viewModel = VehicleDocumentsViewModel(vehicleDocuments, auth)
        keepCollecting(viewModel.uiState)
        viewModel.refresh()

        assertNull(viewModel.uiState.value.documents.data)
        assertEquals(R.string.refresh_offline, viewModel.uiState.value.documents.error)
    }

    // --- Viewer ---

    private fun viewer(source: ViewedFileSource = ViewedFileSource.PERSONNEL, contentType: String? = "application/pdf") = DocumentViewerViewModel(
        SavedStateHandle(mapOf(VIEWER_SOURCE_ARG to source.name, VIEWER_DOCUMENT_ID_ARG to "d-1", VIEWER_CONTENT_TYPE_ARG to contentType)),
        myDocuments,
        vehicleDocuments,
        signedDocuments,
        hrDocuments,
        cashPayments,
        pdfOpener,
        imageDecoder,
        cleaner,
    )

    @Test
    fun `a signed document is downloaded by the viewer and shown as pdf pages`() {
        signedDocuments.openResult = DocumentContentResult.Available("/cache/private_document_view/signed-d-1.pdf", "application/pdf")
        val viewModel = viewer(ViewedFileSource.SIGNED)

        assertTrue(viewModel.uiState.value.content is ViewerContent.Pdf)
        assertEquals(listOf("d-1"), signedDocuments.opened)
        assertTrue(myDocuments.openRequests.isEmpty())

        viewModel.clearForTest()
        assertEquals(listOf("/cache/private_document_view/signed-d-1.pdf"), cleaner.discarded)
    }

    @Test
    fun `a signed proof is shown in the app and says when it is not ready yet`() {
        assertTrue(viewer(ViewedFileSource.HR_RECEIPT).uiState.value.content is ViewerContent.Pdf)

        hrDocuments.receiptResult = DocumentContentResult.Unavailable(ActionRejection.NOT_FOUND)
        assertEquals(R.string.hr_receipt_pending, viewer(ViewedFileSource.HR_RECEIPT).uiState.value.error)

        // Only the proof reads 404 as "still being produced"; a personnel file that is gone says so.
        myDocuments.openResult = DocumentContentResult.Unavailable(ActionRejection.NOT_FOUND)
        assertEquals(R.string.document_viewer_not_found, viewer(ViewedFileSource.PERSONNEL).uiState.value.error)
    }

    @Test
    fun `a pdf opens in the app and its file is deleted when the viewer closes`() {
        val pages = FakePdfPages(pageCount = 2)
        pdfOpener.open = { pages }
        val viewModel = viewer()

        val content = viewModel.uiState.value.content
        assertTrue(content is ViewerContent.Pdf)
        assertEquals(listOf("d-1" to "application/pdf"), myDocuments.openRequests)

        viewModel.clearForTest()

        assertTrue(pages.closed)
        assertEquals(listOf("/cache/private_document_view/p.pdf"), cleaner.discarded)
    }

    @Test
    fun `a vehicle photo is decoded for the in-app viewer`() {
        imageDecoder.image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
        val viewModel = viewer(ViewedFileSource.VEHICLE, contentType = "image/png")

        assertTrue(viewModel.uiState.value.content is ViewerContent.Picture)
        assertEquals(listOf("d-1"), vehicleDocuments.openRequests)
        assertEquals(listOf("/cache/private_document_view/v.png"), imageDecoder.decoded)
    }

    @Test
    fun `404, 503 and a file the phone cannot show each get their own message`() {
        myDocuments.openResult = DocumentContentResult.Unavailable(ActionRejection.NOT_FOUND)
        assertEquals(R.string.document_viewer_not_found, viewer().uiState.value.error)

        myDocuments.openResult = DocumentContentResult.Unavailable(ActionRejection.SERVICE_UNAVAILABLE)
        assertEquals(R.string.document_viewer_storage_unavailable, viewer().uiState.value.error)

        myDocuments.openResult = DocumentContentResult.Unavailable(ActionRejection.RATE_LIMITED)
        assertEquals(R.string.refresh_rate_limited, viewer().uiState.value.error)

        myDocuments.openResult = DocumentContentResult.Available("/cache/private_document_view/x.bin", "application/zip")
        val unreadable = viewer()
        assertEquals(R.string.document_viewer_unreadable, unreadable.uiState.value.error)
        // Nothing unreadable stays on the phone.
        assertEquals(listOf("/cache/private_document_view/x.bin"), cleaner.discarded)
    }

    @Test
    fun `retry downloads again after a passing failure`() {
        myDocuments.openResult = DocumentContentResult.Unavailable(ActionRejection.SERVICE_UNAVAILABLE)
        val viewModel = viewer()
        myDocuments.openResult = DocumentContentResult.Available("/cache/private_document_view/p.pdf", "application/pdf")

        viewModel.onRetry()

        assertTrue(viewModel.uiState.value.content is ViewerContent.Pdf)
        assertEquals(2, myDocuments.openRequests.size)
    }
}

/** `onCleared` is protected; the ViewModelStore calls it when the screen is gone. */
private fun DocumentViewerViewModel.clearForTest() {
    val store = androidx.lifecycle.ViewModelStore()
    store.put("viewer", this)
    store.clear()
}
