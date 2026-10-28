package com.dynamictransport.dynamicone.feature.cashpayments

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import com.dynamictransport.dynamicone.domain.model.CashPayment
import com.dynamictransport.dynamicone.domain.model.CashPaymentLookup
import com.dynamictransport.dynamicone.domain.model.CashPaymentScope
import com.dynamictransport.dynamicone.domain.model.CashPaymentState
import com.dynamictransport.dynamicone.domain.model.CashSignFailure
import com.dynamictransport.dynamicone.domain.model.CashSignResult
import com.dynamictransport.dynamicone.domain.model.DrawnSignature
import com.dynamictransport.dynamicone.domain.model.LocalCashSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.SignaturePoint
import com.dynamictransport.dynamicone.domain.repository.AuthRepository
import com.dynamictransport.dynamicone.domain.repository.CashPaymentRepository
import com.dynamictransport.dynamicone.domain.rules.HrDocumentRules
import com.dynamictransport.dynamicone.feature.common.ActionBar
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.messageRes
import com.dynamictransport.dynamicone.feature.hrdocuments.SignaturePad
import com.dynamictransport.dynamicone.feature.personnelfiles.FileToView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Argument names of [com.dynamictransport.dynamicone.app.navigation.CashPaymentDetailDestination]. */
const val CASH_SCOPE_ARG = "scope"
const val CASH_PAYMENT_ID_ARG = "paymentId"

/** The result of the last signature, shown on the screen until it is left. */
data class CashOutcomeUi(@param:StringRes val message: Int, val tone: StatusTone)

data class CashPaymentDetailUiState(
    val scope: CashPaymentScope = CashPaymentScope.DISBURSEMENTS,
    val lookup: CashPaymentLookup = CashPaymentLookup.Loading,
    /** The payment could not be read from the server and is not known from a list either. */
    @param:StringRes val loadError: Int? = null,
    /** The server answered for this payment while this screen was open: only then may it be signed (P5). */
    val fresh: Boolean = false,
    val refreshing: Boolean = false,
    val declarationAccepted: Boolean = false,
    /** Finished strokes of the signature being drawn. */
    val strokes: List<List<SignaturePoint>> = emptyList(),
    val submitting: Boolean = false,
    val outcome: CashOutcomeUi? = null,
    @param:StringRes val message: Int? = null,
    /** The server confirmed the hand-over on this screen: the signed receipt opens once. */
    val openReceipt: Boolean = false,
    /** The account lost its permission: the screen closes after the message. */
    val closeRequested: Boolean = false,
) {
    val payment: CashPayment? get() = (lookup as? CashPaymentLookup.Found)?.payment
    val local: LocalCashSignature? get() = (lookup as? CashPaymentLookup.Found)?.local

    /** Signed on the phone, not confirmed by the server: the same command is sent again. */
    val waitingToSend: Boolean
        get() = local?.state == LocalSignatureState.PENDING || local?.state == LocalSignatureState.SENDING

    /** The server's current answer allows a signature from this phone and nothing is waiting. */
    val canSign: Boolean
        get() {
            val current = payment ?: return false
            return fresh && current.state == CashPaymentState.APPROVED && current.canSign && current.formHash != null && !waitingToSend
        }

    val readyToSign: Boolean
        get() = canSign && declarationAccepted && HrDocumentRules.isValid(strokes) && !submitting
}

/**
 * One approved cash payment: the form finance approved, the recipient's finger signature and the hand-over
 * declaration, then the signed receipt (docs/28). The detail is read from the server when the screen opens; the
 * signature is stored on the phone first and only the server's answer marks the payment as handed over.
 */
@HiltViewModel
class CashPaymentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CashPaymentRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val scope: CashPaymentScope = CashPaymentScope.fromWire(savedStateHandle[CASH_SCOPE_ARG]) ?: CashPaymentScope.OWN
    private val paymentId: String = checkNotNull(savedStateHandle[CASH_PAYMENT_ID_ARG])
    private val state = MutableStateFlow(CashPaymentDetailUiState(scope = scope))
    private val signFlight = SingleFlight()

    val uiState: StateFlow<CashPaymentDetailUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { repository.observePayment(scope, paymentId).collect(::onLookup) }
        refresh()
    }

    /** Reads the payment again (another device may have completed or finance cancelled it). */
    fun refresh() {
        if (state.value.refreshing) return
        state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val result = repository.refreshPayment(scope, paymentId)
            val error = result.messageRes()
            state.update {
                it.copy(
                    refreshing = false,
                    fresh = it.fresh || error == null,
                    // Only needed while the payment is not known at all; otherwise the known one is shown.
                    loadError = error?.takeIf { _ -> it.lookup is CashPaymentLookup.Loading },
                )
            }
        }
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

    /** "I received the money": stores and sends the signature. A nervous double tap never pays twice. */
    fun onSign() {
        val current = state.value
        val formHash = current.payment?.formHash ?: return
        if (!current.readyToSign) {
            state.update { it.copy(message = if (it.canSign) R.string.cash_sign_needs else R.string.cash_sign_not_ready) }
            return
        }
        submit(formHash, DrawnSignature(current.strokes, HrDocumentRules.PAD_ASPECT_RATIO))
    }

    /** Sends the signature waiting on the phone again, unchanged (same command id). */
    fun onResend() {
        val current = state.value
        if (!current.waitingToSend || current.submitting) return
        submit(current.payment?.formHash.orEmpty(), DrawnSignature(emptyList(), HrDocumentRules.PAD_ASPECT_RATIO))
    }

    fun onReceiptOpened() {
        state.update { it.copy(openReceipt = false) }
    }

    fun onMessageShown() {
        state.update { it.copy(message = null) }
    }

    private fun submit(formHash: String, signature: DrawnSignature) {
        signFlight.launch(viewModelScope) {
            state.update { it.copy(submitting = true, outcome = null) }
            val result = repository.sign(scope, paymentId, formHash, signature)
            state.update { it.copy(submitting = false).after(result) }
            if (result is CashSignResult.AccessRequired) auth.refreshProfile()
        }
    }

    private fun onLookup(lookup: CashPaymentLookup) {
        state.update { it.copy(lookup = lookup, loadError = if (lookup is CashPaymentLookup.Loading) it.loadError else null) }
    }

    private fun CashPaymentDetailUiState.after(result: CashSignResult): CashPaymentDetailUiState = when (result) {
        is CashSignResult.Signed -> copy(
            outcome = CashOutcomeUi(R.string.cash_signed_success, StatusTone.Positive),
            strokes = emptyList(),
            declarationAccepted = false,
            openReceipt = true,
        )
        is CashSignResult.Saved -> copy(outcome = CashOutcomeUi(R.string.cash_saved_offline, StatusTone.Warning), strokes = emptyList())
        is CashSignResult.Failed -> copy(
            outcome = CashOutcomeUi(result.failure.messageRes(), StatusTone.Danger),
            strokes = emptyList(),
            // A changed form has to be read and confirmed again.
            declarationAccepted = declarationAccepted && result.failure != CashSignFailure.FORM_CHANGED && result.failure != CashSignFailure.CONFLICT,
        )
        CashSignResult.AlreadyReceived -> copy(outcome = CashOutcomeUi(R.string.cash_already_received, StatusTone.Positive), strokes = emptyList())
        CashSignResult.Cancelled -> copy(outcome = CashOutcomeUi(R.string.cash_cancelled, StatusTone.Neutral), strokes = emptyList())
        CashSignResult.AccessRequired -> copy(
            outcome = CashOutcomeUi(R.string.cash_access_removed, StatusTone.Danger),
            strokes = emptyList(),
            closeRequested = true,
        )
        CashSignResult.Missing -> copy(strokes = emptyList())
        CashSignResult.Incomplete -> copy(message = R.string.cash_sign_needs)
        CashSignResult.NotLoggedIn -> copy(message = R.string.error_not_logged_in)
    }
}

/** [onOpenReceipt] shows the signed receipt in the app's own viewer, which downloads it with the session's token. */
@Composable
fun CashPaymentDetailRoute(onBack: () -> Unit, onOpenReceipt: (FileToView) -> Unit, viewModel: CashPaymentDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val fallbackTitle = stringResource(R.string.cash_detail_title)
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val openReceipt = {
        state.payment?.let { payment ->
            onOpenReceipt(FileToView(payment.id, payment.title ?: fallbackTitle, RECEIPT_CONTENT_TYPE))
        }
        Unit
    }
    // The first confirmed hand-over opens the signed receipt by itself (docs/28: "show the signed receipt").
    LaunchedEffect(state.openReceipt) {
        if (state.openReceipt) {
            viewModel.onReceiptOpened()
            openReceipt()
        }
    }
    // Permission lost (403): the account's modules are read again and this screen closes.
    LaunchedEffect(state.closeRequested) {
        if (state.closeRequested) onBack()
    }
    CashPaymentDetailScreen(
        state = state,
        onBack = onBack,
        onDeclarationChanged = viewModel::onDeclarationChanged,
        onStroke = viewModel::onStroke,
        onClearSignature = viewModel::onClearSignature,
        onSign = viewModel::onSign,
        onResend = viewModel::onResend,
        onOpenReceipt = openReceipt,
        onRetryLoad = viewModel::refresh,
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun CashPaymentDetailScreen(
    state: CashPaymentDetailUiState,
    onBack: () -> Unit,
    onDeclarationChanged: (Boolean) -> Unit,
    onStroke: (List<SignaturePoint>) -> Unit,
    onClearSignature: () -> Unit,
    onSign: () -> Unit,
    onResend: () -> Unit,
    onOpenReceipt: () -> Unit,
    onRetryLoad: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)
    Scaffold(
        topBar = {
            DriverTopBar(
                title = state.payment?.title ?: stringResource(state.scope.titleRes()),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
        bottomBar = { DetailActionBar(state, onSign, onResend, onOpenReceipt) },
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
                .testTag("cash_detail"),
        ) {
            when (state.lookup) {
                CashPaymentLookup.Loading -> loadingItems(state.loadError, onRetryLoad)
                CashPaymentLookup.Missing -> item(key = "missing") {
                    EmptyState(Icons.Filled.SearchOff, stringResource(R.string.cash_missing), body = null, modifier = Modifier.testTag("cash_missing"))
                }
                is CashPaymentLookup.Found -> paymentItems(state, onDeclarationChanged, onStroke, onClearSignature)
            }
        }
    }
}

private fun LazyListScope.loadingItems(@StringRes loadError: Int?, onRetryLoad: () -> Unit) {
    if (loadError == null) {
        item(key = "loading") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("cash_detail_loading")) }
    } else {
        item(key = "load_error") {
            EmptyState(Icons.Filled.CloudOff, stringResource(loadError), body = null, modifier = Modifier.testTag("cash_detail_error")) {
                Spacer(Modifier.height(12.dp))
                SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRetryLoad, icon = Icons.Filled.Refresh)
            }
        }
    }
}

private fun LazyListScope.paymentItems(
    state: CashPaymentDetailUiState,
    onDeclarationChanged: (Boolean) -> Unit,
    onStroke: (List<SignaturePoint>) -> Unit,
    onClearSignature: () -> Unit,
) {
    val payment = state.payment ?: return
    state.outcome?.let { outcome ->
        item(key = "outcome") {
            StatusBanner(stringResource(outcome.message), outcome.tone.icon(), outcome.tone, Modifier.testTag("cash_outcome"))
        }
    }
    if (state.refreshing) {
        item(key = "refreshing") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
    }
    item(key = "header") { HeaderCard(payment, state.local) }
    item(key = "form") { FormCard(payment) }
    if (state.canSign) {
        item(key = "sign") { SigningSection(state, onDeclarationChanged, onStroke, onClearSignature) }
    } else if (state.fresh && payment.state == CashPaymentState.APPROVED && !payment.canSign && !state.waitingToSend) {
        item(key = "not_signable") {
            StatusBanner(stringResource(R.string.cash_sign_not_ready), Icons.Filled.ErrorOutline, StatusTone.Neutral, Modifier.testTag("cash_not_signable"))
        }
    }
}

/** Who gets how much: the officer checks the name, number and amount against the money in hand. */
@Composable
private fun HeaderCard(payment: CashPayment, local: LocalCashSignature?) {
    val locale = currentLocale()
    SectionCard(modifier = Modifier.testTag("cash_header")) {
        Text(payment.employeeName, style = MaterialTheme.typography.titleLarge)
        val details = listOfNotNull(
            payment.registrationNumber?.let { stringResource(R.string.cash_registration_number, it) },
            kindText(payment),
        )
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
        Spacer(Modifier.height(8.dp))
        Text(
            text = amountText(payment),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("cash_detail_amount"),
        )
        // The planned date is on the status line below; the amount stands alone.
        // approvedByName is the form's own text (the server may already include a label): shown as it came.
        val approval = listOfNotNull(
            payment.approvedByName,
            payment.approvedAt?.let { stringResource(R.string.cash_approved_at, cashDateTime(it, locale)) },
        )
        if (approval.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(approval.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
        }
        Spacer(Modifier.height(10.dp))
        CashPaymentStatusLine(payment, local)
    }
}

/** The approved form exactly as the server sent it: nothing is rewritten or translated on the phone (P10). */
@Composable
private fun FormCard(payment: CashPayment) {
    SectionCard(modifier = Modifier.testTag("cash_form")) {
        Text(stringResource(R.string.cash_form_title), style = MaterialTheme.typography.labelLarge, color = DriverTheme.colors.muted)
        payment.title?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.titleMedium)
        }
        if (payment.lines.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            payment.lines.forEach { line -> Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth()) }
        }
        payment.declaration?.let { declaration ->
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.cash_declaration_title), style = MaterialTheme.typography.labelLarge, color = DriverTheme.colors.muted)
            Spacer(Modifier.height(4.dp))
            Text(declaration, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("cash_declaration_text"))
        }
    }
}

@Composable
private fun SigningSection(
    state: CashPaymentDetailUiState,
    onDeclarationChanged: (Boolean) -> Unit,
    onStroke: (List<SignaturePoint>) -> Unit,
    onClearSignature: () -> Unit,
) {
    SectionCard(modifier = Modifier.testTag("cash_sign_section")) {
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
                .testTag("cash_declaration"),
        ) {
            Checkbox(checked = state.declarationAccepted, onCheckedChange = null, enabled = !state.submitting)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.cash_declaration_accept), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.cash_signature_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.cash_signature_hint), style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
        Spacer(Modifier.height(8.dp))
        SignaturePad(
            strokes = state.strokes,
            onStroke = onStroke,
            description = stringResource(R.string.cash_signature_pad),
            enabled = !state.submitting,
        )
        Spacer(Modifier.height(8.dp))
        SecondaryActionButton(
            text = stringResource(R.string.cash_signature_clear),
            onClick = onClearSignature,
            icon = Icons.AutoMirrored.Filled.Backspace,
            enabled = state.strokes.isNotEmpty() && !state.submitting,
            modifier = Modifier.testTag("cash_signature_clear"),
        )
    }
}

/** The screen's single dominant action: "I received the money", "send again" while waiting, the receipt once handed over. */
@Composable
private fun DetailActionBar(state: CashPaymentDetailUiState, onSign: () -> Unit, onResend: () -> Unit, onOpenReceipt: () -> Unit) {
    val payment = state.payment ?: return
    when {
        payment.state == CashPaymentState.RECEIVED -> ActionBar {
            PrimaryActionButton(
                text = stringResource(R.string.cash_open_receipt),
                onClick = onOpenReceipt,
                icon = Icons.Filled.PictureAsPdf,
                enabled = payment.receiptAvailable,
                modifier = Modifier.testTag("cash_open_receipt"),
            )
            if (!payment.receiptAvailable) Hint(stringResource(R.string.cash_receipt_pending))
        }
        state.waitingToSend -> ActionBar {
            PrimaryActionButton(
                text = stringResource(R.string.cash_resend_action),
                onClick = onResend,
                icon = Icons.Filled.CloudUpload,
                loading = state.submitting,
                modifier = Modifier.testTag("cash_resend"),
            )
            Hint(stringResource(R.string.cash_waiting_hint))
        }
        state.canSign -> ActionBar {
            PrimaryActionButton(
                text = stringResource(R.string.cash_sign_action),
                onClick = onSign,
                icon = Icons.Filled.Draw,
                enabled = state.readyToSign,
                loading = state.submitting,
                modifier = Modifier.testTag("cash_sign"),
            )
            if (!state.readyToSign && !state.submitting) Hint(stringResource(R.string.cash_sign_needs))
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
