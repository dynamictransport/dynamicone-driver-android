package com.dynamictransport.dynamicone.app

import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.core.push.PushSession
import com.dynamictransport.dynamicone.core.sync.SyncScheduler
import com.dynamictransport.dynamicone.data.sync.SyncEngine
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.AuthRepository
import com.dynamictransport.dynamicone.domain.repository.NetworkMonitor
import com.dynamictransport.dynamicone.domain.repository.SyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Splash duties (docs/02_SCREEN_SPEC.md §1): session check, then loading or syncing the driver's data. */
@Singleton
class AppStartup @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncRepository: SyncRepository,
    private val syncEngine: Provider<SyncEngine>,
    private val syncScheduler: SyncScheduler,
    private val networkMonitor: NetworkMonitor,
    private val appInfo: AppInfo,
    private val pushSession: PushSession,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch { authRepository.restoreSession() }
        if (appInfo.isDemoMode) startDemoSync() else startApiReads()
    }

    /** Demo: queue recovery and background sync against the in-app backend. */
    private fun startDemoSync() {
        scope.launch {
            syncEngine.get().recoverAfterRestart()
            syncScheduler.requestSync()
        }
        scope.launch {
            // Connectivity regained: resend immediately instead of waiting for the backoff.
            networkMonitor.isOnline.drop(1).filter { it }.collect { syncScheduler.requestSync() }
        }
    }

    /** Real API: read the server state when a session starts and when the connection returns. Writes are never replayed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startApiReads() {
        scope.launch {
            authRepository.sessionState
                .map { (it as? SessionState.LoggedIn)?.driverId }
                .distinctUntilChanged()
                .filterNotNull()
                .collectLatest { syncRepository.refreshFromServer() }
        }
        scope.launch {
            // Push follows the session: a signed-in driver receives notifications, a signed-out device none.
            authRepository.sessionState
                .filter { it !is SessionState.Loading }
                .map { it is SessionState.LoggedIn }
                .distinctUntilChanged()
                .collect { signedIn -> if (signedIn) pushSession.onSignedIn() else pushSession.onSignedOut() }
        }
        scope.launch {
            networkMonitor.isOnline.drop(1).filter { it }.collect {
                if (authRepository.sessionState.value is SessionState.LoggedIn) syncRepository.refreshFromServer()
            }
        }
    }
}
