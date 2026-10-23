package com.dynamictransport.dynamicone.core.design.layout

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import com.dynamictransport.dynamicone.core.design.theme.Dimens

/**
 * How a screen arranges itself: [COMPACT] is a phone in the hand (navigation and the dominant action at the bottom),
 * [WIDE] a tablet mounted in the truck or a phone in landscape (navigation on the left, the dominant action in a
 * pane on the right, content no wider than it stays readable).
 */
enum class ScreenLayout { COMPACT, WIDE }

/** Material 3 window size classes: from 600 dp of width the screen is no longer a compact phone. */
const val WIDE_SCREEN_MIN_WIDTH_DP = 600

@Composable
fun screenLayout(): ScreenLayout =
    if (LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_MIN_WIDTH_DP) ScreenLayout.WIDE else ScreenLayout.COMPACT

@Composable
fun isWideScreen(): Boolean = screenLayout() == ScreenLayout.WIDE

/**
 * Which way a screen may turn. Phones are used in the hand and stay upright, so an accidental turn never
 * rearranges the screen; tablets (smallest side of 600 dp or more) turn freely, mounted landscape in the truck.
 */
object ScreenOrientationPolicy {
    fun forSmallestWidth(smallestScreenWidthDp: Int): Int =
        if (smallestScreenWidthDp >= WIDE_SCREEN_MIN_WIDTH_DP) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}

/** Applies [ScreenOrientationPolicy] to this activity; call it in `onCreate`. */
fun Activity.applyScreenOrientationPolicy() {
    requestedOrientation = ScreenOrientationPolicy.forSmallestWidth(resources.configuration.smallestScreenWidthDp)
}

/**
 * Keeps a block readable on wide screens: it takes the available width up to [max] and sits centred in the rest.
 * On a phone, where less than [max] is available, it simply fills the width. The block itself ignores a minimum
 * width the parent passes down (a `Surface` or `fillMaxWidth` above it): this layout node takes the whole width
 * for the parent, the block inside stays at [max].
 */
fun Modifier.readableWidth(max: Dp = Dimens.ContentMaxWidth): Modifier = layout { measurable, constraints ->
    val maxPx = max.roundToPx()
    val width = if (constraints.hasBoundedWidth) minOf(constraints.maxWidth, maxPx) else maxPx
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    val totalWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
    layout(totalWidth, placeable.height) {
        placeable.placeRelative((totalWidth - placeable.width) / 2, 0)
    }
}
