package com.dynamictransport.dynamicone.core.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.domain.model.RemoteSessionRequest
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

const val EXTRA_REMOTE_SESSION_ID = "remoteSessionId"
const val ACTION_REMOTE_SESSION_DECLINE = "com.dynamictransport.dynamicone.action.REMOTE_SESSION_DECLINE"

/** What the driver reads when the office asks for the screen (docs/29 §4): who is asking, nothing else. */
object RemoteSessionTexts {
    fun title(context: Context, request: RemoteSessionRequest): String =
        request.requestedBy?.let { context.getString(R.string.remote_session_request_title_named, it) }
            ?: context.getString(R.string.remote_session_request_title_office)
}

@Singleton
class AndroidRemoteSessionNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : RemoteSessionNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(REQUEST_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.remote_session_channel_request))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(SESSION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.remote_session_channel_session))
                .setDescription(context.getString(R.string.remote_session_channel_session_description))
                .build(),
        )
    }

    @SuppressLint("MissingPermission") // canNotify() checks the permission right before posting.
    override fun showRequest(request: RemoteSessionRequest, remaining: Duration) {
        ensureChannels()
        val screenIntent = Intent(context, RemoteSessionActivity::class.java)
            .putExtra(EXTRA_REMOTE_SESSION_ID, request.sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        if (canNotify()) {
            val screen = PendingIntent.getActivity(context, REQUEST_SCREEN, screenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val decline = PendingIntent.getBroadcast(
                context,
                REQUEST_DECLINE,
                Intent(context, RemoteSessionReceiver::class.java)
                    .setAction(ACTION_REMOTE_SESSION_DECLINE)
                    .putExtra(EXTRA_REMOTE_SESSION_ID, request.sessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, REQUEST_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(RemoteSessionTexts.title(context, request))
                .setContentText(context.getString(R.string.remote_session_request_notification))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(screen)
                .setFullScreenIntent(screen, true)
                .setTimeoutAfter(remaining.toMillis())
                .addAction(0, context.getString(R.string.remote_session_decline), decline)
                .build()
            manager.notify(REQUEST_NOTIFICATION_ID, notification)
        }
        // While the app is on screen, and always for a device owner, the prompt may open by itself; elsewhere the
        // system keeps it behind the notification.
        try {
            context.startActivity(screenIntent)
        } catch (_: RuntimeException) {
            // The notification is the way in.
        }
    }

    override fun cancelRequest() = manager.cancel(REQUEST_NOTIFICATION_ID)

    /** docs/29 §1: visible for the whole session, with the driver's way to stop it. */
    fun session(): Notification {
        ensureChannels()
        val stop = PendingIntent.getService(
            context,
            REQUEST_STOP,
            Intent(context, ScreenStreamService::class.java).setAction(ScreenStreamService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, SESSION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.remote_session_active_title))
            .setContentText(context.getString(R.string.remote_session_active_body))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.remote_session_stop), stop)
            .build()
    }

    private fun canNotify(): Boolean = manager.areNotificationsEnabled() &&
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    companion object {
        const val REQUEST_CHANNEL_ID = "remote_session_request"
        const val SESSION_CHANNEL_ID = "remote_session"
        const val REQUEST_NOTIFICATION_ID = 4_951
        const val SESSION_NOTIFICATION_ID = 4_952
        private const val REQUEST_SCREEN = 4_953
        private const val REQUEST_DECLINE = 4_954
        private const val REQUEST_STOP = 4_955
    }
}

/** "Decline" on the request notification itself (the screen is not always open). */
@AndroidEntryPoint
class RemoteSessionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var controller: RemoteSessionController

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMOTE_SESSION_DECLINE) return
        val sessionId = intent.getStringExtra(EXTRA_REMOTE_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return
        val result = goAsync()
        scope.launch {
            try {
                controller.decline(sessionId)
            } finally {
                result.finish()
            }
        }
    }
}
