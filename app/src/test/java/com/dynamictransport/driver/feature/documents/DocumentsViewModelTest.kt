package com.dynamictransport.driver.feature.documents

import androidx.lifecycle.SavedStateHandle
import com.dynamictransport.driver.R
import com.dynamictransport.driver.domain.model.ActionRejection
import com.dynamictransport.driver.domain.model.ActionResult
import com.dynamictransport.driver.domain.model.DocumentContentResult
import com.dynamictransport.driver.domain.model.DocumentSlotState
import com.dynamictransport.driver.domain.model.DocumentType
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.model.StopActivity
import com.dynamictransport.driver.domain.model.TripContainer
import com.dynamictransport.driver.domain.model.TripState
import com.dynamictransport.driver.feature.common.messageRes
import com.dynamictransport.driver.feature.tripdetail.TRIP_ID_ARG
import com.dynamictransport.driver.testing.FakeAppInfo
import com.dynamictransport.driver.testing.FakeDocumentMediaStore
import com.dynamictransport.driver.testing.FakeDocumentRepository
import com.dynamictransport.driver.testing.FakeTripRepository
import com.dynamictransport.driver.testing.MainDispatcherRule
import com.dynamictransport.driver.testing.keepCollecting
import com.dynamictransport.driver.testing.testSlot
import com.dynamictransport.driver.testing.testStop
import com.dynamictransport.driver.testing.testTrip
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DocumentsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val runningTrip = testTrip(
        state = TripState.STARTED,
        stops = listOf(testStop("s1", 1, StopActivity.PICKUP, "Rotterdam ECT Delta")),
        containers = listOf(TripContainer("c1", "MSKU 458921-7", "781392"), TripContainer("c2", null, null)),
    )
    private val trips = FakeTripRepository(listOf(runningTrip))
    private val documents = FakeDocumentRepository()
    private val media = FakeDocumentMediaStore()

    private val cmr = testSlot("Cmr:trip:all", DocumentType.CMR, DocumentSlotState.MISSING)
    private val customs = testSlot("Customs:trip:all", DocumentType.CUSTOMS, DocumentSlotState.NOT_REQUIRED, required = false)
    private val rejectedInterchange = testSlot(
        "Interchange:s1:c1", DocumentType.INTERCHANGE, DocumentSlotState.REJECTED, stopId = "s1", containerId = "c1", documentId = "d1", reviewNote = "Unreadable",
    )
    private val reviewedInterchange = testSlot(
        "Interchange:s1:c2", DocumentType.INTERCHANGE, DocumentSlotState.PENDING_REVIEW, stopId = "s1", containerId = "c2", documentId = "d2",
    )
    private val seal = testSlot("Seal:s1:c1", DocumentType.SEAL_PHOTO, DocumentSlotState.MISSING, stopId = "s1", containerId = "c1")

    init {
        documents.slots.value = listOf(customs, rejectedInterchange, cmr, reviewedInterchange, seal)
    }

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle(), demo: Boolean = false) =
        DocumentsViewModel(handle, trips, documents, media, FakeAppInfo(isDemoMode = demo))

    @Test
    fun `every category and slot is listed separately from the server checklist`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        val state = viewModel.uiState.value
        assertEquals("trip-1", state.trip?.id)
        assertEquals(listOf("trip-1"), documents.refreshed)
        assertEquals(listOf(DocumentType.CMR, DocumentType.CUSTOMS, DocumentType.INTERCHANGE, DocumentType.SEAL_PHOTO), state.groups.map { it.first })
        assertEquals(listOf(rejectedInterchange, reviewedInterchange), state.groups[2].second)
        // Required-and-missing and rejected slots; a not required slot is never counted.
        assertEquals(3, state.openCount)
        assertEquals(rejectedInterchange.key, state.selectedSlotKey)
        assertFalse(state.loading)
    }

    @Test
    fun `requirements are the server's, never recalculated on the device`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        assertEquals(3, viewModel.uiState.value.openCount)

        documents.slots.value = listOf(customs.copy(required = true, state = DocumentSlotState.MISSING), cmr)

        assertEquals(2, viewModel.uiState.value.openCount)
    }

    @Test
    fun `photo of the selected slot is checked by the scanner and uploaded to that slot`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onSelectSlot(seal.key)

        viewModel.onTakePhoto()
        assertEquals(ScanRequest(DocumentType.SEAL_PHOTO, sourcePath = null), viewModel.uiState.value.scanRequest)
        assertTrue(documents.uploaded.isEmpty())
        viewModel.onScanLaunched()
        viewModel.onScanAccepted("/files/document_media/scan-1.jpg")
        advanceUntilIdle()

        assertEquals(FakeDocumentRepository.Uploaded("trip-1", seal.key, "/files/document_media/scan-1.jpg"), documents.uploaded.single())
        assertEquals(R.string.documents_uploaded, viewModel.uiState.value.message)
    }

    @Test
    fun `pdf is refused for photo slots and uploaded directly for document slots`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onSelectSlot(seal.key)

        viewModel.onPdfResult("content://pdf")
        advanceUntilIdle()
        assertEquals(ActionRejection.PHOTO_REQUIRED.messageRes(), viewModel.uiState.value.message)
        assertTrue(documents.uploaded.isEmpty())

        viewModel.onSelectSlot(cmr.key)
        viewModel.onPdfResult("content://pdf")
        advanceUntilIdle()

        assertEquals(FakeDocumentRepository.Uploaded("trip-1", cmr.key, "/files/document_media/imported.jpg"), documents.uploaded.single())
        assertNull(viewModel.uiState.value.scanRequest)
    }

    @Test
    fun `gallery photo is checked by the scanner before it is uploaded`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onSelectSlot(cmr.key)

        viewModel.onGalleryResult("content://picked")
        advanceUntilIdle()
        assertEquals(ScanRequest(DocumentType.CMR, "/files/document_media/imported.jpg"), viewModel.uiState.value.scanRequest)
        assertTrue(documents.uploaded.isEmpty())

        viewModel.onScanLaunched()
        viewModel.onScanAccepted("/files/document_media/scan-1.jpg")
        advanceUntilIdle()

        assertEquals(cmr.key, documents.uploaded.single().slotKey)
    }

    @Test
    fun `server refusals of an accepted scan are shown`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        documents.uploadResult = ActionResult.Rejected(ActionRejection.FILE_TOO_LARGE)

        viewModel.onTakePhoto()
        viewModel.onScanAccepted("/files/document_media/scan-1.jpg")
        advanceUntilIdle()

        assertEquals(R.string.error_file_too_large, viewModel.uiState.value.message)
    }

    @Test
    fun `a slot that left the checklist during the scan is not guessed`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onSelectSlot(seal.key)
        viewModel.onTakePhoto()

        documents.slots.value = listOf(cmr, customs)
        viewModel.onScanAccepted("/files/document_media/scan-1.jpg")
        advanceUntilIdle()

        assertTrue(documents.uploaded.isEmpty())
        assertEquals(listOf("/files/document_media/scan-1.jpg"), media.discarded)
        assertEquals(R.string.error_plan_changed, viewModel.uiState.value.message)
    }

    @Test
    fun `scan result is not lost when the app process is recreated`() = runTest {
        val handle = SavedStateHandle()
        val before = viewModel(handle)
        keepCollecting(before.uiState)
        before.onSelectSlot(reviewedInterchange.key)
        before.onTakePhoto()

        val after = viewModel(handle)
        after.onScanAccepted("/files/document_media/scan-1.jpg")
        advanceUntilIdle()

        assertEquals(reviewedInterchange.key, documents.uploaded.single().slotKey)
    }

    @Test
    fun `a new assignment cannot receive documents, a completed trip can`() = runTest {
        trips.trips.value = listOf(testTrip(state = TripState.ASSIGNED))
        val newTrip = viewModel(SavedStateHandle(mapOf(TRIP_ID_ARG to "trip-1")))
        keepCollecting(newTrip.uiState)

        newTrip.onTakePhoto()

        assertFalse(newTrip.uiState.value.canUpload)
        assertNull(newTrip.uiState.value.scanRequest)
        assertEquals(R.string.documents_upload_not_possible, newTrip.uiState.value.message)

        trips.trips.value = listOf(testTrip(state = TripState.FINISHED))
        assertTrue(newTrip.uiState.value.canUpload)
    }

    @Test
    fun `a failed first read is an error, a later failure keeps the checklist as stale`() = runTest {
        documents.slots.value = null
        documents.refreshResult = RefreshResult.SERVICE_UNAVAILABLE
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        assertEquals(R.string.refresh_service_unavailable, viewModel.uiState.value.loadError)
        assertFalse(viewModel.uiState.value.stale)

        documents.slots.value = listOf(cmr)
        viewModel.onRetry()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.loadError)
        assertTrue(viewModel.uiState.value.stale)
    }

    @Test
    fun `active document of a slot is opened through the repository`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        documents.openResult = DocumentContentResult.Available("/cache/document_view/document-d1.jpg", "image/jpeg")

        viewModel.onOpenSlotDocument(rejectedInterchange)
        advanceUntilIdle()
        assertEquals(listOf("d1"), documents.opened)
        assertEquals("image/jpeg", viewModel.uiState.value.openRequest?.contentType)

        viewModel.onOpenFailed()
        assertNull(viewModel.uiState.value.openRequest)
        assertEquals(R.string.documents_open_unavailable, viewModel.uiState.value.message)
    }

    @Test
    fun `low storage prevents opening the scanner`() = runTest {
        media.enoughSpace = false
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onTakePhoto()

        assertEquals(R.string.error_low_storage, viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.scanRequest)
    }
}
