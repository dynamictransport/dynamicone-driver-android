package com.dynamictransport.dynamicone.core.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.app.WakeUpCallActivity
import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.network.WakeUpWireNames
import com.dynamictransport.dynamicone.core.wakeup.WakeUpCallController
import com.dynamictransport.dynamicone.domain.model.WakeUpCall
import com.dynamictransport.dynamicone.domain.model.WakeUpDecision
import com.dynamictransport.dynamicone.domain.model.WakeUpReason
import com.dynamictransport.dynamicone.domain.repository.WakeUpCallRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The planner rings the driver's devices, or silences them (docs/25 §4). */
const val TYPE_DRIVER_WAKEUP = WakeUpWireNames.PUSH_TYPE
const val EXTRA_EVENT = "event"
const val EXTRA_CALL_ID = "callId"
const val EXTRA_EXPIRES_AT = "expiresAt"
const val ACTION_WAKE_UP_ACKNOWLEDGE = "com.dynamictransport.dynamicone.action.WAKE_UP_ACKNOWLEDGE"

/** The data of a `driver.wakeup` push as it arrived; nothing is trusted before the repository looked at it. */
data class WakeUpSignal(
    val event: String?,
    val callId: String?,
    val reason: String?,
    val expiresAt: Instant?,
) {
    val isRing: Boolean get() = event.isNullOrBlank() || event.equals(WakeUpWireNames.EVENT_RING, ignoreCase = true)
    val isStop: Boolean get() = event.equals(WakeUpWireNames.EVENT_STOP, ignoreCase = true)

    companion object {
        fun from(data: Map<String, String>): WakeUpSignal = WakeUpSignal(
            event = data[EXTRA_EVENT]?.trim(),
            callId = data[EXTRA_CALL_ID]?.trim()?.takeIf { it.isNotBlank() },
            reason = data[EXTRA_REASON]?.trim(),
            expiresAt = data[EXTRA_EXPIRES_AT]?.trim()?.toInstantOrNull(),
        )
    }
}

interface WakeUpCallNotifier {
    fun ensureChannel()

    /** Alarm sound and full screen over the lock screen until the driver answers or the call's deadline passes. */
    fun ring(call: WakeUpCall)

    fun cancel()
}

/** What the driver reads on the ringing screen and the notification (docs/25 U3): who calls and why, no free text. */
object WakeUpCallTexts {
    fun title(context: Context, call: WakeUpCall): String =
        call.requestedByDisplayName?.let { context.getString(R.string.wake_up_title_named, it) }
            ?: context.getString(R.string.wake_up_title_office)

    fun reason(context: Context, reason: WakeUpReason): String = context.getString(reasonLabel(reason))

    fun reasonLabel(reason: WakeUpReason): Int = when (reason) {
        WakeUpReason.CALL_OFFICE -> R.string.wake_up_reason_call_office
        WakeUpReason.DEPARTURE_TIME -> R.string.wake_up_reason_departure_time
        WakeUpReason.LOADING_TIME_CHANGED -> R.string.wake_up_reason_loading_time_changed
        WakeUpReason.TRIP_CHANGED -> R.string.wake_up_reason_trip_changed
        WakeUpReason.OTHER -> R.string.wake_up_reason_other
    }
}

@Singleton
class AndroidWakeUpCallNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clock: Clock,
) : WakeUpCallNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun ensureChannel() {
        val alarmSound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.notification_channel_wake_up))
                .setDescription(context.getString(R.string.wake_up_channel_description))
                .setSound(
                    alarmSound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setVibrationEnabled(true)
                .setVibrationPattern(longArrayOf(0, 800, 400, 800, 400, 800))
                .build(),
        )
    }

    @SuppressLint("MissingPermission") // canNotify() checks the permission right before posting.
    override fun ring(call: WakeUpCall) {
        if (!canNotify()) return
        val remaining = Duration.between(clock.instant(), call.expiresAt)
        if (remaining.isNegative || remaining.isZero) return
        val title = WakeUpCallTexts.title(context, call)
        val body = listOfNotNull(
            WakeUpCallTexts.reason(context, call.reason),
            call.tripNo?.let { context.getString(R.string.wake_up_trip, it) },
        ).joinToString(" ")
        val screen = screen(call.callId)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // Nothing beyond the caller's name in it: readable on the lock screen (U3).
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            // Like an alarm clock, the sound ends by itself at the call's deadline (U2).
            .setTimeoutAfter(remaining.toMillis())
            .addAction(0, context.getString(R.string.wake_up_acknowledge), acknowledge(call.callId))
            .build()
        // The alarm sound repeats until the driver answers or the deadline ends it.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun canNotify(): Boolean = manager.areNotificationsEnabled() &&
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    private fun screen(callId: String): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_SCREEN,
        Intent(context, WakeUpCallActivity::class.java)
            .putExtra(EXTRA_CALL_ID, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun acknowledge(callId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_ACKNOWLEDGE,
        Intent(context, WakeUpCallReceiver::class.java).setAction(ACTION_WAKE_UP_ACKNOWLEDGE).putExtra(EXTRA_CALL_ID, callId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ID = "wake_up_call"
        const val NOTIFICATION_ID = 4_801
        private const val REQUEST_SCREEN = 4_802
        private const val REQUEST_ACKNOWLEDGE = 4_803
    }
}

/** "I'm awake" on the notification itself (the screen is not always open). */
@AndroidEntryPoint
class WakeUpCallReceiver : BroadcastReceiver() {

    @Inject
    lateinit var controller: WakeUpCallController

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKE_UP_ACKNOWLEDGE) return
        val callId = intent.getStringExtra(EXTRA_CALL_ID)?.takeIf { it.isNotBlank() } ?: return
        val result = goAsync()
        scope.launch {
            try {
                controller.acknowledge(callId)
            } finally {
                result.finish()
            }
        }
    }
}

/**
 * A push only says that a call exists or ended (docs/13 §2): a `ring` is checked with the server and then rings,
 * a `stop` silences this device.
 */
@Singleton
class WakeUpCallSignalHandler @Inject constructor(
    private val repository: WakeUpCallRepository,
    private val controller: WakeUpCallController,
) {
    suspend fun onSignal(signal: WakeUpSignal) {
        val callId = signal.callId ?: return
        when {
            signal.isStop -> controller.stop(callId)
            signal.isRing -> when (val decision = repository.onRingSignal(callId, signal.reason, signal.expiresAt)) {
                is WakeUpDecision.Ring -> controller.ring(decision.call)
                WakeUpDecision.Ignore -> Unit
            }
        }
    }
}
