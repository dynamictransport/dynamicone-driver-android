package com.dynamictransport.dynamicone.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * Monthly pay information published by HR (`GET me/payroll`). The app only displays it: amounts are
 * never recalculated, re-signed, summed across currencies or replaced by zero when absent.
 */
sealed interface MonthlyPayroll {
    val month: YearMonth

    /** HR has not published this month: nothing is shown, not even zero amounts. */
    data class Unpublished(override val month: YearMonth) : MonthlyPayroll

    /** The latest immutable snapshot HR published for the month. */
    data class Published(
        override val month: YearMonth,
        val publishedAt: Instant?,
        val currencies: List<PayrollCurrency>,
        val finalized: FinalizedMonthlyPay?,
    ) : MonthlyPayroll
}

/** One currency of the compensation ledger. Currencies are never added together. */
data class PayrollCurrency(
    val currencyCode: String,
    val baseGross: BigDecimal?,
    val additions: List<PayrollLine>,
    val deductions: List<PayrollLine>,
    val grossEarnings: BigDecimal?,
    val totalDeductions: BigDecimal?,
    /** Not net pay and not the amount paid to the bank. */
    val balanceBeforeStatutoryDeductions: BigDecimal?,
)

/** A typed, dated line in the category (addition or deduction) the server put it in. */
data class PayrollLine(
    /** Backend type name, e.g. `Bonus`; unknown names are shown as they are. */
    val type: String,
    val amount: BigDecimal?,
    val effectiveFrom: LocalDate?,
)

/** Separately recorded finalized monthly amounts; never added to the currency totals. */
data class FinalizedMonthlyPay(
    val baseGross: BigDecimal?,
    val overtimeGross: BigDecimal?,
    val overtimeHours: BigDecimal?,
    val currency: String?,
)
