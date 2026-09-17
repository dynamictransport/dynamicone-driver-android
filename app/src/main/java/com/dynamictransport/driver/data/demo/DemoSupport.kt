package com.dynamictransport.driver.data.demo

import androidx.room.withTransaction
import com.dynamictransport.driver.core.common.ConnectivityObserver
import com.dynamictransport.driver.core.database.DriverDatabase
import com.dynamictransport.driver.core.sync.SyncScheduler
import com.dynamictransport.driver.data.remote.fake.FakeDriverBackend
import com.dynamictransport.driver.domain.repository.DemoBackendControls
import com.dynamictransport.driver.domain.repository.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeDemoBackendControls @Inject constructor(
    private val backend: FakeDriverBackend,
    private val database: DriverDatabase,
    private val syncScheduler: SyncScheduler,
) : DemoBackendControls {

    override val isAvailable: Boolean = true

    override val simulateOffline: StateFlow<Boolean> = backend.simulateOffline.asStateFlow()

    override fun setSimulateOffline(enabled: Boolean) {
        backend.simulateOffline.value = enabled
        if (!enabled) syncScheduler.requestSync()
    }

    override suspend fun resetDemoData() {
        backend.reset()
        database.withTransaction {
            database.tripDao().deleteAll()
            database.shiftDao().deleteAll()
            database.pendingActionDao().deleteAll()
            database.documentDao().deleteAll()
        }
        syncScheduler.requestSync()
    }
}

object UnavailableDemoBackendControls : DemoBackendControls {
    override val isAvailable: Boolean = false
    override val simulateOffline: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    override fun setSimulateOffline(enabled: Boolean) = Unit

    override suspend fun resetDemoData() = Unit
}

/** Device connectivity, combined with the demo "simulate offline" switch. */
class AppNetworkMonitor @Inject constructor(
    connectivityObserver: ConnectivityObserver,
    demoControls: DemoBackendControls,
) : NetworkMonitor {
    override val isOnline: Flow<Boolean> =
        combine(connectivityObserver.isOnline, demoControls.simulateOffline) { online, simulatedOffline ->
            online && !simulatedOffline
        }.distinctUntilChanged()
}
