package com.dynamictransport.dynamicone.feature.documents

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusBadge
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.component.toneContainer
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.DocumentSlot
import com.dynamictransport.dynamicone.domain.model.DocumentSlotState
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripDocument
import com.dynamictransport.dynamicone.domain.rules.DocumentRules
import com.dynamictransport.dynamicone.feature.common.ActionBar
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.LabeledDivider
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.OpenDocumentEffect
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.icon
import com.dynamictransport.dynamicone.feature.common.labelRes
import com.dynamictransport.dynamicone.feature.common.toStatusUi
import com.dynamictransport.dynamicone.feature.common.tripContextLabel

@Composable
fun DocumentsRoute(
    onBack: (() -> Unit)?,
    onScan: (ScanRequest) -> Unit,
    acceptedScan: String?,
    onAcceptedScanConsumed: () -> Unit,
    viewModel: DocumentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onGalleryResult(uri?.toString())
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onPdfResult(uri?.toString())
    }
    val scanRequest = state.scanRequest
    LaunchedEffect(scanRequest) {
        if (scanRequest != null) {
            viewModel.onScanLaunched()
            onScan(scanRequest)
        }
    }
    // A photo accepted by the scanner is uploaded exactly once; the hand-over survives process death.
    LaunchedEffect(acceptedScan) {
        if (acceptedScan != null) {
            onAcceptedScanConsumed()
            viewModel.onScanAccepted(acceptedScan)
        }
    }
    OpenDocumentEffect(state.openRequest, viewModel::onOpenRequestHandled, viewModel::onOpenFailed)
    DocumentsScreen(
        state = state,
        onBack = onBack,
        onSelectSlot = viewModel::onSelectSlot,
        onTakePhoto = viewModel::onTakePhoto,
        onPickFromGallery = {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onPickPdf = { pickPdf.launch(arrayOf(DocumentRules.PDF)) },
        onMessageShown = viewModel::onMessageShown,
        onOpenSlotDocument = viewModel::onOpenSlotDocument,
        onOpenFile = viewModel::onOpenFile,
        onRetry = viewModel::onRetry,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DocumentsScreen(
    state: DocumentsUiState,
    onBack: (() -> Unit)?,
    onSelectSlot: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    onMessageShown: () -> Unit,
    onPickPdf: () -> Unit = {},
    onOpenSlotDocument: (DocumentSlot) -> Unit = {},
    onOpenFile: (TripDocument) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)
    val trip = state.trip

    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.documents_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
        bottomBar = {
            if (trip != null && state.canUpload && state.slots != null) {
                ActionBar { UploadActions(state, onTakePhoto, onPickFromGallery, onPickPdf) }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .readableWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        ) {
            if (trip == null) {
                EmptyState(
                    icon = Icons.Filled.Description,
                    title = stringResource(R.string.documents_no_trip_title),
                    body = stringResource(R.string.documents_no_trip_body),
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(trip.tripNo, style = MaterialTheme.typography.titleMedium)
                    trip.orderNumber?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
                    }
                }
                if (state.slots != null) {
                    val open = state.openCount
                    StatusBadge(
                        text = if (open > 0) {
                            pluralStringResource(R.plurals.documents_open_count, open, open)
                        } else {
                            stringResource(R.string.documents_none_open)
                        },
                        icon = if (open > 0) Icons.Filled.Info else Icons.Filled.CheckCircle,
                        tone = if (open > 0) StatusTone.Warning else StatusTone.Positive,
                        modifier = Modifier.testTag("documents_open_count"),
                    )
                }
            }
            if (!state.canUpload) {
                StatusBanner(
                    message = stringResource(R.string.documents_upload_not_possible),
                    icon = Icons.Filled.Info,
                    tone = StatusTone.Neutral,
                    modifier = Modifier.testTag("documents_upload_not_possible"),
                )
            }
            if (state.stale) {
                StatusBanner(
                    message = stringResource(R.string.data_stale),
                    icon = Icons.Filled.SyncProblem,
                    tone = StatusTone.Warning,
                    actionLabel = if (state.refreshing) null else stringResource(R.string.common_retry),
                    onAction = onRetry,
                    modifier = Modifier.testTag("documents_stale"),
                )
            }
            val slots = state.slots
            val loadError = state.loadError
            when {
                slots != null -> {
                    state.groups.forEach { (type, group) ->
                        SlotGroup(type, group, trip, state, onSelectSlot, onOpenSlotDocument)
                    }
                    if (state.files.isNotEmpty()) FilesCard(state.files, onOpenFile)
                }
                loadError != null -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(loadError),
                    body = null,
                    modifier = Modifier.testTag("documents_load_error"),
                ) {
                    Spacer(Modifier.height(12.dp))
                    SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRetry, icon = Icons.Filled.Refresh)
                }
                else -> LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("documents_loading"),
                )
            }
        }
    }
}

@Composable
private fun UploadActions(
    state: DocumentsUiState,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    onPickPdf: () -> Unit,
) {
    val slot = state.selectedSlot
    if (slot == null) {
        Text(
            text = stringResource(R.string.documents_select_slot),
            style = MaterialTheme.typography.titleSmall,
            color = DriverTheme.colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        )
        return
    }
    PrimaryActionButton(
        text = stringResource(R.string.documents_take_photo, stringResource(slot.type.labelRes())),
        onClick = onTakePhoto,
        icon = Icons.Filled.PhotoCamera,
        loading = state.submitting,
        modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryActionButton(
            text = stringResource(R.string.documents_pick_gallery),
            onClick = onPickFromGallery,
            icon = Icons.Filled.PhotoLibrary,
            enabled = !state.submitting,
            modifier = Modifier.weight(1f),
        )
        if (slot.type?.photoOnly != true) {
            SecondaryActionButton(
                text = stringResource(R.string.documents_pick_pdf),
                onClick = onPickPdf,
                icon = Icons.Filled.PictureAsPdf,
                enabled = !state.submitting,
                modifier = Modifier
                    .weight(1f)
                    .testTag("documents_pick_pdf"),
            )
        }
    }
}

/** One category; each slot (per stop / container) is shown and uploaded separately. */
@Composable
private fun SlotGroup(
    type: DocumentType?,
    slots: List<DocumentSlot>,
    trip: Trip,
    state: DocumentsUiState,
    onSelectSlot: (String) -> Unit,
    onOpenSlotDocument: (DocumentSlot) -> Unit,
) {
    SectionCard(modifier = Modifier.testTag("slot_group_${type?.name ?: "UNKNOWN"}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(type.icon(), contentDescription = null, tint = DriverTheme.colors.brand)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (type == null) slots.firstNotNullOfOrNull { it.label } ?: stringResource(type.labelRes()) else stringResource(type.labelRes()),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(4.dp))
        slots.forEachIndexed { index, slot ->
            if (index > 0) LabeledDivider()
            SlotRow(
                slot = slot,
                trip = trip,
                selected = state.canUpload && slot.key == state.selectedSlotKey,
                selectable = state.canUpload,
                onSelect = { onSelectSlot(slot.key) },
                onOpen = if (slot.documentId != null) ({ onOpenSlotDocument(slot) }) else null,
            )
        }
    }
}

@Composable
private fun SlotRow(
    slot: DocumentSlot,
    trip: Trip,
    selected: Boolean,
    selectable: Boolean,
    onSelect: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val status = slot.toStatusUi()
    val statusColors = toneContainer(status.tone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) DriverTheme.colors.infoContainer else Color.Transparent)
            .selectable(selected = selected, enabled = selectable, onClick = onSelect, role = Role.RadioButton)
            .heightIn(min = 64.dp)
            .padding(8.dp)
            .testTag("slot_${slot.key}"),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tripContextLabel(trip, slot.stopId, slot.containerId) ?: stringResource(R.string.slot_trip_level),
                style = MaterialTheme.typography.titleSmall,
            )
            val requirement = slot.required?.let { stringResource(if (it) R.string.slot_required else R.string.slot_optional) }
            Text(
                text = listOfNotNull(stringResource(status.label), requirement).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = DriverTheme.colors.muted,
                modifier = Modifier.testTag("slot_state_${slot.key}"),
            )
            slot.fileName?.let {
                Text(
                    text = stringResource(R.string.documents_active_file, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = DriverTheme.colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            slot.reviewNote?.let { note ->
                Text(
                    text = stringResource(if (slot.state == DocumentSlotState.REJECTED) R.string.slot_review_note else R.string.slot_review_comment, note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (slot.state == DocumentSlotState.REJECTED) DriverTheme.colors.danger else DriverTheme.colors.muted,
                    modifier = Modifier.testTag("slot_review_note_${slot.key}"),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .background(statusColors.container, CircleShape),
        ) {
            Icon(status.icon, contentDescription = null, tint = statusColors.content, modifier = Modifier.size(20.dp))
        }
        if (onOpen != null) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onOpen, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Visibility, contentDescription = stringResource(R.string.documents_open), tint = DriverTheme.colors.brand)
            }
        }
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.documents_selected), tint = DriverTheme.colors.brand)
        }
    }
}

/** Every uploaded file, including earlier revisions of a slot. */
@Composable
private fun FilesCard(files: List<TripDocument>, onOpenFile: (TripDocument) -> Unit) {
    val locale = currentLocale()
    SectionCard(modifier = Modifier.testTag("document_files")) {
        Text(stringResource(R.string.documents_files_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        files.forEachIndexed { index, file ->
            if (index > 0) LabeledDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { onOpenFile(file) }
                    .testTag("document_file_${file.id}"),
            ) {
                Icon(file.type.icon(), contentDescription = null, tint = DriverTheme.colors.brand)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(file.type.labelRes()), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = listOfNotNull(file.fileName, file.submittedAt?.let { DriverFormats.dayTime(it, locale) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = DriverTheme.colors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Filled.Visibility, contentDescription = stringResource(R.string.documents_open), tint = DriverTheme.colors.brand)
            }
        }
    }
}
