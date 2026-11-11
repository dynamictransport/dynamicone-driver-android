package com.dynamictransport.dynamicone.feature.mdm

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
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.component.toneContainer
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.remote.RemoteSessionTexts
import com.dynamictransport.dynamicone.domain.model.RemoteSessionRequest
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG

/**
 * Full screen over the lock screen while the office asks to see the phone (docs/29 §4): who is asking, what it means,
 * one dominant Accept and a plain Decline. No text input.
 */
@Composable
fun RemoteSessionConsentScreen(
    request: RemoteSessionRequest,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val context = LocalContext.current
    val colors = toneContainer(StatusTone.Info)
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
                    Icon(Icons.AutoMirrored.Filled.ScreenShare, contentDescription = null, tint = colors.content, modifier = Modifier.size(72.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = RemoteSessionTexts.title(context, request),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .semantics { heading() }
                        .testTag("remote_session_title"),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.remote_session_request_body),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("remote_session_body"),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryActionButton(
                    text = stringResource(R.string.remote_session_accept),
                    onClick = onAccept,
                    icon = Icons.Filled.CheckCircle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .testTag(PRIMARY_ACTION_TAG),
                )
                SecondaryActionButton(
                    text = stringResource(R.string.remote_session_decline),
                    onClick = onDecline,
                    icon = Icons.Filled.Close,
                    modifier = Modifier.testTag("remote_session_decline"),
                )
            }
        }
    }
}
