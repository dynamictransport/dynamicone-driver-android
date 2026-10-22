package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

/** Personnel documents the office sends to be signed in the app (docs/17_HR_DOCUMENT_SIGNING.md). */
enum class HrDocumentStatus {
    /** Waiting for the signature; the deadline may already have passed. */
    PENDING,
    SIGNED,

    /** The office withdrew it before it was signed. */
    WITHDRAWN,
    UNKNOWN,
}

/** One language version of a document: its PDF, title and the declaration the employee signs. */
data class HrDocumentLanguage(
    /** ISO 639-1 code in lower case; [MULTILINGUAL] for one PDF with several languages. */
    val code: String,
    val title: String,
    /** Text of the declaration shown above the signature; null when the PDF holds it only. */
    val declaration: String?,
    /** SHA-256 of the PDF bytes, upper-case hex. */
    val contentSha256: String,
    val sizeBytes: Long?,
    val pageCount: Int?,
) {
    companion object {
        const val MULTILINGUAL = "mul"
    }
}

/** A document sent to this employee (one assignment); [id] is the assignment id. */
data class HrDocument(
    val id: String,
    val version: String?,
    /** The version that applies when the translations differ; null when the office did not name one. */
    val referenceLanguage: String?,
    val sentAt: Instant?,
    /** Null: no deadline. */
    val dueAt: Instant?,
    val status: HrDocumentStatus,
    val firstOpenedAt: Instant?,
    /** Official signature time once signed. */
    val signedAt: Instant?,
    val signedLanguage: String?,
    /** The signed PDF can be downloaded. */
    val receiptAvailable: Boolean,
    /** At least one. */
    val languages: List<HrDocumentLanguage>,
) {
    fun language(code: String?): HrDocumentLanguage? = languages.firstOrNull { it.code == code }
}

/** A point of a finger stroke relative to the signature pad: [x] to the right and [y] down, both 0..1. */
data class SignaturePoint(val x: Float, val y: Float)

/** A signature drawn with the finger; [aspectRatio] is the pad's width divided by its height. */
data class DrawnSignature(
    val strokes: List<List<SignaturePoint>>,
    val aspectRatio: Float,
)

enum class LocalSignatureState {
    /**
     * Stored on the phone, waiting to be sent (no connection, server busy, session ended, access missing). A
     * [LocalSignature.failure] on a waiting signature is why the server did not take it yet, not a final answer.
     */
    PENDING,

    /** Being sent right now. */
    SENDING,

    /** Rejected by the server; the employee signs again. */
    FAILED,
}

/** Why the server did not take a signature: a final answer on a `FAILED` one (400/409), the hold of a `PENDING` one (403). */
enum class SignatureFailure {
    /** The content changed after it was shown: the new version has to be read and signed. */
    DOCUMENT_CHANGED,

    /** The drawn signature did not meet the server's rules. */
    SIGNATURE_INVALID,

    /** The chosen language version does not exist (any more). */
    LANGUAGE_UNAVAILABLE,

    /** The document is not assigned to this account (any more). */
    NOT_FOUND,

    /** 403: the account may not sign documents (any more); the signature waits and is sent again later. */
    ACCESS_REQUIRED,

    /** The same command id was used for a different signature. */
    CONFLICT,
    REJECTED,
}

/** A signature stored on the phone that the server has not confirmed yet. Confirmed signatures are not kept. */
data class LocalSignature(
    val commandId: String,
    val documentId: String,
    val languageCode: String,
    /** Phone clock when it was signed. */
    val signedAt: Instant,
    val state: LocalSignatureState,
    val failure: SignatureFailure?,
)

/** What the employee sees for one document; a signature on the phone is never shown as signed. */
enum class HrDocumentDisplayState {
    TO_SIGN,

    /** The deadline is today or tomorrow. */
    DUE_SOON,
    OVERDUE,

    /** Signed on the phone, not confirmed by the server yet. */
    WAITING_TO_SEND,

    /** The server rejected the signature: sign again. */
    SEND_FAILED,
    SIGNED,
    WITHDRAWN,
}

data class HrDocumentLists(
    /** Documents to sign, soonest deadline first. Null until read from the server or the phone. */
    val pending: List<HrDocument>?,
    /** Signed documents, newest first. Null until read. */
    val signed: List<HrDocument>?,
    val canLoadMoreSigned: Boolean,
    /** Signatures on the phone not confirmed by the server. */
    val localSignatures: List<LocalSignature>,
    /** Last successful read of the pending documents; null when they come from nowhere yet. */
    val updatedAt: Instant?,
) {
    fun localFor(documentId: String): LocalSignature? = localSignatures.lastOrNull { it.documentId == documentId }

    companion object {
        val EMPTY = HrDocumentLists(null, null, canLoadMoreSigned = false, localSignatures = emptyList(), updatedAt = null)
    }
}

/** One document for its screen. */
sealed interface HrDocumentLookup {
    data object Loading : HrDocumentLookup

    data class Found(val document: HrDocument, val local: LocalSignature?) : HrDocumentLookup

    /** Not assigned to this account (any more). */
    data object Missing : HrDocumentLookup
}

enum class HrContentProblem {
    OFFLINE,
    WITHDRAWN,
    NOT_FOUND,

    /** The downloaded bytes do not match the document's checksum. */
    INTEGRITY,
    LOW_STORAGE,
    SESSION_EXPIRED,
    SERVICE_UNAVAILABLE,
    RATE_LIMITED,
    FAILED,
}

sealed interface HrContentResult {
    /** A PDF on the phone whose bytes match [contentSha256]. */
    data class Available(val filePath: String, val languageCode: String, val contentSha256: String) : HrContentResult

    data class Unavailable(val problem: HrContentProblem) : HrContentResult
}

sealed interface HrSignResult {
    /** The server recorded the signature. */
    data class Signed(val document: HrDocument) : HrSignResult

    /**
     * Stored on the phone; sent automatically with the same command id. [hold] names why the server did not take it
     * yet when it answered at all (403).
     */
    data class Saved(val commandId: String, val hold: SignatureFailure? = null) : HrSignResult

    /** The server rejected it; the employee signs again. */
    data class Failed(val failure: SignatureFailure) : HrSignResult

    /** Signed before with another command id (another phone): nothing more to do. */
    data object AlreadySigned : HrSignResult

    /** The office withdrew the document: nothing to sign. */
    data object Withdrawn : HrSignResult

    /** The drawn signature is too small or the declaration was not accepted; nothing stored. */
    data object Incomplete : HrSignResult

    data object NotLoggedIn : HrSignResult
}
