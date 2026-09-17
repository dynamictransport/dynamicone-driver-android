package com.dynamictransport.dynamicone.feature.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.TripActionType

/** The next step exists but the backend does not allow it now (e.g. mandatory documents missing). */
@Composable
fun BlockedAction(action: TripActionType) {
    val ui = action.toActionUi()
    PrimaryActionButton(
        text = stringResource(ui.label),
        onClick = {},
        icon = Icons.Filled.Lock,
        enabled = false,
        tone = ui.tone,
        modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
    )
    Text(
        text = stringResource(R.string.action_blocked),
        style = MaterialTheme.typography.bodyMedium,
        color = DriverTheme.colors.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
