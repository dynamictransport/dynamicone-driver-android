package com.dynamictransport.dynamicone.feature.shift

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.alarm.RestAlarmStatus
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.component.toneContainer
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.DriverTimes
import com.dynamictransport.dynamicone.domain.model.DriverTimesAvailability
import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.rules.RestAlarmOffReason
import com.dynamictransport.dynamicone.domain.rules.RestAlarmPlan
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.formatDuration
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What the driver screens know about the driver's day: tachograph summary and wake-up alarm (docs/16 §3). */
data class DriverDayState(
    val times: DriverTimes?,
    val alarm: RestAlarmStatus?,
)

/**
 * Tachograph card: during the daily rest when it ends and when the alarm rings, while working the remaining driving
 * time and the next break. Values are indicative (Webfleet); the tachograph itself is authoritative.
 */
@Composable
fun DriverDayCard(
    state: DriverDayState,
    shift: Shift,
    now: Instant,
    onDismissAlarm: (RestWindow) -> Unit,
    onRestoreAlarm: () -> Unit,
    onOpenAlarmSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val times = state.times ?: return
    SectionCard(modifier = modifier.testTag("driver_day_card")) {
        when (times.availability) {
            DriverTimesAvailability.NOT_LINKED -> Unavailable(R.string.driver_day_not_linked)
            DriverTimesAvailability.NO_DATA -> Unavailable(R.string.driver_day_no_data)
            DriverTimesAvailability.AVAILABLE -> {
                val rest = times.restingRest
                if (rest != null && !shift.isActive) {
                    RestContent(rest.endsAt ?: return@SectionCard, now)
                    state.alarm?.let { AlarmLine(it, onDismissAlarm, onRestoreAlarm, onOpenAlarmSettings) }
                } else {
                    WorkingContent(times, now)
                }
                Footer(times, now)
            }
        }
    }
}

@Composable
private fun Unavailable(text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Speed, contentDescription = null, tint = DriverTheme.colors.muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
    }
}

@Composable
private fun RestContent(endsAt: Instant, now: Instant) {
    val over = !now.isBefore(endsAt)
    val colors = toneContainer(if (over) StatusTone.Positive else StatusTone.Info)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .background(colors.container, CircleShape),
        ) {
            Icon(if (over) Icons.Filled.WbSunny else Icons.Filled.Bedtime, contentDescription = null, tint = colors.content, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(if (over) R.string.driver_day_rest_over else R.string.driver_day_rest),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(if (over) R.string.driver_day_rest_ended_at else R.string.driver_day_rest_ends_at, dayTime(endsAt, now)),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("driver_day_rest_end"),
            )
            Text(
                text = if (over) stringResource(R.string.driver_day_rest_over_body) else stringResource(R.string.driver_day_rest_left, formatDuration(Duration.between(now, endsAt))),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun AlarmLine(
    status: RestAlarmStatus,
    onDismiss: (RestWindow) -> Unit,
    onRestore: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val plan = status.plan
    Spacer(Modifier.size(8.dp))
    when {
        plan is RestAlarmPlan.Scheduled -> InfoLine(
            icon = Icons.Filled.Alarm,
            text = stringResource(if (plan.snoozed) R.string.driver_day_alarm_snoozed else R.string.driver_day_alarm_at, DriverFormats.time(plan.triggerAt)),
            action = stringResource(R.string.driver_day_alarm_off_for_rest),
            onAction = { onDismiss(plan.rest) },
            tag = "driver_day_alarm_scheduled",
        )
        plan is RestAlarmPlan.Off && plan.reason == RestAlarmOffReason.DISMISSED -> InfoLine(
            icon = Icons.Filled.AlarmOff,
            text = stringResource(R.string.driver_day_alarm_dismissed),
            action = stringResource(R.string.driver_day_alarm_on_again),
            onAction = onRestore,
            tag = "driver_day_alarm_dismissed",
        )
        else -> Unit
    }
    val permissions = status.permissions
    if (plan is RestAlarmPlan.Scheduled && (!permissions.notifications || !permissions.exactAlarms)) {
        InfoLine(
            icon = Icons.Filled.Warning,
            text = stringResource(if (!permissions.notifications) R.string.driver_day_alarm_no_notifications else R.string.driver_day_alarm_inexact),
            action = stringResource(R.string.driver_day_alarm_settings),
            onAction = onOpenSettings,
            tone = StatusTone.Warning,
            tag = "driver_day_alarm_permission",
        )
    }
}

@Composable
private fun WorkingContent(times: DriverTimes, now: Instant) {
    Text(
        text = stringResource(R.string.driver_day_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.size(4.dp))
    times.remainingDrivingToday?.let { remaining ->
        InfoLine(
            icon = Icons.Filled.Timer,
            text = if (remaining.isNegative) {
                stringResource(R.string.driver_day_driving_exceeded, formatDuration(remaining.negated()))
            } else {
                stringResource(R.string.driver_day_driving_left, formatDuration(remaining))
            },
            tone = if (remaining.isNegative) StatusTone.Danger else StatusTone.Neutral,
            tag = "driver_day_driving_left",
        )
    }
    if (times.isOutdated(now)) {
        // Break and shift-end times from an old snapshot would be dates in the past: only the warning is shown.
        InfoLine(
            icon = Icons.Filled.Warning,
            text = times.referenceTime?.let { stringResource(R.string.driver_day_outdated, dayTime(it, now)) }
                ?: stringResource(R.string.driver_day_outdated_unknown),
            tone = StatusTone.Warning,
            tag = "driver_day_outdated",
        )
    } else {
        times.nextBreakDueAt?.let { due ->
            val minimum = times.nextBreakMinimum
            InfoLine(
                icon = Icons.Filled.Coffee,
                text = if (minimum != null) {
                    stringResource(R.string.driver_day_break_due_with, dayTime(due, now), formatDuration(minimum))
                } else {
                    stringResource(R.string.driver_day_break_due, dayTime(due, now))
                },
                tone = if (due.isBefore(now)) StatusTone.Warning else StatusTone.Neutral,
                tag = "driver_day_break_due",
            )
        }
        times.latestShiftEnd?.let { end ->
            InfoLine(
                icon = Icons.Filled.Bedtime,
                text = stringResource(R.string.driver_day_shift_latest_end, dayTime(end, now)),
                tag = "driver_day_shift_end",
            )
        }
    }
    if (times.hasInfringement) {
        InfoLine(icon = Icons.Filled.Warning, text = stringResource(R.string.driver_day_infringement), tone = StatusTone.Warning)
    }
}

@Composable
private fun Footer(times: DriverTimes, now: Instant) {
    val reference = times.referenceTime
    Spacer(Modifier.size(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.History, contentDescription = null, tint = DriverTheme.colors.muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = when {
                reference == null || times.isOutdated(now) -> stringResource(R.string.driver_day_indicative)
                times.isStale(now) -> stringResource(R.string.driver_day_as_of_stale, dayTime(reference, now))
                else -> stringResource(R.string.driver_day_as_of, DriverFormats.time(reference))
            },
            style = MaterialTheme.typography.bodySmall,
            color = DriverTheme.colors.muted,
        )
    }
}

@Composable
private fun InfoLine(
    icon: ImageVector,
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    tone: StatusTone = StatusTone.Neutral,
    tag: String? = null,
) {
    val tint = if (tone == StatusTone.Neutral) DriverTheme.colors.muted else toneContainer(tone).content
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .heightIn(min = 40.dp)
            .let { if (tag != null) it.testTag(tag) else it },
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).padding(start = 4.dp)) {
                Text(action, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** Only the time when it is today, otherwise with the day. */
@Composable
private fun dayTime(instant: Instant, now: Instant): String {
    val zone = ZoneId.systemDefault()
    return if (instant.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) {
        DriverFormats.time(instant)
    } else {
        DriverFormats.dayTime(instant, currentLocale())
    }
}
