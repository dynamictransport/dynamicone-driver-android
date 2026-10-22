package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.AssignedVehicleDto
import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.DriverLoginRequestDto
import com.dynamictransport.dynamicone.core.network.DriverPasswordRequestDto
import com.dynamictransport.dynamicone.core.network.DriverProfileResponseDto
import com.dynamictransport.dynamicone.core.network.DriverTokenResponseDto
import com.dynamictransport.dynamicone.core.network.MobileApi
import com.dynamictransport.dynamicone.core.network.MobileProfileResponseDto
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.AssignedVehicle
import com.dynamictransport.dynamicone.domain.model.UserProfile
import okhttp3.ResponseBody
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

    /** The account's HR photo (docs/21); null when this API has no photos. */
    suspend fun photo(): ResponseBody?
}

class MobileAccountRemote(private val api: MobileApi) : AccountRemote {
    override suspend fun login(request: DriverLoginRequestDto) = api.login(request)

    override suspend fun logout() = api.logout()

    override suspend fun changePassword(request: DriverPasswordRequestDto) = api.changePassword(request)

    override suspend fun me(): UserProfile? = AccountMapping.profile(api.me())

    override suspend fun photo(): ResponseBody = api.mePhoto()
}

class DriverAccountRemote(private val api: DriverApi) : AccountRemote {
    override suspend fun login(request: DriverLoginRequestDto) = api.login(request)

    override suspend fun logout() = api.logout()

    override suspend fun changePassword(request: DriverPasswordRequestDto) = api.changePassword(request)

    override suspend fun me(): UserProfile? = AccountMapping.profile(api.me())

    /** Photos exist only in the Mobile API. */
    override suspend fun photo(): ResponseBody? = null
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
            assignedVehicle = assignedVehicle(dto.assignedVehicle),
            photoSha256 = photoSha256(dto.photoSha256),
            jobTitle = dto.jobTitle?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Only a well-formed SHA-256 names a photo; the app compares it with the hash of the downloaded bytes. */
    fun photoSha256(value: String?): String? = value?.trim()?.uppercase()?.takeIf { SHA256.matches(it) }

    private val SHA256 = Regex("[0-9A-F]{64}")

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
            assignedVehicle = assignedVehicle(dto.assignedVehicle),
        )
    }

    /** Null only when the server sends none; an empty code or plate is treated as missing. */
    fun assignedVehicle(dto: AssignedVehicleDto?): AssignedVehicle? = dto?.let {
        AssignedVehicle(
            vehicleId = it.vehicleId?.takeIf(String::isNotBlank),
            companyCode = it.companyCode?.takeIf(String::isNotBlank),
            registrationNumber = it.registrationNumber?.takeIf(String::isNotBlank),
        )
    }
}
