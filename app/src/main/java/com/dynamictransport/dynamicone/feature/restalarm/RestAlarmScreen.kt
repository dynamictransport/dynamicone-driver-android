package com.dynamictransport.dynamicone.feature.restalarm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.component.toneContainer
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.rememberCurrentInstant
import java.time.Instant

/**
 * Full screen over the lock screen when the rest ends: one dominant action (start the shift), snooze and stop.
 * A test alarm only offers stop.
 */
@Composable
fun RestAlarmScreen(
    restEnd: Instant,
    test: Boolean,
    onStartShift: () -> Unit,
    onSnooze: () -> Unit,
    onStop: () -> Unit,
    now: Instant = rememberCurrentInstant(),
) {
    val colors = toneContainer(StatusTone.Positive)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = Dimens.ScreenPadding, vertical = 24.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(32.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(128.dp)
                        .background(colors.container, CircleShape),
                ) {
                    Icon(Icons.Filled.WbSunny, contentDescription = null, tint = colors.content, modifier = Modifier.size(72.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = DriverFormats.time(now),
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        when {
                            test -> R.string.rest_alarm_test_title
                            now.isBefore(restEnd) -> R.string.rest_alarm_title_soon
                            else -> R.string.rest_alarm_title
                        },
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .semantics { heading() }
                        .testTag("rest_alarm_title"),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        test -> stringResource(R.string.rest_alarm_test_body)
                        now.isBefore(restEnd) -> stringResource(R.string.rest_alarm_body_before, DriverFormats.time(restEnd))
                        else -> stringResource(R.string.rest_alarm_body_ended, DriverFormats.time(restEnd))
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.CardGap), modifier = Modifier.fillMaxWidth()) {
                if (!test) {
                    PrimaryActionButton(
                        text = stringResource(R.string.rest_alarm_start_shift),
                        onClick = onStartShift,
                        icon = Icons.Filled.PlayCircle,
                        modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                    )
                    SecondaryActionButton(
                        text = stringResource(R.string.rest_alarm_snooze),
                        onClick = onSnooze,
                        icon = Icons.Filled.Snooze,
                        modifier = Modifier.testTag("rest_alarm_snooze"),
                    )
                }
                SecondaryActionButton(
                    text = stringResource(R.string.rest_alarm_stop),
                    onClick = onStop,
                    icon = Icons.Filled.AlarmOff,
                    modifier = Modifier.testTag("rest_alarm_stop"),
                )
            }
        }
    }
}
