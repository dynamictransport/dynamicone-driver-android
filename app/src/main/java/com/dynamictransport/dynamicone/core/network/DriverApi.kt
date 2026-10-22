package com.dynamictransport.dynamicone.core.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * DynamicONE Driver App API v1, relative to `/api/driver/v1/` (OpenAPI "driver-v1" plus the operations
 * contract of TH-2026-0439). The driver is always resolved from the token: no driver or employee id is sent.
 */
interface DriverApi {

    @POST("auth/login")
    suspend fun login(@Body body: DriverLoginRequestDto): DriverTokenResponseDto

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @POST("auth/change-password")
    suspend fun changePassword(@Body body: DriverPasswordRequestDto): Response<Unit>

    @GET("health")
    suspend fun health(): DriverHealthResponseDto

    @GET("me")
    suspend fun me(): DriverProfileResponseDto

    @GET("trips")
    suspend fun trips(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Query("includeCompleted") includeCompleted: Boolean,
    ): DriverPageDto<DriverTripSummaryResponseDto>

    @GET("trips/{tripId}")
    suspend fun trip(@Path("tripId") tripId: String): DriverTripResponseDto

    @GET("trips/{tripId}/stops")
    suspend fun stops(@Path("tripId") tripId: String): List<DriverStopResponseDto>

    @POST("trips/{tripId}/accept")
    suspend fun accept(@Path("tripId") tripId: String): DriverCommandResponseDto

    // --- Trip actions workflow ---

    @GET("trips/{tripId}/execution")
    suspend fun execution(@Path("tripId") tripId: String): DriverExecutionResponseDto

    @POST("trips/{tripId}/actions")
    suspend fun executeAction(
        @Path("tripId") tripId: String,
        @Body body: DriverActionCommandDto,
    ): DriverExecutionResultDto

    @GET("trips/{tripId}/actions")
    suspend fun actions(
        @Path("tripId") tripId: String,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): DriverPageDto<DriverActionReceiptDto>

    // --- Trailer confirmation after accepting (docs/20_DRIVER_TRAILER_CONFIRMATION.md) ---

    /** The active trailers and this trip's confirmation. The client never creates a trailer. */
    @GET("trips/{tripId}/trailers")
    suspend fun trailers(@Path("tripId") tripId: String): DriverTrailerStateDto

    /** 400 `invalid_trailer`, 404 `not_found`, 409 `conflict` (plan changed, or the trip already started). */
    @POST("trips/{tripId}/trailer-confirmation")
    suspend fun confirmTrailer(
        @Path("tripId") tripId: String,
        @Body body: DriverTrailerCommandDto,
    ): DriverExecutionResultDto

    // --- Legacy status workflow (trips started before trip actions existed) ---

    @GET("trips/{tripId}/statuses")
    suspend fun statuses(
        @Path("tripId") tripId: String,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): DriverPageDto<DriverStatusResponseDto>

    @POST("trips/{tripId}/statuses")
    suspend fun reportStatus(
        @Path("tripId") tripId: String,
        @Body body: DriverStatusRequestDto,
    ): DriverCommandResponseDto

    // --- Documents ---

    @GET("trips/{tripId}/document-requirements")
    suspend fun documentRequirements(@Path("tripId") tripId: String): List<DriverDocumentSlotDto>

    /** multipart/form-data: `slot` (opaque key from the requirements) and the `document` file. */
    @Multipart
    @POST("trips/{tripId}/document-slots")
    suspend fun uploadSlotDocument(
        @Path("tripId") tripId: String,
        @Part("slot") slot: RequestBody,
        @Part document: MultipartBody.Part,
    ): DriverExecutionResultDto

    @GET("trips/{tripId}/documents")
    suspend fun documents(
        @Path("tripId") tripId: String,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): DriverPageDto<DriverDocumentResponseDto>

    @Streaming
    @GET("trips/{tripId}/documents/{documentId}/content")
    suspend fun documentContent(
        @Path("tripId") tripId: String,
        @Path("documentId") documentId: String,
    ): ResponseBody

    // --- Personnel ---

    @GET("me/payroll")
    suspend fun payroll(
        @Query("year") year: Int,
        @Query("month") month: Int,
    ): DriverPayrollResponseDto

    @GET("me/signed-documents")
    suspend fun signedDocuments(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): DriverPageDto<DriverSignedDocumentDto>

    @Streaming
    @GET("me/signed-documents/{documentId}/content")
    suspend fun signedDocumentContent(@Path("documentId") documentId: String): ResponseBody

    // --- Push devices (TH-2026-0444) ---

    /** 204. The same installation id updates its registration. */
    @POST("me/devices")
    suspend fun registerDevice(@Body body: DriverDeviceRequestDto): Response<Unit>

    /** 204, also when nothing is registered. Only the session that registered the device removes it. */
    @DELETE("me/devices/{installationId}")
    suspend fun deleteDevice(@Path("installationId") installationId: String): Response<Unit>

    // --- Messages ---

    /**
     * Without filters: the driver's inbox, general and accessible trip messages together (TH-2026-0440; earlier
     * backends returned only the general conversation). [tripId]: that trip only. [generalOnly]: the general
     * conversation only; never combined with [tripId] (400 `invalid_message_filter`).
     */
    @GET("messages")
    suspend fun messages(
        @Query("tripId") tripId: String?,
        @Query("generalOnly") generalOnly: Boolean?,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): DriverPageDto<DriverMessageDto>

    @POST("messages")
    suspend fun sendMessage(@Body body: DriverSendMessageDto): DriverExecutionResultDto

    @POST("messages/{messageId}/read")
    suspend fun markMessageRead(
        @Path("messageId") messageId: String,
        @Body body: DriverMessageReadDto,
    ): Response<Unit>

    // --- Assigned vehicle position (docs/19_ASSIGNED_VEHICLE_POSITION.md, proposed; 404 until the backend has it) ---

    /** Always 200 with a `status`; the vehicle is the one of `me.assignedVehicle`. */
    @GET("assigned-vehicle/position")
    suspend fun assignedVehiclePosition(): AssignedVehiclePositionResponseDto

    // --- Daily shift, tachograph and vehicle check (docs/16_DRIVER_SHIFT_REST_AND_VEHICLE_CHECK.md) ---

    @GET("driver-times")
    suspend fun driverTimes(): DriverTimesResponseDto

    @GET("shift")
    suspend fun shift(): ShiftStateResponseDto

    /** 404 `vehicle_tag_unknown` for a tag without an active vehicle. */
    @GET("vehicle-tags/{tagUid}")
    suspend fun vehicleTag(@Path("tagUid") tagUid: String): VehicleTagResponseDto

    @GET("vehicle-check/template")
    suspend fun vehicleCheckTemplate(): VehicleCheckTemplateDto

    /** 201 for a new start, 200 with the first result when the same `commandId` is sent again. */
    @POST("shift/start")
    suspend fun startShift(@Body body: ShiftStartRequestDto): ShiftStartResponseDto

    @POST("shift/end")
    suspend fun endShift(@Body body: ShiftEndRequestDto): ShiftEndResponseDto

    /** multipart/form-data: `photoId` (from the start request) and the `photo` file; idempotent by `photoId`. */
    @Multipart
    @POST("vehicle-checks/{checkId}/photos")
    suspend fun uploadCheckPhoto(
        @Path("checkId") checkId: String,
        @Part("photoId") photoId: RequestBody,
        @Part photo: MultipartBody.Part,
    ): CheckPhotoResponseDto

    // --- Remote road snapshots (docs/24) ---

    /** 200 while pending; `409 snapshot_request_closed` afterwards, `404 snapshot_request_not_found` for another driver's. */
    @GET("trips/{tripId}/snapshot-requests/{requestId}")
    suspend fun snapshotRequest(@Path("tripId") tripId: String, @Path("requestId") requestId: String): SnapshotRequestDto

    /** multipart/form-data: `capturedAt` (UTC), `camera` (`External`/`Back`) and the EXIF-free JPEG; idempotent by request. */
    @Multipart
    @POST("trips/{tripId}/snapshot-requests/{requestId}/photo")
    suspend fun uploadSnapshotPhoto(
        @Path("tripId") tripId: String,
        @Path("requestId") requestId: String,
        @Part("capturedAt") capturedAt: RequestBody,
        @Part("camera") camera: RequestBody,
        @Part photo: MultipartBody.Part,
    ): SnapshotPhotoResponseDto

    /** 204, also for an already closed request. */
    @POST("trips/{tripId}/snapshot-requests/{requestId}/result")
    suspend fun reportSnapshotResult(
        @Path("tripId") tripId: String,
        @Path("requestId") requestId: String,
        @Body body: SnapshotResultRequestDto,
    ): Response<Unit>

    // --- Wake-up calls (docs/25) ---

    /** 200 in every state; `404 wake_up_call_not_found` for another driver's call. */
    @GET("me/wake-up-calls/{callId}")
    suspend fun wakeUpCall(@Path("callId") callId: String): WakeUpCallDto

    /** 204; a call that is no longer pending stays as it is. */
    @POST("me/wake-up-calls/{callId}/ringing")
    suspend fun reportWakeUpRinging(@Path("callId") callId: String, @Body body: WakeUpRingingRequestDto): Response<Unit>

    /** 204, also for an already closed call; idempotent by `clientActionId`. */
    @POST("me/wake-up-calls/{callId}/result")
    suspend fun reportWakeUpResult(@Path("callId") callId: String, @Body body: WakeUpResultRequestDto): Response<Unit>
}

/** Refresh runs on a client without the authenticator, so a failed refresh can never loop. */
interface DriverAuthApi {
    @POST("auth/refresh")
    fun refresh(@Body body: DriverRefreshRequestDto): Call<DriverTokenResponseDto>
}
