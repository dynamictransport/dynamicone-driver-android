package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.DriverLoginRequestDto
import com.dynamictransport.dynamicone.core.network.DriverPasswordRequestDto
import com.dynamictransport.dynamicone.core.network.DriverProfileResponseDto
import com.dynamictransport.dynamicone.core.network.DriverTokenResponseDto
import com.dynamictransport.dynamicone.core.network.MobileApi
import com.dynamictransport.dynamicone.core.network.MobileProfileResponseDto
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.UserProfile
import retrofit2.Response

/**
 * The API that signs accounts in and describes them. Until the Mobile API is live (`app.mobileApi`), drivers sign in
 * through the Driver API and only get the driver module; the rest of the session works the same way.
 */
interface AccountRemote {
    suspend fun login(request: DriverLoginRequestDto): DriverTokenResponseDto

    suspend fun logout(): Response<Unit>

    suspend fun changePassword(request: DriverPasswordRequestDto): Response<Unit>

    /** Null when the response does not identify an account; modules are empty when the account may not use the app. */
    suspend fun me(): UserProfile?
}

class MobileAccountRemote(private val api: MobileApi) : AccountRemote {
    override suspend fun login(request: DriverLoginRequestDto) = api.login(request)

    override suspend fun logout() = api.logout()

    override suspend fun changePassword(request: DriverPasswordRequestDto) = api.changePassword(request)

    override suspend fun me(): UserProfile? = AccountMapping.profile(api.me())
}

class DriverAccountRemote(private val api: DriverApi) : AccountRemote {
    override suspend fun login(request: DriverLoginRequestDto) = api.login(request)

    override suspend fun logout() = api.logout()

    override suspend fun changePassword(request: DriverPasswordRequestDto) = api.changePassword(request)

    override suspend fun me(): UserProfile? = AccountMapping.profile(api.me())
}

object AccountMapping {

    /** Mobile API `me`: the modules come from the server; `driver` is only kept together with a driver id. */
    fun profile(dto: MobileProfileResponseDto): UserProfile? {
        val userId = dto.userId?.takeIf { it.isNotBlank() } ?: return null
        val driverId = dto.driverId?.takeIf { it.isNotBlank() }
        val modules = AppModule.fromWire(dto.modules).filterTo(LinkedHashSet()) { it != AppModule.DRIVER || driverId != null }
        return UserProfile(
            id = userId,
            displayName = dto.displayName.orEmpty(),
            personnelNo = null,
            preferredLanguage = dto.preferredLanguageCode?.takeIf { it.isNotBlank() },
            modules = modules,
            driverId = driverId,
        )
    }

    /** Driver API `me`: the driver id is the session key and the only module is `driver`. Null without a driver id. */
    fun profile(dto: DriverProfileResponseDto): UserProfile? {
        val driverId = dto.driverId?.takeIf { it.isNotBlank() } ?: return null
        return UserProfile(
            id = driverId,
            displayName = dto.displayName.orEmpty(),
            personnelNo = null,
            preferredLanguage = dto.preferredLanguageCode?.takeIf { it.isNotBlank() },
            modules = setOf(AppModule.DRIVER),
            driverId = driverId,
        )
    }
}
