package com.dynamictransport.dynamicone.core.push

import android.Manifest
import android.annotation.SuppressLint
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
import com.dynamictransport.dynamicone.core.network.SnapshotWireNames
import com.dynamictransport.dynamicone.core.snapshot.SnapshotCaptureCoordinator
import com.dynamictransport.dynamicone.domain.model.SnapshotDecision
import com.dynamictransport.dynamicone.domain.model.SnapshotFailure
import com.dynamictransport.dynamicone.domain.model.SnapshotRequest
import com.dynamictransport.dynamicone.domain.repository.RoadSnapshotRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** The planner asks the vehicle tablet for a photo of the road (docs/24 §4). */
const val TYPE_TRIP_SNAPSHOT = SnapshotWireNames.PUSH_TYPE
const val EXTRA_REQUEST_ID = "requestId"
const val ACTION_SNAPSHOT_DISMISS = "com.dynamictransport.dynamicone.action.SNAPSHOT_DISMISS"

fun snapshotTag(requestId: String): String = "snapshot-$requestId"

interface RoadSnapshotNotifier {
    fun ensureChannel()

    /** Full-screen notification: the app is not in front, the driver has to open it with one tap. */
    fun show(request: SnapshotRequest)

    fun cancel(requestId: String)
}

@Singleton
class AndroidRoadSnapshotNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clock: Clock,
) : RoadSnapshotNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.notification_channel_snapshots))
                .build(),
        )
    }

    @SuppressLint("MissingPermission") // canNotify() checks the permission right before posting.
    override fun show(request: SnapshotRequest) {
        if (!canNotify()) return
        val remaining = Duration.between(clock.instant(), request.expiresAt)
        if (remaining.isNegative || remaining.isZero) return
        val body = request.requestedByDisplayName?.let { context.getString(R.string.snapshot_notification_body, it) }
            ?: context.getString(R.string.snapshot_notification_body_office)
        val open = openApp(request.requestId)
        val posted = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.snapshot_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(true)
            // The request is worthless after its deadline: the notification leaves by itself.
            .setTimeoutAfter(remaining.toMillis())
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, context.getString(R.string.snapshot_notification_take), open)
            .addAction(0, context.getString(R.string.snapshot_notification_dismiss), dismiss(request.requestId))
            .build()
        manager.notify(snapshotTag(request.requestId), AndroidMessageNotifier.NOTIFICATION_ID, posted)
    }

    override fun cancel(requestId: String) = manager.cancel(snapshotTag(requestId), AndroidMessageNotifier.NOTIFICATION_ID)

    private fun canNotify(): Boolean = manager.areNotificationsEnabled() &&
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    private fun openApp(requestId: String): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        val intent = launch
            .putExtra(EXTRA_TYPE, TYPE_TRIP_SNAPSHOT)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            snapshotTag(requestId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismiss(requestId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        snapshotTag(requestId).hashCode() + 1,
        Intent(context, SnapshotDismissReceiver::class.java).setAction(ACTION_SNAPSHOT_DISMISS).putExtra(EXTRA_REQUEST_ID, requestId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ID = "road_snapshots"
    }
}

/** "Not now" on the notification: the planner learns that the driver declined (docs/24 §7). */
@AndroidEntryPoint
class SnapshotDismissReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: RoadSnapshotRepository

    @Inject
    lateinit var notifier: RoadSnapshotNotifier

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SNAPSHOT_DISMISS) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf { it.isNotBlank() } ?: return
        notifier.cancel(requestId)
        val result = goAsync()
        scope.launch {
            try {
                repository.reportFailure(requestId, SnapshotFailure.DRIVER_DISMISSED)
            } finally {
                result.finish()
            }
        }
    }
}

/**
 * A push only says that a request exists (docs/13 §2): it is read from the API. With the app on screen the photo is
 * taken at once; otherwise the driver gets the full-screen notification.
 */
@Singleton
class RoadSnapshotSignalHandler @Inject constructor(
    private val repository: RoadSnapshotRepository,
    private val coordinator: SnapshotCaptureCoordinator,
    private val notifier: RoadSnapshotNotifier,
) {
    suspend fun onSignal(tripId: String?, requestId: String?) {
        if (tripId.isNullOrBlank() || requestId.isNullOrBlank()) return
        when (val decision = repository.onRequestSignal(tripId, requestId)) {
            is SnapshotDecision.Capture -> if (coordinator.isAppVisible) coordinator.capture(decision.request) else notifier.show(decision.request)
            SnapshotDecision.Ignore -> notifier.cancel(requestId)
        }
    }
}
