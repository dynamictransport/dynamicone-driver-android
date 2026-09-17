package com.dynamictransport.driver.feature.payroll

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.driver.R
import com.dynamictransport.driver.core.design.component.DriverTopBar
import com.dynamictransport.driver.core.design.component.EmptyState
import com.dynamictransport.driver.core.design.component.SecondaryActionButton
import com.dynamictransport.driver.core.design.component.SectionCard
import com.dynamictransport.driver.core.design.component.StatusBanner
import com.dynamictransport.driver.core.design.component.StatusTone
import com.dynamictransport.driver.core.design.theme.Dimens
import com.dynamictransport.driver.core.design.theme.DriverTheme
import com.dynamictransport.driver.domain.model.FinalizedMonthlyPay
import com.dynamictransport.driver.domain.model.MonthlyPayroll
import com.dynamictransport.driver.domain.model.PayrollCurrency
import com.dynamictransport.driver.domain.model.PayrollLine
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.repository.AppInfo
import com.dynamictransport.driver.domain.repository.PayrollRepository
import com.dynamictransport.driver.feature.common.DriverFormats
import com.dynamictransport.driver.feature.common.LabeledDivider
import com.dynamictransport.driver.feature.common.currentLocale
import com.dynamictransport.driver.feature.common.messageRes
import com.dynamictransport.driver.feature.common.payrollTypeLabelRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

data class PayrollUiState(
    val month: YearMonth,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    /** Null until the month was read. */
    val payroll: MonthlyPayroll? = null,
    val refreshing: Boolean = false,
    @param:StringRes val loadError: Int? = null,
    /** The last read failed; the previously read snapshot is shown. */
    val stale: Boolean = false,
    /** The connected source has no payroll (demo). */
    val unavailable: Boolean = false,
    val isDemoMode: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PayrollViewModel @Inject constructor(
    private val payrollRepository: PayrollRepository,
    clock: Clock,
    private val appInfo: AppInfo,
) : ViewModel() {

    private val latestMonth: YearMonth = YearMonth.from(clock.instant().atZone(ZoneId.systemDefault()))
    private val month = MutableStateFlow(latestMonth)
    private val refreshing = MutableStateFlow(false)
    private val results = MutableStateFlow<Map<YearMonth, RefreshResult>>(emptyMap())

    private val payroll = month.flatMapLatest { selected -> payrollRepository.observePayroll(selected).map { selected to it } }

    val uiState: StateFlow<PayrollUiState> = combine(payroll, refreshing, results) { (selected, data), busy, all ->
        val result = all[selected]
        val failed = result?.takeIf { it != RefreshResult.SUCCESS && it != RefreshResult.NOT_SUPPORTED }
        PayrollUiState(
            month = selected,
            canGoBack = selected > EARLIEST_MONTH,
            canGoForward = selected < latestMonth,
            payroll = data,
            refreshing = busy,
            loadError = failed?.takeIf { data == null }?.messageRes(),
            stale = failed != null && data != null,
            unavailable = result == RefreshResult.NOT_SUPPORTED,
            isDemoMode = appInfo.isDemoMode,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PayrollUiState(month = latestMonth, canGoBack = true, canGoForward = false, isDemoMode = appInfo.isDemoMode),
    )

    init {
        refresh()
    }

    fun onPreviousMonth() {
        if (month.value <= EARLIEST_MONTH) return
        month.value = month.value.minusMonths(1)
        refresh()
    }

    fun onNextMonth() {
        if (month.value >= latestMonth) return
        month.value = month.value.plusMonths(1)
        refresh()
    }

    fun refresh() {
        val selected = month.value
        viewModelScope.launch {
            refreshing.value = true
            val result = payrollRepository.refresh(selected)
            results.update { it + (selected to result) }
            refreshing.value = false
        }
    }

    private companion object {
        /** The Driver API accepts years 2000–2100. */
        val EARLIEST_MONTH: YearMonth = YearMonth.of(2000, 1)
    }
}

@Composable
fun PayrollRoute(onBack: () -> Unit, viewModel: PayrollViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PayrollScreen(
        state = state,
        onBack = onBack,
        onPreviousMonth = viewModel::onPreviousMonth,
        onNextMonth = viewModel::onNextMonth,
        onRefresh = viewModel::refresh,
    )
}

@Composable
fun PayrollScreen(
    state: PayrollUiState,
    onBack: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onRefresh: () -> Unit,
) {
    val locale = currentLocale()
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.payroll_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
                actions = {
                    if (!state.unavailable) {
                        IconButton(onClick = onRefresh, enabled = !state.refreshing, modifier = Modifier.size(56.dp)) {
                            if (state.refreshing) {
                                CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        ) {
            MonthSelector(state, locale, onPreviousMonth, onNextMonth)
            if (state.stale) {
                StatusBanner(
                    message = stringResource(R.string.data_stale),
                    icon = Icons.Filled.SyncProblem,
                    tone = StatusTone.Warning,
                    actionLabel = if (state.refreshing) null else stringResource(R.string.common_retry),
                    onAction = onRefresh,
                    modifier = Modifier.testTag("payroll_stale"),
                )
            }
            val payroll = state.payroll
            val loadError = state.loadError
            when {
                state.unavailable -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(if (state.isDemoMode) R.string.feature_demo_unavailable else R.string.feature_not_connected),
                    body = null,
                    modifier = Modifier.testTag("payroll_unavailable"),
                )
                payroll is MonthlyPayroll.Unpublished -> EmptyState(
                    icon = Icons.Filled.EventBusy,
                    title = stringResource(R.string.payroll_unpublished),
                    body = null,
                    modifier = Modifier.testTag("payroll_unpublished"),
                )
                payroll is MonthlyPayroll.Published -> PublishedPayroll(payroll, locale)
                loadError != null -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(loadError),
                    body = null,
                    modifier = Modifier.testTag("payroll_error"),
                ) {
                    Spacer(Modifier.height(12.dp))
                    SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRefresh, icon = Icons.Filled.Refresh)
                }
                else -> LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payroll_loading"),
                )
            }
        }
    }
}

@Composable
private fun MonthSelector(state: PayrollUiState, locale: Locale, onPreviousMonth: () -> Unit, onNextMonth: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onPreviousMonth, enabled = state.canGoBack, modifier = Modifier.size(56.dp).testTag("payroll_previous")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.payroll_previous_month))
        }
        Text(
            text = DriverFormats.month(state.month, locale).replaceFirstChar { it.titlecase(locale) },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .testTag("payroll_month"),
        )
        IconButton(onClick = onNextMonth, enabled = state.canGoForward, modifier = Modifier.size(56.dp).testTag("payroll_next")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.payroll_next_month))
        }
    }
}

@Composable
private fun PublishedPayroll(payroll: MonthlyPayroll.Published, locale: Locale) {
    payroll.publishedAt?.let {
        Text(
            text = stringResource(R.string.payroll_published_at, DriverFormats.dayTime(it, locale)),
            style = MaterialTheme.typography.bodyMedium,
            color = DriverTheme.colors.muted,
        )
    }
    if (payroll.currencies.isEmpty()) {
        EmptyState(icon = Icons.Filled.Info, title = stringResource(R.string.payroll_no_currency), body = null)
    }
    // Currencies stay separate: amounts in different currencies are never added together.
    payroll.currencies.forEach { CurrencyCard(it, locale) }
    payroll.finalized?.let { FinalizedCard(it, locale) }
    StatusBanner(stringResource(R.string.payroll_disclaimer), Icons.Filled.Info, StatusTone.Neutral)
}

@Composable
private fun CurrencyCard(currency: PayrollCurrency, locale: Locale) {
    val code = currency.currencyCode.takeIf { it.isNotBlank() }
    val key = code ?: "UNKNOWN"
    val colors = DriverTheme.colors
    SectionCard(modifier = Modifier.testTag("payroll_currency_$key")) {
        Text(
            text = stringResource(R.string.payroll_currency, code ?: stringResource(R.string.value_unknown)),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        // The final figure first and large. It is the balance before statutory deductions: never called net pay.
        Column(modifier = Modifier.fillMaxWidth().testTag("payroll_balance_$key")) {
            Text(stringResource(R.string.payroll_balance), style = MaterialTheme.typography.titleSmall, color = colors.muted)
            Text(
                text = signedMoney(currency.balanceBeforeStatutoryDeductions, AmountKind.NEUTRAL, code, locale),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(stringResource(R.string.payroll_balance_note), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.CardGap), modifier = Modifier.fillMaxWidth()) {
            SummaryFigure(
                label = stringResource(R.string.payroll_gross_earnings),
                amount = currency.grossEarnings,
                kind = AmountKind.EARNING,
                currency = code,
                locale = locale,
                modifier = Modifier.weight(1f).testTag("payroll_earnings_total_$key"),
            )
            SummaryFigure(
                label = stringResource(R.string.payroll_total_deductions),
                amount = currency.totalDeductions,
                kind = AmountKind.DEDUCTION,
                currency = code,
                locale = locale,
                modifier = Modifier.weight(1f).testTag("payroll_deductions_total_$key"),
            )
        }
        LabeledDivider()
        SectionTitle(stringResource(R.string.payroll_earnings), AmountKind.EARNING)
        AmountRow(stringResource(R.string.payroll_base_gross), currency.baseGross, code, locale, kind = AmountKind.EARNING)
        LinesSection(stringResource(R.string.payroll_additions), currency.additions, AmountKind.EARNING, code, locale, "payroll_additions_$key")
        LabeledDivider()
        SectionTitle(stringResource(R.string.payroll_deductions), AmountKind.DEDUCTION)
        LinesSection(null, currency.deductions, AmountKind.DEDUCTION, code, locale, "payroll_deductions_$key")
    }
}

/**
 * How an amount is presented. Earnings are green with "+", deductions red with a minus sign (agreed exception to
 * CLAUDE.md section 7); the sign always comes with text, never colour alone.
 */
private enum class AmountKind { EARNING, DEDUCTION, NEUTRAL }

@Composable
private fun AmountKind.color() = when (this) {
    AmountKind.EARNING -> DriverTheme.colors.earning
    AmountKind.DEDUCTION -> DriverTheme.colors.deduction
    AmountKind.NEUTRAL -> MaterialTheme.colorScheme.onSurface
}

/**
 * The category sign in front of the amount as published. Amounts are never recalculated or re-signed: a zero or
 * negative amount is shown exactly as the server sent it.
 */
@Composable
private fun signedMoney(amount: BigDecimal?, kind: AmountKind, currency: String?, locale: Locale): String {
    if (amount == null) return stringResource(R.string.value_unknown)
    val formatted = DriverFormats.money(amount, currency, locale)
    if (amount.signum() <= 0) return formatted
    return when (kind) {
        AmountKind.EARNING -> "+$formatted"
        AmountKind.DEDUCTION -> "−$formatted"
        AmountKind.NEUTRAL -> formatted
    }
}

@Composable
private fun SummaryFigure(label: String, amount: BigDecimal?, kind: AmountKind, currency: String?, locale: Locale, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
        Text(signedMoney(amount, kind, currency, locale), style = MaterialTheme.typography.titleLarge, color = kind.color())
    }
}

@Composable
private fun SectionTitle(title: String, kind: AmountKind) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = kind.color(), modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
}

/** Lines in the category the server put them in; their sign is never recalculated from the type. */
@Composable
private fun LinesSection(title: String?, lines: List<PayrollLine>, kind: AmountKind, currency: String?, locale: Locale, tag: String) {
    Column(modifier = Modifier.testTag(tag)) {
        title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        if (lines.isEmpty()) {
            Text(stringResource(R.string.payroll_none), style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
        }
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = payrollTypeLabelRes(line.type)?.let { stringResource(it) } ?: line.type.ifBlank { stringResource(R.string.value_unknown) },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    line.effectiveFrom?.let {
                        Text(
                            text = stringResource(R.string.payroll_effective_from, DriverFormats.date(it, locale)),
                            style = MaterialTheme.typography.bodySmall,
                            color = DriverTheme.colors.muted,
                        )
                    }
                }
                Text(
                    text = signedMoney(line.amount, kind, currency, locale),
                    style = MaterialTheme.typography.titleSmall,
                    color = kind.color(),
                )
            }
        }
    }
}

@Composable
private fun FinalizedCard(finalized: FinalizedMonthlyPay, locale: Locale) {
    SectionCard(modifier = Modifier.testTag("payroll_finalized")) {
        Text(stringResource(R.string.payroll_finalized_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        finalized.baseGross?.let { AmountRow(stringResource(R.string.payroll_finalized_base), it, finalized.currency, locale) }
        finalized.overtimeGross?.let { AmountRow(stringResource(R.string.payroll_finalized_overtime), it, finalized.currency, locale) }
        finalized.overtimeHours?.let {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.payroll_finalized_hours), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(DriverFormats.decimal(it, locale), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun AmountRow(
    label: String,
    amount: BigDecimal?,
    currency: String?,
    locale: Locale,
    modifier: Modifier = Modifier,
    kind: AmountKind = AmountKind.NEUTRAL,
    emphasized: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasized) FontWeight.Bold else null,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = signedMoney(amount, kind, currency, locale),
            style = MaterialTheme.typography.titleMedium,
            color = kind.color(),
        )
    }
}
