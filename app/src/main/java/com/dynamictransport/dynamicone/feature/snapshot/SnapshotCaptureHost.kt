package com.dynamictransport.dynamicone.feature.snapshot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.common.AppDirectories
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.push.NotificationNavigation
import com.dynamictransport.dynamicone.core.push.RoadSnapshotNotifier
import com.dynamictransport.dynamicone.core.snapshot.CaptureNotice
import com.dynamictransport.dynamicone.core.snapshot.SnapshotCaptureCoordinator
import com.dynamictransport.dynamicone.domain.model.SnapshotCamera
import com.dynamictransport.dynamicone.domain.model.SnapshotFailure
import com.dynamictransport.dynamicone.domain.model.SnapshotRequest
import com.dynamictransport.dynamicone.domain.repository.RoadSnapshotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Takes the planner's road photo on this tablet (docs/24 §1): a request that arrives while the app is on screen
 * is taken at once, one opened from the notification is taken as soon as the app is resumed. The host draws
 * nothing but a short banner after the attempt; the screen underneath stays as it is.
 */
@Composable
fun SnapshotCaptureHost(viewModel: SnapshotCaptureViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pending by viewModel.pendingCapture.collectAsStateWithLifecycle()
    val tapped by viewModel.tappedRequest.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var resumed by remember { mutableStateOf(false) }

    LifecycleResumeEffect(viewModel) {
        resumed = true
        viewModel.onHostVisible(true)
        onPauseOrDispose {
            resumed = false
            viewModel.onHostVisible(false)
        }
    }

    // The notification path: the driver tapped, so asking for the camera permission is fine here.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onPermissionAnswered(granted)
    }
    LaunchedEffect(tapped, resumed) {
        val requestId = tapped ?: return@LaunchedEffect
        if (!resumed) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        viewModel.onNotificationOpened(requestId, granted) { permission.launch(Manifest.permission.CAMERA) }
    }

    // The push path and the continuation of the notification path: take the photo while on screen.
    LaunchedEffect(pending, resumed) {
        val request = pending ?: return@LaunchedEffect
        if (!resumed) return@LaunchedEffect
        viewModel.capture(request) { preferred, output -> takePhoto(context, lifecycleOwner, preferred, output) }
    }

    notice?.let { current ->
        LaunchedEffect(current) {
            delay(NOTICE_MILLIS)
            viewModel.clearNotice(current)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = notice != null, modifier = Modifier.align(Alignment.TopCenter)) {
            val current = notice ?: return@AnimatedVisibility
            val failure = current.failure
            val text = when {
                failure != null -> stringResource(R.string.snapshot_banner_failed, stringResource(failure.label()))
                current.requestedByDisplayName != null -> stringResource(R.string.snapshot_banner_taken, current.requestedByDisplayName)
                else -> stringResource(R.string.snapshot_banner_taken_office)
            }
            StatusBanner(
                message = text,
                icon = if (failure == null) Icons.Filled.PhotoCamera else Icons.Filled.NoPhotography,
                tone = if (failure == null) StatusTone.Positive else StatusTone.Warning,
                modifier = Modifier
                    .statusBarsPadding()
                    .readableWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("snapshot_banner"),
            )
        }
    }
}

/** Which camera actually took the photo, and the file it was written to. */
data class TakenPhoto(val file: File, val camera: SnapshotCamera, val capturedAt: Instant)

sealed interface CaptureAttempt {
    data class Taken(val photo: TakenPhoto) : CaptureAttempt
    data class Failed(val failure: SnapshotFailure) : CaptureAttempt
}

@HiltViewModel
class SnapshotCaptureViewModel @Inject constructor(
    private val coordinator: SnapshotCaptureCoordinator,
    private val navigation: NotificationNavigation,
    private val repository: RoadSnapshotRepository,
    private val notifier: RoadSnapshotNotifier,
    private val directories: AppDirectories,
    private val clock: Clock,
) : ViewModel() {

    val pendingCapture: StateFlow<SnapshotRequest?> = coordinator.pendingCapture
    val tappedRequest: StateFlow<String?> = navigation.snapshotRequest
    val notice: StateFlow<CaptureNotice?> = coordinator.notice

    private var awaitingPermission: SnapshotRequest? = null
    private var sequence = 0L

    fun onHostVisible(visible: Boolean) = coordinator.setHostVisible(visible)

    fun clearNotice(notice: CaptureNotice) = coordinator.clearNotice(notice)

    /** The driver opened the notification: the stored request is taken, after the permission if it is missing. */
    fun onNotificationOpened(requestId: String, hasPermission: Boolean, askPermission: () -> Unit) {
        navigation.consumeSnapshot(requestId)
        notifier.cancel(requestId)
        viewModelScope.launch {
            val request = repository.pendingRequest(requestId) ?: return@launch
            if (hasPermission) {
                coordinator.capture(request)
            } else {
                awaitingPermission = request
                askPermission()
            }
        }
    }

    fun onPermissionAnswered(granted: Boolean) {
        val request = awaitingPermission ?: return
        awaitingPermission = null
        if (granted) coordinator.capture(request) else fail(request, SnapshotFailure.PERMISSION_DENIED)
    }

    /** [take] runs the camera on the main thread with the host's lifecycle. */
    fun capture(request: SnapshotRequest, take: suspend (SnapshotCamera, File) -> CaptureAttempt) {
        coordinator.consume(request.requestId)
        viewModelScope.launch {
            if (request.isExpiredAt(clock.instant())) {
                fail(request, SnapshotFailure.EXPIRED)
                return@launch
            }
            val output = File(File(directories.cacheDir, CAPTURE_DIRECTORY).apply { mkdirs() }, "${request.requestId}.jpg")
            when (val attempt = withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) { take(request.preferredCamera, output) }) {
                is CaptureAttempt.Taken -> {
                    val stored = repository.storePhoto(request.requestId, attempt.photo.file, attempt.photo.capturedAt, attempt.photo.camera)
                    announce(request, if (stored) null else SnapshotFailure.CAMERA_UNAVAILABLE)
                }
                is CaptureAttempt.Failed -> fail(request, attempt.failure)
                null -> {
                    output.delete()
                    fail(request, SnapshotFailure.CAMERA_UNAVAILABLE)
                }
            }
        }
    }

    private fun fail(request: SnapshotRequest, failure: SnapshotFailure) {
        viewModelScope.launch {
            repository.reportFailure(request.requestId, failure)
            announce(request, failure)
        }
    }

    private fun announce(request: SnapshotRequest, failure: SnapshotFailure?) {
        coordinator.announce(CaptureNotice(request.requestId, request.requestedByDisplayName, failure, ++sequence))
    }

    private companion object {
        const val CAPTURE_DIRECTORY = "road_snapshot_capture"
        const val CAPTURE_TIMEOUT_MILLIS = 30_000L
    }
}

/**
 * One photo without a preview (the automatic path never interrupts the driver with a permission dialog,
 * ASSUMPTIONS S6: without the permission the planner learns `permission_denied`): the dock camera when the request prefers it and the tablet has one, else the back
 * camera. The use case is bound to the host's lifecycle only for the moment of the capture.
 */
private suspend fun takePhoto(context: Context, lifecycleOwner: LifecycleOwner, preferred: SnapshotCamera, output: File): CaptureAttempt {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        return CaptureAttempt.Failed(SnapshotFailure.PERMISSION_DENIED)
    }
    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return CaptureAttempt.Failed(SnapshotFailure.CAMERA_UNAVAILABLE)
    val provider = try {
        awaitProvider(context)
    } catch (_: Exception) {
        return CaptureAttempt.Failed(SnapshotFailure.CAMERA_UNAVAILABLE)
    }
    // The tablet lists its dock input as an external camera even with nothing docked: a camera that cannot be
    // configured is given up after a few seconds and the next one is tried.
    for ((selector, camera) in cameraCandidates(provider, preferred)) {
        val taken = withTimeoutOrNull(ATTEMPT_TIMEOUT_MILLIS) { captureWith(context, lifecycleOwner, provider, selector, output) } == true
        if (taken && output.length() > 0) return CaptureAttempt.Taken(TakenPhoto(output, camera, Instant.now()))
        output.delete()
    }
    return CaptureAttempt.Failed(SnapshotFailure.CAMERA_UNAVAILABLE)
}

/** One attempt on one camera; the use case is bound only for the moment of the capture. */
private suspend fun captureWith(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    provider: ProcessCameraProvider,
    selector: CameraSelector,
    output: File,
): Boolean {
    val capture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setJpegQuality(JPEG_QUALITY)
        .build()
    return try {
        provider.bindToLifecycle(lifecycleOwner, selector, capture)
        suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(output).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    } finally {
        runCatching { provider.unbind(capture) }
    }
}

private suspend fun awaitProvider(context: Context): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener(
        {
            try {
                continuation.resume(future.get())
            } catch (e: Exception) {
                if (continuation.isActive) continuation.cancel(e)
            }
        },
        ContextCompat.getMainExecutor(context),
    )
}

@androidx.annotation.OptIn(ExperimentalLensFacing::class)
private fun cameraCandidates(provider: ProcessCameraProvider, preferred: SnapshotCamera): List<Pair<CameraSelector, SnapshotCamera>> {
    val external = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL).build() to SnapshotCamera.EXTERNAL
    val back = CameraSelector.DEFAULT_BACK_CAMERA to SnapshotCamera.BACK
    val available = listOf(external, back).filter { (selector, _) -> runCatching { provider.hasCamera(selector) }.getOrDefault(false) }
    return if (preferred == SnapshotCamera.EXTERNAL) available else available.sortedBy { (_, camera) -> if (camera == SnapshotCamera.BACK) 0 else 1 }
}

private fun SnapshotFailure.label(): Int = when (this) {
    SnapshotFailure.CAMERA_UNAVAILABLE -> R.string.snapshot_reason_camera
    SnapshotFailure.PERMISSION_DENIED -> R.string.snapshot_reason_permission
    SnapshotFailure.EXPIRED -> R.string.snapshot_reason_expired
    SnapshotFailure.DRIVER_DISMISSED -> R.string.snapshot_reason_dismissed
}

private const val NOTICE_MILLIS = 5_000L
private const val ATTEMPT_TIMEOUT_MILLIS = 8_000L
private const val JPEG_QUALITY = 90
