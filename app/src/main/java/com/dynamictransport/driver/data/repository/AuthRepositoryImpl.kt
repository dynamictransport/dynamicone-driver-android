package com.dynamictransport.driver.data.repository

import androidx.room.withTransaction
import com.dynamictransport.driver.core.common.ApplicationScope
import com.dynamictransport.driver.core.database.DriverDatabase
import com.dynamictransport.driver.core.database.DriverProfileDao
import com.dynamictransport.driver.core.network.RemoteException
import com.dynamictransport.driver.core.security.SessionEvent
import com.dynamictransport.driver.core.security.SessionPreferences
import com.dynamictransport.driver.core.security.TokenManager
import com.dynamictransport.driver.core.security.TokenPair
import com.dynamictransport.driver.domain.model.PasswordChangeResult
import com.dynamictransport.driver.core.sync.SyncScheduler
import com.dynamictransport.driver.data.local.toDomain
import com.dynamictransport.driver.data.local.toEntity
import com.dynamictransport.driver.data.remote.DriverRemoteDataSource
import com.dynamictransport.driver.domain.model.Driver
import com.dynamictransport.driver.domain.model.LoginResult
import com.dynamictransport.driver.domain.model.SessionState
import com.dynamictransport.driver.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val remote: DriverRemoteDataSource,
    private val tokenManager: TokenManager,
    private val sessionPreferences: SessionPreferences,
    private val database: DriverDatabase,
    private val driverProfileDao: DriverProfileDao,
    private val syncScheduler: SyncScheduler,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : AuthRepository {

    private val state = MutableStateFlow<SessionState>(SessionState.Loading)
    override val sessionState: StateFlow<SessionState> = state.asStateFlow()

    override val sessionExpired: StateFlow<Boolean> =
        sessionPreferences.sessionExpired.stateIn(appScope, SharingStarted.Eagerly, false)

    init {
        appScope.launch {
            tokenManager.sessionEvents.collect { if (it == SessionEvent.EXPIRED) endSession(expired = true) }
        }
    }

    override suspend fun restoreSession() {
        val driverId = sessionPreferences.driverId()
        state.value = if (driverId != null && tokenManager.hasSession()) {
            SessionState.LoggedIn(driverId)
        } else {
            SessionState.LoggedOut
        }
    }

    override fun observeDriver(): Flow<Driver?> = sessionState.flatMapLatest { session ->
        if (session is SessionState.LoggedIn) {
            driverProfileDao.observe(session.driverId).map { it?.toDomain() }
        } else {
            flowOf(null)
        }
    }

    override suspend fun login(username: String, password: String, twoFactorCode: String?): LoginResult {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) return LoginResult.INVALID_CREDENTIALS
        return try {
            val demoTokens = remote.login(user, password)
            tokenManager.store(TokenPair(demoTokens.accessToken, demoTokens.refreshToken, demoTokens.expiresAt, null))
            val profile = remote.getProfile()
            val previousDriver = sessionPreferences.lastDriverId()
            if (previousDriver != null && previousDriver != profile.id) clearDriverScopedData()
            driverProfileDao.upsert(profile.toEntity())
            sessionPreferences.setLoggedInDriver(profile.id)
            sessionPreferences.setLastUsername(user)
            state.value = SessionState.LoggedIn(profile.id)
            syncScheduler.requestSync()
            LoginResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            tokenManager.clear()
            when (e) {
                is RemoteException.Unauthorized -> LoginResult.INVALID_CREDENTIALS
                is RemoteException.Offline -> LoginResult.OFFLINE
                else -> LoginResult.FAILED
            }
        }
    }

    /** Pending offline actions are kept; they are sent when this driver signs in again. */
    override suspend fun logout() = endSession(expired = false)

    override suspend fun lastUsername(): String? = sessionPreferences.lastUsername()

    /** The demo backend has no password workflow. */
    override suspend fun changePassword(currentPassword: String, newPassword: String): PasswordChangeResult =
        PasswordChangeResult.NOT_SUPPORTED

    private suspend fun endSession(expired: Boolean) {
        tokenManager.clear()
        sessionPreferences.clearLoggedInDriver(expired)
        state.value = SessionState.LoggedOut
    }

    /** A different driver signed in on this device: drop the previous driver's cached server data. */
    private suspend fun clearDriverScopedData() = database.withTransaction {
        database.tripDao().deleteAll()
        database.shiftDao().deleteAll()
        database.driverProfileDao().deleteAll()
        database.documentDao().deleteSynced()
    }
}
