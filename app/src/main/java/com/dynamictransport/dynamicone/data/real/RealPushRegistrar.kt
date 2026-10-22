package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.DeviceProfile
import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.DriverDeviceRequestDto
import com.dynamictransport.dynamicone.core.network.MobileApi
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.network.requireSuccess
import com.dynamictransport.dynamicone.core.push.PushRegistrar
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.core.security.TokenManager
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import kotlinx.coroutines.CancellationException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Push device registration (TH-2026-0444): `POST me/devices` for the signed-in account and
 * `DELETE me/devices/{installationId}` before signing out. Driver accounts register through the Driver API as before;
 * accounts without the driver module that receive personnel documents register through the Mobile API (docs/17 §5.6).
 * Best effort: a failure never affects the session, and the next app start or token change registers again. The push
 * token is neither stored nor logged.
 */
@Singleton
class RealPushRegistrar @Inject constructor(
    private val api: DriverApi,
    private val tokenManager: TokenManager,
    private val sessionPreferences: SessionPreferences,
    private val tracker: ApiActivityTracker,
    private val appInfo: AppInfo,
    private val mobileApi: MobileApi,
    private val deviceProfile: DeviceProfile,
) : PushRegistrar {

    override suspend fun register(pushToken: String) {
        val target = target() ?: return
        val request = DriverDeviceRequestDto(
            installationId = sessionPreferences.installationId(),
            platform = PLATFORM,
            pushToken = pushToken,
            appVersion = appInfo.versionName.take(MAX_APP_VERSION_LENGTH),
            // The app language (per-app language sets the default locale); the backend accepts 2–20 letters.
            locale = Locale.getDefault().language.takeIf { language -> language.length in 2..20 && language.all { it in 'a'..'z' } },
            // Only a vehicle tablet with a camera receives road snapshot requests (docs/24 §3).
            deviceKind = deviceProfile.kind,
            deviceModel = deviceProfile.model,
            hasCamera = deviceProfile.hasCamera,
        )
        bestEffort {
            when (target) {
                Target.DRIVER_API -> api.registerDevice(request)
                Target.MOBILE_API -> mobileApi.registerDevice(request)
            }.requireSuccess()
        }
    }

    override suspend fun unregister() {
        val target = target() ?: return
        val installationId = sessionPreferences.installationId()
        bestEffort {
            when (target) {
                Target.DRIVER_API -> api.deleteDevice(installationId)
                Target.MOBILE_API -> mobileApi.deleteDevice(installationId)
            }.requireSuccess()
        }
    }

    /**
     * Without a session the call could only fail, and its failed refresh would report an expired session. Accounts
     * with no module that sends pushes register nowhere.
     */
    private suspend fun target(): Target? {
        if (appInfo.isDemoMode || !tokenManager.hasSession()) return null
        return when {
            sessionPreferences.driverId() != null -> Target.DRIVER_API
            AppModule.HR_DOCUMENTS in sessionPreferences.modules() -> Target.MOBILE_API
            else -> null
        }
    }

    private suspend fun bestEffort(call: suspend () -> Unit) {
        try {
            tracker.call(call)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RemoteException) {
            // Registered again at the next app start or token change.
        }
    }

    private enum class Target { DRIVER_API, MOBILE_API }

    private companion object {
        const val PLATFORM = "Android"
        const val MAX_APP_VERSION_LENGTH = 40
    }
}
