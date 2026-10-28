package com.dynamictransport.dynamicone.feature.cashpayments

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ClickableCard
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.CashPayment
import com.dynamictransport.dynamicone.domain.model.CashPaymentScope
import com.dynamictransport.dynamicone.domain.model.CashPaymentState
import com.dynamictransport.dynamicone.domain.model.LocalCashSignature
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.repository.CashPaymentRepository
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The three states a list can be filtered by; the default is what waits to be handed over. */
val CASH_FILTERS: List<CashPaymentState> = listOf(CashPaymentState.APPROVED, CashPaymentState.RECEIVED, CashPaymentState.CANCELLED)

/** A payment with the signature waiting on the phone, if any. */
data class CashPaymentRow(val payment: CashPayment, val local: LocalCashSignature?)

data class CashPaymentsUiState(
    val scope: CashPaymentScope = CashPaymentScope.DISBURSEMENTS,
    val filter: CashPaymentState = CashPaymentState.APPROVED,
    /** Null until read from the server. */
    val items: List<CashPaymentRow>? = null,
    val canLoadMore: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    @param:StringRes val loadError: Int? = null,
    /** The list was read before; the last read failed. */
    val stale: Boolean = false,
    /** The server has no cash payment endpoints. */
    val unavailable: Boolean = false,
    val accessRemoved: Boolean = false,
    @param:StringRes val message: Int? = null,
)

/**
 * The officer's queue (`cashDisbursements`) or the own payments (`cashPayments`), by state. Nothing is kept for
 * offline use: a hand-over can only be recorded against the server (docs/28, P4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CashPaymentsViewModel.Factory::class)
class CashPaymentsViewModel @AssistedInject constructor(
    @Assisted private val scope: CashPaymentScope,
    private val repository: CashPaymentRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(scope: CashPaymentScope): CashPaymentsViewModel
    }

    private val filter = MutableStateFlow(CashPaymentState.APPROVED)
    private val refreshing = MutableStateFlow(false)
    private val loadingMore = MutableStateFlow(false)

    /** The last refresh result per filter, so switching back does not show another filter's error. */
    private val lastRefresh = MutableStateFlow<Map<CashPaymentState, RefreshResult>>(emptyMap())
    private val message = MutableStateFlow<Int?>(null)

    private val flags = combine(refreshing, loadingMore, ::Pair)
    private val localSignatures = repository.observeLocalSignatures(scope)

    val uiState: StateFlow<CashPaymentsUiState> = filter.flatMapLatest { state ->
        combine(repository.observeList(scope, state), lastRefresh, flags, message, localSignatures) { list, refreshes, (isRefreshing, isLoadingMore), currentMessage, local ->
            val refresh = refreshes[state]
            val failed = refresh?.takeIf { it !in QUIET_RESULTS }
            CashPaymentsUiState(
                scope = scope,
                filter = state,
                items = list?.items?.map { payment -> CashPaymentRow(payment, local.lastOrNull { it.paymentId == payment.id }) },
                canLoadMore = list?.hasMore == true,
                refreshing = isRefreshing,
                loadingMore = isLoadingMore,
                loadError = failed?.takeIf { list == null }?.messageRes(),
                stale = failed != null && list != null,
                unavailable = refresh == RefreshResult.NOT_SUPPORTED,
                accessRemoved = refresh == RefreshResult.ACCESS_REQUIRED,
                message = currentMessage,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CashPaymentsUiState(scope = scope))

    /** Read every time the screen shows: finance may have approved or cancelled something in the meantime. */
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        val state = filter.value
        viewModelScope.launch {
            val result = repository.refresh(scope, state)
            lastRefresh.value = lastRefresh.value + (state to result)
            refreshing.value = false
        }
    }

    fun onFilterSelected(state: CashPaymentState) {
        if (filter.value == state) return
        filter.value = state
        refresh()
    }

    fun onLoadMore() {
        if (loadingMore.value) return
        loadingMore.value = true
        viewModelScope.launch {
            repository.loadMore(scope, filter.value).messageRes()?.let { message.value = it }
            loadingMore.value = false
        }
    }

    fun onMessageShown() {
        message.value = null
    }

    private companion object {
        val QUIET_RESULTS = setOf(RefreshResult.SUCCESS, RefreshResult.NOT_SUPPORTED, RefreshResult.ACCESS_REQUIRED)
    }
}

@Composable
fun CashPaymentsRoute(
    scope: CashPaymentScope,
    onOpenPayment: (String) -> Unit,
    onBack: (() -> Unit)?,
    viewModel: CashPaymentsViewModel = hiltViewModel(
        creationCallback = { factory: CashPaymentsViewModel.Factory -> factory.create(scope) },
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    CashPaymentsScreen(
        state = state,
        onOpenPayment = onOpenPayment,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onFilterSelected = viewModel::onFilterSelected,
        onLoadMore = viewModel::onLoadMore,
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun CashPaymentsScreen(
    state: CashPaymentsUiState,
    onOpenPayment: (String) -> Unit,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onFilterSelected: (CashPaymentState) -> Unit,
    onLoadMore: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(state.scope.titleRes()),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.refreshing, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = Dimens.ScreenPadding,
                end = Dimens.ScreenPadding,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier
                .fillMaxSize()
                .readableWidth()
                .testTag("cash_list"),
        ) {
            item(key = "filters") { FilterRow(state.filter, onFilterSelected) }
            if (state.refreshing && state.items != null) {
                item(key = "refreshing") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            paymentItems(state, onOpenPayment, onRefresh, onLoadMore)
        }
    }
}

@Composable
private fun FilterRow(selected: CashPaymentState, onSelect: (CashPaymentState) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        CASH_FILTERS.forEach { state ->
            FilterChip(
                selected = state == selected,
                onClick = { onSelect(state) },
                label = { Text(stringResource(state.filterRes()), style = MaterialTheme.typography.titleSmall) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cash_filter_${state.name}"),
            )
        }
    }
}

private fun LazyListScope.paymentItems(
    state: CashPaymentsUiState,
    onOpenPayment: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
) {
    if (state.stale) {
        item(key = "stale") {
            StatusBanner(
                message = stringResource(R.string.data_stale),
                icon = Icons.Filled.SyncProblem,
                tone = StatusTone.Warning,
                actionLabel = stringResource(R.string.common_retry),
                onAction = onRefresh,
                modifier = Modifier.testTag("cash_stale"),
            )
        }
    }
    val items = state.items
    val loadError = state.loadError
    when {
        state.unavailable && items == null -> item(key = "unavailable") {
            EmptyState(Icons.Filled.CloudOff, stringResource(R.string.feature_not_connected), body = null, modifier = Modifier.testTag("cash_unavailable"))
        }
        state.accessRemoved -> item(key = "access") {
            EmptyState(Icons.Filled.PhonelinkErase, stringResource(R.string.cash_access_removed), body = null, modifier = Modifier.testTag("cash_access_removed"))
        }
        items == null && loadError != null -> item(key = "error") {
            EmptyState(Icons.Filled.CloudOff, stringResource(loadError), body = null, modifier = Modifier.testTag("cash_error")) {
                Spacer(Modifier.height(12.dp))
                SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRefresh, icon = Icons.Filled.Refresh)
            }
        }
        items == null -> item(key = "loading") {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cash_loading"),
            )
        }
        items.isEmpty() -> item(key = "empty") {
            EmptyState(
                icon = if (state.filter == CashPaymentState.APPROVED) Icons.Filled.CheckCircle else Icons.Filled.Payments,
                title = stringResource(emptyRes(state.filter)),
                body = null,
                modifier = Modifier.testTag("cash_empty"),
            )
        }
        else -> {
            items(items, key = { "payment_${it.payment.id}" }) { row ->
                PaymentCard(row.payment, row.local, onClick = { onOpenPayment(row.payment.id) })
            }
            if (state.canLoadMore) {
                item(key = "more") {
                    SecondaryActionButton(
                        text = stringResource(R.string.common_load_more),
                        onClick = onLoadMore,
                        icon = Icons.Filled.ExpandMore,
                        enabled = !state.loadingMore,
                        modifier = Modifier.testTag("cash_load_more"),
                    )
                }
            }
        }
    }
}

@StringRes
private fun emptyRes(filter: CashPaymentState): Int = when (filter) {
    CashPaymentState.APPROVED -> R.string.cash_none_approved
    CashPaymentState.RECEIVED -> R.string.cash_none_received
    CashPaymentState.CANCELLED, CashPaymentState.UNKNOWN -> R.string.cash_none_cancelled
}

/** Recipient, registration number, type, amount with currency and the planned date: what the officer checks at a glance. */
@Composable
private fun PaymentCard(payment: CashPayment, local: LocalCashSignature?, onClick: () -> Unit) {
    ClickableCard(onClick = onClick, modifier = Modifier.testTag("cash_payment_${payment.id}")) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = payment.employeeName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = listOfNotNull(
                    payment.registrationNumber?.let { stringResource(R.string.cash_registration_number, it) },
                    kindText(payment),
                )
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = amountText(payment),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.testTag("cash_amount_${payment.id}"),
            )
        }
        Spacer(Modifier.height(10.dp))
        CashPaymentStatusLine(payment, local)
    }
}
