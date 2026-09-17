package com.dynamictransport.dynamicone.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripStop

/** Localized label of a server action, falling back to the backend's display label. */
@Composable
fun executionActionLabel(action: ExecutionAction): String =
    action.knownLabelRes()?.let { stringResource(it) } ?: action.label ?: stringResource(R.string.exec_stop_done)

/** "2. Pickup · Rotterdam ECT Delta" */
@Composable
fun stopLabel(stop: TripStop): String = stringResource(
    R.string.stop_sequence_label,
    stop.sequence,
    stringResource(stop.activity.labelRes()),
    stop.locationName ?: stop.address ?: stringResource(R.string.value_unknown),
)

/**
 * Which stop and container an action or document slot belongs to, from the trip the server returned.
 * Null when it belongs to the whole trip.
 */
@Composable
fun tripContextLabel(trip: Trip?, stopId: String?, containerId: String?): String? {
    val parts = buildList {
        if (stopId != null) {
            val stop = trip?.stops?.firstOrNull { it.id == stopId }
            add(stop?.let { stopLabel(it) } ?: stringResource(R.string.slot_stop_unknown))
        }
        if (containerId != null) {
            val number = trip?.containers?.firstOrNull { it.id == containerId }?.number
            add(stringResource(R.string.exec_context_container, number ?: stringResource(R.string.value_unknown)))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
