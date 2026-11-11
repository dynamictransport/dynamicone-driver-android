package com.dynamictransport.dynamicone.core.mdm

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.core.remote.RemoteSessionNotifier
import com.dynamictransport.dynamicone.domain.model.CheckInResult
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.DeviceManagementRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** When the phone talks to the office (docs/29 §3.2). Nothing is scheduled while device management is off. */
interface DeviceCheckInScheduler {
    /** Every [intervalSeconds]; [replace] when the server changed the interval. */
    fun startPeriodic(intervalSeconds: Long, replace: Boolean = false)

    /** As soon as the system allows: app start, enrolment, a `device.command` push, a finished command. */
    fun requestCheckIn()

    fun stop()
}

@Singleton
class WorkManagerDeviceCheckInScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appInfo: AppInfo,
) : DeviceCheckInScheduler {

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun startPeriodic(intervalSeconds: Long, replace: Boolean) {
        if (!appInfo.isDeviceManagementEnabled) return
        val seconds = intervalSeconds.coerceAtLeast(DeviceManagementStateStore.MIN_CHECK_IN_SECONDS)
        val request = PeriodicWorkRequestBuilder<DeviceCheckInWorker>(seconds, TimeUnit.SECONDS)
            .setConstraints(connected)
            // The check-in "now" is sent separately: the periodic one starts a full interval later.
            .setInitialDelay(seconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun requestCheckIn() {
        if (!appInfo.isDeviceManagementEnabled) return
        val request = OneTimeWorkRequestBuilder<DeviceCheckInWorker>()
            .setConstraints(connected)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // A check-in that is running may already have read its commands: the new one runs after it, never instead.
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun stop() {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK_NAME)
            cancelUniqueWork(ONCE_WORK_NAME)
        }
    }

    companion object {
        const val PERIODIC_WORK_NAME = "device-check-in"
        const val ONCE_WORK_NAME = "device-check-in-now"
    }
}

@HiltWorker
class DeviceCheckInWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: DeviceManagementRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (repository.checkIn()) {
        CheckInResult.OFFLINE -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        else -> Result.success()
    }

    /** Before Android 12 an expedited check-in runs as a short foreground service and needs a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        AndroidDeviceAlerts.SYNC_NOTIFICATION_ID,
        NotificationCompat.Builder(applicationContext, AndroidDeviceAlerts.SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.mdm_sync_notification))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build(),
    )

    private companion object {
        /** The periodic check-in comes anyway: a phone without network does not retry for hours. */
        const val MAX_ATTEMPTS = 5
    }
}

/** Process start (docs/29 §3.2): the channels exist, the periodic check-in follows the enrolment, one check-in now. */
@Singleton
class DeviceManagementStartup @Inject constructor(
    private val repository: DeviceManagementRepository,
    private val scheduler: DeviceCheckInScheduler,
    private val state: DeviceManagementStateStore,
    private val alerts: DeviceAlerts,
    private val remoteSessionNotifier: RemoteSessionNotifier,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        alerts.ensureChannels()
        remoteSessionNotifier.ensureChannels()
        scope.launch {
            repository.enrolled.collect { enrolled ->
                if (enrolled) scheduler.startPeriodic(state.checkInSeconds()) else scheduler.stop()
            }
        }
        scope.launch {
            if (!repository.isEnrolled()) return@launch
            // A process the periodic check-in itself started needs no second check-in.
            delay(APP_START_DELAY_MILLIS)
            val last = state.lastCheckInAt.first()
            if (last == null || Duration.between(last, clock.instant()).abs() > RECENT_CHECK_IN) scheduler.requestCheckIn()
        }
    }

    private companion object {
        const val APP_START_DELAY_MILLIS = 10_000L
        val RECENT_CHECK_IN: Duration = Duration.ofMinutes(1)
    }
}
