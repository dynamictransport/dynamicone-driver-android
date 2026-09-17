package com.dynamictransport.driver.feature.documents.scan

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dynamictransport.driver.domain.scan.FrameVerdict
import com.dynamictransport.driver.domain.scan.LumaImage
import com.dynamictransport.driver.domain.scan.ScanMode
import com.dynamictransport.driver.domain.scan.ScanQualityPolicy
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * CameraX preview in the sensor's 4:3 shape with live checks of every analysed frame (off the main thread,
 * only the latest frame is kept) and a full-resolution photo written to [captureRequest].
 */
@Composable
internal fun DocumentCameraPreview(
    mode: ScanMode,
    captureRequest: String?,
    torchOn: Boolean,
    onFrameAnalyzed: (FrameVerdict, Long) -> Unit,
    onCaptureRequestHandled: () -> Unit,
    onCaptureSaved: () -> Unit,
    onCaptureFailed: () -> Unit,
    onCameraUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnFrame by rememberUpdatedState(onFrameAnalyzed)
    val latestOnSaved by rememberUpdatedState(onCaptureSaved)
    val latestOnFailed by rememberUpdatedState(onCaptureFailed)
    val latestOnUnavailable by rememberUpdatedState(onCameraUnavailable)
    val latestTorchOn by rememberUpdatedState(torchOn)
    val controller = remember(context) { documentCameraController(context) }

    DisposableEffect(controller, lifecycleOwner, mode) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        controller.setImageAnalysisAnalyzer(analysisExecutor, DocumentFrameAnalyzer(mode) { verdict, timestamp ->
            latestOnFrame(verdict, timestamp)
        })
        controller.bindToLifecycle(lifecycleOwner)
        val initialization = controller.initializationFuture
        initialization.addListener(
            {
                val available = runCatching {
                    initialization.get()
                    controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                }.getOrDefault(false)
                if (available) controller.enableTorch(latestTorchOn) else latestOnUnavailable()
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(controller, torchOn) {
        controller.enableTorch(torchOn)
    }

    LaunchedEffect(controller, captureRequest) {
        val path = captureRequest ?: return@LaunchedEffect
        onCaptureRequestHandled()
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(File(path)).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = latestOnSaved()

                override fun onError(exception: ImageCaptureException) = latestOnFailed()
            },
        )
    }

    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                this.controller = controller
            }
        },
        modifier = modifier,
    )
}

private fun documentCameraController(context: Context): LifecycleCameraController {
    val fourThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
    return LifecycleCameraController(context).apply {
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
        imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        previewResolutionSelector = ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build()
        imageCaptureResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(fourThree)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
        imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(fourThree)
            .setResolutionStrategy(
                ResolutionStrategy(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()
        imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
        isTapToFocusEnabled = true
    }
}

private const val ANALYSIS_WIDTH = 1280
private const val ANALYSIS_HEIGHT = 960

/** Checks each analysed frame with the same rules that later judge the stored photo. */
internal class DocumentFrameAnalyzer(
    private val mode: ScanMode,
    private val onVerdict: (FrameVerdict, Long) -> Unit,
) : ImageAnalysis.Analyzer {

    private val policy = ScanQualityPolicy()

    override fun analyze(image: ImageProxy) {
        image.use { frame ->
            val plane = frame.planes.firstOrNull() ?: return
            val crop = frame.cropRect
            val upright = LumaImage.fromLumaPlane(
                plane.buffer, plane.rowStride, crop.left, crop.top, crop.width(), crop.height(), ANALYSIS_EDGE_PX,
            ).rotate(frame.imageInfo.rotationDegrees)
            // Blur is judged at full analysis resolution in the middle of the frame, where the sheet is.
            val patchSize = min(crop.width(), crop.height()) * SHARPNESS_PATCH_PERCENT / 100
            val patch = LumaImage.fromLumaPlane(
                plane.buffer, plane.rowStride, crop.centerX() - patchSize / 2, crop.centerY() - patchSize / 2, patchSize, patchSize,
            )
            onVerdict(policy.evaluateFrame(mode, upright, patch), frame.imageInfo.timestamp)
        }
    }

    private companion object {
        const val ANALYSIS_EDGE_PX = 480
        const val SHARPNESS_PATCH_PERCENT = 30
    }
}
