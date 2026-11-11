package com.dynamictransport.dynamicone.core.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import com.dynamictransport.dynamicone.core.common.AppLogger
import com.dynamictransport.dynamicone.core.mdm.DeviceCheckInScheduler
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What the stream's socket tells the service. Called on OkHttp threads. */
interface RemoteStreamEvents {
    fun onOpen()

    fun onText(text: String)

    /** [code] is the WebSocket close code; null when the connection failed. */
    fun onClosed(code: Int?)
}

/** Opens `GET mdm/remote-sessions/{sessionId}/stream` (docs/29 §4.1). */
fun interface RemoteStreamConnector {
    fun connect(sessionId: String, events: RemoteStreamEvents): RemoteStreamSocket
}

/** The stream over OkHttp. [client] carries the device credential, never the driver's token. */
class OkHttpRemoteStreamConnector(
    private val client: OkHttpClient,
    private val mdmBaseUrl: String,
) : RemoteStreamConnector {

    override fun connect(sessionId: String, events: RemoteStreamEvents): RemoteStreamSocket {
        val request = Request.Builder().url("${mdmBaseUrl}remote-sessions/$sessionId/stream").build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = events.onOpen()

                override fun onMessage(webSocket: WebSocket, text: String) = events.onText(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    events.onClosed(code)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = events.onClosed(null)
            },
        )
        return object : RemoteStreamSocket {
            override fun sendText(text: String): Boolean = socket.send(text)

            override fun sendFrame(jpeg: ByteArray): Boolean = socket.send(jpeg.toByteString())

            override fun pendingBytes(): Long = socket.queueSize()

            override fun close() {
                socket.close(NORMAL_CLOSURE, null)
            }
        }
    }

    companion object {
        const val NORMAL_CLOSURE = 1000

        /** docs/29 §4.1: the credential was refused. */
        const val CLOSE_BAD_CREDENTIAL = 4401
    }
}

/** The screen through the driver's MediaProjection permission: a mirror display into an image reader, as JPEG. */
class MediaProjectionFrameSource(
    private val context: Context,
    private val projection: MediaProjection,
) : ScreenFrameSource {

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var bitmap: Bitmap? = null

    override fun displaySize(): FrameSize = context.realDisplayMetrics().let { FrameSize(it.widthPixels, it.heightPixels) }

    override fun start(size: FrameSize) {
        val target = newReader(size)
        reader = target
        display = projection.createVirtualDisplay(
            DISPLAY_NAME,
            size.width,
            size.height,
            context.realDisplayMetrics().densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            target.surface,
            null,
            null,
        )
    }

    /** One projection allows one virtual display (Android 14+): it is resized, never created again. */
    override fun resize(size: FrameSize) {
        val current = display ?: return
        val old = reader
        val target = newReader(size)
        current.resize(size.width, size.height, context.realDisplayMetrics().densityDpi)
        current.surface = target.surface
        reader = target
        old?.close()
    }

    override fun capture(jpegQuality: Int): ByteArray? {
        val image = try {
            reader?.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return null
        try {
            val plane = image.planes[0]
            // Rows may be padded: the bitmap takes the padded width and the JPEG only the visible part.
            val paddedWidth = plane.rowStride / plane.pixelStride
            val buffer = bitmap?.takeIf { it.width == paddedWidth && it.height == image.height }
                ?: createBitmap(paddedWidth, image.height).also {
                    bitmap?.recycle()
                    bitmap = it
                }
            buffer.copyPixelsFromBuffer(plane.buffer)
            val visible = if (paddedWidth == image.width) buffer else Bitmap.createBitmap(buffer, 0, 0, image.width, image.height)
            val out = ByteArrayOutputStream(INITIAL_JPEG_BYTES)
            visible.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            if (visible !== buffer) visible.recycle()
            return out.toByteArray()
        } finally {
            image.close()
        }
    }

    override fun stop() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        bitmap?.recycle()
        bitmap = null
        try {
            projection.stop()
        } catch (_: RuntimeException) {
            // Already stopped by the system.
        }
    }

    // ImageReader takes ImageFormat or PixelFormat constants; screen content arrives as RGBA_8888.
    @SuppressLint("WrongConstant")
    private fun newReader(size: FrameSize): ImageReader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, MAX_IMAGES)

    private companion object {
        const val DISPLAY_NAME = "dynamicone-remote-session"
        const val MAX_IMAGES = 2
        const val INITIAL_JPEG_BYTES = 96 * 1024
    }
}

@Singleton
class AndroidScreenStreamLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ScreenStreamLauncher {

    override fun start(sessionId: String, resultCode: Int, permission: Intent) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ScreenStreamService::class.java)
                .setAction(ScreenStreamService.ACTION_START)
                .putExtra(EXTRA_REMOTE_SESSION_ID, sessionId)
                .putExtra(ScreenStreamService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenStreamService.EXTRA_PERMISSION, permission),
        )
    }

    override fun stop() {
        // The service says goodbye to the office itself.
        try {
            context.startService(Intent(context, ScreenStreamService::class.java).setAction(ScreenStreamService.ACTION_STOP))
        } catch (_: IllegalStateException) {
            // Not running, and the app is in the background: there is nothing to stop.
        }
    }
}

/**
 * The office sees the screen (docs/29 §4): a foreground service of type `mediaProjection` with a permanent
 * notification and a Stop action, for exactly one session the driver accepted. The logic of the stream is
 * [RemoteStreamSession]; this class only connects it to Android.
 */
@AndroidEntryPoint
class ScreenStreamService : Service() {

    @Inject
    lateinit var controller: RemoteSessionController

    @Inject
    lateinit var notifier: AndroidRemoteSessionNotifier

    @Inject
    lateinit var connector: RemoteStreamConnector

    @Inject
    lateinit var inputDispatcher: RemoteInputDispatcher

    @Inject
    lateinit var checkIns: DeviceCheckInScheduler

    @Inject
    lateinit var logger: AppLogger

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val main = Handler(Looper.getMainLooper())

    private var sessionId: String? = null
    private var session: RemoteStreamSession? = null
    private var projection: MediaProjection? = null
    private var loop: Job? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        /** The driver ended the capture in the system UI, or the system took it away. */
        override fun onStop() = finish(ByeReason.PROJECTION_LOST)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> if (sessionId == null) stopSelf() else finish(ByeReason.DRIVER_STOPPED)
            else -> if (sessionId == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        // The notification first: Android allows the projection only to a running mediaProjection service.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        try {
            ServiceCompat.startForeground(this, AndroidRemoteSessionNotifier.SESSION_NOTIFICATION_ID, notifier.session(), type)
        } catch (e: RuntimeException) {
            logger.warn("remote session foreground", e)
            abandon(intent.getStringExtra(EXTRA_REMOTE_SESSION_ID))
            return
        }
        val id = intent.getStringExtra(EXTRA_REMOTE_SESSION_ID)?.takeIf { it.isNotBlank() }
        val permission = IntentCompat.getParcelableExtra(intent, EXTRA_PERMISSION, Intent::class.java)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        if (id == null || permission == null || sessionId != null) {
            if (sessionId == null) abandon(id)
            return
        }
        val granted = try {
            getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(resultCode, permission)
        } catch (e: RuntimeException) {
            logger.warn("remote session projection", e)
            null
        }
        if (granted == null) {
            abandon(id)
            return
        }
        granted.registerCallback(projectionCallback, main)
        projection = granted
        sessionId = id
        inputDispatcher.onSessionStarted(id)
        val events = object : RemoteStreamEvents {
            // Through the main thread: the session below exists by the time this runs.
            override fun onOpen() {
                main.post { onSocketOpen() }
            }

            override fun onText(text: String) {
                if (session?.onText(text) == false) finish(null)
            }

            override fun onClosed(code: Int?) {
                if (code == OkHttpRemoteStreamConnector.CLOSE_BAD_CREDENTIAL) checkIns.requestCheckIn()
                // The office's side is gone: no `bye` can reach it.
                finish(null)
            }
        }
        session = RemoteStreamSession(
            socket = connector.connect(id, events),
            frames = MediaProjectionFrameSource(this, granted),
            input = inputDispatcher,
            controlEnabled = { inputDispatcher.isServiceConnected },
            nowMillis = SystemClock::elapsedRealtime,
        )
        logger.event("remote_session_started")
    }

    private fun onSocketOpen() {
        scope.launch {
            val current = session ?: return@launch
            try {
                current.onOpen()
            } catch (e: RuntimeException) {
                logger.warn("remote session capture", e)
                finish(ByeReason.ERROR)
                return@launch
            }
            loop = launch {
                while (isActive) {
                    val alive = try {
                        current.tick()
                    } catch (e: RuntimeException) {
                        logger.warn("remote session frame", e)
                        false
                    }
                    if (!alive) {
                        finish(ByeReason.ERROR)
                        break
                    }
                    delay(current.frameIntervalMillis)
                }
            }
        }
    }

    /** Ends the session once, from any thread. [reason] null: the other side ended it. */
    private fun finish(reason: ByeReason?) {
        main.post {
            val id = sessionId ?: return@post
            sessionId = null
            loop?.cancel()
            inputDispatcher.onSessionEnded(id)
            projection?.unregisterCallback(projectionCallback)
            projection = null
            val ending = session
            session = null
            try {
                ending?.close(reason)
            } catch (e: RuntimeException) {
                logger.warn("remote session close", e)
            }
            controller.onStreamEnded(id)
            logger.event("remote_session_ended", mapOf("reason" to (reason?.wireName ?: "office")))
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** The stream never started. */
    private fun abandon(id: String?) {
        id?.let(controller::onStreamEnded)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Removed by the system without a stop: the capture must not outlive the notification.
        sessionId?.let { id ->
            sessionId = null
            inputDispatcher.onSessionEnded(id)
            projection?.unregisterCallback(projectionCallback)
            runCatching { session?.close(ByeReason.ERROR) }
            controller.onStreamEnded(id)
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.dynamictransport.dynamicone.action.REMOTE_SESSION_START"
        const val ACTION_STOP = "com.dynamictransport.dynamicone.action.REMOTE_SESSION_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_PERMISSION = "permission"
    }
}
