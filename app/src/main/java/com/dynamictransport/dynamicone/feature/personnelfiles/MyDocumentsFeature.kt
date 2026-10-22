package com.dynamictransport.dynamicone.feature.personnelfiles

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusBadge
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.component.toneContainer
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.DocumentRequirement
import com.dynamictransport.dynamicone.domain.model.DocumentRequirements
import com.dynamictransport.dynamicone.domain.model.PersonnelDocument
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.RequirementStatus
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.domain.repository.AuthRepository
import com.dynamictransport.dynamicone.domain.repository.MyDocumentRepository
import com.dynamictransport.dynamicone.domain.rules.PersonnelFileRules
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** One part of the screen read from the server: its data, or why it is missing or old. */
data class LoadedPart<T>(
    /** Null until read. */
    val data: T? = null,
    /** Nothing to show and the read failed. */
    @param:StringRes val error: Int? = null,
    /** [data] is shown although the last read failed. */
    val stale: Boolean = false,
) {
    companion object {
        fun <T> of(data: T?, lastRead: RefreshResult?): LoadedPart<T> {
            val failed = lastRead?.takeIf { it != RefreshResult.SUCCESS }
            return LoadedPart(
                data = data,
                error = failed?.takeIf { data == null }?.messageRes(),
                stale = failed != null && data != null,
            )
        }
    }
}

/** What the viewer needs to open a file. */
data class FileToView(val documentId: String, val title: String, val contentType: String?)

data class MyDocumentsUiState(
    /** Driver module: the required documents and their summary. */
    val showRequirements: Boolean = false,
    /**
     * `hrDocuments` module: every file of the personnel file. The server hands out file contents with this module only,
     * so without it a required document shows its state but cannot be opened.
     */
    val showHistory: Boolean = false,
    val requirements: LoadedPart<DocumentRequirements> = LoadedPart(),
    val history: LoadedPart<List<PersonnelDocument>> = LoadedPart(),
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    @param:StringRes val message: Int? = null,
)

/** The account's own personnel file: read again every time the screen shows, never kept for offline use. */
@HiltViewModel
class MyDocumentsViewModel @Inject constructor(
    private val repository: MyDocumentRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val modules = authRepository.sessionState.map { (it as? SessionState.LoggedIn)?.modules.orEmpty() }
    private val requirementsRead = MutableStateFlow<RefreshResult?>(null)
    private val historyRead = MutableStateFlow<RefreshResult?>(null)
    private val refreshing = MutableStateFlow(false)
    private val loadingMore = MutableStateFlow(false)
    private val message = MutableStateFlow<Int?>(null)

    private val flags = combine(refreshing, loadingMore, message, ::Triple)
    private val reads = combine(requirementsRead, historyRead, ::Pair)

    val uiState: StateFlow<MyDocumentsUiState> = combine(
        modules,
        repository.observeRequirements(),
        repository.observeHistory(),
        reads,
        flags,
    ) { accountModules, requirements, history, (requirementsResult, historyResult), (isRefreshing, isLoadingMore, currentMessage) ->
        MyDocumentsUiState(
            showRequirements = AppModule.DRIVER in accountModules,
            showHistory = AppModule.HR_DOCUMENTS in accountModules,
            requirements = LoadedPart.of(requirements, requirementsResult),
            history = LoadedPart.of(history?.documents, historyResult),
            canLoadMore = history?.canLoadMore == true,
            loadingMore = isLoadingMore,
            refreshing = isRefreshing,
            message = currentMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyDocumentsUiState())

    /** Only the parts the account's modules allow are read; a file may have been added or replaced meanwhile. */
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        val accountModules = (authRepository.sessionState.value as? SessionState.LoggedIn)?.modules.orEmpty()
        viewModelScope.launch {
            val requirements = if (AppModule.DRIVER in accountModules) launch { requirementsRead.value = repository.refreshRequirements() } else null
            val history = if (AppModule.HR_DOCUMENTS in accountModules) launch { historyRead.value = repository.refreshHistory() } else null
            requirements?.join()
            history?.join()
            refreshing.value = false
        }
    }

    fun onLoadMore() {
        if (loadingMore.value) return
        loadingMore.value = true
        viewModelScope.launch {
            repository.loadMoreHistory().messageRes()?.let { message.value = it }
            loadingMore.value = false
        }
    }

    fun onMessageShown() {
        message.value = null
    }
}

@Composable
fun MyDocumentsRoute(
    onBack: (() -> Unit)?,
    onOpenFile: (FileToView) -> Unit,
    viewModel: MyDocumentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    MyDocumentsScreen(
        state = state,
        onBack = onBack,
        onOpenFile = onOpenFile,
        onRetry = viewModel::refresh,
        onLoadMore = viewModel::onLoadMore,
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun MyDocumentsScreen(
    state: MyDocumentsUiState,
    onBack: (() -> Unit)?,
    onOpenFile: (FileToView) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onMessageShown: () -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.my_documents_title),
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
                .readableWidth()
                .testTag(MY_DOCUMENTS_LIST_TAG),
        ) {
            if (state.refreshing) {
                item(key = "refreshing") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            if (state.showRequirements) requirementsSection(state.requirements, state.showHistory, onOpenFile, onRetry)
            if (state.showHistory) historySection(state, today, onOpenFile, onRetry, onLoadMore)
            // No upload from the phone: the office keeps the personnel file.
            item(key = "note") {
                StatusBanner(stringResource(R.string.my_documents_no_upload), Icons.Filled.Info, StatusTone.Neutral)
            }
        }
    }
}

private fun LazyListScope.requirementsSection(
    part: LoadedPart<DocumentRequirements>,
    filesOpenable: Boolean,
    onOpenFile: (FileToView) -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "requirements_title") { SectionTitle(R.string.my_documents_required) }
    val requirements = part.data
    val error = part.error
    when {
        requirements == null && error != null -> item(key = "requirements_error") {
            LoadError(error, onRetry, Modifier.testTag("my_documents_requirements_error"))
        }
        requirements == null -> item(key = "requirements_loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else -> {
            if (part.stale) item(key = "requirements_stale") { StaleBanner(onRetry) }
            item(key = "requirements_summary") { RequirementSummary(requirements) }
            if (requirements.items.isEmpty()) {
                item(key = "requirements_empty") {
                    EmptyState(Icons.Filled.FolderOpen, stringResource(R.string.my_documents_requirements_empty), body = null)
                }
            }
            items(requirements.items, key = { "requirement_${it.type.name}_${it.documentId}" }) { requirement ->
                RequirementRow(requirement, filesOpenable, onOpenFile)
            }
        }
    }
}

private fun LazyListScope.historySection(
    state: MyDocumentsUiState,
    today: LocalDate,
    onOpenFile: (FileToView) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    item(key = "history_title") { SectionTitle(R.string.my_documents_history) }
    val documents = state.history.data
    val error = state.history.error
    when {
        documents == null && error != null -> item(key = "history_error") {
            LoadError(error, onRetry, Modifier.testTag("my_documents_history_error"))
        }
        documents == null -> item(key = "history_loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        documents.isEmpty() -> item(key = "history_empty") {
            EmptyState(
                icon = Icons.Filled.FolderOpen,
                title = stringResource(R.string.my_documents_history_empty),
                body = null,
                modifier = Modifier.testTag("my_documents_history_empty"),
            )
        }
        else -> {
            if (state.history.stale) item(key = "history_stale") { StaleBanner(onRetry) }
            items(documents, key = { "history_${it.id}" }) { document -> HistoryRow(document, today, onOpenFile) }
            if (state.canLoadMore) {
                item(key = "history_more") {
                    SecondaryActionButton(
                        text = stringResource(R.string.common_load_more),
                        onClick = onLoadMore,
                        icon = Icons.Filled.ExpandMore,
                        enabled = !state.loadingMore,
                        modifier = Modifier.testTag("my_documents_load_more"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 4.dp)
            .semantics { heading() },
    )
}

@Composable
internal fun LoadError(@StringRes message: Int, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(icon = Icons.Filled.CloudOff, title = stringResource(message), body = null, modifier = modifier) {
        Spacer(Modifier.height(12.dp))
        SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRetry, icon = Icons.Filled.Refresh)
    }
}

@Composable
internal fun StaleBanner(onRetry: () -> Unit) {
    StatusBanner(
        message = stringResource(R.string.data_stale),
        icon = Icons.Filled.SyncProblem,
        tone = StatusTone.Warning,
        actionLabel = stringResource(R.string.common_retry),
        onAction = onRetry,
    )
}

/** The four counts of the web screen, each with its own icon and word, not colour alone. */
@Composable
private fun RequirementSummary(requirements: DocumentRequirements) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("my_documents_summary")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile(RequirementStatus.VALID, R.string.documents_summary_valid, requirements.validCount, Modifier.weight(1f))
            SummaryTile(RequirementStatus.EXPIRING_SOON, R.string.documents_summary_expiring, requirements.expiringSoonCount, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile(RequirementStatus.EXPIRED, R.string.documents_summary_expired, requirements.expiredCount, Modifier.weight(1f))
            SummaryTile(RequirementStatus.MISSING, R.string.documents_summary_missing, requirements.missingOrIncompleteCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryTile(status: RequirementStatus, @StringRes label: Int, count: Int, modifier: Modifier = Modifier) {
    val visual = status.visual()
    // A zero count needs no attention: it stays neutral.
    val colors = toneContainer(if (count > 0) visual.tone else StatusTone.Neutral)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 64.dp)
            .background(colors.container, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("my_documents_summary_${status.name}"),
    ) {
        Icon(visual.icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(count.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.content)
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = colors.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RequirementRow(requirement: DocumentRequirement, filesOpenable: Boolean, onOpenFile: (FileToView) -> Unit) {
    val locale = currentLocale()
    val visual = requirement.status.visual()
    val title = stringResource(requirement.type.labelRes())
    val documentId = requirement.documentId
    val hasFile = PersonnelFileRules.canOpen(requirement)
    val canOpen = hasFile && filesOpenable
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                requirement.expiresOn?.let { expiresOn ->
                    Text(
                        text = stringResource(
                            if (requirement.status == RequirementStatus.EXPIRED) R.string.document_expired_on else R.string.document_expires_on,
                            DriverFormats.date(expiresOn, locale),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DriverTheme.colors.muted,
                    )
                }
                Spacer(Modifier.height(6.dp))
                StatusBadge(stringResource(visual.label), visual.icon, visual.tone)
            }
            if (canOpen) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.document_open),
                    tint = DriverTheme.colors.brand,
                )
            } else if (!hasFile) {
                // Missing: nothing to download, and the phone cannot upload.
                Text(stringResource(R.string.document_no_file), style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
            }
        }
    }
    val tag = "my_documents_requirement_${requirement.type.name}"
    if (canOpen && documentId != null) {
        ClickableCard(onClick = { onOpenFile(FileToView(documentId, title, null)) }, modifier = Modifier.testTag(tag)) { content() }
    } else {
        SectionCard(modifier = Modifier.testTag(tag)) { content() }
    }
}

@Composable
private fun HistoryRow(document: PersonnelDocument, today: LocalDate, onOpenFile: (FileToView) -> Unit) {
    val locale = currentLocale()
    val title = documentTitle(document)
    ClickableCard(
        onClick = { onOpenFile(FileToView(document.id, title, document.contentType)) },
        modifier = Modifier.testTag("my_documents_history_${document.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Description, contentDescription = null, tint = DriverTheme.colors.brand, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                document.uploadedAt?.let { uploadedAt ->
                    Text(
                        text = stringResource(R.string.document_uploaded_on, DriverFormats.dayTime(uploadedAt, locale)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DriverTheme.colors.muted,
                    )
                }
                val pastExpiry = PersonnelFileRules.isPastExpiry(document, today)
                if (document.isCurrent || pastExpiry) Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (document.isCurrent) {
                        StatusBadge(stringResource(R.string.document_current), Icons.Filled.Description, StatusTone.Info)
                    }
                    document.expiresOn?.let { expiresOn ->
                        // An expired file can still be opened; its date says so plainly.
                        if (pastExpiry) {
                            StatusBadge(
                                stringResource(R.string.document_expired_on, DriverFormats.date(expiresOn, locale)),
                                Icons.Filled.EventBusy,
                                StatusTone.Warning,
                                modifier = Modifier.testTag("my_documents_history_expired_${document.id}"),
                            )
                        }
                    }
                }
                if (!pastExpiry) {
                    document.expiresOn?.let { expiresOn ->
                        Text(
                            text = stringResource(R.string.document_expires_on, DriverFormats.date(expiresOn, locale)),
                            style = MaterialTheme.typography.bodySmall,
                            color = DriverTheme.colors.muted,
                        )
                    }
                }
            }
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.document_open), tint = DriverTheme.colors.brand)
        }
    }
}

const val MY_DOCUMENTS_LIST_TAG = "my_documents_list"
