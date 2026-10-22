package com.dynamictransport.dynamicone.feature.snapshot

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.domain.model.SnapshotFailure
import com.dynamictransport.dynamicone.domain.model.SnapshotOutcome
import com.dynamictransport.dynamicone.domain.model.SnapshotOutcomeState
import com.dynamictransport.dynamicone.feature.common.DriverFormats

/** The planner's latest road photo request as the driver should see it (docs/24 §8, S3): who, when, and whether it went out. */
@Composable
fun SnapshotBanner(outcome: SnapshotOutcome, modifier: Modifier = Modifier) {
    val who = outcome.requestedByDisplayName ?: stringResource(R.string.snapshot_office)
    val time = DriverFormats.time(outcome.capturedAt ?: outcome.updatedAt)
    val (text, icon, tone) = when (outcome.state) {
        SnapshotOutcomeState.WAITING_FOR_DRIVER ->
            Triple(stringResource(R.string.snapshot_home_waiting, who), Icons.Filled.PhotoCamera, StatusTone.Info)
        SnapshotOutcomeState.CAPTURED ->
            Triple(stringResource(R.string.snapshot_home_pending_upload, who, time), Icons.Filled.CloudUpload, StatusTone.Info)
        SnapshotOutcomeState.UPLOADED ->
            Triple(stringResource(R.string.snapshot_home_uploaded, who, time), Icons.Filled.PhotoCamera, StatusTone.Positive)
        SnapshotOutcomeState.FAILED ->
            Triple(stringResource(R.string.snapshot_home_failed, stringResource(outcome.failure.label())), Icons.Filled.NoPhotography, StatusTone.Warning)
    }
    StatusBanner(message = text, icon = icon, tone = tone, modifier = modifier.testTag("snapshot_home_${outcome.state.name}"))
}

internal fun SnapshotFailure?.label(): Int = when (this) {
    SnapshotFailure.CAMERA_UNAVAILABLE, null -> R.string.snapshot_reason_camera
    SnapshotFailure.PERMISSION_DENIED -> R.string.snapshot_reason_permission
    SnapshotFailure.EXPIRED -> R.string.snapshot_reason_expired
    SnapshotFailure.DRIVER_DISMISSED -> R.string.snapshot_reason_dismissed
}
