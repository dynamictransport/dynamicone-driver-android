package com.dynamictransport.dynamicone.feature.tripdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.component.toneContainer
import com.dynamictransport.dynamicone.domain.model.StopStatus
import com.dynamictransport.dynamicone.domain.model.TarPhase
import com.dynamictransport.dynamicone.domain.model.TerminalAppointment
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import java.time.Instant

/** Icon, colour and text of a TAR phase: never colour alone (CLAUDE.md §7). */
private data class TarPhaseUi(val tone: StatusTone, val icon: ImageVector, val label: Int)

private fun TarPhase.ui(): TarPhaseUi = when (this) {
    TarPhase.UPCOMING -> TarPhaseUi(StatusTone.Info, Icons.Filled.Schedule, R.string.tar_phase_upcoming)
    TarPhase.ACTIVE -> TarPhaseUi(StatusTone.Positive, Icons.Filled.CheckCircle, R.string.tar_phase_active)
    TarPhase.ENDING_SOON -> TarPhaseUi(StatusTone.Warning, Icons.Filled.Warning, R.string.tar_phase_ending_soon)
    TarPhase.EXPIRED -> TarPhaseUi(StatusTone.Danger, Icons.Filled.Error, R.string.tar_phase_expired)
    TarPhase.PAST -> TarPhaseUi(StatusTone.Neutral, Icons.Filled.History, R.string.tar_phase_past)
}

/**
 * The planner's terminal appointment for one stop (docs/26): the code as plain text with a copy button, the window
 * in the terminal's own time zone, the phase at [now] (server-corrected) and, while the window can still be met,
 * the countdown. Read-only: nothing here is added, edited or removed by the driver.
 */
@Composable
internal fun TarBlock(
    tar: TerminalAppointment,
    stopStatus: StopStatus,
    now: Instant,
    onCopy: (String) -> Unit,
    tag: String,
) {
    val status = tar.statusAt(now, stopStatus)
    val phase = status.phase.ui()
    val colors = toneContainer(phase.tone)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.container, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
            .testTag(tag),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
            Icon(phase.icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.tar_title), style = MaterialTheme.typography.labelLarge, color = colors.content)
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(phase.label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.content,
                modifier = Modifier.testTag("${tag}_phase"),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tar.code,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colors.content,
                modifier = Modifier
                    .weight(1f)
                    .testTag("${tag}_code"),
            )
            IconButton(
                onClick = { onCopy(tar.code) },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("${tag}_copy"),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.tar_copy), tint = colors.content)
            }
        }
        Text(
            text = DriverFormats.tarWindow(tar),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.content,
            modifier = Modifier.testTag("${tag}_window"),
        )
        Text(
            text = stringResource(R.string.tar_terminal_time, DriverFormats.zoneName(tar.timeZone)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.content,
        )
        status.remaining?.let { remaining ->
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                Text(
                    text = stringResource(if (status.phase == TarPhase.UPCOMING) R.string.tar_until_start else R.string.tar_until_end),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.content,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = DriverFormats.countdown(remaining),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.content,
                    modifier = Modifier.testTag("${tag}_countdown"),
                )
            }
        }
    }
}
