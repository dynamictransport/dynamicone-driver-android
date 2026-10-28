package com.dynamictransport.dynamicone.feature.personnelfiles

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.media.DocumentImageDecoder
import com.dynamictransport.dynamicone.core.pdf.PdfDocumentOpener
import com.dynamictransport.dynamicone.core.pdf.PdfPageSource
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.CashPaymentScope
import com.dynamictransport.dynamicone.domain.repository.CashPaymentRepository
import com.dynamictransport.dynamicone.domain.repository.HrDocumentRepository
import com.dynamictransport.dynamicone.domain.repository.MyDocumentRepository
import com.dynamictransport.dynamicone.domain.repository.SignedDocumentRepository
import com.dynamictransport.dynamicone.domain.repository.VehicleDocumentRepository
import com.dynamictransport.dynamicone.domain.repository.ViewedFileCleaner
import com.dynamictransport.dynamicone.feature.hrdocuments.PdfPage
import com.dynamictransport.dynamicone.feature.hrdocuments.PdfZoomDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Whose file the viewer opens; the server checks the account's access on every download. [SIGNED] is a PDF the
 * employee signed in the web (leave, compensation), [HR_RECEIPT] the signed proof of a personnel document,
 * [CASH_RECEIPT] / [MY_CASH_RECEIPT] the signed receipt of a cash hand-over (officer's queue / own account, docs/28).
 */
enum class ViewedFileSource { PERSONNEL, VEHICLE, SIGNED, HR_RECEIPT, CASH_RECEIPT, MY_CASH_RECEIPT }

/** Argument names of [com.dynamictransport.dynamicone.app.navigation.DocumentViewerDestination]. */
const val VIEWER_SOURCE_ARG = "source"
const val VIEWER_DOCUMENT_ID_ARG = "documentId"
const val VIEWER_CONTENT_TYPE_ARG = "contentType"

sealed interface ViewerContent {
    data class Pdf(val pages: PdfPageSource) : ViewerContent

    data class Picture(val image: ImageBitmap) : ViewerContent
}

data class DocumentViewerUiState(
    val loading: Boolean = true,
    val content: ViewerContent? = null,
    @param:StringRes val error: Int? = null,
)

/**
 * Downloads one file with the signed-in account's token into a private temporary file and shows it inside the app, so
 * no PDF viewer app is needed on the phone. Nothing leaves the app, and the file is deleted as soon as the viewer
 * closes (`private, no-store`).
 */
@HiltViewModel
class DocumentViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val myDocuments: MyDocumentRepository,
    private val vehicleDocuments: VehicleDocumentRepository,
    private val signedDocuments: SignedDocumentRepository,
    private val hrDocuments: HrDocumentRepository,
    private val cashPayments: CashPaymentRepository,
    private val pdfOpener: PdfDocumentOpener,
    private val imageDecoder: DocumentImageDecoder,
    private val cleaner: ViewedFileCleaner,
) : ViewModel() {

    private val source = ViewedFileSource.valueOf(checkNotNull(savedStateHandle[VIEWER_SOURCE_ARG]))
    private val documentId: String = checkNotNull(savedStateHandle[VIEWER_DOCUMENT_ID_ARG])
    private val contentTypeHint: String? = savedStateHandle[VIEWER_CONTENT_TYPE_ARG]
    private val state = MutableStateFlow(DocumentViewerUiState())
    private var loadJob: Job? = null
    private var filePath: String? = null

    val uiState: StateFlow<DocumentViewerUiState> = state.asStateFlow()

    init {
        load()
    }

    fun onRetry() {
        if (loadJob?.isActive == true) return
        load()
    }

    private fun load() {
        state.value = DocumentViewerUiState(loading = true)
        loadJob = viewModelScope.launch {
            val result = when (source) {
                ViewedFileSource.PERSONNEL -> myDocuments.open(documentId, contentTypeHint)
                ViewedFileSource.VEHICLE -> vehicleDocuments.open(documentId, contentTypeHint)
                ViewedFileSource.SIGNED -> signedDocuments.open(documentId)
                ViewedFileSource.HR_RECEIPT -> hrDocuments.openReceipt(documentId)
                ViewedFileSource.CASH_RECEIPT -> cashPayments.openReceipt(CashPaymentScope.DISBURSEMENTS, documentId)
                ViewedFileSource.MY_CASH_RECEIPT -> cashPayments.openReceipt(CashPaymentScope.OWN, documentId)
            }
            state.value = when (result) {
                is DocumentContentResult.Unavailable -> DocumentViewerUiState(loading = false, error = errorMessage(result.reason))
                is DocumentContentResult.Available -> {
                    filePath = result.filePath
                    show(result)
                }
            }
        }
    }

    private suspend fun show(file: DocumentContentResult.Available): DocumentViewerUiState {
        val type = file.contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val content = when {
            type == PDF -> pdfOpener.open(file.filePath)?.let(ViewerContent::Pdf)
            type.startsWith("image/") -> imageDecoder.decode(file.filePath, MAX_IMAGE_EDGE_PX)?.let(ViewerContent::Picture)
            else -> null
        }
        return if (content == null) {
            discardFile()
            DocumentViewerUiState(loading = false, error = R.string.document_viewer_unreadable)
        } else {
            DocumentViewerUiState(loading = false, content = content)
        }
    }

    /**
     * A signed proof answers 404 while the server is still producing it (docs/17 §5.5): that is not a missing file. A
     * cash receipt that answers 404 does not exist for this account (docs/28 cash_receipt_not_found).
     */
    private fun errorMessage(reason: ActionRejection): Int = when {
        source == ViewedFileSource.HR_RECEIPT && reason == ActionRejection.NOT_FOUND -> R.string.hr_receipt_pending
        source.isCashReceipt && reason == ActionRejection.NOT_FOUND -> R.string.cash_receipt_missing
        else -> reason.fileMessageRes()
    }

    private val ViewedFileSource.isCashReceipt: Boolean
        get() = this == ViewedFileSource.CASH_RECEIPT || this == ViewedFileSource.MY_CASH_RECEIPT

    private fun discardFile() {
        filePath?.let(cleaner::discard)
        filePath = null
    }

    override fun onCleared() {
        (state.value.content as? ViewerContent.Pdf)?.pages?.close()
        discardFile()
    }

    private companion object {
        const val PDF = "application/pdf"

        /** Sharp enough to zoom into a scanned page, small enough for the phone's memory. */
        const val MAX_IMAGE_EDGE_PX = 3000
    }
}

@Composable
fun DocumentViewerRoute(title: String, onBack: () -> Unit, viewModel: DocumentViewerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DocumentViewerScreen(title = title, state = state, onBack = onBack, onRetry = viewModel::onRetry)
}

@Composable
fun DocumentViewerScreen(title: String, state: DocumentViewerUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    var zoomedPage by rememberSaveable { mutableStateOf<Int?>(null) }
    Scaffold(
        topBar = {
            DriverTopBar(title = title, onBack = onBack, backContentDescription = stringResource(R.string.common_back))
        },
    ) { padding ->
        val content = state.content
        val error = state.error
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag(DOCUMENT_VIEWER_LOADING_TAG),
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.document_viewer_loading), style = MaterialTheme.typography.bodyLarge)
                }
                error != null -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(error),
                    body = null,
                    modifier = Modifier
                        .padding(Dimens.ScreenPadding)
                        .testTag(DOCUMENT_VIEWER_ERROR_TAG),
                ) {
                    Spacer(Modifier.height(12.dp))
                    SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRetry, icon = Icons.Filled.Refresh)
                }
                content is ViewerContent.Pdf -> LazyColumn(
                    contentPadding = PaddingValues(Dimens.ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(DOCUMENT_VIEWER_PDF_TAG),
                ) {
                    items(count = content.pages.pageCount, key = { "page_$it" }) { index ->
                        PdfPage(content.pages, index, onZoom = { zoomedPage = index })
                    }
                }
                content is ViewerContent.Picture -> ZoomableImage(content.image)
            }
        }
        val pages = (content as? ViewerContent.Pdf)?.pages
        val page = zoomedPage
        if (pages != null && page != null && page < pages.pageCount) {
            PdfZoomDialog(pages, page, onDismiss = { zoomedPage = null })
        }
    }
}

/** A document photo on black, zoomable with two fingers or a double tap so small print can be read. */
@Composable
private fun ZoomableImage(image: ImageBitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Image(
        bitmap = image,
        contentDescription = stringResource(R.string.document_viewer_image),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(DOCUMENT_VIEWER_IMAGE_TAG)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                        offset = Offset.Zero
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

const val DOCUMENT_VIEWER_LOADING_TAG = "document_viewer_loading"
const val DOCUMENT_VIEWER_ERROR_TAG = "document_viewer_error"
const val DOCUMENT_VIEWER_PDF_TAG = "document_viewer_pdf"
const val DOCUMENT_VIEWER_IMAGE_TAG = "document_viewer_image"
