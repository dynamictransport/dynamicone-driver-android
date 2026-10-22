package com.dynamictransport.dynamicone.core.network

import kotlinx.serialization.Serializable

// Wire models of the DynamicONE Driver App API v1 (OpenAPI document "driver-v1",
// https://localhost:7167/swagger/driver-v1/swagger.json, backend TH-2026-0432).
// Every field the contract marks nullable is nullable here; unknown fields are ignored.

@Serializable
data class DriverLoginRequestDto(
    val userName: String,
    val password: String,
    val twoFactorCode: String? = null,
) {
    override fun toString(): String = "DriverLoginRequestDto(***)"
}

@Serializable
data class DriverRefreshRequestDto(
    val refreshToken: String,
) {
    override fun toString(): String = "DriverRefreshRequestDto(***)"
}

@Serializable
data class DriverPasswordRequestDto(
    val currentPassword: String,
    val newPassword: String,
) {
    override fun toString(): String = "DriverPasswordRequestDto(***)"
}

@Serializable
data class DriverTokenResponseDto(
    val tokenType: String? = null,
    val accessToken: String? = null,
    val expiresIn: Int = 0,
    val refreshToken: String? = null,
    val refreshTokenExpiresAtUtc: String? = null,
    val mustChangePassword: Boolean = false,
) {
    override fun toString(): String =
        "DriverTokenResponseDto(expiresIn=$expiresIn, mustChangePassword=$mustChangePassword)"
}

@Serializable
data class DriverProfileResponseDto(
    val userId: String? = null,
    val driverId: String? = null,
    val displayName: String? = null,
    val preferredLanguageCode: String? = null,
    /** The driver's current fleet vehicle; null without one (backend PR #133). */
    val assignedVehicle: AssignedVehicleDto? = null,
)

/** `me.assignedVehicle` of the Driver and Mobile API. */
@Serializable
data class AssignedVehicleDto(
    val vehicleId: String? = null,
    /** DYNA code; null when the vehicle has none. */
    val companyCode: String? = null,
    val registrationNumber: String? = null,
)

@Serializable
data class DriverHealthResponseDto(
    val status: String? = null,
    val apiVersion: String? = null,
    val checkedAtUtc: String? = null,
)

@Serializable
data class DriverPageDto<T>(
    val items: List<T>? = null,
    val page: Int = 1,
    val pageSize: Int = 25,
    val hasMore: Boolean = false,
)

@Serializable
data class DriverTripSummaryResponseDto(
    val id: String,
    val tripNumber: String? = null,
    val orderNumber: String? = null,
    val status: String? = null,
    val assignmentStatus: String? = null,
    val vehicleRegistration: String? = null,
    /** DYNA code of the trip's vehicle; null when the vehicle has none. */
    val vehicleCompanyCode: String? = null,
    val assignedFromUtc: String? = null,
    /** First stop; null when the trip has no stop. */
    val origin: DriverLocationDto? = null,
    /** Last stop; null when the trip has no stop. */
    val destination: DriverLocationDto? = null,
    /** First stop that is not completed or skipped; null for a completed trip. */
    val nextStop: DriverLocationDto? = null,
)

@Serializable
data class DriverTripResponseDto(
    val trip: DriverTripSummaryResponseDto? = null,
    val instructions: String? = null,
    val stops: List<DriverStopResponseDto>? = null,
    val containers: List<DriverContainerResponseDto>? = null,
)

@Serializable
data class DriverStopResponseDto(
    val id: String,
    val sequence: Int = 0,
    val activity: String? = null,
    val status: String? = null,
    val location: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val plannedArrivalUtc: String? = null,
    val plannedDepartureUtc: String? = null,
    val actualArrivalUtc: String? = null,
    val actualDepartureUtc: String? = null,
    val reference: String? = null,
)

@Serializable
data class DriverContainerResponseDto(
    val id: String? = null,
    val number: String? = null,
    val sealNumber: String? = null,
)

@Serializable
data class DriverDocumentResponseDto(
    val id: String,
    val type: String? = null,
    val fileName: String? = null,
    val contentType: String? = null,
    val submittedAtUtc: String? = null,
)

@Serializable
data class DriverStatusRequestDto(
    val status: String,
    val note: String? = null,
)

@Serializable
data class DriverStatusResponseDto(
    val id: String,
    val status: String? = null,
    val note: String? = null,
    val reportedAtUtc: String? = null,
)

@Serializable
data class DriverCommandResponseDto(
    val succeeded: Boolean = false,
    val message: String? = null,
)

/** application/problem+json with the backend's `code` / `traceId` extensions. */
@Serializable
data class ProblemDetailsDto(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
    val code: String? = null,
    val traceId: String? = null,
    val fields: List<String>? = null,
    val errors: List<String>? = null,
)

/** Case-sensitive names accepted by POST trips/{tripId}/statuses. Numeric values are rejected. */
object DriverStatusNames {
    const val DEPARTED = "Departed"
    const val ARRIVED_AT_LOADING = "ArrivedAtLoading"
    const val LOADING_COMPLETED = "LoadingCompleted"
    const val ARRIVED_AT_DELIVERY = "ArrivedAtDelivery"
    const val DELIVERY_COMPLETED = "DeliveryCompleted"
    const val RESTING = "Resting"
    const val PROBLEM_REPORTED = "ProblemReported"

    /** Backend-enforced order of the progression steps after acceptance. */
    val PROGRESSION = listOf(DEPARTED, ARRIVED_AT_LOADING, LOADING_COMPLETED, ARRIVED_AT_DELIVERY, DELIVERY_COMPLETED)

    /** Completions the backend refuses (409 `workflow_conflict`) while required evidence is open (docs/18). */
    val EVIDENCE_GATED = setOf(LOADING_COMPLETED, DELIVERY_COMPLETED)
}

/** Operational trip document types (`TripDocumentType`). "A1" in this business is the transport customs document: `Customs`. */
object DriverDocumentTypeNames {
    const val CMR = "Cmr"
    const val CUSTOMS = "Customs"
    const val INTERCHANGE = "Interchange"
    const val CONTAINER = "Container"
    const val SEAL = "Seal"
    const val OTHER = "Other"
}

/** Stable problem `code` values. Clients branch on these and HTTP status, never on English titles. */
object DriverErrorCodes {
    const val API_DISABLED = "api_disabled"
    const val UNAUTHORIZED = "unauthorized"
    const val FORBIDDEN = "forbidden"
    const val NOT_FOUND = "not_found"
    const val CONFLICT = "conflict"
    const val VALIDATION_FAILED = "validation_failed"
    const val INVALID_CREDENTIALS = "invalid_credentials"
    const val TWO_FACTOR_REQUIRED = "two_factor_required"
    const val DRIVER_ACCESS_REQUIRED = "driver_access_required"
    const val INVALID_REFRESH_TOKEN = "invalid_refresh_token"
    const val PASSWORD_CHANGE_REQUIRED = "password_change_required"
    const val PASSWORD_CHANGE_FAILED = "password_change_failed"
    const val WORKFLOW_CONFLICT = "workflow_conflict"
    const val INVALID_STATUS = "invalid_status"
    const val INVALID_ACTION = "invalid_action"
    const val INVALID_SLOT = "invalid_slot"

    /** 409 on a trip action: required evidence of the step is missing or rejected (docs/18). */
    const val REQUIRED_DOCUMENTS_MISSING = "required_documents_missing"
    const val INVALID_DOCUMENT = "invalid_document"
    const val INVALID_DOCUMENT_SIZE = "invalid_document_size"
    const val DOCUMENT_REJECTED = "document_rejected"
    const val INVALID_MESSAGE = "invalid_message"
    const val RATE_LIMIT_EXCEEDED = "rate_limit_exceeded"
}

/** ASP.NET Core Identity error codes returned in `errors` of `password_change_failed`. */
object IdentityErrorCodes {
    const val PASSWORD_MISMATCH = "PasswordMismatch"
    val WEAK_PASSWORD = setOf(
        "PasswordTooShort",
        "PasswordRequiresNonAlphanumeric",
        "PasswordRequiresDigit",
        "PasswordRequiresLower",
        "PasswordRequiresUpper",
        "PasswordRequiresUniqueChars",
    )
}
