package com.dynamictransport.dynamicone.core.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
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
import com.dynamictransport.dynamicone.core.mdm.DeviceCheckInScheduler
import com.dynamictransport.dynamicone.core.mdm.DeviceManagementStateStore
import com.dynamictransport.dynamicone.domain.model.CheckInResult
import com.dynamictransport.dynamicone.domain.repository.DeviceManagementRepository
import com.dynamictransport.dynamicone.core.sync.DriverDaySync
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidMessageNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MessageNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.notification_channel_messages))
                .build(),
        )
    }

    override fun canNotify(): Boolean = manager.areNotificationsEnabled() && hasPermission()

    @SuppressLint("MissingPermission") // canNotify() checks the permission right before posting.
    override fun show(notification: MessageNotification) {
        if (!canNotify()) return
        val title = when {
            notification.tripId == null -> context.getString(R.string.messages_general_title)
            notification.tripNo != null -> context.getString(R.string.messages_trip_label, notification.tripNo)
            else -> context.getString(R.string.trip_messages)
        }
        val sender = notification.latest.senderName
        val text = if (sender != null) {
            context.getString(R.string.messages_preview_sender, sender, notification.latest.body)
        } else {
            context.getString(R.string.messages_preview_office, notification.latest.body)
        }
        // The lock screen only tells that a message arrived, not what it says.
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_new_message))
            .build()
        val posted = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setNumber(notification.unreadCount)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setContentIntent(openConversation(notification.tripId))
            .build()
        manager.notify(conversationTag(notification.tripId), NOTIFICATION_ID, posted)
    }

    override fun cancel(tripId: String?) = manager.cancel(conversationTag(tripId), NOTIFICATION_ID)

    override fun cancelAll() = manager.cancelAll()

    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openConversation(tripId: String?): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        val intent = launch
            .putExtra(EXTRA_TYPE, TYPE_DRIVER_MESSAGE)
            .putExtra(EXTRA_TRIP_ID, tripId.orEmpty())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            conversationTag(tripId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Also the channel of notifications the system shows from a push (AndroidManifest). */
        const val CHANNEL_ID = "messages"

        /** FCM posts its notifications with id 0: the same id lets the app replace and remove them by tag. */
        const val NOTIFICATION_ID = 0
    }
}

/** The office reaches a managed phone with a `device.command` push (docs/29 §3.6). */
const val TYPE_DEVICE_COMMAND = "device.command"

/** This phone's push token for the device check-in (docs/29 §3.2); null without Firebase or without network. */
fun interface DevicePushTokenSource {
    suspend fun current(): String?
}

/**
 * Asks FCM for the token directly: a managed phone is reachable with nobody signed in, although FCM's automatic
 * registration stays tied to the account session. The token is neither stored nor logged.
 */
@Singleton
class FirebaseDevicePushTokenSource @Inject constructor(
    private val appInfo: AppInfo,
) : DevicePushTokenSource {

    override suspend fun current(): String? {
        if (!appInfo.isPushConfigured || appInfo.isDemoMode) return null
        return withTimeoutOrNull(TOKEN_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                try {
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (continuation.isActive) continuation.resume(if (task.isSuccessful) task.result else null)
                    }
                } catch (_: IllegalStateException) {
                    // Firebase is not initialised in this process.
                    continuation.resume(null)
                }
            }
        }?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TOKEN_TIMEOUT_MILLIS = 10_000L
    }
}

@Singleton
class FirebasePushSession @Inject constructor(
    private val appInfo: AppInfo,
    private val notifier: MessageNotifier,
    private val registrar: PushRegistrar,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val deviceManagement: DeviceManagementRepository,
    private val deviceState: DeviceManagementStateStore,
    private val deviceCheckIns: DeviceCheckInScheduler,
) : PushSession {

    private val enabled: Boolean get() = appInfo.isPushConfigured && !appInfo.isDemoMode

    override fun onSignedIn() {
        if (!enabled) return
        val messaging = FirebaseMessaging.getInstance()
        // Auto-init is off in the manifest: the device registers with FCM only for a signed-in driver.
        messaging.isAutoInitEnabled = true
        messaging.token.addOnSuccessListener { token ->
            scope.launch {
                deviceState.setAccountPushActive(true)
                registrar.register(token)
            }
        }
    }

    override fun onSignedOut() {
        // The backend registration was already removed while the session was still valid (AuthRepository.logout).
        notifier.cancelAll()
        if (!enabled) return
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = false
        scope.launch {
            val managed = deviceManagement.isEnrolled()
            // A managed phone nobody signed in to since its token was last replaced keeps it: the office reaches the
            // phone through it (docs/29 §3.6).
            if (managed && !deviceState.accountPushActive()) return@launch
            // A signed-out device must not receive the previous driver's notifications.
            messaging.deleteToken().addOnCompleteListener {
                scope.launch {
                    deviceState.setAccountPushActive(false)
                    // The managed phone tells the office its new token.
                    if (managed) deviceCheckIns.requestCheckIn()
                }
            }
        }
    }
}

/**
 * Receives push signals: new messages, personnel documents, tachograph rest changes and device commands. Other push
 * types are ignored.
 */
@AndroidEntryPoint
class DriverMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var handler: IncomingMessageHandler

    @Inject
    lateinit var registrar: PushRegistrar

    @Inject
    lateinit var appInfo: AppInfo

    @Inject
    lateinit var driverDaySync: DriverDaySync

    @Inject
    lateinit var hrDocumentSignals: HrDocumentSignalHandler

    @Inject
    lateinit var snapshotSignals: RoadSnapshotSignalHandler

    @Inject
    lateinit var wakeUpSignals: WakeUpCallSignalHandler

    @Inject
    lateinit var deviceManagement: DeviceManagementRepository

    @Inject
    lateinit var deviceCheckIns: DeviceCheckInScheduler

    override fun onMessageReceived(message: RemoteMessage) {
        if (appInfo.isDemoMode) return
        when (message.data[EXTRA_TYPE]) {
            // The office has a command for this phone (docs/29 §3.6): the answer is a check-in, with or without a
            // signed-in account. When it cannot finish here, the worker sends it as soon as the system allows.
            TYPE_DEVICE_COMMAND -> if (appInfo.isDeviceManagementEnabled) {
                val result = runBlocking { withTimeoutOrNull(SIGNAL_TIMEOUT_MILLIS) { deviceManagement.checkIn() } }
                if (result == null || result == CheckInResult.OFFLINE || result == CheckInResult.FAILED) deviceCheckIns.requestCheckIn()
            }
            // The planner rings this device, or silences it because another device answered (docs/25 §4).
            TYPE_DRIVER_WAKEUP -> runBlocking {
                withTimeoutOrNull(SIGNAL_TIMEOUT_MILLIS) { wakeUpSignals.onSignal(WakeUpSignal.from(message.data)) }
            }
            // The planner asks the vehicle tablet for a photo of the road (docs/24 §4).
            TYPE_TRIP_SNAPSHOT -> runBlocking {
                withTimeoutOrNull(SIGNAL_TIMEOUT_MILLIS) { snapshotSignals.onSignal(message.data[EXTRA_TRIP_ID], message.data[EXTRA_REQUEST_ID]) }
            }
            TYPE_DRIVER_MESSAGE -> {
                // Called on a Firebase worker thread: finish the read before the process may be stopped.
                val messageId = message.data[EXTRA_MESSAGE_ID]?.takeIf { it.isNotBlank() }
                runBlocking { withTimeoutOrNull(SIGNAL_TIMEOUT_MILLIS) { handler.onMessageSignal(messageId) } }
            }
            // A personnel document to sign was sent, is due, overdue or withdrawn (docs/17 §6).
            TYPE_HR_DOCUMENT -> runBlocking {
                withTimeoutOrNull(SIGNAL_TIMEOUT_MILLIS) { hrDocumentSignals.onSignal(message.data[EXTRA_DOCUMENT_ID], message.data[EXTRA_REASON]) }
            }
            // The tachograph rest changed (docs/16 §11): read it and set the wake-up alarm again.
            TYPE_DRIVER_TIMES -> if (appInfo.isDriverShiftEnabled) {
                runBlocking { withTimeoutOrNull(SIGNAL_TIMEOUT_MILLIS) { driverDaySync.refresh() } }
            }
        }
    }

    override fun onNewToken(token: String) {
        if (appInfo.isDemoMode) return
        runBlocking { registrar.register(token) }
        // A managed phone reports its new token with the next check-in.
        if (appInfo.isDeviceManagementEnabled) deviceCheckIns.requestCheckIn()
    }

    private companion object {
        const val SIGNAL_TIMEOUT_MILLIS = 15_000L
    }
}
