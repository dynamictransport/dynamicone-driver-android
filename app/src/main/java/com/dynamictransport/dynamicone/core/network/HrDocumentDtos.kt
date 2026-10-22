package com.dynamictransport.dynamicone.core.network

import kotlinx.serialization.Serializable

/** Personnel document signing wire model (docs/17_HR_DOCUMENT_SIGNING.md §4, §5). */
@Serializable
data class HrDocumentLanguageDto(
    val languageCode: String? = null,
    val title: String? = null,
    val declaration: String? = null,
    val contentSha256: String? = null,
    val sizeBytes: Long? = null,
    val pageCount: Int? = null,
)

@Serializable
data class HrDocumentDto(
    val id: String? = null,
    val version: String? = null,
    val referenceLanguageCode: String? = null,
    val sentAtUtc: String? = null,
    val dueAtUtc: String? = null,
    val status: String? = null,
    val firstOpenedAtUtc: String? = null,
    val signedAtUtc: String? = null,
    val signedLanguageCode: String? = null,
    val receiptAvailable: Boolean? = null,
    val languages: List<HrDocumentLanguageDto>? = null,
)

@Serializable
data class HrSignaturePointDto(val x: Float, val y: Float)

@Serializable
data class HrSignatureDrawingDto(
    val aspectRatio: Float,
    val strokes: List<List<HrSignaturePointDto>>,
)

@Serializable
data class HrSignatureRequestDto(
    val commandId: String,
    val languageCode: String,
    val contentSha256: String,
    val declarationAccepted: Boolean,
    val signature: HrSignatureDrawingDto,
    /** Phone clock when it was signed. */
    val signedAtUtc: String,
    /** Monotonic time between the signature and this request; null when the phone restarted in between. */
    val elapsedSinceSignMs: Long?,
    val installationId: String,
) {
    // The drawn signature is personal evidence: keep it out of logs.
    override fun toString(): String = "HrSignatureRequestDto(commandId=$commandId, languageCode=$languageCode)"
}

@Serializable
data class HrSignatureResultDto(
    val document: HrDocumentDto? = null,
)

/** Wire names of the signing contract (case-sensitive text, never numbers). */
object HrDocumentWireNames {
    const val STATE_PENDING = "Pending"
    const val STATE_SIGNED = "Signed"
    const val STATUS_PENDING = "Pending"
    const val STATUS_SIGNED = "Signed"
    const val STATUS_WITHDRAWN = "Withdrawn"
    const val REASON_ASSIGNED = "Assigned"
    const val REASON_REMINDER = "Reminder"
    const val REASON_OVERDUE = "Overdue"
    const val REASON_WITHDRAWN = "Withdrawn"
}

/** Problem `code` values of the signing endpoints (docs/17 §5). */
object HrDocumentErrorCodes {
    const val ACCESS_REQUIRED = "hr_documents_access_required"
    const val NOT_FOUND = "hr_document_not_found"
    const val WITHDRAWN = "hr_document_withdrawn"
    const val ALREADY_SIGNED = "hr_document_already_signed"
    const val CHANGED = "hr_document_changed"
    const val LANGUAGE_UNAVAILABLE = "hr_language_unavailable"
    const val DECLARATION_REQUIRED = "hr_declaration_required"
    const val SIGNATURE_INVALID = "hr_signature_invalid"
    const val RECEIPT_NOT_AVAILABLE = "hr_receipt_not_available"
}
