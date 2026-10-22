package com.dynamictransport.dynamicone.feature.hrdocuments

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.core.pdf.PdfDocumentOpener
import com.dynamictransport.dynamicone.core.pdf.PdfPageSource
import com.dynamictransport.dynamicone.domain.model.DrawnSignature
import com.dynamictransport.dynamicone.domain.model.HrContentResult
import com.dynamictransport.dynamicone.domain.model.HrDocument
import com.dynamictransport.dynamicone.domain.model.HrDocumentLanguage
import com.dynamictransport.dynamicone.domain.model.HrDocumentLookup
import com.dynamictransport.dynamicone.domain.model.HrDocumentStatus
import com.dynamictransport.dynamicone.domain.model.HrSignResult
import com.dynamictransport.dynamicone.domain.model.LocalSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.SignatureFailure
import com.dynamictransport.dynamicone.domain.model.SignaturePoint
import com.dynamictransport.dynamicone.domain.repository.HrDocumentRepository
import com.dynamictransport.dynamicone.domain.rules.HrDocumentRules
import com.dynamictransport.dynamicone.feature.common.ActionBar
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.messageRes
import com.dynamictransport.dynamicone.feature.personnelfiles.FileToView
import com.dynamictransport.dynamicone.feature.common.rememberCurrentInstant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

/** Argument name of [com.dynamictransport.dynamicone.app.navigation.HrDocumentDetailDestination]. */
const val HR_DOCUMENT_ID_ARG = "documentId"

/** The result of the last signature, shown on the screen until it is left. */
data class SignOutcomeUi(@param:StringRes val message: Int, val tone: StatusTone)

data class HrDocumentDetailUiState(
    val lookup: HrDocumentLookup = HrDocumentLookup.Loading,
    /** The document could not be read and is not on the phone. */
    @param:StringRes val loadError: Int? = null,
    val selectedLanguage: HrDocumentLanguage? = null,
    /** The pages of [selectedLanguage] once they are on the phone and open. */
    val pages: PdfPageSource? = null,
    val contentLoading: Boolean = false,
    @param:StringRes val contentError: Int? = null,
    val declarationAccepted: Boolean = false,
    /** Finished strokes of the signature being drawn. */
    val strokes: List<List<SignaturePoint>> = emptyList(),
    val submitting: Boolean = false,
    val outcome: SignOutcomeUi? = null,
    @param:StringRes val message: Int? = null,
) {
    val document: HrDocument? get() = (lookup as? HrDocumentLookup.Found)?.document
    val local: LocalSignature? get() = (lookup as? HrDocumentLookup.Found)?.local

    /** Waiting for a signature from this phone: nothing signed yet, or the last one was rejected. */
    val canBeSigned: Boolean
        get() = document?.status == HrDocumentStatus.PENDING && local.let { it == null || it.state == LocalSignatureState.FAILED }

    val readyToSign: Boolean
        get() = canBeSigned && pages != null && declarationAccepted && HrDocumentRules.isValid(strokes) && !submitting
}

/**
 * One personnel document: its pages in the chosen language, the declaration and the finger signature (docs/17). The
 * signature is stored on the phone first; the screen says "waiting to be sent" until the server confirmed it.
 */
@HiltViewModel
class HrDocumentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: HrDocumentRepository,
    private val pdfOpener: PdfDocumentOpener,
) : ViewModel() {

    private val documentId: String = checkNotNull(savedStateHandle[HR_DOCUMENT_ID_ARG])
    private val state = MutableStateFlow(HrDocumentDetailUiState())
    private val signFlight = SingleFlight()
    private var contentJob: Job? = null

    /** Checksum of the content being shown or loaded. */
    private var requestedSha: String? = null

    /** The employee's language choice first, then the app language. */
    private var preferredLanguages: List<String> = listOf(Locale.getDefault().language)

    val uiState: StateFlow<HrDocumentDetailUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { repository.observeDocument(documentId).collect(::onLookup) }
        refresh()
    }

    /** Reads the document's current state (a reminder, the signed PDF becoming ready). */
    fun refresh() {
        viewModelScope.launch {
            val error = repository.refreshDocument(documentId).messageRes()
            // Only needed while the document is not on the phone at all; otherwise the stored one is shown.
            state.update { it.copy(loadError = error?.takeIf { _ -> it.lookup is HrDocumentLookup.Loading }) }
        }
    }

    fun onSelectLanguage(code: String) {
        val language = state.value.document?.language(code) ?: return
        if (language.code == state.value.selectedLanguage?.code) return
        preferredLanguages = listOf(code) + preferredLanguages
        // The declaration is accepted for the text that was read.
        state.update { it.copy(declarationAccepted = false) }
        load(language)
    }

    fun onRetryContent() {
        state.value.selectedLanguage?.let(::load)
    }

    fun onDeclarationChanged(accepted: Boolean) {
        state.update { it.copy(declarationAccepted = accepted) }
    }

    fun onStroke(stroke: List<SignaturePoint>) {
        state.update { current ->
            if (!HrDocumentRules.canStartStroke(current.strokes)) current else current.copy(strokes = current.strokes + listOf(stroke))
        }
    }

    fun onClearSignature() {
        state.update { it.copy(strokes = emptyList()) }
    }

    fun onSign() {
        val current = state.value
        val language = current.selectedLanguage ?: return
        if (!current.readyToSign) {
            state.update { it.copy(message = R.string.hr_sign_needs) }
            return
        }
        // A nervous double tap never signs twice; the repository also sends one waiting signature per document.
        signFlight.launch(viewModelScope) {
            state.update { it.copy(submitting = true, outcome = null) }
            val signature = DrawnSignature(current.strokes, HrDocumentRules.PAD_ASPECT_RATIO)
            val result = repository.sign(documentId, language.code, language.contentSha256, signature)
            state.update { it.copy(submitting = false).after(result) }
        }
    }

    fun onMessageShown() {
        state.update { it.copy(message = null) }
    }

    override fun onCleared() {
        contentJob?.cancel()
        state.value.pages?.close()
    }

    private fun onLookup(lookup: HrDocumentLookup) {
        state.update { it.copy(lookup = lookup, loadError = if (lookup is HrDocumentLookup.Loading) it.loadError else null) }
        val document = (lookup as? HrDocumentLookup.Found)?.document ?: return
        // A signed or withdrawn document is not read again here: the signed PDF is its record.
        if (document.status != HrDocumentStatus.PENDING) {
            closePages()
            return
        }
        val selected = state.value.selectedLanguage?.let { document.language(it.code) }
        val language = selected ?: HrDocumentRules.pickLanguage(document, preferredLanguages) ?: return
        // New content (the office replaced it) or a first load; the same content is not loaded twice.
        if (language.contentSha256 != requestedSha) {
            if (requestedSha != null) state.update { it.copy(declarationAccepted = false) }
            load(language)
        }
    }

    private fun load(language: HrDocumentLanguage) {
        requestedSha = language.contentSha256
        contentJob?.cancel()
        closePages()
        state.update { it.copy(selectedLanguage = language, contentLoading = true, contentError = null) }
        contentJob = viewModelScope.launch {
            when (val result = repository.content(documentId, language.code)) {
                is HrContentResult.Available -> {
                    // Opened without interruption so an opened file is never lost unclosed.
                    val pages = withContext(NonCancellable) { pdfOpener.open(result.filePath) }
                    if (!isActive) {
                        pages?.close()
                        return@launch
                    }
                    state.update {
                        if (pages == null) {
                            it.copy(contentLoading = false, contentError = R.string.hr_content_open_failed)
                        } else {
                            it.copy(contentLoading = false, pages = pages)
                        }
                    }
                }
                is HrContentResult.Unavailable -> state.update { it.copy(contentLoading = false, contentError = result.problem.messageRes()) }
            }
        }
    }

    private fun closePages() {
        val pages = state.value.pages ?: return
        state.update { it.copy(pages = null) }
        pages.close()
    }

    private fun HrDocumentDetailUiState.after(result: HrSignResult): HrDocumentDetailUiState = when (result) {
        is HrSignResult.Signed -> copy(outcome = SignOutcomeUi(R.string.hr_signed_success, StatusTone.Positive), strokes = emptyList())
        is HrSignResult.Saved -> copy(
            outcome = SignOutcomeUi(
                if (result.hold == SignatureFailure.ACCESS_REQUIRED) R.string.hr_state_waiting_access else R.string.hr_saved_offline,
                StatusTone.Info,
            ),
            strokes = emptyList(),
        )
        is HrSignResult.Failed -> copy(
            outcome = SignOutcomeUi(result.failure.messageRes(), StatusTone.Danger),
            strokes = emptyList(),
            declarationAccepted = declarationAccepted && result.failure != SignatureFailure.DOCUMENT_CHANGED,
        )
        HrSignResult.AlreadySigned -> copy(outcome = SignOutcomeUi(R.string.hr_already_signed, StatusTone.Positive), strokes = emptyList())
        HrSignResult.Withdrawn -> copy(outcome = SignOutcomeUi(R.string.hr_withdrawn, StatusTone.Neutral), strokes = emptyList())
        HrSignResult.Incomplete -> copy(message = R.string.hr_sign_needs)
        HrSignResult.NotLoggedIn -> copy(message = R.string.error_not_logged_in)
    }
}

private const val SIGN_KEY = "sign"

/** [onOpenReceipt] shows the signed proof PDF in the app's own viewer, which downloads it; no PDF app is needed. */
@Composable
fun HrDocumentDetailRoute(onBack: () -> Unit, onOpenReceipt: (FileToView) -> Unit, viewModel: HrDocumentDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val fallbackTitle = state.document?.let { preferredLanguage(it)?.title } ?: stringResource(R.string.hr_documents_title)
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    HrDocumentDetailScreen(
        state = state,
        onBack = onBack,
        onSelectLanguage = viewModel::onSelectLanguage,
        onDeclarationChanged = viewModel::onDeclarationChanged,
        onStroke = viewModel::onStroke,
        onClearSignature = viewModel::onClearSignature,
        onSign = viewModel::onSign,
        onOpenReceipt = {
            state.document?.let { document ->
                // The viewer's bar shows the document's name in the language it was signed in.
                val title = document.language(document.signedLanguage)?.title ?: fallbackTitle
                onOpenReceipt(FileToView(document.id, title, RECEIPT_CONTENT_TYPE))
            }
        },
        onRetryContent = viewModel::onRetryContent,
        onRetryLoad = viewModel::refresh,
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun HrDocumentDetailScreen(
    state: HrDocumentDetailUiState,
    onBack: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onDeclarationChanged: (Boolean) -> Unit,
    onStroke: (List<SignaturePoint>) -> Unit,
    onClearSignature: () -> Unit,
    onSign: () -> Unit,
    onOpenReceipt: () -> Unit,
    onRetryContent: () -> Unit,
    onRetryLoad: () -> Unit,
    onMessageShown: () -> Unit,
    now: Instant = rememberCurrentInstant(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)
    val listState = rememberLazyListState()
    val signingVisible by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == SIGN_KEY } } }
    var zoomPage by rememberSaveable { mutableStateOf<Int?>(null) }
    val document = state.document
    Scaffold(
        topBar = {
            DriverTopBar(
                title = state.selectedLanguage?.title ?: document?.let { preferredLanguage(it)?.title } ?: stringResource(R.string.hr_documents_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
        bottomBar = { DetailActionBar(state, signingVisible, onSign, onOpenReceipt) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            state = listState,
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
                .testTag("hr_detail"),
        ) {
            when (state.lookup) {
                HrDocumentLookup.Loading -> loadingItems(state.loadError, onRetryLoad)
                HrDocumentLookup.Missing -> item(key = "missing") {
                    EmptyState(Icons.Filled.SearchOff, stringResource(R.string.hr_missing), body = null, modifier = Modifier.testTag("hr_missing"))
                }
                is HrDocumentLookup.Found -> documentItems(
                    state = state,
                    now = now,
                    onSelectLanguage = onSelectLanguage,
                    onZoom = { zoomPage = it },
                    onRetryContent = onRetryContent,
                    onDeclarationChanged = onDeclarationChanged,
                    onStroke = onStroke,
                    onClearSignature = onClearSignature,
                )
            }
        }
    }
    val pages = state.pages
    val zoom = zoomPage
    if (pages != null && zoom != null && zoom < pages.pageCount) PdfZoomDialog(pages, zoom) { zoomPage = null }
}

private fun LazyListScope.loadingItems(@StringRes loadError: Int?, onRetryLoad: () -> Unit) {
    if (loadError == null) {
        item(key = "loading") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("hr_detail_loading")) }
    } else {
        item(key = "load_error") {
            EmptyState(Icons.Filled.CloudOff, stringResource(loadError), body = null, modifier = Modifier.testTag("hr_detail_error")) {
                Spacer(Modifier.height(12.dp))
                SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRetryLoad, icon = Icons.Filled.Refresh)
            }
        }
    }
}

private fun LazyListScope.documentItems(
    state: HrDocumentDetailUiState,
    now: Instant,
    onSelectLanguage: (String) -> Unit,
    onZoom: (Int) -> Unit,
    onRetryContent: () -> Unit,
    onDeclarationChanged: (Boolean) -> Unit,
    onStroke: (List<SignaturePoint>) -> Unit,
    onClearSignature: () -> Unit,
) {
    val document = state.document ?: return
    state.outcome?.let { outcome ->
        item(key = "outcome") {
            StatusBanner(stringResource(outcome.message), outcome.tone.icon(), outcome.tone, Modifier.testTag("hr_outcome"))
        }
    }
    item(key = "header") { HeaderCard(document, state.local, now) }
    if (document.status == HrDocumentStatus.SIGNED) {
        item(key = "signed") { SignedCard(document) }
        return
    }
    if (document.status != HrDocumentStatus.PENDING) return
    if (document.languages.size > 1) {
        item(key = "languages") { LanguageChips(document.languages, state.selectedLanguage?.code, onSelectLanguage) }
    }
    val pages = state.pages
    val contentError = state.contentError
    when {
        contentError != null -> item(key = "content_error") {
            EmptyState(Icons.Filled.CloudOff, stringResource(contentError), body = null, modifier = Modifier.testTag("hr_content_error")) {
                Spacer(Modifier.height(12.dp))
                SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRetryContent, icon = Icons.Filled.Refresh)
            }
        }
        pages == null -> item(key = "content_loading") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().testTag("hr_content_loading")) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.hr_content_loading), style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
            }
        }
        else -> {
            item(key = "zoom_hint") {
                Text(stringResource(R.string.hr_page_zoom), style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
            }
            items(count = pages.pageCount, key = { "page_$it" }) { index -> PdfPage(pages, index, onZoom = { onZoom(index) }) }
            if (state.canBeSigned) {
                item(key = SIGN_KEY) { SigningSection(state, onDeclarationChanged, onStroke, onClearSignature) }
            }
        }
    }
}

@Composable
private fun HeaderCard(document: HrDocument, local: LocalSignature?, now: Instant) {
    val locale = currentLocale()
    SectionCard(modifier = Modifier.testTag("hr_header")) {
        HrDocumentStatusLine(document, local, now)
        Spacer(Modifier.height(8.dp))
        val details = listOfNotNull(
            document.version?.let { stringResource(R.string.hr_version, it) },
            document.sentAt?.let { stringResource(R.string.hr_sent_at, dateTime(it, locale)) },
        )
        if (details.isNotEmpty()) {
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
        }
        val reference = document.referenceLanguage
        if (reference != null && document.languages.size > 1) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.hr_reference_language, languageName(reference)),
                style = MaterialTheme.typography.bodySmall,
                color = DriverTheme.colors.muted,
            )
        }
    }
}

@Composable
private fun SignedCard(document: HrDocument) {
    val language = document.signedLanguage ?: return
    SectionCard(modifier = Modifier.testTag("hr_signed_details")) {
        Text(stringResource(R.string.hr_signed_language, languageName(language)), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LanguageChips(languages: List<HrDocumentLanguage>, selected: String?, onSelect: (String) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Translate, contentDescription = null, tint = DriverTheme.colors.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.hr_language_label), style = MaterialTheme.typography.titleSmall)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            languages.forEach { language ->
                FilterChip(
                    selected = language.code == selected,
                    onClick = { onSelect(language.code) },
                    label = { Text(languageName(language.code), style = MaterialTheme.typography.titleSmall) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("hr_language_${language.code}"),
                )
            }
        }
    }
}

@Composable
private fun SigningSection(
    state: HrDocumentDetailUiState,
    onDeclarationChanged: (Boolean) -> Unit,
    onStroke: (List<SignaturePoint>) -> Unit,
    onClearSignature: () -> Unit,
) {
    SectionCard(modifier = Modifier.testTag("hr_sign_section")) {
        state.selectedLanguage?.declaration?.let { declaration ->
            Text(stringResource(R.string.hr_declaration_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(declaration, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(
                    value = state.declarationAccepted,
                    enabled = !state.submitting,
                    role = Role.Checkbox,
                    onValueChange = onDeclarationChanged,
                )
                .testTag("hr_declaration"),
        ) {
            Checkbox(checked = state.declarationAccepted, onCheckedChange = null, enabled = !state.submitting)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.hr_declaration_accept), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.hr_signature_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.hr_signature_hint), style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
        Spacer(Modifier.height(8.dp))
        SignaturePad(
            strokes = state.strokes,
            onStroke = onStroke,
            description = stringResource(R.string.hr_signature_pad),
            enabled = !state.submitting,
        )
        Spacer(Modifier.height(8.dp))
        SecondaryActionButton(
            text = stringResource(R.string.hr_signature_clear),
            onClick = onClearSignature,
            icon = Icons.AutoMirrored.Filled.Backspace,
            enabled = state.strokes.isNotEmpty() && !state.submitting,
            modifier = Modifier.testTag("hr_signature_clear"),
        )
    }
}

/** The screen's single dominant action: sign when the signature section is on screen, open the signed PDF once signed. */
@Composable
private fun DetailActionBar(state: HrDocumentDetailUiState, signingVisible: Boolean, onSign: () -> Unit, onOpenReceipt: () -> Unit) {
    val document = state.document ?: return
    when {
        document.status == HrDocumentStatus.SIGNED -> ActionBar {
            PrimaryActionButton(
                text = stringResource(R.string.hr_open_receipt),
                onClick = onOpenReceipt,
                icon = Icons.Filled.PictureAsPdf,
                enabled = document.receiptAvailable,
                modifier = Modifier.testTag("hr_open_receipt"),
            )
            if (!document.receiptAvailable) Hint(stringResource(R.string.hr_receipt_pending))
        }
        state.canBeSigned && state.pages != null -> ActionBar {
            if (signingVisible) {
                PrimaryActionButton(
                    text = stringResource(R.string.hr_sign_action),
                    onClick = onSign,
                    icon = Icons.Filled.Draw,
                    enabled = state.readyToSign,
                    loading = state.submitting,
                    modifier = Modifier.testTag("hr_sign"),
                )
                if (!state.readyToSign && !state.submitting) Hint(stringResource(R.string.hr_sign_needs))
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("hr_scroll_hint"),
                ) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = null, tint = DriverTheme.colors.brand)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.hr_scroll_to_sign), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DriverTheme.colors.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun StatusTone.icon(): ImageVector = when (this) {
    StatusTone.Positive -> Icons.Filled.CheckCircle
    StatusTone.Danger -> Icons.Filled.ErrorOutline
    StatusTone.Neutral -> Icons.Filled.Block
    StatusTone.Info, StatusTone.Warning -> Icons.Filled.CloudUpload
}

private const val RECEIPT_CONTENT_TYPE = "application/pdf"
