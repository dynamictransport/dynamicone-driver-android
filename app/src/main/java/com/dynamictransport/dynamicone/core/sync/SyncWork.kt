package com.dynamictransport.dynamicone.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
