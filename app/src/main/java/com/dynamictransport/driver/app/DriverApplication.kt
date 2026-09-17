package com.dynamictransport.driver.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.dynamictransport.driver.core.push.MessageNotifier
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DriverApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var appStartup: AppStartup

    @Inject
    lateinit var messageNotifier: MessageNotifier

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // The channel must exist before the system shows a notification from a push.
        messageNotifier.ensureChannel()
        appStartup.start()
    }
}
