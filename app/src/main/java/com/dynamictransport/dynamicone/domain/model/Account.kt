package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

/**
 * A feature area the server enables for the signed-in account (`me.modules`, docs/15_MOBILE_APP_API.md).
 * Screens and navigation follow this set; the app never decides roles itself.
 */
enum class AppModule(val wireName: String) {
    DRIVER("driver"),
    OFFICE_ATTENDANCE("officeAttendance"),

    /** Personnel documents to sign (docs/17_HR_DOCUMENT_SIGNING.md). */
    HR_DOCUMENTS("hrDocuments"),
    ;

    companion object {
        /** Keys of modules this app version does not know are ignored. */
        fun fromWire(keys: Collection<String>?): Set<AppModule> =
            keys.orEmpty().mapNotNullTo(LinkedHashSet()) { key -> entries.firstOrNull { it.wireName == key } }
    }
}

/**
 * The signed-in account. [id] is the session key: the user id after a Mobile API sign-in, the driver id after
 * a Driver API sign-in (and in the demo).
 */
data class UserProfile(
    val id: String,
    val displayName: String,
    /** Not part of the API contracts; only the demo backend provides it. */
    val personnelNo: String?,
    val preferredLanguage: String?,
    val modules: Set<AppModule>,
    /** Driver API identity; null for accounts without the driver module. */
    val driverId: String?,
    /** `me.assignedVehicle`: null when no vehicle is assigned to the account. */
    val assignedVehicle: AssignedVehicle? = null,
    /** `me.photoSha256` (docs/21): identifies the current HR photo; null when there is none or the API has no photos. */
    val photoSha256: String? = null,
    /** `me.jobTitle` (docs/22): HR job title shown under the name; null when there is none. */
    val jobTitle: String? = null,
)

/**
 * The driver's current fleet vehicle (`me.assignedVehicle`). A trip's vehicle or the vehicle of a started shift can be a
 * different one and never stands in for it.
 */
data class AssignedVehicle(
    val vehicleId: String?,
    /** DYNA code of the fleet master data (e.g. `Dyna042`); null when the vehicle has none. */
    val companyCode: String?,
    val registrationNumber: String?,
)

/** What the app knows about the signed-in account's assigned vehicle. */
sealed interface AssignedVehicleState {
    /** No answer from `me` for this account yet. */
    data object Loading : AssignedVehicleState

    /** `me` could not be read and no earlier answer for this account is known. */
    data object Unavailable : AssignedVehicleState

    /** `me` answered: no vehicle is assigned. */
    data object NotAssigned : AssignedVehicleState

    data class Assigned(val vehicle: AssignedVehicle) : AssignedVehicleState

    companion object {
        fun of(vehicle: AssignedVehicle?): AssignedVehicleState = if (vehicle == null) NotAssigned else Assigned(vehicle)
    }
}

class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
) {
    // Never leak token values through logs or crash reports.
    override fun toString(): String = "AuthTokens(expiresAt=$expiresAt)"
}

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val userId: String, val modules: Set<AppModule> = setOf(AppModule.DRIVER)) : SessionState

    /** Signed in, but the backend only allows the password change until it is done. */
    data object PasswordChangeRequired : SessionState
}

enum class LoginResult {
    SUCCESS,
    INVALID_CREDENTIALS,
    TWO_FACTOR_REQUIRED,

    /** The account has no module this app can show (`driver_access_required` / `mobile_access_required`). */
    APP_ACCESS_REQUIRED,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    OFFLINE,
    FAILED,
}

enum class PasswordChangeResult {
    /** Password changed; every session was revoked and the driver must sign in again. */
    SUCCESS,
    REJECTED,

    /** Identity `PasswordMismatch`: the current password is not correct. */
    WRONG_CURRENT_PASSWORD,

    /** Identity password rules (length, digit, upper/lower case, symbol) were not met. */
    WEAK_PASSWORD,
    SESSION_EXPIRED,
    RATE_LIMITED,
    OFFLINE,
    NOT_SUPPORTED,
    FAILED,
}
