package com.dynamictransport.dynamicone.feature.common

import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ActionTone
import com.dynamictransport.dynamicone.core.design.component.ChoiceRow
import com.dynamictransport.dynamicone.core.design.component.ClickableCard
import com.dynamictransport.dynamicone.core.design.component.HoldToConfirmButton
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.StatusBadge
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.model.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val PRIMARY_ACTION_TAG = "primary_action"

@Composable
fun DriverActionButton(
    ui: ActionUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    DriverActionButton(
        text = stringResource(ui.label),
        icon = ui.icon,
        tone = ui.tone,
        requiresHold = ui.requiresHold,
        onClick = onClick,
        modifier = modifier,
        loading = loading,
        enabled = enabled,
    )
}

/** Same as the [ActionUi] variant for labels that come from the server. */
@Composable
fun DriverActionButton(
    text: String,
    icon: ImageVector,
    tone: ActionTone,
    requiresHold: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    if (requiresHold) {
        HoldToConfirmButton(
            text = text,
            hint = stringResource(R.string.common_hold_to_confirm),
            onConfirmed = onClick,
            icon = icon,
            tone = tone,
            loading = loading,
            enabled = enabled,
            modifier = modifier,
        )
    } else {
        PrimaryActionButton(
            text = text,
            onClick = onClick,
            icon = icon,
            tone = tone,
            loading = loading,
            enabled = enabled,
            modifier = modifier,
        )
    }
}

const val ACTION_BAR_TAG = "action_bar"

/**
 * Pinned bottom area holding the screen's dominant action, always within thumb reach. On a wide screen the buttons
 * keep a readable width in the middle instead of stretching across the whole tablet, and the content above keeps
 * the full width (a pane beside the content left one button in an empty column and squeezed the rest).
 */
@Composable
fun ActionBar(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag(ACTION_BAR_TAG),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
                .readableWidth(Dimens.ActionMaxWidth)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            content = content,
        )
    }
}

/**
 * Screen with one dominant action area pinned under the content ([ActionBar]) on phones and tablets alike: the
 * same place on every device, and the content keeps the whole width above it. [actions] null means the screen has
 * no action area (for example a finished trip). [content] receives the padding to apply, as with [Scaffold].
 */
@Composable
fun ActionScaffold(
    topBar: @Composable () -> Unit,
    actions: (@Composable ColumnScope.() -> Unit)?,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = topBar,
        bottomBar = { if (actions != null) ActionBar(content = actions) },
        snackbarHost = snackbarHost,
        content = content,
    )
}

/**
 * [offlineMessage] differs by backend: the demo queues actions offline, the real API needs a
 * connection for every action.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SyncBanners(
    isOnline: Boolean,
    pendingCount: Int,
    failedCount: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes offlineMessage: Int = R.string.sync_offline,
) {
    if (isOnline && pendingCount == 0 && failedCount == 0) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!isOnline) {
            StatusBanner(stringResource(offlineMessage), Icons.Filled.CloudOff, StatusTone.Warning)
        }
        if (pendingCount > 0) {
            StatusBanner(
                pluralStringResource(R.plurals.sync_pending_count, pendingCount, pendingCount),
                Icons.Filled.CloudUpload,
                StatusTone.Info,
            )
        }
        if (failedCount > 0) {
            StatusBanner(
                message = pluralStringResource(R.plurals.sync_failed_count, failedCount, failedCount),
                icon = Icons.Filled.SyncProblem,
                tone = StatusTone.Danger,
                actionLabel = stringResource(R.string.common_retry),
                onAction = onRetry,
            )
        }
    }
}

@Composable
fun ConnectivityBadge(isOnline: Boolean, modifier: Modifier = Modifier) {
    StatusBadge(
        text = stringResource(if (isOnline) R.string.status_online else R.string.status_offline),
        icon = if (isOnline) Icons.Filled.Wifi else Icons.Filled.CloudOff,
        tone = if (isOnline) StatusTone.Positive else StatusTone.Warning,
        modifier = modifier,
    )
}

@Composable
fun DemoBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.demo_badge),
        style = MaterialTheme.typography.labelSmall,
        color = DriverTheme.colors.onWarningContainer,
        modifier = modifier
            .background(DriverTheme.colors.warningContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun StatusUiBadge(status: StatusUi, modifier: Modifier = Modifier) {
    StatusBadge(text = stringResource(status.label), icon = status.icon, tone = status.tone, modifier = modifier)
}

/** Shown only when something still has to reach the server. */
@Composable
fun SyncStateBadge(syncState: SyncState, modifier: Modifier = Modifier) {
    if (syncState == SyncState.SYNCED) return
    StatusUiBadge(syncState.toStatusUi(), modifier)
}

@Composable
fun TripSummaryCard(
    trip: Trip,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    ClickableCard(onClick = onClick, modifier = modifier.testTag("trip_card_${trip.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = trip.tripNo,
                style = MaterialTheme.typography.labelMedium,
                color = DriverTheme.colors.muted,
                modifier = Modifier.weight(1f),
            )
            StatusUiBadge(trip.toStatusUi())
        }
        Text(
            text = tripTitle(trip),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        trip.nextStop?.takeIf { !trip.state.isTerminal }?.let { next ->
            MetaItem(Icons.Filled.Place, stringResource(R.string.trip_next_stop_value, next.name ?: stringResource(R.string.value_unknown)))
            if (next.coordinates == null) {
                MetaItem(
                    icon = Icons.Filled.LocationOff,
                    text = stringResource(R.string.location_missing),
                    modifier = Modifier.testTag("trip_card_location_missing_${trip.id}"),
                )
            }
        }
        trip.plannedStartAt?.let { MetaItem(Icons.Filled.Schedule, DriverFormats.dayTime(it, locale)) }
        // This trip's vehicle, which can differ from the driver's assigned vehicle.
        vehicleLabel(trip.vehicleCompanyCode, trip.vehiclePlate)?.let {
            MetaItem(Icons.Filled.LocalShipping, it, Modifier.testTag("trip_card_vehicle_${trip.id}"))
        }
        trip.containerNo?.let { MetaItem(Icons.Filled.Inventory2, it) }
        if (trip.syncState != SyncState.SYNCED) {
            Spacer(Modifier.size(8.dp))
            SyncStateBadge(trip.syncState)
        }
    }
}

/** Planned route (first → last stop) when known; the Driver API list has no placeholder places. */
@Composable
private fun tripTitle(trip: Trip): String {
    val start = trip.origin?.name ?: trip.pickupName
    val end = trip.destination?.name ?: trip.deliveryName
    return if (start != null && end != null && start != end) {
        stringResource(R.string.trip_route, start, end)
    } else {
        start ?: end ?: trip.orderNumber ?: trip.tripNo
    }
}

@Composable
fun MetaItem(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(vertical = 2.dp)) {
        Icon(icon, contentDescription = null, tint = DriverTheme.colors.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MessageEffect(
    @StringRes message: Int?,
    snackbarHostState: SnackbarHostState,
    onShown: () -> Unit,
) {
    val text = message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            onShown()
        }
    }
}

@Composable
fun NoteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    maxLength: Int,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        label = { Text(label) },
        isError = isError,
        minLines = 2,
        maxLines = 4,
        supportingText = { Text(stringResource(R.string.counter_of, value.length, maxLength)) },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun LocalImageThumbnail(
    path: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    targetPx: Int = 160,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeSampledBitmap(path, targetPx = targetPx) }
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    } else {
        Box(modifier.background(DriverTheme.colors.neutralContainer, shape))
    }
}

private fun decodeSampledBitmap(path: String, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

enum class AppLanguage(val tag: String?, @param:StringRes val label: Int) {
    SYSTEM(null, R.string.language_system),
    ENGLISH("en", R.string.language_en),
    POLISH("pl", R.string.language_pl),
    TURKISH("tr", R.string.language_tr),
    RUSSIAN("ru", R.string.language_ru),
    ;

    companion object {
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            val language = if (locales.isEmpty) null else locales[0]?.language
            return entries.firstOrNull { it.tag != null && it.tag == language } ?: SYSTEM
        }

        fun apply(language: AppLanguage) {
            val locales = language.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}

@Composable
fun LanguageOptions(selected: AppLanguage, onSelect: (AppLanguage) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLanguage.entries.forEach { language ->
            ChoiceRow(
                label = stringResource(language.label),
                selected = language == selected,
                onSelect = { onSelect(language) },
            )
        }
    }
}

@Composable
fun LanguagePickerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.login_language)) },
        text = {
            LanguageOptions(
                selected = AppLanguage.current(),
                onSelect = {
                    onDismiss()
                    AppLanguage.apply(it)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
fun LabeledDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
