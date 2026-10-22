package com.dynamictransport.dynamicone.feature.wakeup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.component.toneContainer
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.push.WakeUpCallTexts
import com.dynamictransport.dynamicone.domain.model.WakeUpCall
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.rememberCurrentInstant
import java.time.Instant

/**
 * Full screen over the lock screen while the office calls (docs/25 U2, U3): who calls, why, the time, and one
 * dominant action. No second button, no text input.
 */
@Composable
fun WakeUpCallScreen(
    call: WakeUpCall,
    onAcknowledge: () -> Unit,
    now: Instant = rememberCurrentInstant(),
) {
    val context = LocalContext.current
    val colors = toneContainer(StatusTone.Warning)
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
                    Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = colors.content, modifier = Modifier.size(72.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = DriverFormats.time(now),
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = WakeUpCallTexts.title(context, call),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .semantics { heading() }
                        .testTag("wake_up_title"),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(WakeUpCallTexts.reasonLabel(call.reason)),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("wake_up_reason"),
                )
                call.tripNo?.let { tripNo ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.wake_up_trip, tripNo),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("wake_up_trip"),
                    )
                }
            }
            PrimaryActionButton(
                text = stringResource(R.string.wake_up_acknowledge),
                onClick = onAcknowledge,
                icon = Icons.Filled.CheckCircle,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .testTag(PRIMARY_ACTION_TAG),
            )
        }
    }
}
