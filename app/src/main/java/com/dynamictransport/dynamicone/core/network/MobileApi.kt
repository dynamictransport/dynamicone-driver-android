package com.dynamictransport.dynamicone.core.network

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * DynamicONE Mobile API v1, relative to `/api/mobile/v1/` (docs/15_MOBILE_APP_API.md). Sign-in and `me` for every
 * module plus office attendance. Driver screens keep using [DriverApi] with the same tokens. The account is always
 * resolved from the token: no user, employee or driver id is sent.
 */
interface MobileApi {

    /** Same request and token response as the Driver API; any account with at least one module may sign in. */
    @POST("auth/login")
    suspend fun login(@Body body: DriverLoginRequestDto): DriverTokenResponseDto

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @POST("auth/change-password")
    suspend fun changePassword(@Body body: DriverPasswordRequestDto): Response<Unit>

    @GET("me")
    suspend fun me(): MobileProfileResponseDto

    /** The account's current HR photo (docs/21_EMPLOYEE_PHOTO.md); `404 photo_not_found` when there is none. */
    @Streaming
    @GET("me/photo")
    suspend fun mePhoto(): ResponseBody

    // --- Office attendance ---

    @GET("attendance/status")
    suspend fun attendanceStatus(): AttendanceStatusDto

    /** 201 for a new punch, 200 with the first result when the same `commandId` is sent again. */
    @POST("attendance/punches")
    suspend fun recordPunch(@Body body: AttendancePunchRequestDto): AttendancePunchResultDto

    /** Work dates, both inclusive, at most 31 days. */
    @GET("attendance/days")
    suspend fun attendanceDays(
        @Query("from") from: String,
        @Query("to") to: String,
    ): AttendanceDaysResponseDto

    // --- Push devices of accounts without the driver module (docs/17 §5.6, same body as the Driver API) ---

    /** 204. The same installation id updates its registration. */
    @POST("me/devices")
    suspend fun registerDevice(@Body body: DriverDeviceRequestDto): Response<Unit>

    /** 204, also when nothing is registered. */
    @DELETE("me/devices/{installationId}")
    suspend fun deleteDevice(@Path("installationId") installationId: String): Response<Unit>

    // --- Personnel documents to sign (docs/17_HR_DOCUMENT_SIGNING.md) ---

    /** [state]: `Pending` (all documents to sign) or `Signed` (paged, newest first). */
    @GET("hr-documents")
    suspend fun hrDocuments(
        @Query("state") state: String,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): DriverPageDto<HrDocumentDto>

    @GET("hr-documents/{documentId}")
    suspend fun hrDocument(@Path("documentId") documentId: String): HrDocumentDto

    /** The PDF of one language version; `ETag` is its SHA-256. */
    @Streaming
    @GET("hr-documents/{documentId}/content")
    suspend fun hrDocumentContent(
        @Path("documentId") documentId: String,
        @Query("languageCode") languageCode: String,
    ): ResponseBody

    /** 201 for a new signature, 200 with the first result when the same `commandId` is sent again. */
    @POST("hr-documents/{documentId}/signature")
    suspend fun signHrDocument(
        @Path("documentId") documentId: String,
        @Body body: HrSignatureRequestDto,
    ): HrSignatureResultDto

    /** The signed PDF: original pages plus the signature page. */
    @Streaming
    @GET("hr-documents/{documentId}/receipt")
    suspend fun hrDocumentReceipt(@Path("documentId") documentId: String): ResponseBody

    // --- Files of the personnel file and of the assigned vehicle (read only, no upload) ---

    /** Every file of the account's own personnel file, newest first. [pageSize] 1–100; module `hrDocuments`. */
    @GET("me/documents")
    suspend fun myDocuments(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): PersonnelDocumentPageDto

    /** The driver's required documents and their state; module `driver`. */
    @GET("me/documents/requirements")
    suspend fun myDocumentRequirements(): DocumentRequirementsDto

    /** The original file (PDF, JPEG, PNG or WebP), `private, no-store`. */
    @Streaming
    @GET("me/documents/{documentId}/content")
    suspend fun myDocumentContent(@Path("documentId") documentId: String): ResponseBody

    /** Files of the driver's currently assigned vehicle; `assignedVehicle` null and empty lists without one. */
    @GET("me/assigned-vehicle/documents")
    suspend fun assignedVehicleDocuments(): VehicleDocumentsResponseDto

    @Streaming
    @GET("me/assigned-vehicle/documents/{documentId}/content")
    suspend fun assignedVehicleDocumentContent(@Path("documentId") documentId: String): ResponseBody

    // --- Cash paid to personnel (docs/28_EMPLOYEE_CASH_PAYMENTS.md): the officer's queue and the own account ---

    /** Approvals the account may hand over; [state] `Approved` (default), `Received` or `Cancelled`; module `cashDisbursements`. */
    @GET("cash-payments")
    suspend fun cashPayments(
        @Query("state") state: String,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): DriverPageDto<CashPaymentDto>

    @GET("cash-payments/{paymentId}")
    suspend fun cashPayment(@Path("paymentId") paymentId: String): CashPaymentDto

    /** 201 for a new hand-over, 200 with the same detail when the same `commandId` is sent again. */
    @POST("cash-payments/{paymentId}/signature")
    suspend fun signCashPayment(
        @Path("paymentId") paymentId: String,
        @Body body: CashSignatureRequestDto,
    ): CashPaymentDto

    /** The signed receipt PDF, with the bearer token; never a public link. */
    @Streaming
    @GET("cash-payments/{paymentId}/receipt")
    suspend fun cashPaymentReceipt(@Path("paymentId") paymentId: String): ResponseBody

    /** The account's own approvals and receipts; module `cashPayments`. */
    @GET("me/cash-payments")
    suspend fun myCashPayments(
        @Query("state") state: String,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): DriverPageDto<CashPaymentDto>

    @GET("me/cash-payments/{paymentId}")
    suspend fun myCashPayment(@Path("paymentId") paymentId: String): CashPaymentDto

    @POST("me/cash-payments/{paymentId}/signature")
    suspend fun signMyCashPayment(
        @Path("paymentId") paymentId: String,
        @Body body: CashSignatureRequestDto,
    ): CashPaymentDto

    @Streaming
    @GET("me/cash-payments/{paymentId}/receipt")
    suspend fun myCashPaymentReceipt(@Path("paymentId") paymentId: String): ResponseBody
}

@Serializable
data class MobileProfileResponseDto(
    val userId: String? = null,
    val employeeId: String? = null,
    val driverId: String? = null,
    val displayName: String? = null,
    val preferredLanguageCode: String? = null,
    val modules: List<String>? = null,
    /** The driver's current fleet vehicle; always null for accounts without the driver module. */
    val assignedVehicle: AssignedVehicleDto? = null,
    /** SHA-256 of the current HR photo (docs/21); null when the account has none. */
    val photoSha256: String? = null,
    /** HR job title (docs/22); null when the account has none. */
    val jobTitle: String? = null,
)

@Serializable
data class AttendancePunchDto(
    val id: String? = null,
    val commandId: String? = null,
    val direction: String? = null,
    val occurredAtUtc: String? = null,
    val receivedAtUtc: String? = null,
    val workDate: String? = null,
    val source: String? = null,
    val pointName: String? = null,
)

@Serializable
data class AttendanceStatusDto(
    val state: String? = null,
    val nextAction: String? = null,
    val workDate: String? = null,
    val timeZoneId: String? = null,
    val lastPunch: AttendancePunchDto? = null,
)

@Serializable
data class AttendancePunchRequestDto(
    val commandId: String,
    val direction: String,
    val tagUid: String,
    val scannedAtUtc: String,
    /** Monotonic time between the scan and this request; null when the phone restarted in between. */
    val elapsedSinceScanMs: Long?,
    val installationId: String,
) {
    // The tag identifier is attendance evidence: keep it out of logs.
    override fun toString(): String = "AttendancePunchRequestDto(commandId=$commandId, direction=$direction)"
}

@Serializable
data class AttendancePunchResultDto(
    val punch: AttendancePunchDto? = null,
    val status: AttendanceStatusDto? = null,
)

@Serializable
data class AttendanceDayDto(
    val workDate: String? = null,
    val state: String? = null,
    val workedMinutes: Int? = null,
    val punches: List<AttendancePunchDto>? = null,
)

@Serializable
data class AttendanceDaysResponseDto(
    val timeZoneId: String? = null,
    val days: List<AttendanceDayDto>? = null,
)

/** Wire names of the attendance contract (case-sensitive text, never numbers). */
object AttendanceWireNames {
    const val IN = "In"
    const val OUT = "Out"
    const val CHECKED_IN = "CheckedIn"
    const val CHECK_IN = "CheckIn"
    const val CHECK_OUT = "CheckOut"
    const val SOURCE_MOBILE_NFC = "MobileNfc"
    const val SOURCE_TERMINAL = "Terminal"
    const val SOURCE_CORRECTION = "Correction"
    const val DAY_COMPLETE = "Complete"
    const val DAY_OPEN = "Open"
    const val DAY_INCOMPLETE = "Incomplete"
    const val DAY_NEEDS_REVIEW = "NeedsReview"
}

/** Problem `code` values of the Mobile API (docs/15_MOBILE_APP_API.md). */
object MobileErrorCodes {
    const val MOBILE_ACCESS_REQUIRED = "mobile_access_required"
    const val PHOTO_NOT_FOUND = "photo_not_found"
    const val ATTENDANCE_ACCESS_REQUIRED = "attendance_access_required"
    const val ATTENDANCE_TAG_UNKNOWN = "attendance_tag_unknown"
    const val ATTENDANCE_TAG_DIRECTION_MISMATCH = "attendance_tag_direction_mismatch"
    const val ATTENDANCE_TIME_INVALID = "attendance_time_invalid"
    const val ATTENDANCE_PUNCH_TOO_OLD = "attendance_punch_too_old"
    const val COMMAND_CONFLICT = "command_conflict"
    const val INVALID_DATE_RANGE = "invalid_date_range"
    const val HR_DOCUMENTS_ACCESS_REQUIRED = "hr_documents_access_required"
    const val DOCUMENT_NOT_FOUND = "document_not_found"
    const val DOCUMENT_STORAGE_UNAVAILABLE = "document_storage_unavailable"
}
