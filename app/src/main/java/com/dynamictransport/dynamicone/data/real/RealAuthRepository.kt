package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.core.network.DriverErrorCodes
import com.dynamictransport.dynamicone.core.network.DriverLoginRequestDto
import com.dynamictransport.dynamicone.core.network.DriverPasswordRequestDto
import com.dynamictransport.dynamicone.core.network.IdentityErrorCodes
import com.dynamictransport.dynamicone.core.network.MobileErrorCodes
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.network.requireSuccess
import com.dynamictransport.dynamicone.core.network.toTokenPair
import com.dynamictransport.dynamicone.core.push.PushRegistrar
import com.dynamictransport.dynamicone.core.security.SessionEvent
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.core.security.TokenManager
import com.dynamictransport.dynamicone.domain.model.LoginResult
import com.dynamictransport.dynamicone.domain.model.PasswordChangeResult
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.domain.model.UserProfile
import com.dynamictransport.dynamicone.domain.repository.AuthRepository
import com.dynamictransport.dynamicone.domain.rules.DriverInputRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API session: `userName` + password (+ optional authenticator code) against auth/login, both tokens in
 * Keystore-encrypted storage, bearer authentication only. The account and its modules always come from `me`;
 * the client never sends a user or driver id.
 */
@Singleton
class RealAuthRepository @Inject constructor(
    private val account: AccountRemote,
    private val tokenManager: TokenManager,
    private val sessionPreferences: SessionPreferences,
    private val tracker: ApiActivityTracker,
    private val sessionData: RealSessionData,
    private val pushRegistrar: PushRegistrar,
    private val clock: Clock,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : AuthRepository {

    private val state = MutableStateFlow<SessionState>(SessionState.Loading)
    override val sessionState: StateFlow<SessionState> = state.asStateFlow()

    override val sessionExpired: StateFlow<Boolean> =
        sessionPreferences.sessionExpired.stateIn(appScope, SharingStarted.Eagerly, false)

    private val profile = MutableStateFlow<UserProfile?>(null)

    init {
        appScope.launch {
            tokenManager.sessionEvents.collect { event ->
                when (event) {
                    SessionEvent.EXPIRED -> endSession(expired = true)
                    SessionEvent.PASSWORD_CHANGE_REQUIRED ->
                        if (tokenManager.hasSession()) state.value = SessionState.PasswordChangeRequired
                }
            }
        }
    }

    override suspend fun restoreSession() {
        val tokens = tokenManager.load()
        val userId = sessionPreferences.userId()
        val modules = sessionPreferences.modules()
        state.value = when {
            tokens == null -> SessionState.LoggedOut
            tokens.mustChangePassword -> SessionState.PasswordChangeRequired
            userId == null || modules.isEmpty() -> {
                // Signed in, but the profile was never loaded: start over with a fresh login.
                tokenManager.clear()
                SessionState.LoggedOut
            }
            else -> {
                // The stored session is usable offline; the first API call refreshes the access token.
                profile.value = UserProfile(
                    id = userId,
                    displayName = sessionPreferences.displayName().orEmpty(),
                    personnelNo = null,
                    preferredLanguage = null,
                    modules = modules,
                    driverId = sessionPreferences.driverId(),
                )
                appScope.launch { refreshProfile(userId) }
                SessionState.LoggedIn(userId, modules)
            }
        }
    }

    override fun observeProfile(): Flow<UserProfile?> = profile

    override suspend fun login(username: String, password: String, twoFactorCode: String?): LoginResult {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) return LoginResult.INVALID_CREDENTIALS
        val request = DriverLoginRequestDto(user, password, twoFactorCode?.trim()?.takeIf { it.isNotEmpty() })
        val tokens = try {
            tracker.call { account.login(request) }.toTokenPair(clock) ?: return LoginResult.FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            return e.toLoginResult()
        }

        tokenManager.store(tokens)
        sessionPreferences.setLastUsername(user)
        if (tokens.mustChangePassword) {
            state.value = SessionState.PasswordChangeRequired
            return LoginResult.SUCCESS
        }

        return try {
            val signedIn = tracker.call { account.me() }
            if (signedIn == null || signedIn.modules.isEmpty()) {
                // No module this app can show (e.g. an account without a linked driver): nothing in the app can work.
                revokeServerSession()
                tokenManager.clear()
                LoginResult.APP_ACCESS_REQUIRED
            } else {
                startSession(signedIn)
                LoginResult.SUCCESS
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            if (e.problem?.code == DriverErrorCodes.PASSWORD_CHANGE_REQUIRED) {
                state.value = SessionState.PasswordChangeRequired
                LoginResult.SUCCESS
            } else {
                revokeServerSession()
                tokenManager.clear()
                e.toLoginResult()
            }
        }
    }

    /** Removes the push registration and revokes the refresh token on the server first, then removes the local session. */
    override suspend fun logout() {
        pushRegistrar.unregister()
        revokeServerSession()
        endSession(expired = false)
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String): PasswordChangeResult {
        if (currentPassword.isEmpty() || newPassword.length !in DriverInputRules.PASSWORD_LENGTH) return PasswordChangeResult.REJECTED
        return try {
            tracker.call { account.changePassword(DriverPasswordRequestDto(currentPassword, newPassword)).requireSuccess() }
            // The backend revokes every session of the account: the user signs in with the new password.
            endSession(expired = false)
            PasswordChangeResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            when (e) {
                is RemoteException.Validation -> {
                    val errors = e.problem?.errors.orEmpty()
                    when {
                        IdentityErrorCodes.PASSWORD_MISMATCH in errors -> PasswordChangeResult.WRONG_CURRENT_PASSWORD
                        errors.any { it in IdentityErrorCodes.WEAK_PASSWORD } -> PasswordChangeResult.WEAK_PASSWORD
                        else -> PasswordChangeResult.REJECTED
                    }
                }
                is RemoteException.Unauthorized -> PasswordChangeResult.SESSION_EXPIRED
                is RemoteException.RateLimited -> PasswordChangeResult.RATE_LIMITED
                is RemoteException.Offline -> PasswordChangeResult.OFFLINE
                else -> PasswordChangeResult.FAILED
            }
        }
    }

    override suspend fun lastUsername(): String? = sessionPreferences.lastUsername()

    private suspend fun startSession(signedIn: UserProfile) {
        sessionData.clearServerData()
        sessionData.keepStoredDataOf(signedIn)
        sessionPreferences.setLoggedInUser(signedIn)
        profile.value = signedIn
        state.value = SessionState.LoggedIn(signedIn.id, signedIn.modules)
    }

    /** Name and modules can change on the server while a session is stored (e.g. a module was granted). */
    private suspend fun refreshProfile(userId: String) {
        try {
            val current = tracker.call { account.me() } ?: return
            val session = state.value as? SessionState.LoggedIn ?: return
            if (current.id != userId || session.userId != userId) return
            if (current.modules.isEmpty()) {
                // Access was removed: the app has nothing left to show for this account.
                logout()
                return
            }
            sessionPreferences.updateProfile(current)
            profile.value = current
            if (current.modules != session.modules) state.value = SessionState.LoggedIn(userId, current.modules)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            // The Mobile API answers `me` with 403 once an account has no module left: same as an empty module list.
            // Anything else (offline, ended session) is reported by session events and the screens.
            if (e is RemoteException.Forbidden && e.problem?.code == MobileErrorCodes.MOBILE_ACCESS_REQUIRED &&
                (state.value as? SessionState.LoggedIn)?.userId == userId
            ) {
                logout()
            }
        }
    }

    private suspend fun revokeServerSession() {
        if (!tokenManager.hasSession()) return
        try {
            tracker.call { account.logout().requireSuccess() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: RemoteException) {
            // Not reachable: the local tokens are removed anyway and expire on the server.
        }
    }

    /**
     * An expired session keeps the driver's unconfirmed commands so the same driver can resend them after
     * signing in again; signing out or a password change removes them with the rest of the session data.
     * Attendance punches stay on the phone in both cases until the same account sends them (docs/15, ASSUMPTIONS M8).
     */
    private suspend fun endSession(expired: Boolean) {
        tokenManager.clear()
        sessionPreferences.clearLoggedInUser(expired)
        sessionData.clearServerData()
        if (!expired) sessionData.clearStoredCommands()
        profile.value = null
        state.value = SessionState.LoggedOut
    }

    private fun RemoteException.toLoginResult(): LoginResult = when (this) {
        is RemoteException.Unauthorized ->
            if (problem?.code == DriverErrorCodes.TWO_FACTOR_REQUIRED) LoginResult.TWO_FACTOR_REQUIRED else LoginResult.INVALID_CREDENTIALS
        is RemoteException.Forbidden -> when (problem?.code) {
            DriverErrorCodes.DRIVER_ACCESS_REQUIRED, MobileErrorCodes.MOBILE_ACCESS_REQUIRED -> LoginResult.APP_ACCESS_REQUIRED
            else -> LoginResult.FAILED
        }
        is RemoteException.RateLimited -> LoginResult.RATE_LIMITED
        is RemoteException.ServerUnavailable -> LoginResult.SERVICE_UNAVAILABLE
        is RemoteException.NotFound -> if (isApiDisabled) LoginResult.SERVICE_UNAVAILABLE else LoginResult.FAILED
        is RemoteException.Offline -> LoginResult.OFFLINE
        else -> LoginResult.FAILED
    }
}
