package com.dynamictransport.dynamicone.feature.attendance

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.WorkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ActionTone
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.component.toneContainer
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.core.nfc.NfcAvailability
import com.dynamictransport.dynamicone.core.nfc.NfcTagReaderEffect
import com.dynamictransport.dynamicone.core.nfc.openNfcSettings
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.LocalPunch
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.feature.common.ActionBar
import com.dynamictransport.dynamicone.feature.common.ConnectivityBadge
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.StatusUiBadge
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.formatDuration
import com.dynamictransport.dynamicone.feature.common.rememberCurrentInstant
import com.dynamictransport.dynamicone.feature.common.toStatusUi
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@Composable
fun AttendanceRoute(onOpenRecords: () -> Unit, viewModel: AttendanceViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    // Tags are only read while the screen waits for one; otherwise the system handles them as usual.
    NfcTagReaderEffect(active = state.scanning != null, onTag = viewModel::onTagRead)
    AttendanceScreen(
        state = state,
        onPrimaryAction = viewModel::onPrimaryAction,
        onCancelScan = viewModel::onCancelScan,
        onOpenNfcSettings = { openNfcSettings(context) },
        onRefresh = viewModel::refresh,
        onResend = viewModel::onResend,
        onDismiss = viewModel::onDismiss,
        onOutcomeDismissed = viewModel::onOutcomeDismissed,
        onOpenRecords = onOpenRecords,
    )
}

@Composable
fun AttendanceScreen(
    state: AttendanceUiState,
    onPrimaryAction: () -> Unit,
    onCancelScan: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onRefresh: () -> Unit,
    onResend: (LocalPunch) -> Unit,
    onDismiss: (LocalPunch) -> Unit,
    onOutcomeDismissed: () -> Unit,
    onOpenRecords: () -> Unit,
    now: Instant = rememberCurrentInstant(),
) {
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.attendance_title),
                actions = { ConnectivityBadge(state.isOnline, Modifier.padding(end = 12.dp)) },
            )
        },
        bottomBar = { if (!state.loading) AttendanceActionBar(state, onPrimaryAction, onCancelScan, onOpenNfcSettings) },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        ) {
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("attendance_loading"))
                return@Column
            }
            if (state.unavailable) {
                EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(R.string.feature_not_connected),
                    body = null,
                    modifier = Modifier.testTag("attendance_unavailable"),
                )
                return@Column
            }
            val scanning = state.scanning
            if (scanning != null) ScanCard(scanning) else StatusCard(state, now)
            state.outcome?.let { OutcomeBanner(it, onOutcomeDismissed) }
            NfcBanner(state.nfc)
            state.refreshError?.let { error ->
                StatusBanner(
                    message = stringResource(error),
                    icon = Icons.Filled.SyncProblem,
                    tone = StatusTone.Warning,
                    actionLabel = if (state.refreshing) null else stringResource(R.string.common_retry),
                    onAction = onRefresh,
                    modifier = Modifier.testTag("attendance_refresh_error"),
                )
            }
            if (state.waitingCount > 0) {
                StatusBanner(
                    message = pluralStringResource(R.plurals.attendance_waiting, state.waitingCount, state.waitingCount),
                    icon = Icons.Filled.CloudUpload,
                    tone = StatusTone.Info,
                    modifier = Modifier.testTag("attendance_waiting"),
                )
            }
            state.failed.forEach { FailedPunchCard(it, state.submitting, onResend, onDismiss) }
            TodayCard(state.today)
            SecondaryActionButton(
                text = stringResource(R.string.attendance_all_records),
                onClick = onOpenRecords,
                icon = Icons.AutoMirrored.Filled.EventNote,
                modifier = Modifier.testTag("attendance_open_records"),
            )
        }
    }
}

@Composable
private fun AttendanceActionBar(
    state: AttendanceUiState,
    onPrimaryAction: () -> Unit,
    onCancelScan: () -> Unit,
    onOpenNfcSettings: () -> Unit,
) {
    val next = state.situation?.nextDirection ?: AttendanceDirection.IN
    ActionBar {
        when {
            state.unavailable -> PrimaryActionButton(
                text = stringResource(next.actionRes()),
                onClick = {},
                icon = next.icon(),
                enabled = false,
                modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
            )
            state.scanning != null -> PrimaryActionButton(
                text = stringResource(R.string.attendance_cancel_scan),
                onClick = onCancelScan,
                icon = Icons.Filled.Close,
                tone = ActionTone.Neutral,
                modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
            )
            state.nfc == NfcAvailability.DISABLED -> PrimaryActionButton(
                text = stringResource(R.string.attendance_open_nfc_settings),
                onClick = onOpenNfcSettings,
                icon = Icons.Filled.Nfc,
                modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
            )
            else -> PrimaryActionButton(
                text = stringResource(next.actionRes()),
                onClick = onPrimaryAction,
                icon = next.icon(),
                // Ending the working time is not the "go" colour: it must not look like starting it.
                tone = if (next == AttendanceDirection.IN) ActionTone.Positive else ActionTone.Neutral,
                enabled = state.nfc == NfcAvailability.AVAILABLE,
                loading = state.submitting,
                modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
            )
        }
    }
}

@Composable
private fun StatusCard(state: AttendanceUiState, now: Instant) {
    val situation = state.situation ?: return
    val locale = currentLocale()
    val since = situation.checkedInSince
    val tone = if (situation.checkedIn) StatusTone.Positive else StatusTone.Neutral
    val colors = toneContainer(tone)
    SectionCard(modifier = Modifier.testTag(if (situation.checkedIn) "attendance_checked_in" else "attendance_checked_out")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .background(colors.container, CircleShape),
            ) {
                TintedIcon(
                    imageVector = if (situation.checkedIn) Icons.Filled.Work else Icons.Filled.WorkOff,
                    tint = colors.content,
                    size = 34,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                state.userName?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
                }
                Text(
                    text = stringResource(if (situation.checkedIn) R.string.attendance_state_in else R.string.attendance_state_out),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                if (since != null) {
                    Text(
                        text = stringResource(
                            R.string.attendance_since,
                            DriverFormats.time(since),
                            formatDuration(Duration.between(since, now)),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    Text(
                        text = DriverFormats.date(situation.today, locale),
                        style = MaterialTheme.typography.bodyLarge,
                        color = DriverTheme.colors.muted,
                    )
                }
            }
        }
    }
}

/** Waiting for the tag: which tag, where, and that the phone is ready. */
@Composable
private fun ScanCard(direction: AttendanceDirection) {
    val pulse by rememberInfiniteTransition(label = "nfc").animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "nfc_scale",
    )
    val colors = toneContainer(StatusTone.Info)
    SectionCard(modifier = Modifier.testTag("attendance_scanning")) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(112.dp)
                    .scale(pulse)
                    .background(colors.container, CircleShape),
            ) {
                TintedIcon(imageVector = Icons.Filled.Nfc, tint = colors.content, size = 64)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(if (direction == AttendanceDirection.IN) R.string.attendance_scan_in_title else R.string.attendance_scan_out_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(if (direction == AttendanceDirection.IN) R.string.attendance_scan_in_body else R.string.attendance_scan_out_body),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun OutcomeBanner(outcome: PunchOutcome, onDismiss: () -> Unit) {
    when (outcome) {
        is PunchOutcome.Recorded -> StatusBanner(
            message = stringResource(outcome.direction.recordedRes(), DriverFormats.time(outcome.time)),
            icon = Icons.Filled.CheckCircle,
            tone = StatusTone.Positive,
            modifier = Modifier.testTag("attendance_outcome_recorded"),
        )
        is PunchOutcome.Saved -> StatusBanner(
            message = stringResource(R.string.attendance_outcome_saved),
            icon = Icons.Filled.CloudUpload,
            tone = StatusTone.Info,
            modifier = Modifier.testTag("attendance_outcome_saved"),
        )
        is PunchOutcome.Failed -> StatusBanner(
            message = stringResource(outcome.failure.messageRes(outcome.direction)),
            icon = Icons.Filled.ErrorOutline,
            tone = StatusTone.Danger,
            actionLabel = stringResource(R.string.profile_dismiss),
            onAction = onDismiss,
            modifier = Modifier.testTag("attendance_outcome_failed"),
        )
        PunchOutcome.UnreadableTag -> StatusBanner(
            message = stringResource(R.string.attendance_outcome_unreadable),
            icon = Icons.Filled.ErrorOutline,
            tone = StatusTone.Warning,
            actionLabel = stringResource(R.string.profile_dismiss),
            onAction = onDismiss,
            modifier = Modifier.testTag("attendance_outcome_unreadable"),
        )
        PunchOutcome.NotLoggedIn -> StatusBanner(
            message = stringResource(R.string.refresh_session_expired),
            icon = Icons.Filled.ErrorOutline,
            tone = StatusTone.Warning,
        )
    }
}

@Composable
private fun NfcBanner(nfc: NfcAvailability) {
    when (nfc) {
        NfcAvailability.AVAILABLE -> Unit
        NfcAvailability.DISABLED -> StatusBanner(
            message = stringResource(R.string.attendance_nfc_disabled),
            icon = Icons.Filled.Nfc,
            tone = StatusTone.Warning,
            modifier = Modifier.testTag("attendance_nfc_disabled"),
        )
        NfcAvailability.NOT_SUPPORTED -> StatusBanner(
            message = stringResource(R.string.attendance_nfc_not_supported),
            icon = Icons.Filled.PhonelinkErase,
            tone = StatusTone.Warning,
            modifier = Modifier.testTag("attendance_nfc_not_supported"),
        )
    }
}

@Composable
private fun FailedPunchCard(punch: LocalPunch, busy: Boolean, onResend: (LocalPunch) -> Unit, onDismiss: (LocalPunch) -> Unit) {
    val locale = currentLocale()
    SectionCard(modifier = Modifier.testTag("attendance_failed_${punch.commandId}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintedIcon(imageVector = Icons.Filled.SyncProblem, tint = DriverTheme.colors.danger, size = 24)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.attendance_failed_title,
                        stringResource(punch.direction.labelRes()),
                        DriverFormats.dayTime(punch.scannedAt, locale),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                punch.failure?.let {
                    Text(stringResource(it.messageRes(punch.direction)), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { onDismiss(punch) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.attendance_remove))
            }
            TextButton(onClick = { onResend(punch) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.attendance_resend))
            }
        }
    }
}

@Composable
private fun TodayCard(today: List<TodayPunch>) {
    SectionCard(modifier = Modifier.testTag("attendance_today")) {
        Text(stringResource(R.string.attendance_today), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (today.isEmpty()) {
            Text(
                text = stringResource(R.string.attendance_today_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = DriverTheme.colors.muted,
            )
        }
        today.forEach { PunchRow(it.direction, it.time, it.pointName, it.syncState) }
    }
}

@Composable
internal fun PunchRow(
    direction: AttendanceDirection,
    time: Instant,
    pointName: String?,
    syncState: SyncState?,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
    ) {
        TintedIcon(imageVector = direction.icon(), tint = DriverTheme.colors.muted, size = 22)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.attendance_punch_line, stringResource(direction.labelRes()), DriverFormats.time(time, zone)),
                style = MaterialTheme.typography.bodyLarge,
            )
            pointName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted) }
        }
        syncState?.let { StatusUiBadge(it.toStatusUi()) }
    }
}

@Composable
private fun TintedIcon(imageVector: ImageVector, tint: Color, size: Int) {
    Icon(imageVector, contentDescription = null, tint = tint, modifier = Modifier.size(size.dp))
}

internal fun AttendanceDirection.actionRes(): Int =
    if (this == AttendanceDirection.IN) R.string.attendance_action_start else R.string.attendance_action_end

internal fun AttendanceDirection.labelRes(): Int =
    if (this == AttendanceDirection.IN) R.string.attendance_direction_in else R.string.attendance_direction_out

private fun AttendanceDirection.recordedRes(): Int =
    if (this == AttendanceDirection.IN) R.string.attendance_outcome_recorded_in else R.string.attendance_outcome_recorded_out

internal fun AttendanceDirection.icon() =
    if (this == AttendanceDirection.IN) Icons.AutoMirrored.Filled.Login else Icons.AutoMirrored.Filled.Logout

