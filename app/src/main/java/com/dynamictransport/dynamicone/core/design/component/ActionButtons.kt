package com.dynamictransport.dynamicone.core.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import kotlinx.coroutines.launch

enum class ActionTone { Positive, Neutral, Danger }

private data class ToneColors(val container: Color, val content: Color)

@Composable
private fun toneColors(tone: ActionTone): ToneColors {
    val colors = DriverTheme.colors
    return when (tone) {
        ActionTone.Positive -> ToneColors(colors.action, colors.onAction)
        ActionTone.Neutral -> ToneColors(colors.neutralAction, colors.onNeutralAction)
        ActionTone.Danger -> ToneColors(colors.danger, colors.onDanger)
    }
}

private val ActionShape = RoundedCornerShape(16.dp)

/** The screen's single dominant action: full width, at least 64dp high. */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    tone: ActionTone = ActionTone.Positive,
) {
    val colors = toneColors(tone)
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.PrimaryActionHeight),
        shape = ActionShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.container,
            contentColor = colors.content,
        ),
    ) {
        ActionContent(text = text, icon = icon, loading = loading, contentColor = colors.content)
    }
}

/**
 * Critical, hard-to-undo actions (complete delivery, finish trip, end shift, reject) must be held
 * for [holdDurationMillis]. A quick or accidental tap never confirms. Accessibility services can
 * still trigger it through the standard click action.
 */
@Composable
fun HoldToConfirmButton(
    text: String,
    hint: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    tone: ActionTone = ActionTone.Positive,
    holdDurationMillis: Int = 900,
) {
    val colors = toneColors(tone)
    val active = enabled && !loading
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latestOnConfirmed by rememberUpdatedState(onConfirmed)
    val container = if (active) colors.container else colors.container.copy(alpha = 0.4f)
    val overlay = Color.Black.copy(alpha = 0.22f)

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.PrimaryActionHeight)
                .clip(ActionShape)
                .background(container)
                .drawBehind {
                    drawRect(color = overlay, size = Size(size.width * progress.value, size.height))
                }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    if (!active) disabled()
                    onClick(label = text) {
                        if (active) latestOnConfirmed()
                        active
                    }
                }
                .pointerInput(active) {
                    if (!active) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val hold = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(holdDurationMillis, easing = LinearEasing))
                            latestOnConfirmed()
                        }
                        waitForUpOrCancellation()
                        if (hold.isActive) hold.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            ActionContent(text = text, icon = icon, loading = loading, contentColor = colors.content)
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = DriverTheme.colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val content = if (danger) DriverTheme.colors.danger else DriverTheme.colors.brand
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.SecondaryActionHeight),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (danger) content else MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = content),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text = text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ActionContent(text: String, icon: ImageVector?, loading: Boolean, contentColor: Color) {
    if (loading) {
        CircularProgressIndicator(color = contentColor, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}
