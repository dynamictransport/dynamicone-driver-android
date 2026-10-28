package com.dynamictransport.dynamicone.feature.cashpayments

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.domain.model.CashPayment
import com.dynamictransport.dynamicone.domain.model.CashPaymentKind
import com.dynamictransport.dynamicone.domain.model.CashPaymentScope
import com.dynamictransport.dynamicone.domain.model.CashPaymentState
import com.dynamictransport.dynamicone.domain.model.CashSignFailure
import com.dynamictransport.dynamicone.domain.model.LocalCashSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.currentLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Hand-over and approval times with the year: a receipt stays visible across a new year. */
internal fun cashDateTime(instant: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).withZone(zone).format(instant)

/** The amount with its currency, never converted: `100,00 €` / `450,00 zł` in the phone's locale. */
@Composable
@ReadOnlyComposable
internal fun amountText(payment: CashPayment): String = DriverFormats.money(payment.amount, payment.currency, currentLocale())

@StringRes
internal fun CashPaymentScope.titleRes(): Int = when (this) {
    CashPaymentScope.DISBURSEMENTS -> R.string.cash_disbursements_title
    CashPaymentScope.OWN -> R.string.my_cash_payments_title
}

/** The payment type as a word; an unknown type is shown as the server sent it (never guessed). */
@Composable
@ReadOnlyComposable
internal fun kindText(payment: CashPayment): String = when (payment.kind) {
    CashPaymentKind.ADVANCE -> stringResource(R.string.cash_kind_advance)
    CashPaymentKind.SALARY -> stringResource(R.string.cash_kind_salary)
    CashPaymentKind.OVERTIME -> stringResource(R.string.cash_kind_overtime)
    CashPaymentKind.UNKNOWN -> payment.kindName ?: stringResource(R.string.value_unknown)
}

@StringRes
internal fun CashPaymentState.filterRes(): Int = when (this) {
    CashPaymentState.APPROVED -> R.string.cash_filter_approved
    CashPaymentState.RECEIVED -> R.string.cash_filter_received
    CashPaymentState.CANCELLED, CashPaymentState.UNKNOWN -> R.string.cash_filter_cancelled
}

/** The payment's state as icon, text and colour, never colour alone (CLAUDE.md §7); a waiting signature comes first. */
@Composable
internal fun CashPaymentStatusLine(payment: CashPayment, local: LocalCashSignature?, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    when {
        payment.state == CashPaymentState.RECEIVED -> StatusBanner(
            message = payment.receivedAt?.let { stringResource(R.string.cash_received_at, cashDateTime(it, locale)) }
                ?: stringResource(R.string.cash_filter_received),
            icon = Icons.Filled.CheckCircle,
            tone = StatusTone.Positive,
            modifier = modifier.testTag("cash_state_received"),
        )
        payment.state == CashPaymentState.CANCELLED ->
            StatusBanner(stringResource(R.string.cash_cancelled), Icons.Filled.Block, StatusTone.Neutral, modifier.testTag("cash_state_cancelled"))
        local?.state == LocalSignatureState.PENDING || local?.state == LocalSignatureState.SENDING -> StatusBanner(
            message = stringResource(R.string.cash_state_waiting),
            icon = Icons.Filled.CloudUpload,
            tone = StatusTone.Warning,
            modifier = modifier.testTag("cash_state_waiting"),
        )
        local?.state == LocalSignatureState.FAILED -> StatusBanner(
            message = stringResource(local.failure?.messageRes() ?: R.string.cash_failure_rejected),
            icon = Icons.Filled.ErrorOutline,
            tone = StatusTone.Danger,
            modifier = modifier.testTag("cash_state_failed"),
        )
        payment.state == CashPaymentState.APPROVED -> StatusBanner(
            message = payment.plannedPaymentDate?.let { stringResource(R.string.cash_planned_for, DriverFormats.date(it, locale)) }
                ?: stringResource(R.string.cash_filter_approved),
            icon = Icons.Filled.Schedule,
            tone = StatusTone.Info,
            modifier = modifier.testTag("cash_state_approved"),
        )
        else -> StatusBanner(stringResource(R.string.value_unknown), Icons.Filled.ErrorOutline, StatusTone.Neutral, modifier.testTag("cash_state_unknown"))
    }
}

@StringRes
internal fun CashSignFailure.messageRes(): Int = when (this) {
    CashSignFailure.FORM_CHANGED -> R.string.cash_failure_form_changed
    CashSignFailure.SIGNATURE_INVALID -> R.string.cash_failure_signature
    CashSignFailure.EMPLOYEE_INACTIVE -> R.string.cash_failure_employee_inactive
    CashSignFailure.ACCESS_REQUIRED -> R.string.cash_access_removed
    CashSignFailure.NOT_FOUND -> R.string.cash_missing
    CashSignFailure.CONFLICT -> R.string.cash_failure_conflict
    CashSignFailure.REJECTED -> R.string.cash_failure_rejected
}
