package com.dynamictransport.dynamicone.feature.tripdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ActionTone
import com.dynamictransport.dynamicone.core.design.component.ChoiceRow
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.HoldToConfirmButton
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.RejectReason
import com.dynamictransport.dynamicone.domain.rules.DriverInputRules
import com.dynamictransport.dynamicone.feature.common.ActionBar
import com.dynamictransport.dynamicone.feature.common.NoteField
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.icon
import com.dynamictransport.dynamicone.feature.common.labelRes

@Composable
fun RejectTripRoute(
    onBack: () -> Unit,
    onRejected: () -> Unit,
    viewModel: RejectTripViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.completed) {
        if (state.completed) onRejected()
    }
    RejectTripScreen(
        state = state,
        onBack = onBack,
        onReasonSelected = viewModel::onReasonSelected,
        onNoteChanged = viewModel::onNoteChanged,
        onSubmit = viewModel::onSubmit,
    )
}

@Composable
fun RejectTripScreen(
    state: RejectTripUiState,
    onBack: () -> Unit,
    onReasonSelected: (RejectReason) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.reject_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
        bottomBar = {
            ActionBar {
                state.error?.let {
                    Text(
                        text = stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HoldToConfirmButton(
                    text = stringResource(R.string.reject_confirm),
                    hint = stringResource(R.string.common_hold_to_confirm),
                    onConfirmed = onSubmit,
                    icon = Icons.Filled.Block,
                    tone = ActionTone.Danger,
                    enabled = state.canSubmit,
                    loading = state.submitting,
                    modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                )
            }
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        ) {
            state.tripNo?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = DriverTheme.colors.muted)
            }
            Text(stringResource(R.string.reject_question), style = MaterialTheme.typography.titleLarge)
            RejectReason.entries.forEach { reason ->
                ChoiceRow(
                    label = stringResource(reason.labelRes()),
                    selected = state.reason == reason,
                    onSelect = { onReasonSelected(reason) },
                    icon = reason.icon(),
                    modifier = Modifier.testTag("reject_reason_${reason.name}"),
                )
            }
            NoteField(
                value = state.note,
                onValueChange = onNoteChanged,
                label = stringResource(if (state.noteRequired) R.string.reject_note_required else R.string.reject_note_optional),
                maxLength = DriverInputRules.REJECT_NOTE_MAX_LENGTH,
                isError = state.noteRequired && state.note.isBlank(),
                modifier = Modifier.testTag("reject_note"),
            )
        }
    }
}
