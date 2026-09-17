package com.dynamictransport.driver.domain.model

import java.time.Instant

/**
 * Operational trip document categories. [CUSTOMS] is the transport customs document the business calls
 * "A1" (not the personnel social-security A1 certificate).
 */
enum class DocumentType {
    CMR,
    CUSTOMS,
    INTERCHANGE,
    CONTAINER_PHOTO,
    SEAL_PHOTO,
    OTHER,
    ;

    /** Container and seal evidence must be a JPEG or PNG photo; the other categories also accept PDF. */
    val photoOnly: Boolean get() = this == CONTAINER_PHOTO || this == SEAL_PHOTO

    companion object {
        fun fromName(value: String?): DocumentType? = entries.firstOrNull { it.name == value }
    }
}

/** Review state of a document slot, as calculated by the backend. */
enum class DocumentSlotState {
    MISSING,
    NOT_REQUIRED,

    /** Uploaded, not yet checked. An upload is never shown as approved. */
    PENDING_REVIEW,
    APPROVED,
    REJECTED,

    /** A state this app version does not know. */
    UNKNOWN,
}

/** One separately addressable evidence slot of a trip (`document-requirements`). */
data class DocumentSlot(
    /** Opaque backend key; sent back unchanged and never built on the device. */
    val key: String,
    /** Null for a category this app version does not know. */
    val type: DocumentType?,
    /** Backend English display fallback. */
    val label: String?,
    val stopId: String?,
    val containerId: String?,
    /** Whether the backend requires the evidence; null when the source does not say. */
    val required: Boolean?,
    val state: DocumentSlotState,
    /** The active document of the slot. Older uploads stay in the file list. */
    val documentId: String?,
    val fileName: String?,
    val reviewNote: String?,
    val revision: String?,
    /** Demo only: state of the newest photo in the local upload queue. */
    val localSync: SyncState? = null,
) {
    /** Needs the driver's attention: required and missing, or rejected. */
    val needsUpload: Boolean
        get() = state == DocumentSlotState.REJECTED || (state == DocumentSlotState.MISSING && required == true)
}

/** Metadata of an uploaded trip file (`GET trips/{tripId}/documents`). */
data class TripDocument(
    val id: String,
    val tripId: String,
    val type: DocumentType?,
    val fileName: String?,
    val contentType: String?,
    val submittedAt: Instant?,
)

sealed interface DocumentContentResult {
    data class Available(val filePath: String, val contentType: String) : DocumentContentResult
    data class Unavailable(val reason: ActionRejection) : DocumentContentResult
}

/** Demo backend: server-side document status. */
enum class RemoteDocumentStatus {
    PENDING,
    UPLOADED,
    REJECTED,
    VERIFIED,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(value: String?): RemoteDocumentStatus = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** Demo backend: a stored document. */
data class RemoteDocument(
    val id: String,
    val type: DocumentType,
    val status: RemoteDocumentStatus,
    val capturedAt: Instant?,
    val thumbnailUrl: String?,
)

/** Demo backend: metadata captured with every document photo (CLAUDE.md §6). */
data class DocumentUploadMetadata(
    val clientActionId: String,
    val tripId: String,
    val type: DocumentType,
    val fileName: String,
    val contentType: String,
    val capturedAt: Instant,
    val containerNo: String?,
    val sealNo: String?,
)
