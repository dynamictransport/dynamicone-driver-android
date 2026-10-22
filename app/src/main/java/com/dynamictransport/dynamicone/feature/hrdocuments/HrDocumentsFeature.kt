package com.dynamictransport.dynamicone.feature.hrdocuments

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
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
import com.dynamictransport.dynamicone.domain.model.HrDocument
import com.dynamictransport.dynamicone.domain.model.LocalSignature
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.repository.HrDocumentRepository
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.messageRes
import com.dynamictransport.dynamicone.feature.common.rememberCurrentInstant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** A document to sign with the signature waiting on the phone, if any. */
data class HrDocumentRow(val document: HrDocument, val local: LocalSignature?)

data class HrDocumentsUiState(
    /** Null until read from the server or the phone. */
    val pending: List<HrDocumentRow>? = null,
    val signed: List<HrDocument>? = null,
    val canLoadMore: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    @param:StringRes val loadError: Int? = null,
    /** The list comes from the phone; the last read failed. */
    val stale: Boolean = false,
    /** The server has no signing endpoints yet. */
    val unavailable: Boolean = false,
    val accessRemoved: Boolean = false,
    @param:StringRes val message: Int? = null,
)

@HiltViewModel
class HrDocumentsViewModel @Inject constructor(
    private val repository: HrDocumentRepository,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val loadingMore = MutableStateFlow(false)
    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)
    private val message = MutableStateFlow<Int?>(null)

    private val flags = combine(refreshing, loadingMore, ::Pair)

    val uiState: StateFlow<HrDocumentsUiState> = combine(
        repository.observeLists(),
        lastRefresh,
        flags,
        message,
    ) { lists, refresh, (isRefreshing, isLoadingMore), currentMessage ->
        val failed = refresh?.takeIf { it !in QUIET_RESULTS }
        HrDocumentsUiState(
            pending = lists.pending?.map { HrDocumentRow(it, lists.localFor(it.id)) },
            signed = lists.signed,
            canLoadMore = lists.canLoadMoreSigned,
            refreshing = isRefreshing,
            loadingMore = isLoadingMore,
            loadError = failed?.takeIf { lists.pending == null }?.messageRes(),
            stale = failed != null && lists.pending != null,
            unavailable = refresh == RefreshResult.NOT_SUPPORTED,
            accessRemoved = refresh == RefreshResult.ACCESS_REQUIRED,
            message = currentMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HrDocumentsUiState())

    /** Read every time the screen shows: a document may have arrived while it was in the background. */
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            lastRefresh.value = repository.refresh()
            refreshing.value = false
        }
    }

    fun onLoadMore() {
        if (loadingMore.value) return
        loadingMore.value = true
        viewModelScope.launch {
            repository.loadMoreSigned().messageRes()?.let { message.value = it }
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
fun HrDocumentsRoute(
    onOpenDocument: (String) -> Unit,
    onBack: (() -> Unit)?,
    viewModel: HrDocumentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    HrDocumentsScreen(
        state = state,
        onOpenDocument = onOpenDocument,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::onLoadMore,
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun HrDocumentsScreen(
    state: HrDocumentsUiState,
    onOpenDocument: (String) -> Unit,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onMessageShown: () -> Unit,
    now: Instant = rememberCurrentInstant(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.hr_documents_title),
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
                .testTag("hr_documents_list"),
        ) {
            if (state.refreshing && state.pending != null) {
                item(key = "refreshing") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            documentItems(state, now, onOpenDocument, onRefresh, onLoadMore)
        }
    }
}

private fun LazyListScope.documentItems(
    state: HrDocumentsUiState,
    now: Instant,
    onOpenDocument: (String) -> Unit,
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
                modifier = Modifier.testTag("hr_stale"),
            )
        }
    }
    val pending = state.pending
    val loadError = state.loadError
    when {
        state.unavailable && pending == null -> item(key = "unavailable") {
            EmptyState(
                icon = Icons.Filled.CloudOff,
                title = stringResource(R.string.feature_not_connected),
                body = null,
                modifier = Modifier.testTag("hr_unavailable"),
            )
        }
        state.accessRemoved -> item(key = "access") {
            EmptyState(
                icon = Icons.Filled.PhonelinkErase,
                title = stringResource(R.string.hr_access_removed),
                body = null,
                modifier = Modifier.testTag("hr_access_removed"),
            )
        }
        pending == null && loadError != null -> item(key = "error") {
            EmptyState(
                icon = Icons.Filled.CloudOff,
                title = stringResource(loadError),
                body = null,
                modifier = Modifier.testTag("hr_error"),
            ) {
                Spacer(Modifier.height(12.dp))
                SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRefresh, icon = Icons.Filled.Refresh)
            }
        }
        pending == null -> item(key = "loading") {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hr_loading"),
            )
        }
        else -> {
            item(key = "pending_header") { SectionHeader(R.string.hr_pending_header) }
            if (pending.isEmpty()) {
                item(key = "pending_empty") {
                    EmptyState(
                        icon = Icons.Filled.CheckCircle,
                        title = stringResource(R.string.hr_none_pending),
                        body = null,
                        modifier = Modifier.testTag("hr_none_pending"),
                    )
                }
            } else {
                items(pending, key = { "pending_${it.document.id}" }) { row ->
                    DocumentCard(row.document, row.local, now, onClick = { onOpenDocument(row.document.id) })
                }
            }
            signedItems(state, now, onOpenDocument, onLoadMore)
        }
    }
}

private fun LazyListScope.signedItems(
    state: HrDocumentsUiState,
    now: Instant,
    onOpenDocument: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val signed = state.signed ?: return
    item(key = "signed_header") { SectionHeader(R.string.hr_signed_header) }
    if (signed.isEmpty()) {
        item(key = "signed_empty") {
            Text(
                text = stringResource(R.string.hr_none_signed),
                style = MaterialTheme.typography.bodyMedium,
                color = DriverTheme.colors.muted,
                modifier = Modifier.testTag("hr_none_signed"),
            )
        }
        return
    }
    items(signed, key = { "signed_${it.id}" }) { document ->
        DocumentCard(document, local = null, now = now, onClick = { onOpenDocument(document.id) })
    }
    if (state.canLoadMore) {
        item(key = "more") {
            SecondaryActionButton(
                text = stringResource(R.string.common_load_more),
                onClick = onLoadMore,
                icon = Icons.Filled.ExpandMore,
                enabled = !state.loadingMore,
                modifier = Modifier.testTag("hr_load_more"),
            )
        }
    }
}

@Composable
private fun SectionHeader(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun DocumentCard(document: HrDocument, local: LocalSignature?, now: Instant, onClick: () -> Unit) {
    val language = document.language(document.signedLanguage) ?: preferredLanguage(document)
    ClickableCard(onClick = onClick, modifier = Modifier.testTag("hr_document_${document.id}")) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                tint = DriverTheme.colors.brand,
                modifier = Modifier.padding(end = 12.dp, top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = language?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                document.version?.let {
                    Text(stringResource(R.string.hr_version, it), style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HrDocumentStatusLine(document, local, now)
    }
}
