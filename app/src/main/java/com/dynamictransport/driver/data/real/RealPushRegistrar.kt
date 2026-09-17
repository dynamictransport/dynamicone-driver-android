package com.dynamictransport.driver.data.real

import com.dynamictransport.driver.core.network.DriverApi
import com.dynamictransport.driver.core.network.DriverDeviceRequestDto
import com.dynamictransport.driver.core.network.RemoteException
import com.dynamictransport.driver.core.network.requireSuccess
import com.dynamictransport.driver.core.push.PushRegistrar
import com.dynamictransport.driver.core.security.SessionPreferences
import com.dynamictransport.driver.core.security.TokenManager
import com.dynamictransport.driver.domain.repository.AppInfo
import kotlinx.coroutines.CancellationException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Push device registration (TH-2026-0444): `POST me/devices` for the signed-in driver and
 * `DELETE me/devices/{installationId}` before signing out. Best effort: a failure never affects the session, and
 * the next app start or token change registers again. The push token is neither stored nor logged.
 */
@Singleton
class RealPushRegistrar @Inject constructor(
    private val api: DriverApi,
    private val tokenManager: TokenManager,
    private val sessionPreferences: SessionPreferences,
    private val tracker: ApiActivityTracker,
    private val appInfo: AppInfo,
) : PushRegistrar {

    override suspend fun register(pushToken: String) {
        if (!canCall()) return
        val request = DriverDeviceRequestDto(
            installationId = sessionPreferences.installationId(),
            platform = PLATFORM,
            pushToken = pushToken,
            appVersion = appInfo.versionName.take(MAX_APP_VERSION_LENGTH),
            // The app language (per-app language sets the default locale); the backend accepts 2–20 letters.
            locale = Locale.getDefault().language.takeIf { language -> language.length in 2..20 && language.all { it in 'a'..'z' } },
        )
        bestEffort { api.registerDevice(request).requireSuccess() }
    }

    override suspend fun unregister() {
        if (!canCall()) return
        bestEffort { api.deleteDevice(sessionPreferences.installationId()).requireSuccess() }
    }

    /** Without a session the call could only fail, and its failed refresh would report an expired session. */
    private suspend fun canCall(): Boolean = !appInfo.isDemoMode && tokenManager.hasSession()

    private suspend fun bestEffort(call: suspend () -> Unit) {
        try {
            tracker.call(call)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RemoteException) {
            // Registered again at the next app start or token change.
        }
    }

    private companion object {
        const val PLATFORM = "Android"
        const val MAX_APP_VERSION_LENGTH = 40
    }
}
