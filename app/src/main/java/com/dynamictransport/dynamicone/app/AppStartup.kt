package com.dynamictransport.dynamicone.app

import com.dynamictransport.dynamicone.core.alarm.RestAlarmController
import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.core.mdm.DeviceManagementStartup
import com.dynamictransport.dynamicone.core.push.PushSession
import com.dynamictransport.dynamicone.core.sync.AttendanceSyncScheduler
import com.dynamictransport.dynamicone.core.sync.CashPaymentSyncScheduler
import com.dynamictransport.dynamicone.core.sync.DriverDayWorkScheduler
import com.dynamictransport.dynamicone.core.sync.HrDocumentSyncScheduler
import com.dynamictransport.dynamicone.core.sync.ShiftSyncScheduler
import com.dynamictransport.dynamicone.core.sync.SnapshotSyncScheduler
import com.dynamictransport.dynamicone.core.sync.SyncScheduler
import com.dynamictransport.dynamicone.core.sync.WakeUpSyncScheduler
import com.dynamictransport.dynamicone.core.tar.TarWarningController
import com.dynamictransport.dynamicone.core.wakeup.WakeUpCallController
import com.dynamictransport.dynamicone.data.sync.SyncEngine
import com.dynamictransport.dynamicone.domain.model.AppModule
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

/** Splash duties (docs/02_SCREEN_SPEC.md §1): session check, then loading or syncing the account's data per module. */
@Singleton
class AppStartup @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncRepository: SyncRepository,
    private val syncEngine: Provider<SyncEngine>,
    private val syncScheduler: SyncScheduler,
    private val attendanceSyncScheduler: AttendanceSyncScheduler,
    private val networkMonitor: NetworkMonitor,
    private val appInfo: AppInfo,
    private val pushSession: PushSession,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val shiftSyncScheduler: ShiftSyncScheduler,
    private val driverDayWork: DriverDayWorkScheduler,
    private val restAlarm: Provider<RestAlarmController>,
    private val hrDocumentSyncScheduler: HrDocumentSyncScheduler,
    private val cashPaymentSyncScheduler: CashPaymentSyncScheduler,
    private val snapshotSyncScheduler: SnapshotSyncScheduler,
    private val wakeUpSyncScheduler: WakeUpSyncScheduler,
    private val wakeUpCalls: Provider<WakeUpCallController>,
    private val tarWarnings: Provider<TarWarningController>,
    private val deviceManagement: Provider<DeviceManagementStartup>,
) {
    /** Driver API daily shift with tachograph rest and wake-up alarm (docs/16). */
    private val dailyShift: Boolean get() = appInfo.isDriverShiftEnabled && !appInfo.isDemoMode

    fun start() {
        scope.launch { authRepository.restoreSession() }
        if (appInfo.isDemoMode) startDemoSync() else startApiReads()
        if (dailyShift) startDriverDay()
        // A wake-up call cut short by a restart rings again from stored data (docs/25 U9: no flag, no demo).
        if (!appInfo.isDemoMode) wakeUpCalls.get().start()
        // TAR warnings follow the trips in memory and the alarms stored on the device (docs/26 §6).
        tarWarnings.get().start()
        // A managed phone checks in with the office with or without a signed-in account (docs/29 §3.2).
        if (appInfo.isDeviceManagementEnabled) deviceManagement.get().start()
    }

    /**
     * The alarm follows the stored data from process start (it must ring after a restart without network). Waiting
     * starts and ends are sent and the day is read in the background while a driver is signed in.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startDriverDay() {
        restAlarm.get().start()
        scope.launch {
            authRepository.sessionState
                .filter { it !is SessionState.Loading }
                .map { (it as? SessionState.LoggedIn)?.modules?.contains(AppModule.DRIVER) == true }
                .distinctUntilChanged()
                .collect { driverSignedIn ->
                    if (driverSignedIn) {
                        shiftSyncScheduler.requestSend()
                        driverDayWork.startPeriodic()
                    } else {
                        driverDayWork.stop()
                    }
                }
        }
        scope.launch {
            networkMonitor.isOnline.drop(1).filter { it }.collect {
                val session = authRepository.sessionState.value as? SessionState.LoggedIn ?: return@collect
                if (AppModule.DRIVER in session.modules) shiftSyncScheduler.requestSend()
            }
        }
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

    /**
     * Real API: the driver module reads the server state when a session starts and when the connection returns; its
     * writes are never replayed. Office attendance punches and document signatures waiting on the phone are sent at the
     * same moments.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startApiReads() {
        scope.launch {
            authRepository.sessionState
                .map { it as? SessionState.LoggedIn }
                .distinctUntilChanged()
                .filterNotNull()
                .collectLatest { session ->
                    if (AppModule.OFFICE_ATTENDANCE in session.modules) attendanceSyncScheduler.requestSend()
                    if (AppModule.HR_DOCUMENTS in session.modules) hrDocumentSyncScheduler.requestSend()
                    if (session.modules.any { it in CASH_MODULES }) cashPaymentSyncScheduler.requestSend()
                    if (AppModule.DRIVER in session.modules) {
                        // Road snapshot photos taken before a restart or without network go out first (docs/24),
                        // as do answers to wake-up calls (docs/25).
                        snapshotSyncScheduler.requestSend()
                        wakeUpSyncScheduler.requestSend()
                        syncRepository.refreshFromServer()
                    }
                }
        }
        scope.launch {
            // Push follows the session: an account with a module that sends pushes (driver messages, personnel documents)
            // receives notifications, a signed-out device none.
            authRepository.sessionState
                .filter { it !is SessionState.Loading }
                .map { state -> (state as? SessionState.LoggedIn)?.modules.orEmpty().any { it in PUSH_MODULES } }
                .distinctUntilChanged()
                .collect { receivesPush -> if (receivesPush) pushSession.onSignedIn() else pushSession.onSignedOut() }
        }
        scope.launch {
            networkMonitor.isOnline.drop(1).filter { it }.collect {
                val session = authRepository.sessionState.value as? SessionState.LoggedIn ?: return@collect
                if (AppModule.OFFICE_ATTENDANCE in session.modules) attendanceSyncScheduler.requestSend()
                if (AppModule.HR_DOCUMENTS in session.modules) hrDocumentSyncScheduler.requestSend()
                if (session.modules.any { it in CASH_MODULES }) cashPaymentSyncScheduler.requestSend()
                if (AppModule.DRIVER in session.modules) {
                    snapshotSyncScheduler.requestSend()
                    wakeUpSyncScheduler.requestSend()
                    syncRepository.refreshFromServer()
                }
            }
        }
    }

    private companion object {
        /** Modules whose server sends push notifications (docs/13, docs/17 §6). */
        val PUSH_MODULES = setOf(AppModule.DRIVER, AppModule.HR_DOCUMENTS)

        /** Modules with cash hand-over signatures that may wait on the phone (docs/28). */
        val CASH_MODULES = setOf(AppModule.CASH_DISBURSEMENTS, AppModule.CASH_PAYMENTS)
    }
}
