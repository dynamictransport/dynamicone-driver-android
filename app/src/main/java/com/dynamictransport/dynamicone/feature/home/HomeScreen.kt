package com.dynamictransport.dynamicone.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.SyncProblem
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.QuickActionTile
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.statemachine.PrimaryAction
import com.dynamictransport.dynamicone.feature.common.ActionBar
import com.dynamictransport.dynamicone.feature.common.BlockedAction
import com.dynamictransport.dynamicone.feature.common.ConnectivityBadge
import com.dynamictransport.dynamicone.feature.common.DemoBadge
import com.dynamictransport.dynamicone.feature.common.DriverActionButton
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.SyncBanners
import com.dynamictransport.dynamicone.feature.common.SyncStateBadge
import com.dynamictransport.dynamicone.feature.common.TripSummaryCard
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.formatDuration
import com.dynamictransport.dynamicone.feature.common.rememberCurrentInstant
import com.dynamictransport.dynamicone.feature.common.toActionUi
import com.dynamictransport.dynamicone.feature.shift.DriverDayCard
import java.time.Duration
import java.time.Instant

@Composable
fun HomeRoute(
    onOpenTrip: (String) -> Unit,
    onOpenTrips: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenEarnings: () -> Unit,
    onReportIncident: (String?) -> Unit,
    onOpenShift: () -> Unit,
    onStartShift: () -> Unit = {},
    onOpenRestAlarmSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    HomeScreen(
        state = state,
        onPrimaryAction = { action ->
            when (action) {
                PrimaryAction.EndShift -> onOpenShift()
                is PrimaryAction.ReviewNewTrip -> onOpenTrip(action.tripId)
                is PrimaryAction.ContinueTrip -> onOpenTrip(action.tripId)
                // A daily shift starts at the vehicle: tag and check (docs/16).
                PrimaryAction.StartShift -> if (state.shift.requiresVehicleCheck) onStartShift() else viewModel.onPrimaryAction(action)
                else -> viewModel.onPrimaryAction(action)
            }
        },
        onDismissRestAlarm = viewModel::onDismissRestAlarm,
        onRestoreRestAlarm = viewModel::onRestoreRestAlarm,
        onOpenRestAlarmSettings = onOpenRestAlarmSettings,
        onOpenTrip = onOpenTrip,
        onOpenTrips = onOpenTrips,
        onOpenDocuments = onOpenDocuments,
        onOpenEarnings = onOpenEarnings,
        onReportIncident = onReportIncident,
        onOpenShift = onOpenShift,
        onRetrySync = viewModel::onRetrySync,
        onMessageShown = viewModel::onMessageShown,
        onRefresh = viewModel::onRefresh,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onPrimaryAction: (PrimaryAction) -> Unit,
    onOpenTrip: (String) -> Unit,
    onOpenTrips: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenEarnings: () -> Unit,
    onReportIncident: (String?) -> Unit,
    onOpenShift: () -> Unit,
    onRetrySync: () -> Unit,
    onMessageShown: () -> Unit,
    onRefresh: () -> Unit = {},
    onDismissRestAlarm: (RestWindow) -> Unit = {},
    onRestoreRestAlarm: () -> Unit = {},
    onOpenRestAlarmSettings: () -> Unit = {},
    now: Instant = rememberCurrentInstant(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)

    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.app_name),
                actions = {
                    if (state.isDemoMode) DemoBadge(Modifier.padding(end = 8.dp))
                    ConnectivityBadge(state.isOnline, Modifier.padding(end = 12.dp))
                },
            )
        },
        bottomBar = {
            if (!state.loading) {
                ActionBar { HomePrimaryAction(state, onPrimaryAction) }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        ) {
            DriverHeader(state = state, now = now, onOpenShift = onOpenShift)
            state.driverDay?.let { day ->
                DriverDayCard(
                    state = day,
                    shift = state.shift,
                    now = now,
                    onDismissAlarm = onDismissRestAlarm,
                    onRestoreAlarm = onRestoreRestAlarm,
                    onOpenAlarmSettings = onOpenRestAlarmSettings,
                )
            }
            SyncBanners(
                isOnline = state.isOnline,
                pendingCount = state.pendingCount,
                failedCount = state.failedCount,
                onRetry = onRetrySync,
                offlineMessage = if (state.isDemoMode) R.string.sync_offline else R.string.sync_offline_real,
            )
            state.refreshError?.let { error ->
                StatusBanner(
                    message = stringResource(error),
                    icon = Icons.Filled.SyncProblem,
                    tone = StatusTone.Danger,
                    actionLabel = if (state.refreshing) null else stringResource(R.string.common_retry),
                    onAction = onRefresh,
                    modifier = Modifier.testTag("home_refresh_error"),
                )
            }
            state.newTrip?.let { trip ->
                NewTripAlert(
                    trip = trip,
                    showViewAction = state.primaryAction !is PrimaryAction.ReviewNewTrip,
                    onView = { onOpenTrip(trip.id) },
                )
            }
            val focusTrip = state.focusTrip
            if (focusTrip != null) {
                TripSummaryCard(trip = focusTrip, onClick = { onOpenTrip(focusTrip.id) })
            } else if (state.loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_loading"),
                )
            } else if (state.tripsLoaded) {
                EmptyState(
                    icon = Icons.Filled.LocalShipping,
                    title = stringResource(R.string.home_no_trip_title),
                    body = stringResource(R.string.home_no_trip_body),
                )
            }
            QuickActions(
                onOpenTrips = onOpenTrips,
                onOpenDocuments = onOpenDocuments,
                onOpenEarnings = onOpenEarnings,
                onReportIncident = { onReportIncident(focusTrip?.id) },
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun HomePrimaryAction(state: HomeUiState, onPrimaryAction: (PrimaryAction) -> Unit) {
    val action = state.primaryAction
    val blocked = state.blockedAction
    when {
        action != null -> DriverActionButton(
            ui = action.toActionUi(),
            onClick = { onPrimaryAction(action) },
            loading = state.submitting,
            modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
        )

        blocked != null -> BlockedAction(blocked)

        else -> Text(
            text = stringResource(R.string.home_no_action),
            style = MaterialTheme.typography.titleSmall,
            color = DriverTheme.colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun DriverHeader(state: HomeUiState, now: Instant, onOpenShift: () -> Unit) {
    Column {
        val firstName = state.driverName?.substringBefore(' ')
        Text(
            text = if (firstName != null) {
                stringResource(R.string.home_greeting, firstName)
            } else {
                stringResource(R.string.home_greeting_no_name)
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(onClick = onOpenShift),
        ) {
            Text(
                text = shiftLine(state.shift, now),
                style = MaterialTheme.typography.bodyLarge,
                color = DriverTheme.colors.muted,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            SyncStateBadge(state.shift.syncState)
        }
        Text(
            text = state.lastSyncAt?.let { stringResource(R.string.sync_last, DriverFormats.time(it)) }
                ?: stringResource(R.string.sync_never),
            style = MaterialTheme.typography.bodySmall,
            color = DriverTheme.colors.muted,
        )
    }
}

@Composable
private fun shiftLine(shift: Shift, now: Instant): String = when (shift.status) {
    ShiftStatus.ACTIVE -> shift.startedAt
        ?.let { stringResource(R.string.shift_line_active, DriverFormats.time(it), formatDuration(Duration.between(it, now))) }
        ?: stringResource(R.string.shift_status_active)

    ShiftStatus.FINISHED -> shift.endedAt
        ?.let { stringResource(R.string.shift_line_finished, DriverFormats.time(it)) }
        ?: stringResource(R.string.shift_status_finished)

    ShiftStatus.NOT_STARTED -> stringResource(R.string.shift_line_not_started)

    ShiftStatus.UNAVAILABLE -> stringResource(R.string.shift_line_unavailable)
}

@Composable
private fun NewTripAlert(trip: Trip, showViewAction: Boolean, onView: () -> Unit) {
    val locale = currentLocale()
    StatusBanner(
        message = trip.plannedStartAt
            ?.let { stringResource(R.string.home_new_trip_alert, DriverFormats.dayTime(it, locale)) }
            ?: stringResource(R.string.home_new_trip_alert_no_time),
        icon = Icons.Filled.NewReleases,
        tone = StatusTone.Warning,
        actionLabel = if (showViewAction) stringResource(R.string.home_view) else null,
        onAction = if (showViewAction) onView else null,
        modifier = Modifier.testTag("new_trip_alert"),
    )
}

@Composable
private fun QuickActions(
    onOpenTrips: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenEarnings: () -> Unit,
    onReportIncident: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.CardGap)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            QuickActionTile(
                label = stringResource(R.string.nav_trips),
                icon = Icons.Filled.LocalShipping,
                onClick = onOpenTrips,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            QuickActionTile(
                label = stringResource(R.string.nav_documents),
                icon = Icons.Filled.Description,
                onClick = onOpenDocuments,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            QuickActionTile(
                label = stringResource(R.string.quick_earnings),
                icon = Icons.Filled.Euro,
                onClick = onOpenEarnings,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            QuickActionTile(
                label = stringResource(R.string.quick_report_problem),
                icon = Icons.Filled.ReportProblem,
                onClick = onReportIncident,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("quick_report_problem"),
            )
        }
    }
}
