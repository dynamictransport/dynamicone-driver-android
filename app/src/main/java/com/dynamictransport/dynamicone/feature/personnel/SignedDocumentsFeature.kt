package com.dynamictransport.dynamicone.feature.personnel

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
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SignedDocument
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.SignedDocumentRepository
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.labelRes
import com.dynamictransport.dynamicone.feature.common.messageRes
import com.dynamictransport.dynamicone.feature.personnelfiles.FileToView
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
    @param:StringRes val message: Int? = null,
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

    private val flags = combine(refreshing, loadingMore, ::Pair)

    val uiState: StateFlow<SignedDocumentsUiState> = combine(
        repository.observeDocuments(),
        repository.observeCanLoadMore(),
        lastRefresh,
        flags,
        message,
    ) { documents, canLoadMore, refresh, (isRefreshing, isLoadingMore), currentMessage ->
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
            message = currentMessage,
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

    fun onMessageShown() {
        message.value = null
    }
}

/** [onOpenFile] shows the signed PDF in the app's own viewer, which downloads it; no PDF app is needed on the phone. */
@Composable
fun SignedDocumentsRoute(onBack: () -> Unit, onOpenFile: (FileToView) -> Unit, viewModel: SignedDocumentsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SignedDocumentsScreen(
        state = state,
        onBack = onBack,
        onOpen = { document, title -> onOpenFile(FileToView(document.id, title, PDF_CONTENT_TYPE)) },
        onLoadMore = viewModel::onLoadMore,
        onRetry = viewModel::refresh,
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun SignedDocumentsScreen(
    state: SignedDocumentsUiState,
    onBack: () -> Unit,
    /** The row's document and its label, shown as the viewer's title. */
    onOpen: (SignedDocument, String) -> Unit,
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
            modifier = Modifier
                .fillMaxSize()
                .readableWidth(),
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
                        SignedDocumentCard(document, onOpen = { title -> onOpen(document, title) })
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
private fun SignedDocumentCard(document: SignedDocument, onOpen: (String) -> Unit) {
    val locale = currentLocale()
    val title = stringResource(document.type.labelRes())
    ClickableCard(onClick = { onOpen(title) }, modifier = Modifier.testTag("signed_document_${document.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = DriverTheme.colors.brand, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = document.signedAt?.let { stringResource(R.string.signed_at, DriverFormats.dayTime(it, locale)) }
                        ?: stringResource(R.string.value_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DriverTheme.colors.muted,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = { onOpen(title) }),
            ) {
                Icon(Icons.Filled.Visibility, contentDescription = stringResource(R.string.signed_open), tint = DriverTheme.colors.brand)
            }
        }
    }
}

private const val PDF_CONTENT_TYPE = "application/pdf"
