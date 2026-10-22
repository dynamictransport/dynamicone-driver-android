package com.dynamictransport.dynamicone.feature.personnelfiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ClickableCard
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusBadge
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.AssignedVehicleState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.VehicleDocument
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentRequirement
import com.dynamictransport.dynamicone.domain.model.VehicleDocuments
import com.dynamictransport.dynamicone.domain.repository.AuthRepository
import com.dynamictransport.dynamicone.domain.repository.VehicleDocumentRepository
import com.dynamictransport.dynamicone.domain.rules.PersonnelFileRules
import com.dynamictransport.dynamicone.feature.common.AssignedVehicleRow
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.currentLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VehicleDocumentsUiState(
    val documents: LoadedPart<VehicleDocuments> = LoadedPart(),
    val refreshing: Boolean = false,
)

/**
 * Files of the vehicle the server currently assigns to the driver. Read again on every visit together with `me`, so
 * a changed assignment replaces the files of the previous vehicle.
 */
@HiltViewModel
class VehicleDocumentsViewModel @Inject constructor(
    private val repository: VehicleDocumentRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val lastRead = MutableStateFlow<RefreshResult?>(null)
    private val refreshing = MutableStateFlow(false)

    val uiState: StateFlow<VehicleDocumentsUiState> = combine(repository.observe(), lastRead, refreshing) { snapshot, result, isRefreshing ->
        // A stale answer the repository still shows is flagged; one it dropped (vehicle changed) reads as missing.
        val part = LoadedPart.of(snapshot?.documents, result)
        VehicleDocumentsUiState(documents = part.copy(stale = part.stale || snapshot?.stale == true), refreshing = isRefreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VehicleDocumentsUiState())

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            // `me` too: the assignment shown elsewhere (home, profile) follows the same answer.
            launch { authRepository.refreshProfile() }
            lastRead.value = repository.refresh()
            refreshing.value = false
        }
    }
}

@Composable
fun VehicleDocumentsRoute(
    onBack: (() -> Unit)?,
    onOpenFile: (FileToView) -> Unit,
    viewModel: VehicleDocumentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    VehicleDocumentsScreen(state = state, onBack = onBack, onOpenFile = onOpenFile, onRetry = viewModel::refresh)
}

@Composable
fun VehicleDocumentsScreen(
    state: VehicleDocumentsUiState,
    onBack: (() -> Unit)?,
    onOpenFile: (FileToView) -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.vehicle_documents_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = Dimens.ScreenPadding,
                end = Dimens.ScreenPadding,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier
                .fillMaxSize()
                .readableWidth(),
        ) {
            if (state.refreshing) item(key = "refreshing") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            val part = state.documents
            val documents = part.data
            val error = part.error
            val vehicle = documents?.vehicle
            when {
                documents == null && error != null -> item(key = "error") {
                    LoadError(error, onRetry, Modifier.testTag("vehicle_documents_error"))
                }
                documents == null -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                vehicle == null -> item(key = "none") {
                    EmptyState(
                        icon = Icons.Outlined.LocalShipping,
                        title = stringResource(R.string.vehicle_documents_none_title),
                        body = stringResource(R.string.vehicle_documents_none_body),
                        modifier = Modifier.testTag(VEHICLE_DOCUMENTS_NONE_TAG),
                    )
                }
                else -> {
                    if (part.stale) item(key = "stale") { StaleBanner(onRetry) }
                    item(key = "vehicle") {
                        SectionCard { AssignedVehicleRow(state = AssignedVehicleState.Assigned(vehicle), onRetry = onRetry) }
                    }
                    items(documents.requirements, key = { "requirement_${it.type.name}_${it.documentId}" }) { requirement ->
                        VehicleRequirementRow(requirement, documents.documents.firstOrNull { it.id == requirement.documentId }, onOpenFile)
                    }
                    val others = documents.otherDocuments
                    if (others.isNotEmpty()) {
                        item(key = "others_title") {
                            Text(
                                text = stringResource(R.string.vehicle_documents_other),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .semantics { heading() },
                            )
                        }
                        items(others, key = { "file_${it.id}" }) { document -> VehicleFileRow(document, onOpenFile) }
                    }
                    item(key = "note") {
                        StatusBanner(stringResource(R.string.my_documents_no_upload), Icons.Filled.Info, StatusTone.Neutral)
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleRequirementRow(requirement: VehicleDocumentRequirement, file: VehicleDocument?, onOpenFile: (FileToView) -> Unit) {
    val locale = currentLocale()
    val visual = requirement.status.visual()
    val title = stringResource(requirement.type.labelRes())
    val documentId = requirement.documentId
    val canOpen = PersonnelFileRules.canOpen(requirement)
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                // Vehicle files carry no dates: only the upload time is known, no validity is shown or guessed.
                file?.uploadedAt?.let { uploadedAt ->
                    Text(
                        text = stringResource(R.string.document_uploaded_on, DriverFormats.dayTime(uploadedAt, locale)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DriverTheme.colors.muted,
                    )
                }
                Spacer(Modifier.height(6.dp))
                StatusBadge(stringResource(visual.label), visual.icon, visual.tone)
            }
            if (canOpen) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.document_open), tint = DriverTheme.colors.brand)
            } else {
                Text(stringResource(R.string.document_no_file), style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
            }
        }
    }
    val tag = "vehicle_documents_requirement_${requirement.type.name}"
    if (canOpen && documentId != null) {
        ClickableCard(onClick = { onOpenFile(FileToView(documentId, title, file?.contentType)) }, modifier = Modifier.testTag(tag)) { content() }
    } else {
        SectionCard(modifier = Modifier.testTag(tag)) { content() }
    }
}

@Composable
private fun VehicleFileRow(document: VehicleDocument, onOpenFile: (FileToView) -> Unit) {
    val locale = currentLocale()
    val title = stringResource(document.type.labelRes())
    ClickableCard(
        onClick = { onOpenFile(FileToView(document.id, title, document.contentType)) },
        modifier = Modifier.testTag("vehicle_documents_file_${document.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Description, contentDescription = null, tint = DriverTheme.colors.brand, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                document.uploadedAt?.let { uploadedAt ->
                    Text(
                        text = stringResource(R.string.document_uploaded_on, DriverFormats.dayTime(uploadedAt, locale)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DriverTheme.colors.muted,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.document_open), tint = DriverTheme.colors.brand)
        }
    }
}

const val VEHICLE_DOCUMENTS_NONE_TAG = "vehicle_documents_none"
