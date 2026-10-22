package com.dynamictransport.dynamicone.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.dynamictransport.dynamicone.core.push.HrDocumentNotifier
import com.dynamictransport.dynamicone.core.push.MessageNotifier
import com.dynamictransport.dynamicone.core.push.RoadSnapshotNotifier
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DynamicOneApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var appStartup: AppStartup

    @Inject
    lateinit var messageNotifier: MessageNotifier

    @Inject
    lateinit var hrDocumentNotifier: HrDocumentNotifier

    @Inject
    lateinit var roadSnapshotNotifier: RoadSnapshotNotifier

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // The channels must exist before the system shows a notification from a push.
        messageNotifier.ensureChannel()
        hrDocumentNotifier.ensureChannel()
        roadSnapshotNotifier.ensureChannel()
        appStartup.start()
    }
}
