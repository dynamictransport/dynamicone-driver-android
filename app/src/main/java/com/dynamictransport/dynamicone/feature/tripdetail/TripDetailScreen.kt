package com.dynamictransport.dynamicone.feature.tripdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.RvHookup
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SearchOff
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ActionTone
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.InfoRow
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.QuickActionTile
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusBadge
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.ActionReceipt
import com.dynamictransport.dynamicone.domain.model.BreakState
import com.dynamictransport.dynamicone.domain.model.DocumentSlot
import com.dynamictransport.dynamicone.domain.model.DocumentSlotState
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import com.dynamictransport.dynamicone.domain.model.PendingTripAction
import com.dynamictransport.dynamicone.domain.model.StopStatus
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripExecution
import com.dynamictransport.dynamicone.domain.model.TripLocation
import com.dynamictransport.dynamicone.domain.model.TripStop
import com.dynamictransport.dynamicone.domain.statemachine.PrimaryAction
import com.dynamictransport.dynamicone.feature.common.ActionScaffold
import com.dynamictransport.dynamicone.feature.common.BlockedAction
import com.dynamictransport.dynamicone.feature.common.CoordinateClipboard
import com.dynamictransport.dynamicone.feature.common.CoordinateFormat
import com.dynamictransport.dynamicone.feature.common.DriverActionButton
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.LabeledDivider
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.NavigationLinks
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.StatusUiBadge
import com.dynamictransport.dynamicone.feature.common.SyncStateBadge
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.documentLabel
import com.dynamictransport.dynamicone.feature.common.executionActionLabel
import com.dynamictransport.dynamicone.feature.common.icon
import com.dynamictransport.dynamicone.feature.common.labelRes
import com.dynamictransport.dynamicone.feature.common.messageRes
import com.dynamictransport.dynamicone.feature.common.receiptLabelRes
import com.dynamictransport.dynamicone.feature.common.toActionUi
import com.dynamictransport.dynamicone.feature.common.toStatusUi
import com.dynamictransport.dynamicone.feature.common.tripContextLabel
import com.dynamictransport.dynamicone.feature.common.vehicleLabel

@Composable
fun TripDetailRoute(
    onBack: () -> Unit,
    /** Trip id and, optionally, the checklist slot to select first. */
    onOpenDocuments: (String, String?) -> Unit,
    onOpenMessages: (String) -> Unit,
    onReject: (String) -> Unit,
    onReportIncident: (String) -> Unit,
    onStartShift: () -> Unit = {},
    onConfirmTrailer: (String) -> Unit = {},
    viewModel: TripDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    TripDetailScreen(
        state = state,
        onBack = onBack,
        onPrimaryAction = { action ->
            // A daily shift starts at the vehicle: tag and check (docs/16).
            if (action == PrimaryAction.StartShift && state.shiftStartsAtVehicle) onStartShift() else viewModel.onPrimaryAction(action)
        },
        onSecondaryAction = { action ->
            when (action) {
                TripActionType.REJECT_TRIP -> state.trip?.let { onReject(it.id) }
                TripActionType.START_BREAK -> viewModel.onStartBreak()
                else -> Unit
            }
        },
        onExecutionAction = { action ->
            // A problem report needs a category and note: it is written on the report screen.
            if (action.code == ExecutionActionCode.PROBLEM) state.trip?.let { onReportIncident(it.id) } else viewModel.onExecutionAction(action)
        },
        onResendPending = viewModel::onResendPending,
        onDiscardPending = viewModel::onDiscardPending,
        onOpenDocuments = { slot -> state.trip?.let { onOpenDocuments(it.id, slot) } },
        onOpenMessages = { state.trip?.let { onOpenMessages(it.id) } },
        onOpenNavigation = { point, label ->
            if (!NavigationLinks.open(context, point, label)) viewModel.showMessage(R.string.navigation_unavailable)
        },
        onCopyCoordinates = { point, label ->
            if (CoordinateClipboard.copy(context, point, label.orEmpty()) && !CoordinateClipboard.systemConfirmsCopy) {
                viewModel.showMessage(R.string.coordinates_copied)
            }
        },
        onReportIncident = { state.trip?.let { onReportIncident(it.id) } },
        onConfirmTrailer = { state.trip?.let { onConfirmTrailer(it.id) } },
        onMessageShown = viewModel::onMessageShown,
        onRetry = viewModel::onRetry,
    )
}

@Composable
fun TripDetailScreen(
    state: TripDetailUiState,
    onBack: () -> Unit,
    onPrimaryAction: (PrimaryAction) -> Unit,
    onSecondaryAction: (TripActionType) -> Unit,
    /** Optionally with the checklist slot to select first. */
    onOpenDocuments: (String?) -> Unit,
    onOpenNavigation: (GeoPoint, String?) -> Unit,
    onReportIncident: () -> Unit,
    onMessageShown: () -> Unit,
    onRetry: () -> Unit = {},
    onExecutionAction: (ExecutionAction) -> Unit = {},
    onResendPending: () -> Unit = {},
    onDiscardPending: () -> Unit = {},
    onOpenMessages: () -> Unit = {},
    onCopyCoordinates: (GeoPoint, String?) -> Unit = { _, _ -> },
    onConfirmTrailer: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)
    val trip = state.trip

    val actions: (@Composable ColumnScope.() -> Unit)? = if (trip != null && !trip.state.isTerminal) {
        {
            if (state.usesServerActions && !state.shiftStartRequired) {
                ExecutionActions(state, trip, onExecutionAction, onResendPending, onDiscardPending, onRetry, onOpenDocuments, onConfirmTrailer)
            } else {
                if (state.shiftStartRequired) CenteredHint(stringResource(R.string.trip_shift_start_first), Modifier.testTag("trip_shift_start_first"))
                TripActions(state, onPrimaryAction, onSecondaryAction)
            }
        }
    } else {
        null
    }

    ActionScaffold(
        topBar = {
            DriverTopBar(
                title = trip?.let { stringResource(R.string.trip_title, it.tripNo) }.orEmpty(),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
        actions = actions,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .readableWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        ) {
            val loadError = state.loadError
            when {
                trip != null -> TripDetailContent(trip, state, onOpenDocuments, onOpenMessages, onOpenNavigation, onCopyCoordinates, onReportIncident)

                state.loading -> LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("trip_loading"),
                )

                loadError != null -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(loadError),
                    body = null,
                    modifier = Modifier.testTag("trip_load_error"),
                ) {
                    Spacer(Modifier.height(12.dp))
                    SecondaryActionButton(
                        text = stringResource(R.string.common_retry),
                        onClick = onRetry,
                        icon = Icons.Filled.Refresh,
                    )
                }

                else -> EmptyState(icon = Icons.Filled.SearchOff, title = stringResource(R.string.trip_not_found), body = null)
            }
        }
    }
}

/** Local and legacy workflows: the plan of [com.dynamictransport.dynamicone.domain.statemachine.NextActionResolver]. */
@Composable
private fun TripActions(
    state: TripDetailUiState,
    onPrimaryAction: (PrimaryAction) -> Unit,
    onSecondaryAction: (TripActionType) -> Unit,
) {
    val plan = state.plan
    val primary = plan.primary
    val blocked = plan.blockedAction
    if (primary != null) {
        DriverActionButton(
            ui = primary.toActionUi(),
            onClick = { onPrimaryAction(primary) },
            loading = state.submitting,
            modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
        )
    } else if (blocked != null) {
        BlockedAction(blocked)
    }
    plan.secondary.forEach { secondary ->
        val ui = secondary.action.toActionUi()
        SecondaryActionButton(
            text = stringResource(ui.label),
            onClick = { onSecondaryAction(secondary.action) },
            icon = ui.icon,
            enabled = !state.submitting,
            danger = secondary.action == TripActionType.REJECT_TRIP,
            modifier = Modifier.testTag("secondary_${secondary.action.name}"),
        )
    }
}

/**
 * Trip action workflow: exactly the options of the server plan, or the stored action that must be resent first.
 * A step the server blocks for missing evidence stays visible but cannot be confirmed; uploading becomes the
 * dominant action (docs/18).
 */
@Composable
private fun ExecutionActions(
    state: TripDetailUiState,
    trip: Trip,
    onExecutionAction: (ExecutionAction) -> Unit,
    onResendPending: () -> Unit,
    onDiscardPending: () -> Unit,
    onRetry: () -> Unit,
    onOpenDocuments: (String?) -> Unit,
    onConfirmTrailer: () -> Unit,
) {
    val pending = state.pendingAction
    val execution = state.execution
    when {
        pending != null -> PendingActionPanel(pending, trip, state.submitting, onResendPending, onDiscardPending)

        // Starting is refused until the trailer under the truck is confirmed (docs/20): that is the one step.
        state.trailerConfirmationRequired -> {
            CenteredHint(stringResource(R.string.trailer_required_hint), Modifier.testTag("trailer_required"))
            DriverActionButton(
                text = stringResource(R.string.trailer_select_action),
                icon = Icons.Filled.RvHookup,
                tone = ActionTone.Neutral,
                requiresHold = false,
                onClick = onConfirmTrailer,
                loading = state.submitting,
                modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
            )
        }

        execution == null -> if (state.planLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("execution_loading"),
            )
            CenteredHint(stringResource(R.string.exec_plan_loading))
        } else {
            CenteredHint(stringResource(R.string.exec_plan_error), Modifier.testTag("execution_error"))
            SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRetry, icon = Icons.Filled.Refresh)
        }

        else -> {
            val progress = state.progressActions
            if (progress.isEmpty()) CenteredHint(stringResource(R.string.exec_no_action), Modifier.testTag("execution_no_action"))
            progress.forEachIndexed { index, action ->
                tripContextLabel(trip, action.stopId, action.containerId)?.let { context ->
                    Text(
                        text = context,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DriverTheme.colors.muted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DriverActionButton(
                    text = executionActionLabel(action),
                    icon = if (action.isBlocked) Icons.Filled.Lock else action.code.icon(),
                    tone = ActionTone.Positive,
                    requiresHold = action.completesLastOpenStop(trip) && !action.isBlocked,
                    onClick = { onExecutionAction(action) },
                    loading = state.submitting,
                    enabled = !action.isBlocked,
                    modifier = Modifier.testTag(if (index == 0) PRIMARY_ACTION_TAG else "execution_action_$index"),
                )
                if (action.isBlocked) BlockedByDocuments(action, execution, Modifier.testTag("execution_blocked_$index"))
            }
            if (progress.any { it.isBlocked }) {
                val upload = stringResource(R.string.exec_upload_documents)
                val openFirst = { onOpenDocuments(state.blockingDocuments.firstOrNull()?.key) }
                val uploadModifier = Modifier.testTag("execution_upload_documents")
                // Dominant only while no step can be confirmed; otherwise the open step stays the one primary action.
                if (progress.all { it.isBlocked }) {
                    PrimaryActionButton(text = upload, onClick = openFirst, icon = Icons.Filled.UploadFile, enabled = !state.submitting, modifier = uploadModifier)
                } else {
                    SecondaryActionButton(text = upload, onClick = openFirst, icon = Icons.Filled.UploadFile, enabled = !state.submitting, modifier = uploadModifier)
                }
            }
            execution.availableActions.filter { !it.isProgress }.forEach { action ->
                SecondaryActionButton(
                    text = executionActionLabel(action),
                    onClick = { onExecutionAction(action) },
                    icon = action.code.icon(),
                    enabled = !state.submitting,
                    modifier = Modifier.testTag("execution_${action.code.name}"),
                )
            }
        }
    }
}

/** Why a step cannot be confirmed yet: icon and text, the number of evidence slots it waits for. */
@Composable
private fun BlockedByDocuments(action: ExecutionAction, execution: TripExecution, modifier: Modifier = Modifier) {
    val count = execution.documents.count { it.key in action.blockingDocumentSlots }
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxWidth()) {
        StatusBadge(
            text = if (count > 0) {
                pluralStringResource(R.plurals.exec_blocked_documents, count, count)
            } else {
                stringResource(R.string.exec_blocked_generic)
            },
            icon = Icons.Filled.Lock,
            tone = StatusTone.Warning,
        )
    }
}

/** The trip's final open stop completes the trip: confirmed by holding, like other hard-to-undo steps. */
private fun ExecutionAction.completesLastOpenStop(trip: Trip): Boolean {
    if (code != ExecutionActionCode.COMPLETE_STOP) return false
    val open = trip.stops.filter { it.status != StopStatus.COMPLETED && it.status != StopStatus.SKIPPED }
    return open.size == 1 && open.single().id == stopId
}

@Composable
private fun PendingActionPanel(
    pending: PendingTripAction,
    trip: Trip,
    busy: Boolean,
    onResend: () -> Unit,
    onDiscard: () -> Unit,
) {
    val sending = pending.state == PendingCommandState.SENDING
    StatusBanner(
        message = listOfNotNull(
            stringResource(R.string.pending_action_title, executionActionLabel(pending.action)),
            tripContextLabel(trip, pending.action.stopId, pending.action.containerId),
            stringResource(pending.state.messageRes()),
        ).joinToString("\n"),
        icon = if (sending) Icons.Filled.CloudUpload else Icons.Filled.SyncProblem,
        tone = if (sending) StatusTone.Info else StatusTone.Warning,
        modifier = Modifier.testTag("pending_action"),
    )
    PrimaryActionButton(
        text = stringResource(R.string.pending_resend),
        onClick = onResend,
        icon = Icons.Filled.Refresh,
        loading = sending || busy,
        modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
    )
    SecondaryActionButton(
        text = stringResource(R.string.pending_discard),
        onClick = onDiscard,
        enabled = !sending && !busy,
        modifier = Modifier.testTag("pending_discard"),
    )
}

@Composable
private fun CenteredHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = DriverTheme.colors.muted,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun TripDetailContent(
    trip: Trip,
    state: TripDetailUiState,
    onOpenDocuments: (String?) -> Unit,
    onOpenMessages: () -> Unit,
    onOpenNavigation: (GeoPoint, String?) -> Unit,
    onCopyCoordinates: (GeoPoint, String?) -> Unit,
    onReportIncident: () -> Unit,
) {
    val locale = currentLocale()

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusUiBadge(trip.toStatusUi())
        SyncStateBadge(trip.syncState)
    }
    if (!state.isOnline) {
        StatusBanner(
            message = stringResource(if (state.offlineActionsQueued) R.string.sync_offline else R.string.sync_offline_real),
            icon = Icons.Filled.CloudOff,
            tone = StatusTone.Warning,
        )
    }
    if (state.isLegacyWorkflow) {
        StatusBanner(
            message = stringResource(R.string.exec_legacy_notice),
            icon = Icons.Filled.Info,
            tone = StatusTone.Neutral,
            modifier = Modifier.testTag("legacy_notice"),
        )
    }
    if (trip.syncState == SyncState.FAILED) {
        StatusBanner(stringResource(R.string.trip_failed_notice), Icons.Filled.SyncProblem, StatusTone.Danger)
    }
    if (trip.breakState == BreakState.ON_BREAK) {
        val title = stringResource(R.string.trip_on_break_title)
        StatusBanner(
            message = state.breakStartedAt
                ?.let { stringResource(R.string.value_separator, title, stringResource(R.string.trip_on_break_since, DriverFormats.time(it))) }
                ?: title,
            icon = Icons.Filled.FreeBreakfast,
            tone = StatusTone.Warning,
            modifier = Modifier.testTag("break_banner"),
        )
    }

    if (state.blockingDocuments.isNotEmpty()) RequiredDocumentsCard(state.blockingDocuments, trip, onOpenDocuments)

    state.navigationStop?.let { NextStopCard(it, onOpenNavigation, onCopyCoordinates) }

    SectionCard {
        val start = trip.pickupName ?: trip.origin?.name
        val end = trip.deliveryName ?: trip.destination?.name
        if (start != null || trip.pickupAddress != null) {
            StopBlock(stringResource(R.string.trip_pickup), start, trip.pickupAddress, Icons.Filled.Place)
            LabeledDivider()
        }
        if (end != null || trip.deliveryAddress != null) {
            StopBlock(stringResource(R.string.trip_delivery), end, trip.deliveryAddress, Icons.Filled.Flag)
            LabeledDivider()
        }
        trip.orderNumber?.let { InfoRow(Icons.Filled.Receipt, stringResource(R.string.trip_order), it) }
        trip.plannedStartAt?.let {
            InfoRow(Icons.Filled.Schedule, stringResource(R.string.trip_planned_start), DriverFormats.dayTime(it, locale))
        }
        if (trip.containers.isNotEmpty()) {
            trip.containers.forEachIndexed { index, container ->
                InfoRow(
                    icon = Icons.Filled.Inventory2,
                    label = stringResource(R.string.trip_container),
                    value = container.number ?: stringResource(R.string.value_unknown),
                    modifier = Modifier.testTag("trip_container_$index"),
                )
                container.sealNumber?.let { InfoRow(Icons.Filled.Lock, stringResource(R.string.trip_seal), it) }
            }
        } else {
            trip.containerNo?.let { InfoRow(Icons.Filled.Inventory2, stringResource(R.string.trip_container), it) }
            trip.sealNo?.let { InfoRow(Icons.Filled.Lock, stringResource(R.string.trip_seal), it) }
        }
        listOfNotNull(vehicleLabel(trip.vehicleCompanyCode, trip.vehiclePlate), trip.trailerPlate)
            .joinToString(" / ")
            .takeIf { it.isNotEmpty() }
            ?.let { InfoRow(Icons.Filled.LocalShipping, stringResource(R.string.trip_vehicle), it, Modifier.testTag("trip_vehicle")) }
        // The trailer the driver confirmed themselves, never the planned one (docs/20).
        state.confirmedTrailer?.let { trailer ->
            InfoRow(
                icon = Icons.Filled.RvHookup,
                label = stringResource(R.string.trailer_confirmed_label),
                value = listOfNotNull(trailer.registrationNumber, trailer.companyCode).joinToString(" · "),
                modifier = Modifier.testTag("trip_trailer"),
            )
        }
    }

    trip.instructions?.takeIf { it.isNotBlank() }?.let { instructions ->
        SectionCard {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = DriverTheme.colors.brand)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.trip_instructions), style = MaterialTheme.typography.titleSmall)
                    Text(instructions, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    if (trip.stops.isNotEmpty()) StopsCard(trip.stops, onOpenNavigation, onCopyCoordinates)

    if (state.timeline.isNotEmpty()) TimelineCard(state.timeline)

    if (state.receipts.isNotEmpty()) ReceiptsCard(state.receipts, trip)

    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.CardGap),
        modifier = Modifier.height(IntrinsicSize.Min),
    ) {
        val openDocuments = state.openDocumentCount ?: 0
        QuickActionTile(
            label = if (openDocuments > 0) {
                stringResource(R.string.nav_documents) + "\n" + pluralStringResource(R.plurals.trip_documents_missing, openDocuments, openDocuments)
            } else {
                stringResource(R.string.nav_documents)
            },
            icon = Icons.Filled.Description,
            onClick = { onOpenDocuments(null) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .testTag("tile_documents"),
        )
        QuickActionTile(
            label = if (state.unreadMessages > 0) {
                stringResource(R.string.trip_messages) + "\n" + pluralStringResource(R.plurals.messages_unread, state.unreadMessages, state.unreadMessages)
            } else {
                stringResource(R.string.trip_messages)
            },
            icon = Icons.Filled.Forum,
            onClick = onOpenMessages,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .testTag("tile_messages"),
        )
        // With trip actions a problem is one of the server's options in the action bar.
        if (!state.usesServerActions && !trip.state.isTerminal) {
            QuickActionTile(
                label = stringResource(R.string.quick_report_problem),
                icon = Icons.Filled.ReportProblem,
                onClick = onReportIncident,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }

    if (trip.state.isTerminal) {
        StatusBanner(stringResource(R.string.trip_closed), Icons.Filled.Info, StatusTone.Neutral)
    }
}

/**
 * The evidence the server requires before the offered step, each with its own upload button. The checklist
 * and its states are the server's; nothing is recalculated here (docs/18).
 */
@Composable
private fun RequiredDocumentsCard(slots: List<DocumentSlot>, trip: Trip, onUpload: (String?) -> Unit) {
    val colors = DriverTheme.colors
    SectionCard(modifier = Modifier.testTag("required_documents")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.brand)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.required_documents_title), style = MaterialTheme.typography.titleMedium)
        }
        slots.forEachIndexed { index, slot ->
            if (index > 0) LabeledDivider() else Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(slot.type.icon(), contentDescription = null, tint = colors.brand, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(documentLabel(slot), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = tripContextLabel(trip, slot.stopId, slot.containerId) ?: stringResource(R.string.slot_trip_level),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                    Spacer(Modifier.height(4.dp))
                    StatusUiBadge(slot.toStatusUi())
                    slot.reviewNote?.takeIf { slot.state == DocumentSlotState.REJECTED }?.let {
                        Text(stringResource(R.string.slot_review_note, it), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryActionButton(
                text = stringResource(R.string.required_documents_upload),
                onClick = { onUpload(slot.key) },
                icon = Icons.Filled.UploadFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("required_document_upload_${slot.key}"),
            )
        }
    }
}

/** Planned location of the next stop. Navigation only with real coordinates. */
@Composable
private fun NextStopCard(
    stop: TripLocation,
    onOpenNavigation: (GeoPoint, String?) -> Unit,
    onCopyCoordinates: (GeoPoint, String?) -> Unit,
) {
    SectionCard(modifier = Modifier.testTag("next_stop")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Place, contentDescription = null, tint = DriverTheme.colors.brand, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.trip_next_stop), style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
                Text(stop.name ?: stringResource(R.string.value_unknown), style = MaterialTheme.typography.titleMedium)
            }
        }
        val point = stop.coordinates
        if (point != null) {
            Spacer(Modifier.height(8.dp))
            CoordinateBlock(point, stop.name, onCopyCoordinates, "next_stop_coordinates")
            Spacer(Modifier.height(8.dp))
            SecondaryActionButton(
                text = stringResource(R.string.trip_navigation),
                onClick = { onOpenNavigation(point, stop.name) },
                icon = Icons.Filled.Navigation,
                modifier = Modifier.testTag("next_stop_navigate"),
            )
        } else {
            Spacer(Modifier.height(8.dp))
            LocationMissing(Modifier.testTag("next_stop_location_missing"))
        }
    }
}

@Composable
private fun LocationMissing(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(Icons.Filled.LocationOff, contentDescription = null, tint = DriverTheme.colors.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.location_missing), style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
    }
}

@Composable
private fun StopBlock(label: String, name: String?, address: String?, icon: ImageVector) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = DriverTheme.colors.brand, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
            Text(name ?: address.orEmpty(), style = MaterialTheme.typography.titleMedium)
            if (name != null) {
                address?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted) }
            }
        }
    }
}

/**
 * Stops in backend order; planned and actual times are the backend's UTC values in local time. Every stop
 * shows its planned coordinates for the truck navigation device, or that they are missing (docs/18).
 */
@Composable
private fun StopsCard(
    stops: List<TripStop>,
    onOpenNavigation: (GeoPoint, String?) -> Unit,
    onCopyCoordinates: (GeoPoint, String?) -> Unit,
) {
    val locale = currentLocale()
    val colors = DriverTheme.colors
    SectionCard(modifier = Modifier.testTag("trip_stops")) {
        Text(stringResource(R.string.trip_stops), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        stops.forEachIndexed { index, stop ->
            if (index > 0) LabeledDivider()
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .background(colors.neutralContainer, CircleShape),
                ) {
                    Text(stop.sequence.toString(), style = MaterialTheme.typography.labelMedium, color = colors.onNeutralContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(stop.activity.labelRes()), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    Text(
                        text = stop.locationName ?: stop.address ?: stringResource(R.string.value_unknown),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (stop.locationName != null) {
                        stop.address?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.muted) }
                    }
                    stop.plannedArrivalAt?.let {
                        StopDetail(stringResource(R.string.stop_planned_arrival, DriverFormats.dayTime(it, locale)))
                    }
                    stop.actualArrivalAt?.let {
                        StopDetail(stringResource(R.string.stop_actual_arrival, DriverFormats.dayTime(it, locale)))
                    }
                    stop.actualDepartureAt?.let {
                        StopDetail(stringResource(R.string.stop_actual_departure, DriverFormats.dayTime(it, locale)))
                    }
                    stop.reference?.let { StopDetail(stringResource(R.string.stop_reference, it)) }
                    stop.status.toStatusUi()?.let {
                        Spacer(Modifier.height(4.dp))
                        StatusUiBadge(it)
                    }
                    if (stop.coordinates == null) LocationMissing(Modifier.testTag("stop_location_missing_${stop.id}"))
                }
                stop.coordinates?.let { point ->
                    IconButton(
                        onClick = { onOpenNavigation(point, stop.locationName) },
                        modifier = Modifier
                            .size(56.dp)
                            .testTag("stop_navigate_${stop.id}"),
                    ) {
                        Icon(Icons.Filled.Navigation, contentDescription = stringResource(R.string.stop_navigate), tint = colors.brand)
                    }
                }
            }
        }
    }
}

/** Latitude and longitude labelled separately; the copy button copies both as one pair, latitude first. */
@Composable
private fun CoordinateBlock(point: GeoPoint, label: String?, onCopy: (GeoPoint, String?) -> Unit, tag: String) {
    Column(modifier = Modifier.testTag(tag)) {
        CoordinateLine(stringResource(R.string.coordinate_latitude), CoordinateFormat.decimal(point.latitude), "${tag}_latitude")
        CoordinateLine(stringResource(R.string.coordinate_longitude), CoordinateFormat.decimal(point.longitude), "${tag}_longitude")
        Spacer(Modifier.height(6.dp))
        SecondaryActionButton(
            text = stringResource(R.string.coordinates_copy),
            onClick = { onCopy(point, label) },
            icon = Icons.Filled.ContentCopy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${tag}_copy"),
        )
    }
}

@Composable
private fun CoordinateLine(label: String, value: String, tag: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun StopDetail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
}

/** Server records of the trip action workflow: receive time and device observation time are shown separately. */
@Composable
private fun ReceiptsCard(receipts: List<ActionReceipt>, trip: Trip) {
    val locale = currentLocale()
    SectionCard(modifier = Modifier.testTag("trip_receipts")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.History, contentDescription = null, tint = DriverTheme.colors.brand)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.receipts_title), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        receipts.forEachIndexed { index, receipt ->
            if (index > 0) LabeledDivider()
            Text(stringResource(receipt.action.receiptLabelRes()), style = MaterialTheme.typography.titleSmall)
            tripContextLabel(trip, receipt.stopId, receipt.containerId)?.let { StopDetail(it) }
            receipt.receivedAt?.let { StopDetail(stringResource(R.string.receipt_received, DriverFormats.dayTime(it, locale))) }
            receipt.observedAt?.let { StopDetail(stringResource(R.string.receipt_observed, DriverFormats.dayTime(it, locale))) }
            receipt.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun TimelineCard(steps: List<TimelineStep>) {
    SectionCard {
        Text(stringResource(R.string.trip_progress), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        steps.forEachIndexed { index, step -> TimelineRow(index + 1, step) }
    }
}

@Composable
private fun TimelineRow(number: Int, step: TimelineStep) {
    val colors = DriverTheme.colors
    val (background, content) = when (step.status) {
        StepStatus.DONE -> colors.action to colors.onAction
        StepStatus.CURRENT -> colors.infoContainer to colors.onInfoContainer
        StepStatus.UPCOMING -> colors.neutralContainer to colors.onNeutralContainer
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .background(background, CircleShape),
        ) {
            if (step.status == StepStatus.DONE) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            } else {
                Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = content)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(step.kind.label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (step.status == StepStatus.CURRENT) FontWeight.ExtraBold else FontWeight.Bold,
            )
            Text(
                text = when (step.status) {
                    StepStatus.DONE -> step.occurredAt?.let { DriverFormats.time(it) } ?: stringResource(R.string.timeline_done)
                    StepStatus.CURRENT -> stringResource(R.string.timeline_next)
                    StepStatus.UPCOMING -> stringResource(R.string.timeline_waiting)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        step.syncState?.let { SyncStateBadge(it) }
    }
}
