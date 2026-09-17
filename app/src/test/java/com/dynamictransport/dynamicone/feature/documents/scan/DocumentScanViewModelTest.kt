package com.dynamictransport.dynamicone.feature.documents.scan

import androidx.lifecycle.SavedStateHandle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.scan.DocumentQuad
import com.dynamictransport.dynamicone.domain.scan.FrameVerdict
import com.dynamictransport.dynamicone.domain.scan.ScanIssue
import com.dynamictransport.dynamicone.domain.scan.ScanMode
import com.dynamictransport.dynamicone.domain.scan.ScanPoint
import com.dynamictransport.dynamicone.testing.FakeDocumentMediaStore
import com.dynamictransport.dynamicone.testing.FakeDocumentScanner
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DocumentScanViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val media = FakeDocumentMediaStore()
    private val scanner = FakeDocumentScanner()

    private val sheet = DocumentQuad(ScanPoint(0.1f, 0.1f), ScanPoint(0.9f, 0.1f), ScanPoint(0.9f, 0.9f), ScanPoint(0.1f, 0.9f))
    private val goodFrame = FrameVerdict(emptyList(), sheet)

    private fun viewModel(type: String = "CMR", galleryPhoto: String? = null, handle: SavedStateHandle? = null) =
        DocumentScanViewModel(
            handle ?: SavedStateHandle(listOfNotNull(DOCUMENT_TYPE_ARG to type, galleryPhoto?.let { SOURCE_PATH_ARG to it }).toMap()),
            media,
            scanner,
        )

    /** Takes a photo of a good, steady document and waits for the check. */
    private fun DocumentScanViewModel.photographGoodDocument() {
        onFrameAnalyzed(goodFrame, 0)
        onCaptureClicked()
        onCaptureRequestHandled()
        onCaptureSaved()
    }

    @Test
    fun `photo cannot be taken while a check fails`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.cameraStarting)

        viewModel.onFrameAnalyzed(FrameVerdict(listOf(ScanIssue.CUT_OFF, ScanIssue.BLURRY), sheet), 0)
        viewModel.onCaptureClicked()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.cameraStarting)
        assertEquals(ScanIssue.CUT_OFF, state.liveIssue)
        assertFalse(state.canCapture)
        assertNull(state.captureRequest)
        assertEquals(0, media.created)
    }

    @Test
    fun `steady good document is photographed automatically, exactly once`() = runTest {
        val viewModel = viewModel()

        viewModel.onFrameAnalyzed(goodFrame, 0)
        assertTrue(viewModel.uiState.value.canCapture)
        assertNull(viewModel.uiState.value.liveIssue)
        assertNull(viewModel.uiState.value.captureRequest)

        viewModel.onFrameAnalyzed(goodFrame, 800 * MILLIS)
        viewModel.onFrameAnalyzed(goodFrame, 1_600 * MILLIS)
        advanceUntilIdle()

        assertEquals("/files/document_media/capture-1.jpg", viewModel.uiState.value.captureRequest)
        assertTrue(viewModel.uiState.value.capturing)
        assertEquals(1, media.created)
    }

    @Test
    fun `a moving document is not photographed automatically`() = runTest {
        val viewModel = viewModel()
        val moved = FrameVerdict(emptyList(), sheet.scale(0.9f, 0.9f))

        viewModel.onFrameAnalyzed(goodFrame, 0)
        viewModel.onFrameAnalyzed(moved, 500 * MILLIS)
        viewModel.onFrameAnalyzed(moved, 900 * MILLIS)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.captureRequest)
        assertTrue(viewModel.uiState.value.canCapture)
    }

    @Test
    fun `container photo is never taken automatically`() = runTest {
        val viewModel = viewModel(type = "SEAL_PHOTO")
        assertEquals(ScanMode.PHOTO, viewModel.uiState.value.mode)
        val goodPhoto = FrameVerdict(emptyList(), null)

        viewModel.onFrameAnalyzed(goodPhoto, 0)
        viewModel.onFrameAnalyzed(goodPhoto, 2_000 * MILLIS)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.captureRequest)

        viewModel.onCaptureClicked()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.captureRequest)
    }

    @Test
    fun `accepted photo is checked, reviewed and handed over once`() = runTest {
        val viewModel = viewModel()

        viewModel.photographGoodDocument()
        advanceUntilIdle()

        val call = scanner.calls.single()
        assertEquals("/files/document_media/capture-1.jpg", call.sourcePath)
        assertEquals(ScanMode.DOCUMENT, call.mode)
        // The unprocessed photo is removed as soon as it has been checked.
        assertEquals(listOf(call.sourcePath), media.discarded)
        val review = viewModel.uiState.value.review
        assertEquals(ScanPhase.REVIEW, viewModel.uiState.value.phase)
        assertEquals(ScanReview(call.outputPath, emptyList()), review)

        viewModel.onUse()
        assertEquals(call.outputPath, viewModel.uiState.value.acceptedPath)
        viewModel.onAcceptedHandled()
        viewModel.onUse()
        viewModel.onRetake()
        viewModel.onClose()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.acceptedPath)
        assertTrue(viewModel.uiState.value.handedOver)
        assertEquals(listOf(call.sourcePath), media.discarded)
    }

    @Test
    fun `photo that fails the final check cannot be used and is replaced by a new one`() = runTest {
        scanner.issues = listOf(ScanIssue.BLURRY, ScanIssue.LOW_RESOLUTION)
        val viewModel = viewModel()

        viewModel.photographGoodDocument()
        advanceUntilIdle()
        val review = viewModel.uiState.value.review
        assertNotNull(review)
        assertFalse(review!!.accepted)

        viewModel.onUse()
        assertNull(viewModel.uiState.value.acceptedPath)
        assertFalse(viewModel.uiState.value.handedOver)

        viewModel.onRetake()
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(ScanPhase.CAMERA, state.phase)
        assertNull(state.review)
        assertFalse(state.canCapture)
        assertTrue(review.imagePath in media.discarded)
    }

    @Test
    fun `gallery photo goes through the same final check`() = runTest {
        scanner.issues = listOf(ScanIssue.NO_DOCUMENT)

        val viewModel = viewModel(galleryPhoto = "/files/document_media/imported.jpg")
        advanceUntilIdle()

        assertEquals("/files/document_media/imported.jpg", scanner.calls.single().sourcePath)
        assertEquals(listOf(ScanIssue.NO_DOCUMENT), viewModel.uiState.value.review?.issues)
        assertTrue("/files/document_media/imported.jpg" in media.discarded)
    }

    @Test
    fun `review survives process death and the gallery photo is not checked twice`() = runTest {
        val handle = SavedStateHandle(mapOf(DOCUMENT_TYPE_ARG to "CMR", SOURCE_PATH_ARG to "/files/document_media/imported.jpg"))
        viewModel(handle = handle)
        advanceUntilIdle()

        val restored = viewModel(handle = handle)
        advanceUntilIdle()

        assertEquals(1, scanner.calls.size)
        assertEquals(ScanPhase.REVIEW, restored.uiState.value.phase)
        assertTrue(restored.uiState.value.review!!.accepted)
    }

    @Test
    fun `leaving without using the photo removes it`() = runTest {
        val viewModel = viewModel()
        viewModel.photographGoodDocument()
        advanceUntilIdle()
        val imagePath = viewModel.uiState.value.review!!.imagePath

        viewModel.onClose()
        advanceUntilIdle()

        assertTrue(imagePath in media.discarded)
    }

    @Test
    fun `low storage blocks the photo without retrying on every frame`() = runTest {
        media.enoughSpace = false
        val viewModel = viewModel()

        viewModel.onFrameAnalyzed(goodFrame, 0)
        viewModel.onFrameAnalyzed(goodFrame, 800 * MILLIS)
        advanceUntilIdle()
        assertEquals(R.string.error_low_storage, viewModel.uiState.value.message)
        viewModel.onMessageShown()

        viewModel.onFrameAnalyzed(goodFrame, 1_600 * MILLIS)
        viewModel.onFrameAnalyzed(goodFrame, 2_400 * MILLIS)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.message)
        assertEquals(0, media.created)
    }

    @Test
    fun `failed or unreadable photo returns to the camera with a message`() = runTest {
        val viewModel = viewModel()
        viewModel.onFrameAnalyzed(goodFrame, 0)
        viewModel.onCaptureClicked()
        advanceUntilIdle()

        viewModel.onCaptureFailed()
        advanceUntilIdle()
        assertEquals(ScanPhase.CAMERA, viewModel.uiState.value.phase)
        assertFalse(viewModel.uiState.value.capturing)
        assertEquals(R.string.error_file, viewModel.uiState.value.message)
        assertTrue("/files/document_media/capture-1.jpg" in media.discarded)

        scanner.unreadable = true
        viewModel.onMessageShown()
        viewModel.photographGoodDocument()
        advanceUntilIdle()
        assertEquals(ScanPhase.CAMERA, viewModel.uiState.value.phase)
        assertEquals(R.string.error_file, viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.review)
    }

    private companion object {
        const val MILLIS = 1_000_000L
    }
}
