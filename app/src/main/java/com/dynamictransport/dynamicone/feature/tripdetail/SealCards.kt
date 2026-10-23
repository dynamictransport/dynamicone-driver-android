package com.dynamictransport.dynamicone.feature.tripdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusBadge
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.DocumentSlotState
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import com.dynamictransport.dynamicone.domain.model.PendingTripAction
import com.dynamictransport.dynamicone.domain.model.SealEntry
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.rules.DriverInputRules
import com.dynamictransport.dynamicone.feature.common.LabeledDivider
import com.dynamictransport.dynamicone.feature.common.StatusUiBadge
import com.dynamictransport.dynamicone.feature.common.messageRes
import com.dynamictransport.dynamicone.feature.common.stopLabel
import com.dynamictransport.dynamicone.feature.common.toSealPhotoUi

/**
 * One card per container of the export loading stop (docs/27): the seal number and the seal photo are
 * two separate server records with their own state. Neither is shown as done because of the other, and
 * the stop is never completed here; the planner closes it.
 */
@Composable
internal fun SealCards(
    entries: List<SealEntry>,
    trip: Trip,
    pending: PendingTripAction?,
    submitting: Boolean,
    onRecordSealNumber: (ExecutionAction, String) -> Unit,
    onOpenDocuments: (String?) -> Unit,
) {
    entries.forEach { entry ->
        val pendingHere = pending?.takeIf {
            it.action.code == ExecutionActionCode.RECORD_SEAL_NUMBER && it.action.containerId == entry.containerId
        }
        SealCard(entry, trip, pendingHere, submitting, onRecordSealNumber, onOpenDocuments)
    }
}

@Composable
private fun SealCard(
    entry: SealEntry,
    trip: Trip,
    pending: PendingTripAction?,
    submitting: Boolean,
    onRecordSealNumber: (ExecutionAction, String) -> Unit,
    onOpenDocuments: (String?) -> Unit,
) {
    val colors = DriverTheme.colors
    val containerId = entry.containerId.orEmpty()
    val containerNumber = entry.action.containerNumber
        ?: trip.containers.firstOrNull { it.id == entry.containerId }?.number
        ?: stringResource(R.string.value_unknown)
    val stopName = entry.action.stopName
        ?: trip.stops.firstOrNull { it.id == entry.action.stopId }?.let { stopLabel(it) }
    val recorded = entry.recordedSealNumber
    // The draft starts from the server's value and is replaced when the server's value changes (after a save).
    var draft by rememberSaveable(containerId, recorded) { mutableStateOf(recorded.orEmpty()) }
    val normalized = DriverInputRules.normalizeSealNumber(draft)
    val valid = DriverInputRules.validateSealNumber(draft) == null
    val canSave = !submitting && pending == null && valid && normalized != recorded
    val save = { onRecordSealNumber(entry.action, draft) }

    SectionCard(modifier = Modifier.testTag("seal_card_$containerId")) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.brand, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.seal_card_title, containerNumber), style = MaterialTheme.typography.titleMedium)
                stopName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
            }
        }
        if (entry.complete) {
            Spacer(Modifier.height(8.dp))
            StatusBadge(
                text = stringResource(R.string.seal_complete),
                icon = Icons.Filled.TaskAlt,
                tone = StatusTone.Positive,
                modifier = Modifier.testTag("seal_complete_$containerId"),
            )
        }
        Spacer(Modifier.height(12.dp))

        // --- Seal number: recorded on the server or not; the text field is the only input ---
        Text(stringResource(R.string.seal_number_label), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (recorded != null) {
            StatusBadge(
                text = stringResource(R.string.seal_number_recorded, recorded),
                icon = Icons.Filled.Check,
                tone = StatusTone.Positive,
                modifier = Modifier.testTag("seal_number_state_$containerId"),
            )
        } else {
            StatusBadge(
                text = stringResource(R.string.seal_number_missing),
                icon = Icons.Filled.Lock,
                tone = StatusTone.Warning,
                modifier = Modifier.testTag("seal_number_state_$containerId"),
            )
        }
        pending?.let {
            val sending = it.state == PendingCommandState.SENDING
            Spacer(Modifier.height(4.dp))
            StatusBadge(
                text = stringResource(it.state.messageRes()),
                icon = if (sending) Icons.Filled.CloudUpload else Icons.Filled.SyncProblem,
                tone = if (sending) StatusTone.Info else StatusTone.Warning,
                modifier = Modifier.testTag("seal_pending_$containerId"),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(DriverInputRules.SEAL_NUMBER_MAX_LENGTH) },
            label = { Text(stringResource(R.string.seal_number_label)) },
            singleLine = true,
            isError = draft.isNotEmpty() && !valid,
            supportingText = { Text(stringResource(R.string.counter_of, draft.length, DriverInputRules.SEAL_NUMBER_MAX_LENGTH)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) save() }),
            enabled = pending == null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("seal_input_$containerId"),
        )
        Spacer(Modifier.height(4.dp))
        PrimaryActionButton(
            text = stringResource(R.string.seal_save),
            onClick = save,
            icon = Icons.Filled.Lock,
            enabled = canSave,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("seal_save_$containerId"),
        )

        LabeledDivider()

        // --- Seal photo: the server's slot state, never "approved" for a pending upload ---
        Text(stringResource(R.string.seal_photo_label), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        val slot = entry.photoSlot
        StatusUiBadge(slot?.state.toSealPhotoUi(), Modifier.testTag("seal_photo_state_$containerId"))
        slot?.reviewNote?.takeIf { slot.state == DocumentSlotState.REJECTED }?.let {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.slot_review_note, it), style = MaterialTheme.typography.bodyMedium)
        }
        if (slot != null) {
            Spacer(Modifier.height(8.dp))
            val label = when (slot.state) {
                DocumentSlotState.MISSING, DocumentSlotState.UNKNOWN -> R.string.seal_photo_take
                DocumentSlotState.REJECTED -> R.string.seal_photo_retake
                DocumentSlotState.PENDING_REVIEW, DocumentSlotState.APPROVED, DocumentSlotState.NOT_REQUIRED -> R.string.seal_photo_replace
            }
            SecondaryActionButton(
                text = stringResource(label),
                onClick = { onOpenDocuments(slot.key) },
                icon = Icons.Filled.AddAPhoto,
                enabled = !submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("seal_photo_upload_$containerId"),
            )
        }
    }
}
