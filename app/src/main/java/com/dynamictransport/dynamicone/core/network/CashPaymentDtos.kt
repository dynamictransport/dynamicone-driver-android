@file:UseSerializers(BigDecimalSerializer::class)

package com.dynamictransport.dynamicone.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import java.math.BigDecimal

/** Employee cash payment wire model (docs/28_EMPLOYEE_CASH_PAYMENTS.md). List items and the detail share one shape. */
@Serializable
data class CashPaymentDto(
    val id: String? = null,
    val employeeId: String? = null,
    val employeeName: String? = null,
    val registrationNumber: String? = null,
    val kind: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val plannedPaymentDate: String? = null,
    val state: String? = null,
    val approvedAtUtc: String? = null,
    val receivedAtUtc: String? = null,
    val title: String? = null,
    /** The approved form's lines; the contract does not fix their shape, so they are read tolerantly. */
    val lines: List<JsonElement>? = null,
    val declaration: String? = null,
    val approvedByName: String? = null,
    val formHash: String? = null,
    val canSign: Boolean? = null,
    val receiptUrl: String? = null,
) {
    // Names and amounts are financial data: keep them out of logs.
    override fun toString(): String = "CashPaymentDto(id=$id, state=$state)"
}

@Serializable
data class CashSignatureRequestDto(
    val commandId: String,
    val formHash: String,
    val confirmed: Boolean,
    val aspectRatio: Float,
    val strokes: List<List<HrSignaturePointDto>>,
) {
    // The drawn signature is personal evidence: keep it out of logs.
    override fun toString(): String = "CashSignatureRequestDto(commandId=$commandId)"
}

/** Wire names of the cash payment contract (case-sensitive text). */
object CashPaymentWireNames {
    const val STATE_APPROVED = "Approved"
    const val STATE_RECEIVED = "Received"
    const val STATE_CANCELLED = "Cancelled"
    const val KIND_ADVANCE = "advance"
    const val KIND_SALARY = "salary"
    const val KIND_OVERTIME = "overtime"
}

/** Problem `code` values of the cash payment endpoints (docs/28). */
object CashPaymentErrorCodes {
    const val DELIVERY_REQUIRED = "cash_delivery_required"
    const val ACCESS_REQUIRED = "cash_access_required"
    const val PAYMENT_NOT_FOUND = "cash_payment_not_found"
    const val RECEIPT_NOT_FOUND = "cash_receipt_not_found"
    const val INVALID_FILTER = "invalid_cash_filter"
    const val INVALID_SIGNATURE = "invalid_cash_signature"
    const val FORM_CHANGED = "cash_form_changed"
    const val EMPLOYEE_INACTIVE = "cash_employee_inactive"
    const val PAYMENT_CANCELLED = "cash_payment_cancelled"
    const val ALREADY_RECEIVED = "cash_payment_already_received"
    const val PAYMENT_CONFLICT = "cash_payment_conflict"
    const val RECEIPT_STORAGE_UNAVAILABLE = "cash_receipt_storage_unavailable"
}
