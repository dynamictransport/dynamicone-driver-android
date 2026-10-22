package com.dynamictransport.dynamicone.core.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic colours. Red is reserved for errors, danger and cancellation (CLAUDE.md §7). */
@Immutable
data class DriverColors(
    val action: Color,
    val onAction: Color,
    val neutralAction: Color,
    val onNeutralAction: Color,
    val danger: Color,
    val onDanger: Color,
    val positiveContainer: Color,
    val onPositiveContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
    val brand: Color,
    val muted: Color,
    /** The driver's own messages in a conversation. */
    val sentMessage: Color,
    val onSentMessage: Color,
    /** Messages from the office: a clearly separate bubble on the screen background. */
    val receivedMessage: Color,
    val receivedMessageBorder: Color,
    /** Pay screen: earnings (with "+"). */
    val earning: Color,
    /** Pay screen: deductions (with a minus sign); the one agreed use of red outside errors (CLAUDE.md section 7). */
    val deduction: Color,
)

private val LightDriverColors = DriverColors(
    action = Color(0xFF157B4F),
    onAction = Color.White,
    neutralAction = Color(0xFF102B46),
    onNeutralAction = Color.White,
    danger = Color(0xFFAA2F35),
    onDanger = Color.White,
    positiveContainer = Color(0xFFE6F4ED),
    onPositiveContainer = Color(0xFF0F5C3A),
    warningContainer = Color(0xFFFFF3D8),
    onWarningContainer = Color(0xFF6E4600),
    dangerContainer = Color(0xFFFCE8E9),
    onDangerContainer = Color(0xFF8A1F25),
    infoContainer = Color(0xFFE3F0F8),
    onInfoContainer = Color(0xFF0D4F73),
    neutralContainer = Color(0xFFEDF1F4),
    onNeutralContainer = Color(0xFF4A5560),
    brand = Color(0xFF102B46),
    muted = Color(0xFF56636E),
    // White text on this blue: contrast about 5.8:1.
    sentMessage = Color(0xFF1565C0),
    onSentMessage = Color.White,
    // White bubble with a grey edge on the light grey conversation background.
    receivedMessage = Color.White,
    receivedMessageBorder = Color(0xFFC3CDD5),
    // On white cards: contrast about 5.4:1 (green) and 6.3:1 (red).
    earning = Color(0xFF157B4F),
    deduction = Color(0xFFAA2F35),
)

private val DarkDriverColors = DriverColors(
    action = Color(0xFF178556),
    onAction = Color.White,
    neutralAction = Color(0xFF9CC3E6),
    onNeutralAction = Color(0xFF0B2236),
    danger = Color(0xFFE5737A),
    onDanger = Color(0xFF3A0B0E),
    positiveContainer = Color(0xFF0F2E21),
    onPositiveContainer = Color(0xFF8FE0B8),
    warningContainer = Color(0xFF3A2A08),
    onWarningContainer = Color(0xFFFFD58A),
    dangerContainer = Color(0xFF3D1618),
    onDangerContainer = Color(0xFFFFB3B6),
    infoContainer = Color(0xFF10283A),
    onInfoContainer = Color(0xFF9CCBEA),
    neutralContainer = Color(0xFF243240),
    onNeutralContainer = Color(0xFFC5D0D9),
    brand = Color(0xFF9CC3E6),
    muted = Color(0xFFA7B4BF),
    // White text on this blue: contrast about 6:1.
    sentMessage = Color(0xFF1E5FAF),
    onSentMessage = Color.White,
    receivedMessage = Color(0xFF243240),
    receivedMessageBorder = Color(0xFF3E5061),
    earning = Color(0xFF6FD3A4),
    deduction = Color(0xFFFF9AA2),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF102B46),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4F2),
    onPrimaryContainer = Color(0xFF0B2236),
    secondary = Color(0xFF0F5F8A),
    onSecondary = Color.White,
    tertiary = Color(0xFF157B4F),
    onTertiary = Color.White,
    error = Color(0xFFAA2F35),
    onError = Color.White,
    background = Color(0xFFEEF2F5),
    onBackground = Color(0xFF14202B),
    surface = Color(0xFFF7F9FA),
    onSurface = Color(0xFF14202B),
    surfaceVariant = Color(0xFFE4EAEE),
    onSurfaceVariant = Color(0xFF56636E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF1F4F6),
    outline = Color(0xFF8F9BA5),
    outlineVariant = Color(0xFFDDE4E9),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF9CC3E6),
    onPrimary = Color(0xFF0B2236),
    primaryContainer = Color(0xFF1C3A57),
    onPrimaryContainer = Color(0xFFD6E4F2),
    secondary = Color(0xFF8CC8EC),
    onSecondary = Color(0xFF06283B),
    tertiary = Color(0xFF6FD3A4),
    onTertiary = Color(0xFF00391F),
    error = Color(0xFFFFB3B6),
    onError = Color(0xFF5E1015),
    background = Color(0xFF0E151C),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0E151C),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF24313C),
    onSurfaceVariant = Color(0xFFA7B4BF),
    surfaceContainerLowest = Color(0xFF111A22),
    surfaceContainerLow = Color(0xFF16202A),
    surfaceContainer = Color(0xFF16202A),
    surfaceContainerHigh = Color(0xFF1D2934),
    outline = Color(0xFF6C7A86),
    outlineVariant = Color(0xFF2C3A46),
)

/** Larger than Material defaults: read at arm's length in a moving cab. */
private val DriverTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold),
)

object Dimens {
    val ScreenPadding = 16.dp
    val CardGap = 12.dp
    val PrimaryActionHeight = 64.dp
    val SecondaryActionHeight = 56.dp
    val TileMinHeight = 88.dp
    val CardCorner = 16.dp

    /** Wide screens (tablet in the truck): content stays readable, the action area stays within one glance. */
    val ContentMaxWidth = 720.dp
    val ActionMaxWidth = 560.dp
    val ActionPaneWidth = 360.dp
    val LoginFormWidth = 480.dp
}

val LocalDriverColors = staticCompositionLocalOf { LightDriverColors }

object DriverTheme {
    val colors: DriverColors
        @Composable get() = LocalDriverColors.current
}

@Composable
fun DynamicOneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDriverColors provides if (darkTheme) DarkDriverColors else LightDriverColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = DriverTypography,
            content = content,
        )
    }
}
