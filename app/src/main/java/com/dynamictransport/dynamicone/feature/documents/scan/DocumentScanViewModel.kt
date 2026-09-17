package com.dynamictransport.dynamicone.feature.documents.scan

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.repository.DocumentMediaStore
import com.dynamictransport.dynamicone.domain.repository.DocumentScanner
import com.dynamictransport.dynamicone.domain.scan.CaptureReadiness
import com.dynamictransport.dynamicone.domain.scan.CaptureStabilizer
import com.dynamictransport.dynamicone.domain.scan.DocumentQuad
import com.dynamictransport.dynamicone.domain.scan.FrameVerdict
import com.dynamictransport.dynamicone.domain.scan.ScanIssue
import com.dynamictransport.dynamicone.domain.scan.ScanMode
import com.dynamictransport.dynamicone.domain.scan.ScanOutcome
import com.dynamictransport.dynamicone.domain.scan.scanMode
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Navigation arguments of the scanner; the names match DocumentScanDestination. */
const val DOCUMENT_TYPE_ARG = "documentType"
const val SOURCE_PATH_ARG = "sourcePath"

/** Saved-state key on the documents screen that receives the accepted image path. */
const val SCAN_RESULT_KEY = "document_scan_result"

enum class ScanPhase { CAMERA, PROCESSING, REVIEW }

data class ScanReview(val imagePath: String, val issues: List<ScanIssue>) {
    val accepted: Boolean get() = issues.isEmpty()
}

data class DocumentScanUiState(
    val documentType: DocumentType,
    val mode: ScanMode,
    val phase: ScanPhase = ScanPhase.CAMERA,
    /** No camera frame has been checked yet. */
    val cameraStarting: Boolean = true,
    /** Most important problem in the live picture; null while every check passes. */
    val liveIssue: ScanIssue? = null,
    /** Detected sheet, normalised to the preview. */
    val quad: DocumentQuad? = null,
    val canCapture: Boolean = false,
    val capturing: Boolean = false,
    val torchOn: Boolean = false,
    val cameraUnavailable: Boolean = false,
    val review: ScanReview? = null,
    /** The accepted photo was handed to the documents screen; nothing can be changed any more. */
    val handedOver: Boolean = false,
    @param:StringRes val message: Int? = null,
    /** File the camera writes the next photo to. */
    val captureRequest: String? = null,
    /** Accepted image waiting to be handed to the documents screen. */
    val acceptedPath: String? = null,
)

/**
 * In-app document scanner. A photo can only be taken while the live checks pass (documents are taken
 * automatically once the sheet is steady); the stored photo is then straightened and checked again, and only
 * an image without issues can be used. Gallery photos ([SOURCE_PATH_ARG]) go through the same final check.
 */
@HiltViewModel
class DocumentScanViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val mediaStore: DocumentMediaStore,
    private val scanner: DocumentScanner,
) : ViewModel() {

    private val documentType: DocumentType = savedStateHandle.get<String>(DOCUMENT_TYPE_ARG)
        ?.let { name -> DocumentType.entries.firstOrNull { it.name == name } }
        ?: DocumentType.OTHER
    private val mode = documentType.scanMode
    private val stabilizer = CaptureStabilizer()
    private val captureFlight = SingleFlight()

    /** Off after low storage blocked a photo, so the automatic photo does not retry on every frame. */
    @Volatile
    private var autoCaptureAllowed = true

    private val state = MutableStateFlow(DocumentScanUiState(documentType, mode))
    val uiState: StateFlow<DocumentScanUiState> = state.asStateFlow()

    init {
        // A photo that was being taken or checked when the process died is incomplete.
        savedStateHandle.get<String>(KEY_WORK_PATH)?.let { unfinished ->
            savedStateHandle.remove<String>(KEY_WORK_PATH)
            viewModelScope.launch { mediaStore.discard(unfinished) }
        }
        val reviewPath = savedStateHandle.get<String>(KEY_REVIEW_PATH)
        val galleryPhoto = savedStateHandle.get<String>(SOURCE_PATH_ARG)
        when {
            reviewPath != null -> {
                val issues = savedStateHandle.get<ArrayList<String>>(KEY_REVIEW_ISSUES).orEmpty()
                    .mapNotNull { name -> ScanIssue.entries.firstOrNull { it.name == name } }
                state.update { it.copy(phase = ScanPhase.REVIEW, review = ScanReview(reviewPath, issues)) }
            }
            galleryPhoto != null && savedStateHandle.get<Boolean>(KEY_GALLERY_CHECKED) != true -> {
                savedStateHandle[KEY_GALLERY_CHECKED] = true
                checkPhoto(galleryPhoto)
            }
        }
    }

    /** Called on the camera analysis thread for every checked frame. */
    fun onFrameAnalyzed(verdict: FrameVerdict, timestampNanos: Long) {
        val takePhoto = synchronized(stabilizer) {
            val current = state.value
            if (current.phase != ScanPhase.CAMERA || current.capturing) return
            val readiness = stabilizer.update(verdict, timestampNanos)
            state.update {
                it.copy(
                    cameraStarting = false,
                    quad = verdict.quad,
                    liveIssue = (readiness as? CaptureReadiness.NotReady)?.issue,
                    canCapture = readiness !is CaptureReadiness.NotReady,
                )
            }
            readiness == CaptureReadiness.Ready && mode == ScanMode.DOCUMENT && autoCaptureAllowed
        }
        if (takePhoto) capture()
    }

    fun onCaptureClicked() {
        if (!state.value.canCapture) return
        autoCaptureAllowed = true
        capture()
    }

    private fun capture() {
        if (!mediaStore.hasEnoughSpace()) {
            autoCaptureAllowed = false
            state.update { it.copy(message = R.string.error_low_storage) }
            return
        }
        captureFlight.launch(viewModelScope) {
            val current = state.value
            if (current.phase != ScanPhase.CAMERA || current.capturing) return@launch
            state.update { it.copy(capturing = true) }
            val path = mediaStore.createCaptureFile()
            savedStateHandle[KEY_WORK_PATH] = path
            state.update { it.copy(captureRequest = path) }
        }
    }

    fun onCaptureRequestHandled() {
        state.update { it.copy(captureRequest = null) }
    }

    fun onCaptureSaved() {
        val photo = savedStateHandle.get<String>(KEY_WORK_PATH) ?: return
        checkPhoto(photo)
    }

    fun onCaptureFailed() {
        val photo = savedStateHandle.get<String>(KEY_WORK_PATH)
        savedStateHandle.remove<String>(KEY_WORK_PATH)
        returnToCamera(R.string.error_file)
        photo?.let { viewModelScope.launch { mediaStore.discard(it) } }
    }

    fun onCameraUnavailable() {
        state.update { it.copy(cameraUnavailable = true) }
    }

    fun onToggleTorch() {
        state.update { it.copy(torchOn = !it.torchOn) }
    }

    fun onRetake() {
        val current = state.value
        val review = current.review ?: return
        if (current.handedOver) return
        clearSavedReview()
        viewModelScope.launch { mediaStore.discard(review.imagePath) }
        autoCaptureAllowed = true
        returnToCamera(message = null)
    }

    fun onUse() {
        val current = state.value
        val review = current.review?.takeIf { it.accepted } ?: return
        if (current.handedOver) return
        clearSavedReview()
        state.update { it.copy(handedOver = true, acceptedPath = review.imagePath) }
    }

    fun onAcceptedHandled() {
        state.update { it.copy(acceptedPath = null) }
    }

    /** Leaving without using the photo: nothing of it stays on the phone. */
    fun onClose() {
        val current = state.value
        if (current.handedOver) return
        val leftovers = listOfNotNull(current.review?.imagePath, savedStateHandle.get<String>(KEY_WORK_PATH))
        clearSavedReview()
        savedStateHandle.remove<String>(KEY_WORK_PATH)
        if (leftovers.isEmpty()) return
        // The screen is closing, so the clean-up has to outlive this ViewModel.
        viewModelScope.launch(NonCancellable) { leftovers.forEach { mediaStore.discard(it) } }
    }

    fun onMessageShown() {
        state.update { it.copy(message = null) }
    }

    private fun checkPhoto(photoPath: String) {
        savedStateHandle[KEY_WORK_PATH] = photoPath
        state.update { it.copy(phase = ScanPhase.PROCESSING, capturing = false, captureRequest = null, quad = null) }
        viewModelScope.launch {
            val outputPath = mediaStore.createCaptureFile()
            val outcome = scanner.process(photoPath, outputPath, mode)
            // The unprocessed photo is never uploaded or kept.
            mediaStore.discard(photoPath)
            savedStateHandle.remove<String>(KEY_WORK_PATH)
            when (outcome) {
                is ScanOutcome.Processed -> showReview(ScanReview(outcome.imagePath, outcome.issues))
                ScanOutcome.Unreadable -> {
                    mediaStore.discard(outputPath)
                    returnToCamera(R.string.error_file)
                }
            }
        }
    }

    private fun showReview(review: ScanReview) {
        savedStateHandle[KEY_REVIEW_PATH] = review.imagePath
        savedStateHandle[KEY_REVIEW_ISSUES] = ArrayList(review.issues.map { it.name })
        state.update { it.copy(phase = ScanPhase.REVIEW, review = review) }
    }

    private fun clearSavedReview() {
        savedStateHandle.remove<String>(KEY_REVIEW_PATH)
        savedStateHandle.remove<ArrayList<String>>(KEY_REVIEW_ISSUES)
    }

    private fun returnToCamera(@StringRes message: Int?) {
        synchronized(stabilizer) { stabilizer.reset() }
        state.update {
            it.copy(
                phase = ScanPhase.CAMERA,
                cameraStarting = true,
                liveIssue = null,
                quad = null,
                canCapture = false,
                capturing = false,
                captureRequest = null,
                review = null,
                message = message ?: it.message,
            )
        }
    }

    private companion object {
        const val KEY_WORK_PATH = "scan_work_path"
        const val KEY_REVIEW_PATH = "scan_review_path"
        const val KEY_REVIEW_ISSUES = "scan_review_issues"
        const val KEY_GALLERY_CHECKED = "scan_gallery_checked"
    }
}
