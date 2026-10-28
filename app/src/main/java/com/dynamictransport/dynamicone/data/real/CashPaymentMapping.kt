package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.network.CashPaymentDto
import com.dynamictransport.dynamicone.core.network.CashPaymentErrorCodes
import com.dynamictransport.dynamicone.core.network.CashPaymentWireNames
import com.dynamictransport.dynamicone.core.network.DriverErrorCodes
import com.dynamictransport.dynamicone.core.network.HrSignaturePointDto
import com.dynamictransport.dynamicone.core.network.MobileErrorCodes
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.CashPayment
import com.dynamictransport.dynamicone.domain.model.CashPaymentKind
import com.dynamictransport.dynamicone.domain.model.CashPaymentState
import com.dynamictransport.dynamicone.domain.model.CashSignFailure
import com.dynamictransport.dynamicone.domain.model.SignaturePoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/** What the server's answer to a cash signature means for the one on the phone (docs/28, ASSUMPTIONS P7). */
sealed interface CashSignatureOutcome {
    /** A final rejection: a new signature is needed; [reread] says whether the form has to be read again first. */
    data class Rejected(val failure: CashSignFailure, val reread: Boolean = false) : CashSignatureOutcome

    /** Not processed or unknown: the same command id is sent again after [delaySeconds]. */
    data class Retry(val delaySeconds: Long = 0L) : CashSignatureOutcome

    /** Handed over before (another device or command): the current state is read again. */
    data object AlreadyReceived : CashSignatureOutcome

    /** Finance cancelled the approval. */
    data object Cancelled : CashSignatureOutcome

    /** The account may not deliver (any more): the signature is not sent again. */
    data object AccessRequired : CashSignatureOutcome

    /** The payment is not available to this account (any more). */
    data object Missing : CashSignatureOutcome
}

/** Mobile API cash payment wire models → domain (docs/28_EMPLOYEE_CASH_PAYMENTS.md). */
object CashPaymentMapping {

    private val strokeSerializer = ListSerializer(ListSerializer(HrSignaturePointDto.serializer()))

    /** A payment without id, name, amount or currency cannot be shown or signed and is skipped. */
    fun payment(dto: CashPaymentDto): CashPayment? {
        val id = dto.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val amount = dto.amount?.takeIf { it.signum() > 0 } ?: return null
        val currency = dto.currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        val kindName = dto.kind?.trim()?.takeIf { it.isNotEmpty() }
        return CashPayment(
            id = id,
            employeeId = dto.employeeId?.trim()?.takeIf { it.isNotEmpty() },
            employeeName = dto.employeeName?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
            registrationNumber = dto.registrationNumber?.trim()?.takeIf { it.isNotEmpty() },
            kind = when (kindName?.lowercase()) {
                CashPaymentWireNames.KIND_ADVANCE -> CashPaymentKind.ADVANCE
                CashPaymentWireNames.KIND_SALARY -> CashPaymentKind.SALARY
                CashPaymentWireNames.KIND_OVERTIME -> CashPaymentKind.OVERTIME
                else -> CashPaymentKind.UNKNOWN
            },
            kindName = kindName,
            amount = amount,
            currency = currency,
            plannedPaymentDate = dto.plannedPaymentDate?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            state = state(dto.state),
            approvedAt = dto.approvedAtUtc?.toInstantOrNull(),
            receivedAt = dto.receivedAtUtc?.toInstantOrNull(),
            title = dto.title?.trim()?.takeIf { it.isNotEmpty() },
            lines = dto.lines.orEmpty().mapNotNull(::line),
            declaration = dto.declaration?.trim()?.takeIf { it.isNotEmpty() },
            approvedByName = dto.approvedByName?.trim()?.takeIf { it.isNotEmpty() },
            formHash = dto.formHash?.trim()?.takeIf { it.isNotEmpty() },
            canSign = dto.canSign == true,
            receiptAvailable = !dto.receiptUrl.isNullOrBlank(),
        )
    }

    fun state(wire: String?): CashPaymentState = when (wire) {
        CashPaymentWireNames.STATE_APPROVED -> CashPaymentState.APPROVED
        CashPaymentWireNames.STATE_RECEIVED -> CashPaymentState.RECEIVED
        CashPaymentWireNames.STATE_CANCELLED -> CashPaymentState.CANCELLED
        else -> CashPaymentState.UNKNOWN
    }

    /**
     * One line of the approved form, shown as the server wrote it. The contract does not fix the element type: a string
     * is taken as is; an object becomes `label: value` (or its primitive fields in order); an empty line is dropped.
     */
    fun line(element: JsonElement): String? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> element.content.trim().takeIf { it.isNotEmpty() }
        is JsonArray -> element.mapNotNull(::line).joinToString(" ").takeIf { it.isNotEmpty() }
        is JsonObject -> {
            val label = element["label"]?.let(::line)
            val value = (element["value"] ?: element["text"])?.let(::line)
            when {
                label != null && value != null -> "$label: $value"
                label != null || value != null -> label ?: value
                else -> element.values.mapNotNull(::line).joinToString(" ").takeIf { it.isNotEmpty() }
            }
        }
    }

    fun encodeStrokes(json: Json, strokes: List<List<SignaturePoint>>): String =
        json.encodeToString(strokeSerializer, strokes.map { stroke -> stroke.map { HrSignaturePointDto(it.x, it.y) } })

    fun decodeStrokes(json: Json, value: String): List<List<HrSignaturePointDto>> =
        runCatching { json.decodeFromString(strokeSerializer, value) }.getOrDefault(emptyList())

    fun signatureOutcome(e: RemoteException): CashSignatureOutcome = when (e) {
        is RemoteException.Validation -> CashSignatureOutcome.Rejected(
            if (e.problem?.code == CashPaymentErrorCodes.INVALID_SIGNATURE) CashSignFailure.SIGNATURE_INVALID else CashSignFailure.REJECTED,
        )
        is RemoteException.Conflict -> when (e.problem?.code) {
            CashPaymentErrorCodes.ALREADY_RECEIVED -> CashSignatureOutcome.AlreadyReceived
            CashPaymentErrorCodes.PAYMENT_CANCELLED -> CashSignatureOutcome.Cancelled
            CashPaymentErrorCodes.FORM_CHANGED -> CashSignatureOutcome.Rejected(CashSignFailure.FORM_CHANGED, reread = true)
            CashPaymentErrorCodes.EMPLOYEE_INACTIVE -> CashSignatureOutcome.Rejected(CashSignFailure.EMPLOYEE_INACTIVE)
            MobileErrorCodes.COMMAND_CONFLICT -> CashSignatureOutcome.Rejected(CashSignFailure.CONFLICT)
            // `cash_payment_conflict` and anything else: the real state is read again before signing again.
            else -> CashSignatureOutcome.Rejected(CashSignFailure.CONFLICT, reread = true)
        }
        // A 404 without the contract's code is a server without these endpoints: keep the signature and try later.
        is RemoteException.NotFound -> if (e.problem?.code == CashPaymentErrorCodes.PAYMENT_NOT_FOUND) {
            CashSignatureOutcome.Missing
        } else {
            CashSignatureOutcome.Retry()
        }
        // A password change comes first and is not a lost permission.
        is RemoteException.Forbidden -> if (e.problem?.code == DriverErrorCodes.PASSWORD_CHANGE_REQUIRED) {
            CashSignatureOutcome.Retry()
        } else {
            CashSignatureOutcome.AccessRequired
        }
        is RemoteException.RateLimited -> CashSignatureOutcome.Retry(delaySeconds = e.retryAfterSeconds ?: 0L)
        is RemoteException.Rejected, is RemoteException.PayloadTooLarge -> CashSignatureOutcome.Rejected(CashSignFailure.REJECTED)
        // Offline, 5xx, unreadable response, 401 (session): the outcome is unknown, the same command id goes again.
        else -> CashSignatureOutcome.Retry()
    }
}
