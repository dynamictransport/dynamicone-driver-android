package com.dynamictransport.dynamicone.feature.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import com.dynamictransport.dynamicone.domain.model.VehiclePositionState
import java.time.Duration
import java.time.Instant
import java.util.Locale

const val VEHICLE_POSITION_TAG = "vehicle_position"
const val VEHICLE_POSITION_VALUE_TAG = "vehicle_position_value"
const val VEHICLE_POSITION_MAP_TAG = "vehicle_position_map"
const val VEHICLE_POSITION_RETRY_TAG = "vehicle_position_retry"

/** The row is left out when the server has no position endpoint or no vehicle is assigned. */
val VehiclePositionState.isShown: Boolean
    get() = this != VehiclePositionState.NotSupported && this != VehiclePositionState.NotAssigned

/**
 * Last reported position of the assigned vehicle (docs/19): place (or coordinates when the phone cannot name it) and how
 * old the position is. The map app is opened with the vehicle's point; nothing is tracked on the phone.
 */
@Composable
fun VehiclePositionRow(
    state: VehiclePositionState,
    placeName: String?,
    now: Instant,
    onShowOnMap: (GeoPoint) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isShown) return
    val known = state as? VehiclePositionState.Known
    val value = when (state) {
        is VehiclePositionState.Known -> stringResource(
            R.string.value_separator,
            placeName ?: coordinatesText(state.position.point),
            positionAge(state.position.positionTime, now),
        )
        VehiclePositionState.Loading -> stringResource(R.string.vehicle_position_loading)
        VehiclePositionState.Unavailable -> stringResource(R.string.vehicle_position_unavailable)
        VehiclePositionState.NotLinked -> stringResource(R.string.vehicle_position_not_linked)
        else -> stringResource(R.string.vehicle_position_no_data)
    }
    Column(modifier = modifier.testTag(VEHICLE_POSITION_TAG)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
                when (state) {
                    is VehiclePositionState.Known ->
                        Icon(Icons.Filled.Place, contentDescription = null, tint = DriverTheme.colors.brand)
                    VehiclePositionState.Loading ->
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    VehiclePositionState.Unavailable ->
                        Icon(Icons.Filled.SyncProblem, contentDescription = null, tint = DriverTheme.colors.danger)
                    else -> Icon(Icons.Filled.LocationOff, contentDescription = null, tint = DriverTheme.colors.muted)
                }
            }
            Spacer(Modifier.width(12.dp))
            // Label and value are read together by screen readers.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(
                    text = stringResource(R.string.vehicle_position_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = DriverTheme.colors.muted,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (known != null) MaterialTheme.colorScheme.onSurface else DriverTheme.colors.muted,
                    modifier = Modifier.testTag(VEHICLE_POSITION_VALUE_TAG),
                )
            }
            if (state == VehiclePositionState.Unavailable) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(VEHICLE_POSITION_RETRY_TAG),
                ) {
                    Text(stringResource(R.string.common_retry))
                }
            }
        }
        if (known != null) {
            SecondaryActionButton(
                text = stringResource(R.string.vehicle_position_show_on_map),
                onClick = { onShowOnMap(known.position.point) },
                icon = Icons.Filled.Map,
                modifier = Modifier
                    .padding(start = 36.dp, top = 4.dp)
                    .testTag(VEHICLE_POSITION_MAP_TAG),
            )
        }
    }
}

/** "just now", "12 min ago", "3 h 5 min ago"; a day or older shows the date and time. */
@Composable
fun positionAge(time: Instant, now: Instant): String {
    val age = Duration.between(time, now)
    return when {
        age < Duration.ofMinutes(1) -> stringResource(R.string.vehicle_position_just_now)
        age < Duration.ofHours(1) -> stringResource(
            R.string.vehicle_position_ago,
            stringResource(R.string.vehicle_position_minutes, age.toMinutes().toInt()),
        )
        age < Duration.ofDays(1) -> stringResource(R.string.vehicle_position_ago, formatDuration(age))
        else -> DriverFormats.dayTime(time, currentLocale())
    }
}

/** Locale-independent decimal degrees, e.g. "50.2945, 18.6714". */
fun coordinatesText(point: GeoPoint): String = String.format(Locale.ROOT, "%.4f, %.4f", point.latitude, point.longitude)
