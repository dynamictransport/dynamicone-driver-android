package com.dynamictransport.driver.domain.model

import java.time.Instant

data class Driver(
    val id: String,
    val displayName: String,
    /** Not part of the Driver API `me` contract; only the demo backend provides it. */
    val personnelNo: String?,
    val preferredLanguage: String?,
)

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
    data class LoggedIn(val driverId: String) : SessionState

    /** Signed in, but the backend only allows the password change until it is done. */
    data object PasswordChangeRequired : SessionState
}

enum class LoginResult {
    SUCCESS,
    INVALID_CREDENTIALS,
    TWO_FACTOR_REQUIRED,
    DRIVER_ACCESS_REQUIRED,
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
