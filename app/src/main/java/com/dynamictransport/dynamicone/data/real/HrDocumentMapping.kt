package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.network.DriverErrorCodes
import com.dynamictransport.dynamicone.core.network.HrDocumentDto
import com.dynamictransport.dynamicone.core.network.HrDocumentErrorCodes
import com.dynamictransport.dynamicone.core.network.HrDocumentWireNames
import com.dynamictransport.dynamicone.core.network.HrSignaturePointDto
import com.dynamictransport.dynamicone.core.network.MobileErrorCodes
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.HrContentProblem
import com.dynamictransport.dynamicone.domain.model.HrDocument
import com.dynamictransport.dynamicone.domain.model.HrDocumentLanguage
import com.dynamictransport.dynamicone.domain.model.HrDocumentStatus
import com.dynamictransport.dynamicone.domain.model.SignatureFailure
import com.dynamictransport.dynamicone.domain.model.SignaturePoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What the server's answer to a signature means for the one on the phone (docs/17 §5.4). */
sealed interface SignatureOutcome {
    /** A final rejection: the employee signs again. */
    data class Rejected(val failure: SignatureFailure) : SignatureOutcome

    /** Not processed or unknown: the same command id is sent again; [hold] is the reason when the server answered (403). */
    data class Retry(val hold: SignatureFailure? = null, val delaySeconds: Long = 0L) : SignatureOutcome

    /** Signed before with another command id. */
    data object AlreadySigned : SignatureOutcome

    /** The office withdrew the document. */
    data object Withdrawn : SignatureOutcome
}

/** Mobile API signing wire models → domain (docs/17_HR_DOCUMENT_SIGNING.md). */
object HrDocumentMapping {

    private val strokeSerializer = ListSerializer(ListSerializer(HrSignaturePointDto.serializer()))
    private val itemsSerializer = ListSerializer(HrDocumentDto.serializer())
    private val sha256 = Regex("^[0-9A-F]{64}$")

    /** A document without id or a usable language version cannot be shown or signed and is skipped. */
    fun document(dto: HrDocumentDto): HrDocument? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        val languages = dto.languages.orEmpty().mapNotNull { language ->
            val code = language.languageCode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            HrDocumentLanguage(
                code = code,
                title = language.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                declaration = language.declaration?.trim()?.takeIf { it.isNotEmpty() },
                contentSha256 = language.contentSha256?.trim()?.uppercase()?.takeIf { sha256.matches(it) } ?: return@mapNotNull null,
                sizeBytes = language.sizeBytes?.takeIf { it > 0 },
                pageCount = language.pageCount?.takeIf { it > 0 },
            )
        }.distinctBy { it.code }
        if (languages.isEmpty()) return null
        return HrDocument(
            id = id,
            version = dto.version?.trim()?.takeIf { it.isNotEmpty() },
            referenceLanguage = dto.referenceLanguageCode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            sentAt = dto.sentAtUtc?.toInstantOrNull(),
            dueAt = dto.dueAtUtc?.toInstantOrNull(),
            status = when (dto.status) {
                HrDocumentWireNames.STATUS_PENDING -> HrDocumentStatus.PENDING
                HrDocumentWireNames.STATUS_SIGNED -> HrDocumentStatus.SIGNED
                HrDocumentWireNames.STATUS_WITHDRAWN -> HrDocumentStatus.WITHDRAWN
                else -> HrDocumentStatus.UNKNOWN
            },
            firstOpenedAt = dto.firstOpenedAtUtc?.toInstantOrNull(),
            signedAt = dto.signedAtUtc?.toInstantOrNull(),
            signedLanguage = dto.signedLanguageCode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            receiptAvailable = dto.receiptAvailable == true,
            languages = languages,
        )
    }

    fun encodeItems(json: Json, items: List<HrDocumentDto>): String = json.encodeToString(itemsSerializer, items)

    fun decodeItems(json: Json, value: String): List<HrDocumentDto> =
        runCatching { json.decodeFromString(itemsSerializer, value) }.getOrDefault(emptyList())

    fun encodeStrokes(json: Json, strokes: List<List<SignaturePoint>>): String =
        json.encodeToString(strokeSerializer, strokes.map { stroke -> stroke.map { HrSignaturePointDto(it.x, it.y) } })

    fun decodeStrokes(json: Json, value: String): List<List<HrSignaturePointDto>> =
        runCatching { json.decodeFromString(strokeSerializer, value) }.getOrDefault(emptyList())

    fun signatureOutcome(e: RemoteException): SignatureOutcome = when (e) {
        is RemoteException.Validation -> SignatureOutcome.Rejected(
            when (e.problem?.code) {
                HrDocumentErrorCodes.SIGNATURE_INVALID -> SignatureFailure.SIGNATURE_INVALID
                HrDocumentErrorCodes.LANGUAGE_UNAVAILABLE -> SignatureFailure.LANGUAGE_UNAVAILABLE
                else -> SignatureFailure.REJECTED
            },
        )
        is RemoteException.Conflict -> when (e.problem?.code) {
            HrDocumentErrorCodes.WITHDRAWN -> SignatureOutcome.Withdrawn
            HrDocumentErrorCodes.ALREADY_SIGNED -> SignatureOutcome.AlreadySigned
            HrDocumentErrorCodes.CHANGED -> SignatureOutcome.Rejected(SignatureFailure.DOCUMENT_CHANGED)
            MobileErrorCodes.COMMAND_CONFLICT -> SignatureOutcome.Rejected(SignatureFailure.CONFLICT)
            else -> SignatureOutcome.Rejected(SignatureFailure.CONFLICT)
        }
        // A 404 without the contract's code is a server without these endpoints (not live yet): keep and try later.
        is RemoteException.NotFound -> if (e.problem?.code == HrDocumentErrorCodes.NOT_FOUND) {
            SignatureOutcome.Rejected(SignatureFailure.NOT_FOUND)
        } else {
            SignatureOutcome.Retry()
        }
        // `403 hr_documents_access_required` means "not sent", not "rejected"; a password change comes first.
        is RemoteException.Forbidden -> SignatureOutcome.Retry(
            hold = if (e.problem?.code == DriverErrorCodes.PASSWORD_CHANGE_REQUIRED) null else SignatureFailure.ACCESS_REQUIRED,
        )
        is RemoteException.RateLimited -> SignatureOutcome.Retry(delaySeconds = e.retryAfterSeconds ?: 0L)
        is RemoteException.Rejected, is RemoteException.PayloadTooLarge -> SignatureOutcome.Rejected(SignatureFailure.REJECTED)
        // Offline, 5xx, unreadable response, 401 (session): the outcome is unknown, the same command id goes again.
        else -> SignatureOutcome.Retry()
    }

    fun contentProblem(e: RemoteException): HrContentProblem = when (e) {
        is RemoteException.Offline -> HrContentProblem.OFFLINE
        is RemoteException.Conflict -> if (e.problem?.code == HrDocumentErrorCodes.WITHDRAWN) HrContentProblem.WITHDRAWN else HrContentProblem.FAILED
        is RemoteException.NotFound -> when {
            e.isApiDisabled -> HrContentProblem.SERVICE_UNAVAILABLE
            e.problem?.code == HrDocumentErrorCodes.NOT_FOUND -> HrContentProblem.NOT_FOUND
            else -> HrContentProblem.FAILED
        }
        is RemoteException.Unauthorized -> HrContentProblem.SESSION_EXPIRED
        is RemoteException.RateLimited -> HrContentProblem.RATE_LIMITED
        is RemoteException.ServerUnavailable -> HrContentProblem.SERVICE_UNAVAILABLE
        else -> HrContentProblem.FAILED
    }
}
