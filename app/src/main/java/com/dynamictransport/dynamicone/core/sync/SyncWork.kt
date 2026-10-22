package com.dynamictransport.dynamicone.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.repository.AttendanceRepository
import com.dynamictransport.dynamicone.domain.repository.DailyShiftRepository
import com.dynamictransport.dynamicone.domain.repository.HrDocumentRepository
import com.dynamictransport.dynamicone.domain.repository.RoadSnapshotRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncRunResult {
    COMPLETED,
    RETRY,
}

/** Pushes the pending action queue and pulls fresh server state. */
interface SyncRunner {
    suspend fun sync(): SyncRunResult
}

interface SyncScheduler {
    fun requestSync()
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SyncScheduler {

    override fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // REPLACE restarts a waiting/backing-off run immediately. Interrupting a running sync is safe:
        // the engine is serialised and the server is idempotent per clientActionId.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "driver-sync"
        private const val BACKOFF_SECONDS = 15L
    }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRunner: SyncRunner,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (syncRunner.sync()) {
        SyncRunResult.COMPLETED -> Result.success()
        SyncRunResult.RETRY -> Result.retry()
    }
}

/** Sends office attendance punches stored on the phone once the server can be reached (docs/15, ASSUMPTIONS M7). */
interface AttendanceSyncScheduler {
    /** [delaySeconds]: the server's `Retry-After` when it asked to be left alone for a while (429). */
    fun requestSend(delaySeconds: Long = 0)
}

@Singleton
class WorkManagerAttendanceSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AttendanceSyncScheduler {

    override fun requestSend(delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, ATTENDANCE_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delaySeconds.coerceIn(0, MAX_RETRY_AFTER_SECONDS), TimeUnit.SECONDS)
            .build()
        // Every punch carries its own command id and the server is idempotent: restarting a running send is safe.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "attendance-sync"
        private const val ATTENDANCE_BACKOFF_SECONDS = 30L

        /** A `Retry-After` beyond this is treated as the limit: the punch must not sit on the phone for hours. */
        private const val MAX_RETRY_AFTER_SECONDS = 15 * 60L
    }
}

@HiltWorker
class AttendanceSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val attendanceRepository: AttendanceRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = if (attendanceRepository.sendPending()) Result.success() else Result.retry()
}

/** Sends daily shift starts, ends and defect photos stored on the phone (docs/16 §2, ASSUMPTIONS S7). */
interface ShiftSyncScheduler {
    /** [delaySeconds]: the server's `Retry-After` when it asked to be left alone for a while (429). */
    fun requestSend(delaySeconds: Long = 0)
}

@Singleton
class WorkManagerShiftSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ShiftSyncScheduler {

    override fun requestSend(delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<ShiftSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SHIFT_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delaySeconds.coerceIn(0, MAX_RETRY_AFTER_SECONDS), TimeUnit.SECONDS)
            .build()
        // Every command carries its own command id and the server is idempotent: restarting a running send is safe.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "shift-sync"
        private const val SHIFT_BACKOFF_SECONDS = 30L
        private const val MAX_RETRY_AFTER_SECONDS = 15 * 60L
    }
}

@HiltWorker
class ShiftSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dailyShiftRepository: DailyShiftRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = if (dailyShiftRepository.sendPending()) Result.success() else Result.retry()
}

/** Reads the driver's shift and tachograph summary and brings the wake-up alarm up to date (docs/16 §3, §11). */
fun interface DriverDaySync {
    suspend fun refresh(): RefreshResult
}

/** Without the daily shift feature there is nothing to read. */
object NoOpDriverDaySync : DriverDaySync {
    override suspend fun refresh(): RefreshResult = RefreshResult.SUCCESS
}

/**
 * Background reads of the driver's day: every [PERIOD_MINUTES] while a driver is signed in, and once more a while after
 * the shift ended, when the tachograph has registered the rest (Webfleet synchronises every five minutes).
 */
interface DriverDayWorkScheduler {
    fun startPeriodic()

    fun stop()

    fun refreshAfter(delayMinutes: Long)
}

@Singleton
class WorkManagerDriverDayWorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DriverDayWorkScheduler {

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun startPeriodic() {
        val request = PeriodicWorkRequestBuilder<DriverDayRefreshWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(connected)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun stop() {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK_NAME)
            cancelUniqueWork(ONCE_WORK_NAME)
        }
    }

    override fun refreshAfter(delayMinutes: Long) {
        val request = OneTimeWorkRequestBuilder<DriverDayRefreshWorker>()
            .setConstraints(connected)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val PERIODIC_WORK_NAME = "driver-day-refresh"
        const val ONCE_WORK_NAME = "driver-day-refresh-once"
        const val PERIOD_MINUTES = 30L
    }
}

@HiltWorker
class DriverDayRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val driverDaySync: DriverDaySync,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (driverDaySync.refresh()) {
        RefreshResult.OFFLINE, RefreshResult.SERVICE_UNAVAILABLE -> Result.retry()
        else -> Result.success()
    }
}

/** Sends personnel document signatures stored on the phone once the server can be reached (docs/17 §5.4). */
interface HrDocumentSyncScheduler {
    /** [delaySeconds]: the server's `Retry-After` when it asked to be left alone for a while (429). */
    fun requestSend(delaySeconds: Long = 0)
}

@Singleton
class WorkManagerHrDocumentSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : HrDocumentSyncScheduler {

    override fun requestSend(delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<HrDocumentSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, HR_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delaySeconds.coerceIn(0, MAX_RETRY_AFTER_SECONDS), TimeUnit.SECONDS)
            .build()
        // Every signature carries its own command id and the server is idempotent: restarting a running send is safe.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "hr-document-sync"
        private const val HR_BACKOFF_SECONDS = 30L
        private const val MAX_RETRY_AFTER_SECONDS = 15 * 60L
    }
}

@HiltWorker
class HrDocumentSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val hrDocumentRepository: HrDocumentRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = if (hrDocumentRepository.sendPending()) Result.success() else Result.retry()
}

/** Sends road snapshot photos and failure reports waiting on the tablet (docs/24 §8, S5). */
interface SnapshotSyncScheduler {
    fun requestSend(delaySeconds: Long = 0)
}

@Singleton
class WorkManagerSnapshotSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SnapshotSyncScheduler {

    override fun requestSend(delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<SnapshotSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SNAPSHOT_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delaySeconds.coerceIn(0, MAX_SNAPSHOT_DELAY_SECONDS), TimeUnit.SECONDS)
            .build()
        // Every send is idempotent by request id: restarting a running send is safe.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "road-snapshot-sync"
        private const val SNAPSHOT_BACKOFF_SECONDS = 20L
        private const val MAX_SNAPSHOT_DELAY_SECONDS = 10 * 60L
    }
}

@HiltWorker
class SnapshotSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val roadSnapshotRepository: RoadSnapshotRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = if (roadSnapshotRepository.sendPending()) Result.success() else Result.retry()
}
