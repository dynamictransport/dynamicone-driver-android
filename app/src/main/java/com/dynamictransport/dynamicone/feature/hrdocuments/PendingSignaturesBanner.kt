package com.dynamictransport.dynamicone.feature.hrdocuments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.domain.model.HrDocumentLists
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.repository.HrDocumentRepository
import com.dynamictransport.dynamicone.domain.rules.DeadlineKind
import com.dynamictransport.dynamicone.domain.rules.HrDocumentRules
import com.dynamictransport.dynamicone.feature.common.rememberCurrentInstant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** Documents that still need this employee's signature: none signed on the phone and waiting to be sent. */
data class PendingSignatures(
    val count: Int,
    /** The soonest deadline; null when none of them has one. */
    val soonestDue: Instant?,
    /** The only waiting document, opened directly. */
    val singleDocumentId: String?,
)

/** The reminder card on the home and attendance screens and the badge in the navigation (docs/17 §12). */
@HiltViewModel
class PendingSignaturesViewModel @Inject constructor(
    private val repository: HrDocumentRepository,
    private val clock: Clock,
) : ViewModel() {

    private val lists: StateFlow<HrDocumentLists> = repository.observeLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HrDocumentLists.EMPTY)

    val pending: StateFlow<PendingSignatures?> = lists
        .map(::summary)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Reads the list when the stored one is old; a push brings new documents in between. */
    fun onResume() {
        val updatedAt = lists.value.updatedAt
        if (updatedAt != null && Duration.between(updatedAt, clock.instant()) < REFRESH_AFTER) return
        viewModelScope.launch { repository.refresh() }
    }

    private companion object {
        val REFRESH_AFTER: Duration = Duration.ofMinutes(10)

        fun summary(lists: HrDocumentLists): PendingSignatures? {
            val waiting = lists.pending.orEmpty().filter { document ->
                val local = lists.localFor(document.id)
                local == null || local.state == LocalSignatureState.FAILED
            }
            if (waiting.isEmpty()) return null
            return PendingSignatures(
                count = waiting.size,
                soonestDue = waiting.mapNotNull { it.dueAt }.minOrNull(),
                singleDocumentId = waiting.singleOrNull()?.id,
            )
        }
    }
}

/** Shown only when something waits for a signature; [onOpen] gets the document id when it is the only one. */
@Composable
fun PendingSignaturesBanner(onOpen: (String?) -> Unit, viewModel: PendingSignaturesViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    pending?.let { PendingSignaturesCard(it, onOpen) }
}

@Composable
fun PendingSignaturesCard(pending: PendingSignatures, onOpen: (String?) -> Unit, now: Instant = rememberCurrentInstant()) {
    val deadline = HrDocumentRules.deadline(pending.soonestDue, now, ZoneId.systemDefault())
    val count = pluralStringResource(R.plurals.hr_banner_pending, pending.count, pending.count)
    val message = if (deadline == DeadlineKind.NONE) count else count + "\n" + deadlineText(pending.soonestDue, now)
    StatusBanner(
        message = message,
        icon = Icons.Filled.Draw,
        tone = if (deadline == DeadlineKind.LATER || deadline == DeadlineKind.NONE) StatusTone.Info else StatusTone.Warning,
        actionLabel = stringResource(R.string.hr_banner_open),
        onAction = { onOpen(pending.singleDocumentId) },
        modifier = Modifier.testTag("hr_pending_banner"),
    )
}
