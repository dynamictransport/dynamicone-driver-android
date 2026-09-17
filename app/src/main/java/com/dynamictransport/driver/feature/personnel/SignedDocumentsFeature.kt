package com.dynamictransport.driver.feature.personnel

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.driver.R
import com.dynamictransport.driver.core.design.component.ClickableCard
import com.dynamictransport.driver.core.design.component.DriverTopBar
import com.dynamictransport.driver.core.design.component.EmptyState
import com.dynamictransport.driver.core.design.component.SecondaryActionButton
import com.dynamictransport.driver.core.design.component.StatusBanner
import com.dynamictransport.driver.core.design.component.StatusTone
import com.dynamictransport.driver.core.design.theme.Dimens
import com.dynamictransport.driver.core.design.theme.DriverTheme
import com.dynamictransport.driver.domain.model.DocumentContentResult
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.model.SignedDocument
import com.dynamictransport.driver.domain.repository.AppInfo
import com.dynamictransport.driver.domain.repository.SignedDocumentRepository
import com.dynamictransport.driver.feature.common.DriverFormats
import com.dynamictransport.driver.feature.common.MessageEffect
import com.dynamictransport.driver.feature.common.OpenDocumentEffect
import com.dynamictransport.driver.feature.common.SingleFlight
import com.dynamictransport.driver.feature.common.currentLocale
import com.dynamictransport.driver.feature.common.labelRes
import com.dynamictransport.driver.feature.common.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignedDocumentsUiState(
    /** Null until read. */
    val documents: List<SignedDocument>? = null,
    val canLoadMore: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    @param:StringRes val loadError: Int? = null,
    val stale: Boolean = false,
    val unavailable: Boolean = false,
    val isDemoMode: Boolean = false,
    val opening: Boolean = false,
    @param:StringRes val message: Int? = null,
    val openRequest: DocumentContentResult.Available? = null,
)

@HiltViewModel
class SignedDocumentsViewModel @Inject constructor(
    private val repository: SignedDocumentRepository,
    private val appInfo: AppInfo,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val loadingMore = MutableStateFlow(false)
    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)
    private val message = MutableStateFlow<Int?>(null)
    private val openRequest = MutableStateFlow<DocumentContentResult.Available?>(null)
    private val singleFlight = SingleFlight()

    private val flags = combine(refreshing, loadingMore, singleFlight.busy) { a, b, c -> Triple(a, b, c) }
    private val feedback = combine(message, openRequest, ::Pair)

    val uiState: StateFlow<SignedDocumentsUiState> = combine(
        repository.observeDocuments(),
        repository.observeCanLoadMore(),
        lastRefresh,
        flags,
        feedback,
    ) { documents, canLoadMore, refresh, (isRefreshing, isLoadingMore, opening), (currentMessage, open) ->
        val failed = refresh?.takeIf { it != RefreshResult.SUCCESS && it != RefreshResult.NOT_SUPPORTED }
        SignedDocumentsUiState(
            documents = documents,
            canLoadMore = canLoadMore,
            refreshing = isRefreshing,
            loadingMore = isLoadingMore,
            loadError = failed?.takeIf { documents == null }?.messageRes(),
            stale = failed != null && documents != null,
            unavailable = refresh == RefreshResult.NOT_SUPPORTED,
            isDemoMode = appInfo.isDemoMode,
            opening = opening,
            message = currentMessage,
            openRequest = open,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SignedDocumentsUiState(isDemoMode = appInfo.isDemoMode))

    init {
        refresh()
    }

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
            repository.loadMore().messageRes()?.let { message.value = it }
            loadingMore.value = false
        }
    }

    fun onOpen(document: SignedDocument) {
        singleFlight.launch(viewModelScope) {
            when (val result = repository.open(document.id)) {
                is DocumentContentResult.Available -> openRequest.value = result
                is DocumentContentResult.Unavailable -> message.value = result.reason.messageRes()
            }
        }
    }

    fun onOpenRequestHandled() {
        openRequest.value = null
    }

    fun onOpenFailed() {
        openRequest.value = null
        message.value = R.string.documents_open_unavailable
    }

    fun onMessageShown() {
        message.value = null
    }
}

@Composable
fun SignedDocumentsRoute(onBack: () -> Unit, viewModel: SignedDocumentsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OpenDocumentEffect(state.openRequest, viewModel::onOpenRequestHandled, viewModel::onOpenFailed)
    SignedDocumentsScreen(
        state = state,
        onBack = onBack,
        onOpen = viewModel::onOpen,
        onLoadMore = viewModel::onLoadMore,
        onRetry = viewModel::refresh,
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun SignedDocumentsScreen(
    state: SignedDocumentsUiState,
    onBack: () -> Unit,
    onOpen: (SignedDocument) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.signed_documents_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
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
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "note") {
                StatusBanner(stringResource(R.string.signed_note), Icons.Filled.Info, StatusTone.Neutral)
            }
            if (state.stale) {
                item(key = "stale") {
                    StatusBanner(
                        message = stringResource(R.string.data_stale),
                        icon = Icons.Filled.SyncProblem,
                        tone = StatusTone.Warning,
                        actionLabel = stringResource(R.string.common_retry),
                        onAction = onRetry,
                    )
                }
            }
            val documents = state.documents
            val loadError = state.loadError
            when {
                state.unavailable -> item(key = "unavailable") {
                    EmptyState(
                        icon = Icons.Filled.CloudOff,
                        title = stringResource(if (state.isDemoMode) R.string.feature_demo_unavailable else R.string.feature_not_connected),
                        body = null,
                        modifier = Modifier.testTag("signed_unavailable"),
                    )
                }
                documents == null && loadError != null -> item(key = "error") {
                    EmptyState(
                        icon = Icons.Filled.CloudOff,
                        title = stringResource(loadError),
                        body = null,
                        modifier = Modifier.testTag("signed_error"),
                    ) {
                        Spacer(Modifier.height(12.dp))
                        SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRetry, icon = Icons.Filled.Refresh)
                    }
                }
                documents == null -> item(key = "loading") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("signed_loading"))
                }
                documents.isEmpty() -> item(key = "empty") {
                    EmptyState(icon = Icons.Filled.PictureAsPdf, title = stringResource(R.string.signed_empty), body = null)
                }
                else -> {
                    items(documents, key = { it.id }) { document ->
                        SignedDocumentCard(document, enabled = !state.opening, onOpen = { onOpen(document) })
                    }
                    if (state.canLoadMore) {
                        item(key = "more") {
                            SecondaryActionButton(
                                text = stringResource(R.string.common_load_more),
                                onClick = onLoadMore,
                                icon = Icons.Filled.ExpandMore,
                                enabled = !state.loadingMore,
                                modifier = Modifier.testTag("signed_load_more"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignedDocumentCard(document: SignedDocument, enabled: Boolean, onOpen: () -> Unit) {
    val locale = currentLocale()
    ClickableCard(onClick = { if (enabled) onOpen() }, modifier = Modifier.testTag("signed_document_${document.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = DriverTheme.colors.brand, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(document.type.labelRes()), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = document.signedAt?.let { stringResource(R.string.signed_at, DriverFormats.dayTime(it, locale)) }
                        ?: stringResource(R.string.value_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DriverTheme.colors.muted,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(enabled = enabled, onClick = onOpen),
            ) {
                Icon(Icons.Filled.Visibility, contentDescription = stringResource(R.string.signed_open), tint = DriverTheme.colors.brand)
            }
        }
    }
}
