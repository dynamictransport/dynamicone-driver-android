package com.dynamictransport.dynamicone.feature.wakeup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.PhonelinkRing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.wakeup.WakeUpPermissionIssue
import com.dynamictransport.dynamicone.domain.model.WakeUpOutcome
import com.dynamictransport.dynamicone.domain.model.WakeUpOutcomeState
import com.dynamictransport.dynamicone.feature.common.DriverFormats

/** The office's latest wake-up call as the driver should see it (docs/25 U7): who, when, and whether the answer went out. */
@Composable
fun WakeUpCallBanner(outcome: WakeUpOutcome, modifier: Modifier = Modifier) {
    val who = outcome.requestedByDisplayName ?: stringResource(R.string.wake_up_office)
    val time = DriverFormats.time(outcome.acknowledgedAt ?: outcome.requestedAt)
    val (text, icon, tone) = when (outcome.state) {
        WakeUpOutcomeState.RINGING ->
            Triple(stringResource(R.string.wake_up_home_ringing, who), Icons.Filled.NotificationsActive, StatusTone.Warning)
        WakeUpOutcomeState.ACKNOWLEDGED_PENDING ->
            Triple(stringResource(R.string.wake_up_home_acknowledged_pending, who, time), Icons.Filled.CloudUpload, StatusTone.Info)
        WakeUpOutcomeState.ACKNOWLEDGED ->
            Triple(stringResource(R.string.wake_up_home_acknowledged, who, time), Icons.Filled.CheckCircle, StatusTone.Positive)
        WakeUpOutcomeState.NO_ANSWER ->
            Triple(stringResource(R.string.wake_up_home_no_answer, who, time), Icons.Filled.NotificationsOff, StatusTone.Warning)
        WakeUpOutcomeState.STOPPED ->
            Triple(stringResource(R.string.wake_up_home_stopped, who, time), Icons.Filled.PhonelinkRing, StatusTone.Positive)
    }
    StatusBanner(message = text, icon = icon, tone = tone, modifier = modifier.testTag("wake_up_home_${outcome.state.name}"))
}

/** What keeps the call from ringing on this device, with the way to the system screen that fixes it (docs/25 U5). */
@Composable
fun WakeUpPermissionBanner(issue: WakeUpPermissionIssue, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val (text, icon) = when (issue) {
        WakeUpPermissionIssue.NOTIFICATIONS -> stringResource(R.string.wake_up_permission_notifications) to Icons.Filled.NotificationsOff
        WakeUpPermissionIssue.FULL_SCREEN -> stringResource(R.string.wake_up_permission_full_screen) to Icons.Filled.NotificationsOff
        WakeUpPermissionIssue.BATTERY -> stringResource(R.string.wake_up_permission_battery) to Icons.Filled.BatteryAlert
        WakeUpPermissionIssue.DND -> stringResource(R.string.wake_up_permission_dnd) to Icons.AutoMirrored.Filled.VolumeOff
    }
    StatusBanner(
        message = text,
        icon = icon,
        tone = StatusTone.Warning,
        actionLabel = stringResource(R.string.wake_up_permission_open),
        onAction = onOpenSettings,
        modifier = modifier.testTag("wake_up_permission_${issue.name}"),
    )
}
