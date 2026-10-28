package com.dynamictransport.dynamicone.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Cash paid to personnel against a form finance approved in the web (docs/28_EMPLOYEE_CASH_PAYMENTS.md). The app never
 * creates a payment or changes an amount: it shows the approved form and records the recipient's finger signature.
 */
enum class CashPaymentScope(val wireName: String) {
    /** The officer who hands over the money: every approval the account may deliver (`/cash-payments`). */
    DISBURSEMENTS("disbursements"),

    /** The recipient's own approvals and receipts (`/me/cash-payments`). */
    OWN("own"),
    ;

    companion object {
        fun fromWire(name: String?): CashPaymentScope? = entries.firstOrNull { it.wireName == name || it.name == name }
    }
}

enum class CashPaymentKind {
    ADVANCE,
    SALARY,
    OVERTIME,
    UNKNOWN,
}

enum class CashPaymentState(val wireName: String) {
    /** Approved by finance, waiting to be handed over. */
    APPROVED("Approved"),

    /** Signed by the recipient; the receipt exists. */
    RECEIVED("Received"),
    CANCELLED("Cancelled"),
    UNKNOWN(""),
}

/** One approved payment as the server shows it; [title], [lines] and [declaration] are shown exactly as sent. */
data class CashPayment(
    val id: String,
    val employeeId: String?,
    val employeeName: String,
    val registrationNumber: String?,
    val kind: CashPaymentKind,
    /** The wire value of [kind], shown when the app does not know it. */
    val kindName: String?,
    val amount: BigDecimal,
    /** ISO code (`EUR`, `PLN`); never converted. */
    val currency: String,
    val plannedPaymentDate: LocalDate?,
    val state: CashPaymentState,
    val approvedAt: Instant?,
    /** Official hand-over time from the server; null before it. */
    val receivedAt: Instant?,
    val title: String?,
    val lines: List<String>,
    val declaration: String?,
    val approvedByName: String?,
    /** SHA-256 of the approved form, sent back unchanged with the signature. */
    val formHash: String?,
    /** The server's current answer; it checks again when the signature arrives. */
    val canSign: Boolean,
    /** The signed receipt PDF exists (relative path on the server, downloaded through the contract endpoint). */
    val receiptAvailable: Boolean,
)

/** One page of a list as read from the server; [items] in the server's order (approval time, newest first). */
data class CashPaymentList(
    val items: List<CashPayment>,
    val hasMore: Boolean,
    val updatedAt: Instant,
)

/** Why the server did not record a signature; a final answer on a `FAILED` one. */
enum class CashSignFailure {
    /** Finance changed the form after it was shown: the new one has to be read and signed. */
    FORM_CHANGED,

    /** The drawn signature did not meet the server's rules. */
    SIGNATURE_INVALID,

    /** The recipient is not an active employee (any more). */
    EMPLOYEE_INACTIVE,

    /** This account may not deliver (any more) or may not see the payment. */
    ACCESS_REQUIRED,

    /** The payment is not available to this account (any more). */
    NOT_FOUND,

    /** Another command changed the payment first, or the command id was used for something else. */
    CONFLICT,
    REJECTED,
}

/** A signature stored on the phone that the server has not confirmed yet. Confirmed signatures are not kept. */
data class LocalCashSignature(
    val commandId: String,
    val scope: CashPaymentScope,
    val paymentId: String,
    val signedAt: Instant,
    val state: LocalSignatureState,
    val failure: CashSignFailure?,
)

sealed interface CashPaymentLookup {
    data object Loading : CashPaymentLookup

    data class Found(val payment: CashPayment, val local: LocalCashSignature?) : CashPaymentLookup

    /** Not available to this account (any more). */
    data object Missing : CashPaymentLookup
}

sealed interface CashSignResult {
    /** The server recorded the hand-over (201, or 200 for the same command again). */
    data class Signed(val payment: CashPayment) : CashSignResult

    /** Stored on the phone and sent again with the same command id as soon as the server can be reached. */
    data class Saved(val commandId: String) : CashSignResult

    /** The server rejected it; a new signature is needed. */
    data class Failed(val failure: CashSignFailure) : CashSignResult

    /** Already handed over (another device, an earlier command): the current state was read again. */
    data object AlreadyReceived : CashSignResult

    /** Finance cancelled the approval: nothing to sign. */
    data object Cancelled : CashSignResult

    /** `403`: this account may not deliver (any more); the signature is not sent again. */
    data object AccessRequired : CashSignResult

    /** `404`: the payment is not available to this account (any more). */
    data object Missing : CashSignResult

    /** The drawn signature is too small or the declaration was not confirmed; nothing stored. */
    data object Incomplete : CashSignResult

    data object NotLoggedIn : CashSignResult
}
