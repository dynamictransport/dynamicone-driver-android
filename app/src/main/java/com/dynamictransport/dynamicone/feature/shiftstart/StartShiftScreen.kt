package com.dynamictransport.dynamicone.feature.shiftstart

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RvHookup
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ActionTone
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.HoldToConfirmButton
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusBadge
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.component.toneContainer
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.core.nfc.NfcAvailability
import com.dynamictransport.dynamicone.core.nfc.NfcTagReaderEffect
import com.dynamictransport.dynamicone.core.nfc.openNfcSettings
import com.dynamictransport.dynamicone.domain.model.CheckAnswer
import com.dynamictransport.dynamicone.domain.model.CheckItem
import com.dynamictransport.dynamicone.domain.model.CheckResult
import com.dynamictransport.dynamicone.domain.model.ShiftCommandFailure
import com.dynamictransport.dynamicone.domain.rules.VehicleCheckRules
import com.dynamictransport.dynamicone.feature.common.ActionBar
import com.dynamictransport.dynamicone.feature.common.ConnectivityBadge
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.LocalImageThumbnail
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.NoteField
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.formatDuration
import com.dynamictransport.dynamicone.feature.common.rememberCurrentInstant
import java.io.File
import java.time.Duration
import java.time.Instant

@Composable
fun StartShiftRoute(onClose: () -> Unit, viewModel: StartShiftViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    // Tags are read only while the screen waits for the vehicle's tag.
    NfcTagReaderEffect(
        active = state.step == StartShiftStep.SCAN && !state.lookingUp && state.nfc == NfcAvailability.AVAILABLE,
        onTag = viewModel::onTagRead,
    )

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> viewModel.onPhotoResult(taken) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onTakePhoto() else viewModel.onCameraPermissionDenied()
    }
    LaunchedEffect(state.cameraRequest) {
        val path = state.cameraRequest ?: return@LaunchedEffect
        viewModel.onCameraLaunched()
        try {
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path)))
        } catch (_: ActivityNotFoundException) {
            viewModel.onCameraUnavailable()
        }
    }

    val close = {
        viewModel.onCancel()
        onClose()
    }
    val back = { if (!viewModel.onBack()) close() }
    BackHandler(onBack = back)

    StartShiftScreen(
        state = state,
        onBack = back,
        onClose = if (state.step == StartShiftStep.DONE) onClose else close,
        onRestWarningConfirmed = viewModel::onRestWarningConfirmed,
        onOpenNfcSettings = { openNfcSettings(context) },
        onUsePlannedVehicle = viewModel::onUsePlannedVehicle,
        onRescan = viewModel::onRescan,
        onStartCheck = viewModel::onStartCheck,
        onAnswerOk = viewModel::onAnswerOk,
        onAnswerNotApplicable = viewModel::onAnswerNotApplicable,
        onDefect = viewModel::onDefect,
        onNoteChanged = viewModel::onNoteChanged,
        onTakePhoto = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                viewModel.onTakePhoto()
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        },
        onRemovePhoto = viewModel::onRemovePhoto,
        onDefectDone = viewModel::onDefectDone,
        onEditItem = viewModel::onEditItem,
        onSubmit = viewModel::onSubmit,
        onStartOver = viewModel::onStartOver,
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun StartShiftScreen(
    state: StartShiftUiState,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onRestWarningConfirmed: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onUsePlannedVehicle: () -> Unit,
    onRescan: () -> Unit,
    onStartCheck: () -> Unit,
    onAnswerOk: () -> Unit,
    onAnswerNotApplicable: () -> Unit,
    onDefect: () -> Unit,
    onNoteChanged: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onDefectDone: () -> Unit,
    onEditItem: (String) -> Unit,
    onSubmit: () -> Unit,
    onStartOver: () -> Unit,
    onMessageShown: () -> Unit,
    now: Instant = rememberCurrentInstant(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)
    val language = currentLocale().language

    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(if (state.step == StartShiftStep.CHECK) R.string.check_title else R.string.start_shift_title),
                onBack = if (state.step == StartShiftStep.DONE) null else onBack,
                backContentDescription = stringResource(R.string.common_back),
                actions = {
                    ConnectivityBadge(state.isOnline, Modifier.padding(end = 4.dp))
                    if (state.step != StartShiftStep.DONE) {
                        IconButton(onClick = onClose, modifier = Modifier.size(56.dp).testTag("start_shift_close")) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.start_shift_cancel))
                        }
                    }
                },
            )
        },
        bottomBar = {
            StartShiftActions(
                state = state,
                onClose = onClose,
                onRestWarningConfirmed = onRestWarningConfirmed,
                onOpenNfcSettings = onOpenNfcSettings,
                onUsePlannedVehicle = onUsePlannedVehicle,
                onRescan = onRescan,
                onStartCheck = onStartCheck,
                onAnswerOk = onAnswerOk,
                onAnswerNotApplicable = onAnswerNotApplicable,
                onDefect = onDefect,
                onDefectDone = onDefectDone,
                onSubmit = onSubmit,
                onStartOver = onStartOver,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .readableWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        ) {
            when (state.step) {
                StartShiftStep.LOADING -> LinearProgressIndicator(Modifier.fillMaxWidth().testTag("start_shift_loading"))
                StartShiftStep.REST_WARNING -> RestWarningCard(state.restEndsAt, now)
                StartShiftStep.SCAN -> ScanContent(state)
                StartShiftStep.VEHICLE -> VehicleContent(state)
                StartShiftStep.CHECK -> CheckContent(state, language, onNoteChanged, onTakePhoto, onRemovePhoto)
                StartShiftStep.SUMMARY -> SummaryContent(state, language, onEditItem)
                StartShiftStep.DONE -> DoneContent(state)
            }
        }
    }
}

@Composable
private fun StartShiftActions(
    state: StartShiftUiState,
    onClose: () -> Unit,
    onRestWarningConfirmed: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onUsePlannedVehicle: () -> Unit,
    onRescan: () -> Unit,
    onStartCheck: () -> Unit,
    onAnswerOk: () -> Unit,
    onAnswerNotApplicable: () -> Unit,
    onDefect: () -> Unit,
    onDefectDone: () -> Unit,
    onSubmit: () -> Unit,
    onStartOver: () -> Unit,
) {
    when (state.step) {
        StartShiftStep.LOADING -> Unit
        StartShiftStep.REST_WARNING -> ActionBar {
            HoldToConfirmButton(
                text = stringResource(R.string.start_shift_rest_confirm),
                hint = stringResource(R.string.common_hold_to_confirm),
                onConfirmed = onRestWarningConfirmed,
                icon = Icons.Filled.PlayCircle,
                tone = ActionTone.Neutral,
                modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
            )
            SecondaryActionButton(text = stringResource(R.string.start_shift_rest_wait), onClick = onClose, icon = Icons.Filled.Bedtime)
        }
        StartShiftStep.SCAN -> ActionBar {
            if (state.nfc == NfcAvailability.DISABLED) {
                PrimaryActionButton(
                    text = stringResource(R.string.attendance_open_nfc_settings),
                    onClick = onOpenNfcSettings,
                    icon = Icons.Filled.Nfc,
                    modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                )
            }
            if (state.canUsePlannedVehicle) {
                SecondaryActionButton(
                    text = stringResource(R.string.start_shift_use_planned, state.plannedVehicle?.registrationNumber.orEmpty()),
                    onClick = onUsePlannedVehicle,
                    icon = Icons.Filled.LocalShipping,
                    enabled = !state.lookingUp,
                    modifier = Modifier.testTag("start_shift_use_planned"),
                )
            } else if (state.nfc != NfcAvailability.DISABLED) {
                Text(
                    text = stringResource(R.string.start_shift_tag_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DriverTheme.colors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        StartShiftStep.VEHICLE -> ActionBar {
            PrimaryActionButton(
                text = stringResource(R.string.start_shift_begin_check),
                onClick = onStartCheck,
                icon = Icons.Filled.TaskAlt,
                modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
            )
            SecondaryActionButton(text = stringResource(R.string.start_shift_rescan), onClick = onRescan, icon = Icons.Filled.Nfc)
        }
        StartShiftStep.CHECK -> ActionBar {
            val answer = state.currentAnswer
            if (answer?.result == CheckResult.DEFECT) {
                PrimaryActionButton(
                    text = stringResource(R.string.check_defect_done),
                    onClick = onDefectDone,
                    icon = Icons.Filled.TaskAlt,
                    enabled = state.canContinue,
                    modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                )
                SecondaryActionButton(
                    text = stringResource(R.string.check_no_defect_after_all),
                    onClick = onAnswerOk,
                    icon = Icons.Filled.CheckCircle,
                    modifier = Modifier.testTag("check_answer_ok_after_all"),
                )
            } else {
                PrimaryActionButton(
                    text = stringResource(R.string.check_ok),
                    onClick = onAnswerOk,
                    icon = Icons.Filled.CheckCircle,
                    modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                )
                SecondaryActionButton(
                    text = stringResource(R.string.check_defect),
                    onClick = onDefect,
                    icon = Icons.Filled.Warning,
                    modifier = Modifier.testTag("check_answer_defect"),
                )
                if (state.currentItem?.notApplicableAllowed == true) {
                    SecondaryActionButton(
                        text = stringResource(R.string.check_not_applicable),
                        onClick = onAnswerNotApplicable,
                        icon = Icons.Filled.DoNotDisturbOn,
                        modifier = Modifier.testTag("check_answer_not_applicable"),
                    )
                }
            }
        }
        StartShiftStep.SUMMARY -> ActionBar {
            if (state.failure != null) {
                PrimaryActionButton(
                    text = stringResource(R.string.start_shift_start_over),
                    onClick = onStartOver,
                    icon = Icons.Filled.Nfc,
                    loading = state.submitting,
                    modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                )
            } else {
                HoldToConfirmButton(
                    text = stringResource(R.string.start_shift_submit),
                    hint = stringResource(R.string.common_hold_to_confirm),
                    onConfirmed = onSubmit,
                    icon = Icons.Filled.PlayCircle,
                    loading = state.submitting,
                    modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                )
            }
        }
        StartShiftStep.DONE -> ActionBar {
            PrimaryActionButton(
                text = stringResource(R.string.start_shift_done),
                onClick = onClose,
                icon = Icons.Filled.CheckCircle,
                modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
            )
        }
    }
}

@Composable
private fun RestWarningCard(restEndsAt: Instant?, now: Instant) {
    val colors = toneContainer(StatusTone.Warning)
    SectionCard(modifier = Modifier.testTag("start_shift_rest_warning")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(Icons.Filled.Bedtime, colors.container, colors.content)
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.start_shift_rest_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.height(12.dp))
        if (restEndsAt != null) {
            Text(
                text = stringResource(
                    R.string.start_shift_rest_body,
                    DriverFormats.time(restEndsAt),
                    formatDuration(Duration.between(now, restEndsAt)),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.start_shift_rest_note), style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
    }
}

@Composable
private fun ScanContent(state: StartShiftUiState) {
    val pulse by rememberInfiniteTransition(label = "nfc").animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "nfc_scale",
    )
    val colors = toneContainer(StatusTone.Info)
    SectionCard(modifier = Modifier.testTag("start_shift_scanning")) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(112.dp)
                    .scale(if (state.lookingUp) 1f else pulse)
                    .background(colors.container, CircleShape),
            ) {
                Icon(Icons.Filled.Nfc, contentDescription = null, tint = colors.content, modifier = Modifier.size(64.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.start_shift_scan_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.start_shift_scan_body),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            if (state.lookingUp) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("start_shift_looking_up"))
            }
        }
    }
    when (state.scanProblem) {
        ScanProblem.UNREADABLE -> StatusBanner(
            message = stringResource(R.string.attendance_outcome_unreadable),
            icon = Icons.Filled.ErrorOutline,
            tone = StatusTone.Warning,
            modifier = Modifier.testTag("start_shift_tag_unreadable"),
        )
        ScanProblem.UNKNOWN_TAG -> StatusBanner(
            message = stringResource(R.string.start_shift_tag_unknown),
            icon = Icons.Filled.ErrorOutline,
            tone = StatusTone.Danger,
            modifier = Modifier.testTag("start_shift_tag_unknown"),
        )
        null -> Unit
    }
    when (state.nfc) {
        NfcAvailability.AVAILABLE -> Unit
        NfcAvailability.DISABLED -> StatusBanner(
            message = stringResource(R.string.start_shift_nfc_disabled),
            icon = Icons.Filled.Nfc,
            tone = StatusTone.Warning,
            modifier = Modifier.testTag("start_shift_nfc_disabled"),
        )
        NfcAvailability.NOT_SUPPORTED -> StatusBanner(
            message = stringResource(R.string.start_shift_nfc_not_supported),
            icon = Icons.Filled.PhonelinkErase,
            tone = StatusTone.Warning,
            modifier = Modifier.testTag("start_shift_nfc_not_supported"),
        )
    }
}

@Composable
private fun VehicleContent(state: StartShiftUiState) {
    val colors = toneContainer(if (state.vehicleVerified && !state.manual) StatusTone.Positive else StatusTone.Neutral)
    SectionCard(modifier = Modifier.testTag("start_shift_vehicle")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(Icons.Filled.LocalShipping, colors.container, colors.content)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.vehicle?.registrationNumber ?: stringResource(R.string.start_shift_tag_read),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() }.testTag("start_shift_plate"),
                )
                state.vehicle?.displayName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(
                when {
                    state.manual -> R.string.start_shift_vehicle_manual
                    state.vehicleVerified -> R.string.start_shift_vehicle_verified
                    else -> R.string.start_shift_vehicle_unverified
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        state.plannedVehicle?.trailerRegistrationNumber?.let { trailer ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.RvHookup, contentDescription = null, tint = DriverTheme.colors.muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start_shift_trailer, trailer), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (state.otherThanPlanned) {
        StatusBanner(
            message = stringResource(R.string.start_shift_other_vehicle, state.plannedVehicle?.registrationNumber.orEmpty()),
            icon = Icons.Filled.Warning,
            tone = StatusTone.Warning,
            modifier = Modifier.testTag("start_shift_other_vehicle"),
        )
    }
}

@Composable
private fun CheckContent(
    state: StartShiftUiState,
    language: String,
    onNoteChanged: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onRemovePhoto: (String) -> Unit,
) {
    val item = state.currentItem ?: return
    val total = state.template?.items?.size ?: 0
    val answer = state.currentAnswer
    Text(
        text = stringResource(R.string.check_progress, state.itemIndex + 1, total),
        style = MaterialTheme.typography.titleSmall,
        color = DriverTheme.colors.muted,
        modifier = Modifier.testTag("check_progress"),
    )
    LinearProgressIndicator(progress = { (state.itemIndex + 1f) / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
    SectionCard(modifier = Modifier.testTag("check_item_${item.key}")) {
        state.vehicle?.registrationNumber?.let { plate ->
            Text(plate, style = MaterialTheme.typography.labelLarge, color = DriverTheme.colors.muted)
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = item.title(language),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        item.hint(language)?.let { hint ->
            Spacer(Modifier.height(6.dp))
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = DriverTheme.colors.muted)
        }
        answer?.let {
            Spacer(Modifier.height(12.dp))
            AnswerBadge(it.result)
        }
    }
    if (answer?.result == CheckResult.DEFECT) DefectPanel(item, answer, state.canAddPhoto, onNoteChanged, onTakePhoto, onRemovePhoto)
}

@Composable
private fun DefectPanel(
    item: CheckItem,
    answer: CheckAnswer,
    canAddPhoto: Boolean,
    onNoteChanged: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onRemovePhoto: (String) -> Unit,
) {
    SectionCard(modifier = Modifier.testTag("check_defect_panel")) {
        Text(
            text = stringResource(if (item.photoRequiredOnDefect) R.string.check_defect_photo_required else R.string.check_defect_photo_optional),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        if (answer.photos.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                answer.photos.forEach { photo ->
                    val remove = stringResource(R.string.check_photo_remove)
                    Box {
                        LocalImageThumbnail(photo.filePath, Modifier.size(88.dp))
                        IconButton(
                            onClick = { onRemovePhoto(photo.photoId) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(48.dp)
                                .semantics { contentDescription = remove },
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = null,
                                tint = DriverTheme.colors.onDanger,
                                modifier = Modifier.background(DriverTheme.colors.danger, CircleShape).padding(2.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        SecondaryActionButton(
            text = stringResource(R.string.check_take_photo),
            onClick = onTakePhoto,
            icon = Icons.Filled.AddAPhoto,
            enabled = canAddPhoto,
            modifier = Modifier.testTag("check_take_photo"),
        )
        Spacer(Modifier.height(8.dp))
        NoteField(
            value = answer.note.orEmpty(),
            onValueChange = onNoteChanged,
            label = stringResource(R.string.check_note_optional),
            maxLength = VehicleCheckRules.NOTE_MAX_LENGTH,
            modifier = Modifier.testTag("check_note"),
        )
    }
}

@Composable
private fun SummaryContent(state: StartShiftUiState, language: String, onEditItem: (String) -> Unit) {
    val total = state.template?.items?.size ?: 0
    val defects = state.defects
    val notApplicable = state.answers.values.count { it.result == CheckResult.NOT_APPLICABLE }
    state.failure?.let { failure ->
        StatusBanner(
            message = stringResource(failure.messageRes()),
            icon = Icons.Filled.ErrorOutline,
            tone = StatusTone.Danger,
            modifier = Modifier.testTag("start_shift_failed"),
        )
    }
    SectionCard(modifier = Modifier.testTag("start_shift_summary")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocalShipping, contentDescription = null, tint = DriverTheme.colors.brand, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = state.vehicle?.registrationNumber ?: stringResource(R.string.start_shift_tag_read),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = pluralStringResource(R.plurals.check_summary_items, total, total),
            style = MaterialTheme.typography.bodyLarge,
        )
        SummaryLine(
            icon = if (defects.isEmpty()) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            tone = if (defects.isEmpty()) StatusTone.Positive else StatusTone.Warning,
            text = if (defects.isEmpty()) {
                stringResource(R.string.check_summary_no_defects)
            } else {
                pluralStringResource(R.plurals.check_summary_defects, defects.size, defects.size)
            },
        )
        if (notApplicable > 0) {
            SummaryLine(
                icon = Icons.Filled.DoNotDisturbOn,
                tone = StatusTone.Neutral,
                text = pluralStringResource(R.plurals.check_summary_not_applicable, notApplicable, notApplicable),
            )
        }
        if (state.restWarningAcknowledged) {
            SummaryLine(Icons.Filled.Bedtime, StatusTone.Warning, stringResource(R.string.start_shift_summary_rest))
        }
        if (state.manual) {
            SummaryLine(Icons.Filled.Nfc, StatusTone.Warning, stringResource(R.string.start_shift_vehicle_manual))
        }
    }
    defects.forEach { (item, answer) ->
        SectionCard(modifier = Modifier.clickable { onEditItem(item.key) }.testTag("summary_defect_${item.key}")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = DriverTheme.colors.muted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title(language), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = pluralStringResource(R.plurals.check_photo_count, answer.photos.size, answer.photos.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DriverTheme.colors.muted,
                    )
                    answer.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                Text(stringResource(R.string.check_edit), style = MaterialTheme.typography.titleSmall, color = DriverTheme.colors.brand)
            }
        }
    }
    if (defects.isNotEmpty()) {
        Text(stringResource(R.string.check_summary_defects_note), style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
    }
}

@Composable
private fun DoneContent(state: StartShiftUiState) {
    val colors = toneContainer(if (state.recorded) StatusTone.Positive else StatusTone.Info)
    SectionCard(modifier = Modifier.testTag(if (state.recorded) "start_shift_recorded" else "start_shift_saved")) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            CircleIcon(if (state.recorded) Icons.Filled.CheckCircle else Icons.Filled.CloudUpload, colors.container, colors.content, size = 112, iconSize = 64)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(if (state.recorded) R.string.start_shift_recorded else R.string.start_shift_saved),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            state.vehicle?.registrationNumber?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            if (!state.recorded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (state.holdReason == ShiftCommandFailure.ACCESS_REQUIRED) R.string.shift_hold_access else R.string.start_shift_saved_body,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            if (state.defects.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pluralStringResource(R.plurals.start_shift_defects_reported, state.defects.size, state.defects.size),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun AnswerBadge(result: CheckResult) {
    val (text, icon, tone) = when (result) {
        CheckResult.OK -> Triple(R.string.check_answer_ok, Icons.Filled.CheckCircle, StatusTone.Positive)
        CheckResult.DEFECT -> Triple(R.string.check_answer_defect, Icons.Filled.Warning, StatusTone.Warning)
        CheckResult.NOT_APPLICABLE -> Triple(R.string.check_answer_not_applicable, Icons.Filled.DoNotDisturbOn, StatusTone.Neutral)
    }
    StatusBadge(text = stringResource(text), icon = icon, tone = tone)
}

@Composable
private fun SummaryLine(icon: ImageVector, tone: StatusTone, text: String) {
    val colors = toneContainer(tone)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
        Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CircleIcon(
    icon: ImageVector,
    container: Color,
    content: Color,
    size: Int = 64,
    iconSize: Int = 34,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .background(container, CircleShape),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize.dp))
    }
}

/** Why the server did not take a start or end (docs/16 §7, §9). */
fun ShiftCommandFailure.messageRes(): Int = when (this) {
    ShiftCommandFailure.TAG_UNKNOWN -> R.string.shift_failure_tag_unknown
    ShiftCommandFailure.CHECK_INVALID -> R.string.shift_failure_check_invalid
    ShiftCommandFailure.TIME_REJECTED -> R.string.shift_failure_time
    ShiftCommandFailure.ALREADY_ACTIVE -> R.string.shift_failure_already_active
    ShiftCommandFailure.NOT_ACTIVE -> R.string.shift_failure_not_active
    ShiftCommandFailure.ACCESS_REQUIRED -> R.string.shift_hold_access
    ShiftCommandFailure.CONFLICT, ShiftCommandFailure.REJECTED -> R.string.shift_failure_rejected
}
