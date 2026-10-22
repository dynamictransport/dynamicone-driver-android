package com.dynamictransport.dynamicone.feature.documents

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DocumentSlot
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripDocument
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.DocumentMediaStore
import com.dynamictransport.dynamicone.domain.repository.DocumentRepository
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import com.dynamictransport.dynamicone.domain.rules.DocumentRules
import com.dynamictransport.dynamicone.domain.statemachine.NextActionResolver
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.messageRes
import com.dynamictransport.dynamicone.feature.tripdetail.TRIP_ID_ARG
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Optional navigation argument: the checklist key to select when the screen opens. */
const val DOCUMENT_SLOT_ARG = "slot"

/** Opens the document scanner. [sourcePath] is a gallery photo that has to pass the same checks as a camera photo. */
data class ScanRequest(val documentType: DocumentType, val sourcePath: String?)

data class DocumentsUiState(
    val loading: Boolean = true,
    val trip: Trip? = null,
    /** The backend checklist; null until read. */
    val slots: List<DocumentSlot>? = null,
    val files: List<TripDocument> = emptyList(),
    val selectedSlotKey: String? = null,
    val refreshing: Boolean = false,
    /** Nothing could be read. */
    @param:StringRes val loadError: Int? = null,
    /** The last read failed; the previously read checklist is shown. */
    val stale: Boolean = false,
    val submitting: Boolean = false,
    @param:StringRes val message: Int? = null,
    val scanRequest: ScanRequest? = null,
    /** A server document downloaded to private cache, ready for a viewer app. */
    val openRequest: DocumentContentResult.Available? = null,
) {
    val canUpload: Boolean get() = trip?.let(DocumentRules::canUpload) == true

    val selectedSlot: DocumentSlot? get() = slots?.firstOrNull { it.key == selectedSlotKey }

    /** Required-and-missing or rejected slots. */
    val openCount: Int get() = slots.orEmpty().count { it.needsUpload }

    /** Every category separately in a fixed order; categories this app does not know come last. */
    val groups: List<Pair<DocumentType?, List<DocumentSlot>>>
        get() = slots.orEmpty()
            .groupBy { it.type }
            .entries
            .sortedBy { it.key?.ordinal ?: Int.MAX_VALUE }
            .map { it.key to it.value }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    tripRepository: TripRepository,
    private val documentRepository: DocumentRepository,
    private val mediaStore: DocumentMediaStore,
    private val appInfo: AppInfo,
) : ViewModel() {

    private val tripIdArgument: String? = savedStateHandle[TRIP_ID_ARG]
    /** The driver's own choice survives process death; otherwise the slot the trip screen asked for. */
    private val selectedSlotKey = MutableStateFlow(
        savedStateHandle.get<String>(KEY_SELECTED_SLOT) ?: savedStateHandle.get<String>(DOCUMENT_SLOT_ARG),
    )
    private val scanRequest = MutableStateFlow<ScanRequest?>(null)
    private val openRequest = MutableStateFlow<DocumentContentResult.Available?>(null)
    private val uploading = MutableStateFlow(false)
    private val singleFlight = SingleFlight()
    private val message = MutableStateFlow<Int?>(null)
    private val refreshing = MutableStateFlow(false)
    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)

    private val trip: Flow<Trip?> = if (tripIdArgument != null) {
        tripRepository.observeTrip(tripIdArgument)
    } else {
        tripRepository.observeTrips().map(NextActionResolver::currentWorkTrip)
    }

    private data class Content(val trip: Trip?, val slots: List<DocumentSlot>?, val files: List<TripDocument>)

    private val content: Flow<Content> = trip.flatMapLatest { current ->
        if (current == null) {
            flowOf(Content(null, null, emptyList()))
        } else {
            combine(documentRepository.observeSlots(current.id), documentRepository.observeFiles(current.id)) { slots, files ->
                Content(current, slots, files)
            }
        }
    }

    private val feedback = combine(singleFlight.busy, uploading, message) { flight, upload, currentMessage ->
        (flight || upload) to currentMessage
    }
    private val requests = combine(scanRequest, openRequest, ::Pair)
    private val loadState = combine(refreshing, lastRefresh, ::Pair)

    val uiState: StateFlow<DocumentsUiState> = combine(
        content,
        selectedSlotKey,
        feedback,
        requests,
        loadState,
    ) { data, selected, (busy, currentMessage), (scan, open), (isRefreshing, refresh) ->
        val slots = data.slots
        val failed = refresh?.takeIf { it != RefreshResult.SUCCESS }
        DocumentsUiState(
            loading = data.trip != null && slots == null && failed == null,
            trip = data.trip,
            slots = slots,
            files = data.files,
            selectedSlotKey = selected?.takeIf { key -> slots?.any { it.key == key } == true }
                ?: slots?.firstOrNull { it.needsUpload }?.key,
            refreshing = isRefreshing,
            loadError = failed?.takeIf { slots == null }?.messageRes(),
            stale = failed != null && slots != null,
            submitting = busy,
            message = currentMessage,
            scanRequest = scan,
            openRequest = open,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentsUiState())

    init {
        // The checklist of the trip on screen is read again whenever that trip changes.
        viewModelScope.launch {
            trip.map { it?.id }.distinctUntilChanged().filterNotNull().collect { refresh(it) }
        }
    }

    fun onSelectSlot(key: String) {
        selectedSlotKey.value = key
        savedStateHandle[KEY_SELECTED_SLOT] = key
    }

    fun onRetry() {
        val tripId = uiState.value.trip?.id ?: return
        viewModelScope.launch { refresh(tripId) }
    }

    /** Opens the in-app scanner for the selected slot; the photo is only uploaded after it passed every check. */
    fun onTakePhoto() {
        val (tripId, slot) = uploadTarget() ?: return
        rememberPendingScan(tripId, slot.key)
        scanRequest.value = ScanRequest(slot.type ?: DocumentType.OTHER, sourcePath = null)
    }

    /** A picked gallery photo is copied privately and then checked by the scanner like a camera photo. */
    fun onGalleryResult(uri: String?) {
        if (uri == null) return
        val (tripId, slot) = uploadTarget() ?: return
        singleFlight.launch(viewModelScope) {
            val path = mediaStore.importFromContentUri(uri)
            if (path == null) {
                message.value = R.string.error_file
                return@launch
            }
            rememberPendingScan(tripId, slot.key)
            scanRequest.value = ScanRequest(slot.type ?: DocumentType.OTHER, path)
        }
    }

    /** A PDF cannot be photographed: it is uploaded as it is, for categories that accept PDF. */
    fun onPdfResult(uri: String?) {
        if (uri == null) return
        val (tripId, slot) = uploadTarget() ?: return
        if (slot.type?.photoOnly == true) {
            message.value = ActionRejection.PHOTO_REQUIRED.messageRes()
            return
        }
        singleFlight.launch(viewModelScope) {
            val path = mediaStore.importFromContentUri(uri)
            if (path == null) {
                message.value = R.string.error_file
                return@launch
            }
            upload(tripId, slot.key, path)
        }
    }

    fun onScanLaunched() {
        scanRequest.value = null
    }

    /** The scanner accepted [imagePath]; it belongs to the trip and slot the scan was started for. */
    fun onScanAccepted(imagePath: String) {
        val pending = takePendingScan()
        viewModelScope.launch {
            if (pending == null) {
                mediaStore.discard(imagePath)
                return@launch
            }
            upload(pending.tripId, pending.slotKey, imagePath)
        }
    }

    fun onOpenSlotDocument(slot: DocumentSlot) {
        val tripId = uiState.value.trip?.id ?: return
        val documentId = slot.documentId ?: return
        open(tripId, documentId, null)
    }

    fun onOpenFile(file: TripDocument) = open(file.tripId, file.id, file.contentType)

    fun onOpenRequestHandled() {
        openRequest.value = null
    }

    fun onOpenFailed() {
        openRequest.value = null
        message.value = R.string.documents_open_unavailable
    }

    fun onMessageShown() {
        message.value = null
    }

    private fun uploadTarget(): Pair<String, DocumentSlot>? {
        val state = uiState.value
        val currentTrip = state.trip ?: return null
        val slot = state.selectedSlot ?: return null
        if (!state.canUpload) {
            message.value = R.string.documents_upload_not_possible
            return null
        }
        if (!mediaStore.hasEnoughSpace()) {
            message.value = R.string.error_low_storage
            return null
        }
        return currentTrip.id to slot
    }

    private suspend fun refresh(tripId: String) {
        refreshing.value = true
        lastRefresh.value = null
        lastRefresh.value = documentRepository.refreshDocuments(tripId)
        refreshing.value = false
    }

    /** The slot is looked up again: a checklist that changed in the meantime is never guessed. */
    private suspend fun upload(tripId: String, slotKey: String, path: String) {
        val slot = documentRepository.observeSlots(tripId).first()?.firstOrNull { it.key == slotKey }
        if (slot == null) {
            mediaStore.discard(path)
            message.value = ActionRejection.PLAN_CHANGED.messageRes()
            return
        }
        uploading.value = true
        try {
            message.value = when (val result = documentRepository.upload(tripId, slot, path)) {
                // Demo: stored and queued. Real API: accepted by the server and waiting for the check.
                ActionResult.Queued -> if (appInfo.isDemoMode) R.string.documents_saved else R.string.documents_uploaded
                is ActionResult.Rejected -> result.reason.messageRes()
            }
        } finally {
            uploading.value = false
        }
    }

    private fun open(tripId: String, documentId: String, contentTypeHint: String?) {
        singleFlight.launch(viewModelScope) {
            when (val result = documentRepository.openDocument(tripId, documentId, contentTypeHint)) {
                is DocumentContentResult.Available -> openRequest.value = result
                is DocumentContentResult.Unavailable -> message.value = result.reason.messageRes()
            }
        }
    }

    private data class PendingScan(val tripId: String, val slotKey: String)

    /** Survives process death while the scanner is open. */
    private fun rememberPendingScan(tripId: String, slotKey: String) {
        savedStateHandle[KEY_SCAN_TRIP] = tripId
        savedStateHandle[KEY_SCAN_SLOT] = slotKey
    }

    private fun takePendingScan(): PendingScan? {
        val tripId = savedStateHandle.get<String>(KEY_SCAN_TRIP)
        val slotKey = savedStateHandle.get<String>(KEY_SCAN_SLOT)
        savedStateHandle.remove<String>(KEY_SCAN_TRIP)
        savedStateHandle.remove<String>(KEY_SCAN_SLOT)
        return if (tripId != null && slotKey != null) PendingScan(tripId, slotKey) else null
    }

    private companion object {
        const val KEY_SELECTED_SLOT = "selected_slot"
        const val KEY_SCAN_TRIP = "scan_trip"
        const val KEY_SCAN_SLOT = "scan_slot"
    }
}
