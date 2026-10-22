package com.dynamictransport.dynamicone.feature.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.AssignedVehicleState

const val ASSIGNED_VEHICLE_TAG = "assigned_vehicle"
const val ASSIGNED_VEHICLE_VALUE_TAG = "assigned_vehicle_value"
const val ASSIGNED_VEHICLE_RETRY_TAG = "assigned_vehicle_retry"

/** "Dyna042 · WX 12345": the plate alone without a DYNA code, the code alone without a plate, null without both. */
@Composable
fun vehicleLabel(companyCode: String?, registrationNumber: String?): String? =
    if (companyCode != null && registrationNumber != null) {
        stringResource(R.string.value_separator, companyCode, registrationNumber)
    } else {
        registrationNumber ?: companyCode
    }

/**
 * The driver's current fleet vehicle (`me.assignedVehicle`). Loading, a failed read and "no vehicle assigned" each
 * have their own icon and text. A trip's vehicle is never shown here: it can be a different one.
 */
@Composable
fun AssignedVehicleRow(
    state: AssignedVehicleState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val assigned = state is AssignedVehicleState.Assigned
    val value = when (state) {
        is AssignedVehicleState.Assigned ->
            vehicleLabel(state.vehicle.companyCode, state.vehicle.registrationNumber) ?: stringResource(R.string.value_unknown)
        AssignedVehicleState.NotAssigned -> stringResource(R.string.assigned_vehicle_none)
        AssignedVehicleState.Loading -> stringResource(R.string.assigned_vehicle_loading)
        AssignedVehicleState.Unavailable -> stringResource(R.string.assigned_vehicle_unavailable)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag(ASSIGNED_VEHICLE_TAG),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
            when (state) {
                is AssignedVehicleState.Assigned ->
                    Icon(Icons.Filled.LocalShipping, contentDescription = null, tint = DriverTheme.colors.brand)
                AssignedVehicleState.NotAssigned ->
                    Icon(Icons.Outlined.LocalShipping, contentDescription = null, tint = DriverTheme.colors.muted)
                AssignedVehicleState.Loading ->
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                AssignedVehicleState.Unavailable ->
                    Icon(Icons.Filled.SyncProblem, contentDescription = null, tint = DriverTheme.colors.danger)
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
                text = stringResource(R.string.assigned_vehicle_label),
                style = MaterialTheme.typography.bodySmall,
                color = DriverTheme.colors.muted,
            )
            Text(
                text = value,
                style = if (assigned) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = if (assigned) LocalContentColor.current else DriverTheme.colors.muted,
                modifier = Modifier.testTag(ASSIGNED_VEHICLE_VALUE_TAG),
            )
        }
        if (state == AssignedVehicleState.Unavailable) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(ASSIGNED_VEHICLE_RETRY_TAG),
            ) {
                Text(stringResource(R.string.common_retry))
            }
        }
    }
}
