package com.dynamictransport.dynamicone.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response as HttpResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/**
 * Device management endpoints (docs/29 §3), relative to `{API_BASE_URL}mdm/`. They are called with the device
 * credential, never with the driver's access token: this API has its own HTTP client ([MdmHttpClients]).
 */
interface MdmApi {

    /** No credential. 404 `enrollment_code_invalid`, 400 `validation_failed`. */
    @POST("enroll")
    suspend fun enroll(@Body body: MdmEnrollRequestDto): MdmEnrollResponseDto

    /** 401 `device_not_enrolled` once the server retired the device. */
    @POST("check-in")
    suspend fun checkIn(@Body body: MdmCheckInRequestDto): MdmCheckInResponseDto

    /** 204; posting twice is harmless. 404 `command_not_found`. */
    @POST("commands/{id}/result")
    suspend fun postCommandResult(@Path("id") commandId: String, @Body body: MdmCommandResultRequestDto): Response<Unit>

    /** 204. */
    @POST("remote-sessions/{sessionId}/decline")
    suspend fun declineRemoteSession(@Path("sessionId") sessionId: String): Response<Unit>
}

@Serializable
data class MdmEnrollRequestDto(
    val enrollmentCode: String,
    val installationId: String,
    val manufacturer: String,
    val model: String,
    val serialNumber: String?,
    val osVersion: String,
    val sdkInt: Int,
    val appVersion: String,
    val appVersionCode: Int,
    val isDeviceOwner: Boolean,
) {
    /** The code is a one-time secret: kept out of logs. */
    override fun toString(): String = "MdmEnrollRequestDto(installationId=$installationId)"
}

@Serializable
data class MdmEnrollResponseDto(
    val deviceId: String? = null,
    val deviceSecret: String? = null,
    val checkInSeconds: Long? = null,
) {
    override fun toString(): String = "MdmEnrollResponseDto(deviceId=$deviceId)"
}

@Serializable
data class MdmCheckInRequestDto(
    val appVersion: String,
    val appVersionCode: Int,
    val osVersion: String,
    val sdkInt: Int,
    val securityPatch: String?,
    val isDeviceOwner: Boolean,
    val accessibilityEnabled: Boolean,
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val networkType: String,
    val storageFreeMb: Long,
    val storageTotalMb: Long,
    val pushToken: String?,
    val locale: String,
    val policyVersionApplied: Int?,
) {
    override fun toString(): String = "MdmCheckInRequestDto(appVersionCode=$appVersionCode)"
}

@Serializable
data class MdmCheckInResponseDto(
    val checkInSeconds: Long? = null,
    val policy: MdmPolicyDto? = null,
    val commands: List<MdmCommandDto>? = null,
)

@Serializable
data class MdmPolicyDto(
    val version: Int? = null,
    val blockFactoryReset: Boolean = false,
    val blockUnknownSources: Boolean = false,
    val blockUsbFileTransfer: Boolean = false,
    val blockSafeBoot: Boolean = false,
    val blockAddUser: Boolean = false,
)

@Serializable
data class MdmCommandDto(
    val id: String? = null,
    val type: String? = null,
    val payload: JsonObject? = null,
    val expiresAt: String? = null,
)

@Serializable
data class MdmCommandResultRequestDto(
    val status: String,
    val detail: String?,
)

object MdmErrorCodes {
    const val DEVICE_NOT_ENROLLED = "device_not_enrolled"
    const val ENROLLMENT_CODE_INVALID = "enrollment_code_invalid"
    const val COMMAND_NOT_FOUND = "command_not_found"
}

/** Path of the MDM endpoints below the Driver API base URL. */
const val MDM_PATH = "mdm/"

/** The stored device credential as an `Authorization` value, or null while the device is not enrolled. */
fun interface DeviceAuthorizationSource {
    fun currentAuthorization(): String?
}

/** `Authorization: Device {id}.{secret}` on every MDM call except enrolment (docs/29 §2). */
class DeviceAuthInterceptor(
    private val source: DeviceAuthorizationSource,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): HttpResponse {
        val request = chain.request()
        if (request.url.encodedPath.endsWith("/mdm/enroll")) return chain.proceed(request)
        val authorization = source.currentAuthorization() ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", authorization).build())
    }
}

object MdmHttpClients {

    /**
     * [base] is the client without the driver's bearer interceptor and token refresh: a `401` here never touches the
     * account session.
     */
    fun device(base: OkHttpClient, source: DeviceAuthorizationSource): OkHttpClient = base.newBuilder()
        .addInterceptor(DeviceAuthInterceptor(source))
        .build()

    /** The remote session stream stays open for the whole session: no read timeout. */
    fun stream(device: OkHttpClient): OkHttpClient = device.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}
