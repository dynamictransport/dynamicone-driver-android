package com.dynamictransport.dynamicone.core.design.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme

enum class StatusTone { Positive, Warning, Danger, Info, Neutral }

data class ToneContainer(val container: Color, val content: Color)

@Composable
fun toneContainer(tone: StatusTone): ToneContainer {
    val colors = DriverTheme.colors
    return when (tone) {
        StatusTone.Positive -> ToneContainer(colors.positiveContainer, colors.onPositiveContainer)
        StatusTone.Warning -> ToneContainer(colors.warningContainer, colors.onWarningContainer)
        StatusTone.Danger -> ToneContainer(colors.dangerContainer, colors.onDangerContainer)
        StatusTone.Info -> ToneContainer(colors.infoContainer, colors.onInfoContainer)
        StatusTone.Neutral -> ToneContainer(colors.neutralContainer, colors.onNeutralContainer)
    }
}

/** Status is always icon + text + colour, never colour alone. */
@Composable
fun StatusBadge(
    text: String,
    icon: ImageVector,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = toneContainer(tone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(colors.container, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun ClickableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun QuickActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = Dimens.TileMinHeight),
        shape = RoundedCornerShape(Dimens.CardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = DriverTheme.colors.brand, modifier = Modifier.size(28.dp))
            Text(text = label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Offline / pending / failed banner. Failures always carry a retry action. */
@Composable
fun StatusBanner(
    message: String,
    icon: ImageVector,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = toneContainer(tone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(colors.container, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 48.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.content,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.titleSmall, color = colors.content)
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
    }
}

@Composable
fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(vertical = 6.dp)) {
        Icon(icon, contentDescription = null, tint = DriverTheme.colors.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    SectionCard(modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .background(DriverTheme.colors.neutralContainer, CircleShape),
        ) {
            Icon(icon, contentDescription = null, tint = DriverTheme.colors.brand, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body != null) {
            Spacer(Modifier.size(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
        }
        content()
    }
}

/** Large preset choice row (reject reasons, incident categories). */
@Composable
fun ChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val border = if (selected) DriverTheme.colors.brand else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) DriverTheme.colors.infoContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 60.dp)
                .padding(horizontal = 14.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = DriverTheme.colors.brand, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backContentDescription)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    )
}
