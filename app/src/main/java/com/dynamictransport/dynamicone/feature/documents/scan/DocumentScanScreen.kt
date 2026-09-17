package com.dynamictransport.dynamicone.feature.documents.scan

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ActionTone
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.component.toneContainer
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.scan.DocumentQuad
import com.dynamictransport.dynamicone.domain.scan.ScanMode
import com.dynamictransport.dynamicone.feature.common.ActionBar
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.StatusUi
import com.dynamictransport.dynamicone.feature.common.labelRes
import com.dynamictransport.dynamicone.feature.common.toStatusUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

enum class CameraPermission { GRANTED, DENIED, BLOCKED }

@Composable
fun DocumentScanRoute(
    onClose: () -> Unit,
    onAccepted: (String) -> Unit,
    viewModel: DocumentScanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var permission by remember { mutableStateOf(context.cameraPermission()) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = when {
            granted -> CameraPermission.GRANTED
            activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA) ->
                CameraPermission.BLOCKED
            else -> CameraPermission.DENIED
        }
    }
    // The driver may have allowed the camera in the system settings meanwhile.
    LifecycleResumeEffect(Unit) {
        if (context.cameraPermission() == CameraPermission.GRANTED) permission = CameraPermission.GRANTED
        onPauseOrDispose {}
    }
    val phase = state.phase
    LaunchedEffect(phase, permission) {
        if (phase == ScanPhase.CAMERA && permission != CameraPermission.GRANTED && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val close: () -> Unit = {
        viewModel.onClose()
        onClose()
    }
    BackHandler(onBack = close)
    val accepted = state.acceptedPath
    LaunchedEffect(accepted) {
        if (accepted != null) {
            viewModel.onAcceptedHandled()
            onAccepted(accepted)
        }
    }

    DocumentScanScreen(
        state = state,
        permission = permission,
        onClose = close,
        onCapture = viewModel::onCaptureClicked,
        onToggleTorch = viewModel::onToggleTorch,
        onRetake = viewModel::onRetake,
        onUse = viewModel::onUse,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        onOpenSettings = {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            } catch (_: ActivityNotFoundException) {
                // No settings screen on this device; the permission content stays visible.
            }
        },
        onMessageShown = viewModel::onMessageShown,
        cameraPreview = { modifier ->
            DocumentCameraPreview(
                mode = state.mode,
                captureRequest = state.captureRequest,
                torchOn = state.torchOn,
                onFrameAnalyzed = viewModel::onFrameAnalyzed,
                onCaptureRequestHandled = viewModel::onCaptureRequestHandled,
                onCaptureSaved = viewModel::onCaptureSaved,
                onCaptureFailed = viewModel::onCaptureFailed,
                onCameraUnavailable = viewModel::onCameraUnavailable,
                modifier = modifier,
            )
        },
    )
}

private fun Context.cameraPermission(): CameraPermission =
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
        CameraPermission.GRANTED
    } else {
        CameraPermission.DENIED
    }

private enum class ScanContent { CAMERA, PROCESSING, REVIEW, PERMISSION, UNAVAILABLE }

@Composable
fun DocumentScanScreen(
    state: DocumentScanUiState,
    permission: CameraPermission,
    onClose: () -> Unit,
    onCapture: () -> Unit,
    onToggleTorch: () -> Unit,
    onRetake: () -> Unit,
    onUse: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onMessageShown: () -> Unit,
    cameraPreview: @Composable (Modifier) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)
    val review = state.review
    val content = when {
        state.phase == ScanPhase.PROCESSING -> ScanContent.PROCESSING
        state.phase == ScanPhase.REVIEW && review != null -> ScanContent.REVIEW
        permission != CameraPermission.GRANTED -> ScanContent.PERMISSION
        state.cameraUnavailable -> ScanContent.UNAVAILABLE
        else -> ScanContent.CAMERA
    }

    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(state.documentType.labelRes()),
                onBack = onClose,
                backContentDescription = stringResource(R.string.scan_close),
                actions = {
                    if (content == ScanContent.CAMERA) {
                        IconButton(onClick = onToggleTorch, modifier = Modifier.size(56.dp).testTag("scan_torch")) {
                            Icon(
                                imageVector = if (state.torchOn) Icons.Filled.FlashlightOff else Icons.Filled.FlashlightOn,
                                contentDescription = stringResource(if (state.torchOn) R.string.scan_torch_off else R.string.scan_torch_on),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            when (content) {
                ScanContent.PROCESSING -> Unit
                ScanContent.REVIEW -> ActionBar {
                    if (review?.accepted == true) {
                        PrimaryActionButton(
                            text = stringResource(R.string.scan_use),
                            onClick = onUse,
                            icon = Icons.Filled.Check,
                            enabled = !state.handedOver,
                            modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                        )
                        SecondaryActionButton(
                            text = stringResource(R.string.scan_retake_secondary),
                            onClick = onRetake,
                            icon = Icons.Filled.Replay,
                            enabled = !state.handedOver,
                        )
                    } else {
                        // A photo that failed a check can never be used: taking it again is the only way on.
                        PrimaryActionButton(
                            text = stringResource(R.string.scan_retake),
                            onClick = onRetake,
                            icon = Icons.Filled.PhotoCamera,
                            tone = ActionTone.Neutral,
                            modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                        )
                    }
                }
                ScanContent.PERMISSION -> ActionBar {
                    if (permission == CameraPermission.BLOCKED) {
                        PrimaryActionButton(
                            text = stringResource(R.string.scan_permission_settings),
                            onClick = onOpenSettings,
                            icon = Icons.Filled.Settings,
                            modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                        )
                    } else {
                        PrimaryActionButton(
                            text = stringResource(R.string.scan_permission_allow),
                            onClick = onRequestPermission,
                            icon = Icons.Filled.PhotoCamera,
                            modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                        )
                    }
                }
                ScanContent.UNAVAILABLE -> ActionBar {
                    SecondaryActionButton(text = stringResource(R.string.scan_close), onClick = onClose)
                }
                ScanContent.CAMERA -> ActionBar {
                    PrimaryActionButton(
                        text = stringResource(R.string.scan_capture),
                        onClick = onCapture,
                        icon = Icons.Filled.PhotoCamera,
                        enabled = state.canCapture,
                        loading = state.capturing,
                        modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                    )
                    if (state.mode == ScanMode.DOCUMENT) {
                        Text(
                            text = stringResource(R.string.scan_auto_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = DriverTheme.colors.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (content) {
                ScanContent.PROCESSING -> ProcessingContent()
                ScanContent.REVIEW -> review?.let { ReviewContent(it) }
                ScanContent.PERMISSION -> MessageContent(Icons.Filled.PhotoCamera, R.string.scan_permission_title, R.string.scan_permission_body)
                ScanContent.UNAVAILABLE -> MessageContent(Icons.Filled.NoPhotography, R.string.error_camera_unavailable, null)
                ScanContent.CAMERA -> CameraContent(state, cameraPreview)
            }
        }
    }
}

@Composable
private fun CameraContent(state: DocumentScanUiState, cameraPreview: @Composable (Modifier) -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            // Same 4:3 shape as the sensor, so the checked frame and the visible picture match exactly.
            Box(Modifier.aspectRatio(if (landscape) 4f / 3f else 3f / 4f)) {
                cameraPreview(Modifier.fillMaxSize())
                ScanOverlay(mode = state.mode, quad = state.quad, ready = state.canCapture, modifier = Modifier.fillMaxSize())
            }
        }
        GuidanceBanner(
            ui = guidanceOf(state),
            modifier = Modifier
                .padding(horizontal = Dimens.ScreenPadding, vertical = 10.dp)
                .testTag("scan_guidance"),
        )
    }
}

private fun guidanceOf(state: DocumentScanUiState): StatusUi {
    val issue = state.liveIssue
    return when {
        state.capturing -> StatusUi(R.string.scan_capturing, Icons.Filled.PhotoCamera, StatusTone.Positive)
        state.cameraStarting -> StatusUi(R.string.scan_camera_starting, Icons.Filled.PhotoCamera, StatusTone.Info)
        issue != null -> issue.toStatusUi()
        state.mode == ScanMode.DOCUMENT -> StatusUi(R.string.scan_ready_auto, Icons.Filled.CheckCircle, StatusTone.Positive)
        else -> StatusUi(R.string.scan_ready_photo, Icons.Filled.CheckCircle, StatusTone.Positive)
    }
}

private val NotReadyOutline = Color(0xFFFFB300)

/** Guide corners (documents) and the detected sheet: green when every check passes, amber otherwise. */
@Composable
private fun ScanOverlay(mode: ScanMode, quad: DocumentQuad?, ready: Boolean, modifier: Modifier = Modifier) {
    val outline = if (ready) DriverTheme.colors.action else NotReadyOutline
    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        if (mode == ScanMode.DOCUMENT) {
            val inset = min(size.width, size.height) * 0.04f
            val arm = min(size.width, size.height) * 0.12f
            val guide = Color.White.copy(alpha = 0.9f)
            val guideEdge = Color.Black.copy(alpha = 0.45f)
            listOf(
                Offset(inset, inset) to Offset(1f, 1f),
                Offset(size.width - inset, inset) to Offset(-1f, 1f),
                Offset(size.width - inset, size.height - inset) to Offset(-1f, -1f),
                Offset(inset, size.height - inset) to Offset(1f, -1f),
            ).forEach { (corner, direction) ->
                val horizontalEnd = Offset(corner.x + arm * direction.x, corner.y)
                val verticalEnd = Offset(corner.x, corner.y + arm * direction.y)
                // A dark edge keeps the white guide visible over white paper, a light table or bright sky.
                drawLine(guideEdge, corner, horizontalEnd, stroke * 2, StrokeCap.Round)
                drawLine(guideEdge, corner, verticalEnd, stroke * 2, StrokeCap.Round)
                drawLine(guide, corner, horizontalEnd, stroke, StrokeCap.Round)
                drawLine(guide, corner, verticalEnd, stroke, StrokeCap.Round)
            }
        }
        if (quad != null) {
            val sheet = Path().apply {
                quad.corners.forEachIndexed { index, point ->
                    val x = point.x * size.width
                    val y = point.y * size.height
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(sheet, outline.copy(alpha = 0.22f))
            drawPath(sheet, outline, style = Stroke(width = stroke, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun GuidanceBanner(ui: StatusUi, modifier: Modifier = Modifier) {
    val colors = toneContainer(ui.tone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(colors.container, RoundedCornerShape(14.dp))
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(ui.icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(ui.label), style = MaterialTheme.typography.titleMedium, color = colors.content)
    }
}

@Composable
private fun ProcessingContent() {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.ScreenPadding),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(56.dp))
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.scan_processing), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ReviewContent(review: ScanReview) {
    Column(Modifier.fillMaxSize()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(DriverTheme.colors.neutralContainer),
        ) {
            ReviewImage(
                path = review.imagePath,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .testTag("scan_review_image"),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = 10.dp),
        ) {
            if (review.accepted) {
                GuidanceBanner(
                    ui = StatusUi(R.string.scan_review_accepted, Icons.Filled.CheckCircle, StatusTone.Positive),
                    modifier = Modifier.testTag("scan_review_accepted"),
                )
            } else {
                Text(
                    text = stringResource(R.string.scan_review_rejected),
                    style = MaterialTheme.typography.titleMedium,
                    color = DriverTheme.colors.danger,
                )
                review.issues.take(MAX_SHOWN_ISSUES).forEach { issue ->
                    GuidanceBanner(
                        ui = issue.toStatusUi().copy(tone = StatusTone.Danger),
                        modifier = Modifier.testTag("scan_issue_${issue.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewImage(path: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeForReview(path) }
    }
    val current = bitmap
    if (current != null) {
        Image(bitmap = current, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
    } else {
        Box(modifier)
    }
}

private fun decodeForReview(path: String): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    // At most REVIEW_EDGE_PX on the long edge: enough for the screen, and a full scan never has to fit in memory.
    while (max(bounds.outWidth, bounds.outHeight) / sample > REVIEW_EDGE_PX) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

@Composable
private fun MessageContent(icon: ImageVector, @StringRes title: Int, @StringRes body: Int?) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
    ) {
        EmptyState(icon = icon, title = stringResource(title), body = body?.let { stringResource(it) })
    }
}

private const val MAX_SHOWN_ISSUES = 3
private const val REVIEW_EDGE_PX = 2048
