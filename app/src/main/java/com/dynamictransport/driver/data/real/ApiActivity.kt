package com.dynamictransport.driver.data.real

import com.dynamictransport.driver.core.common.AppLogger
import com.dynamictransport.driver.core.common.ConnectivityObserver
import com.dynamictransport.driver.core.network.DriverErrorCodes
import com.dynamictransport.driver.core.network.RemoteException
import com.dynamictransport.driver.core.network.apiCall
import com.dynamictransport.driver.domain.model.ActionRejection
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.repository.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every Driver API call goes through here: it honours `Retry-After`, records whether the backend
 * was reachable and when data was last read successfully, and logs failures without secrets.
 */
@Singleton
class ApiActivityTracker @Inject constructor(
    private val clock: Clock,
    private val logger: AppLogger,
) {
    private val lastSuccess = MutableStateFlow<Instant?>(null)
    private val reachable = MutableStateFlow(true)

    @Volatile
    private var rateLimitedUntil: Instant? = null

    val lastSuccessfulReadAt: StateFlow<Instant?> = lastSuccess.asStateFlow()
    val backendReachable: StateFlow<Boolean> = reachable.asStateFlow()

    suspend fun <T> call(block: suspend () -> T): T {
        rateLimitedUntil?.let { until ->
            val now = clock.instant()
            if (now.isBefore(until)) throw RemoteException.RateLimited(Duration.between(now, until).seconds.coerceAtLeast(1))
            rateLimitedUntil = null
        }
        return try {
            apiCall(block).also { reachable.value = true }
        } catch (e: RemoteException) {
            record(e)
            throw e
        }
    }

    fun markReadSucceeded() {
        lastSuccess.value = clock.instant()
        reachable.value = true
    }

    /** The device is online again: the next call decides whether the backend is reachable. */
    fun onConnectivityRegained() {
        reachable.value = true
    }

    private fun record(e: RemoteException) {
        when (e) {
            is RemoteException.Offline -> reachable.value = false
            is RemoteException.RateLimited ->
                rateLimitedUntil = clock.instant().plusSeconds(e.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS)
            else -> reachable.value = true
        }
        val problem = e.problem
        // Status, stable code and trace id only: never tokens, credentials, bodies or file content.
        logger.event(
            "driver_api_failure",
            buildMap {
                put("type", e.javaClass.simpleName)
                problem?.status?.let { put("status", it.toString()) }
                problem?.code?.let { put("code", it) }
                problem?.traceId?.let { put("traceId", it) }
            },
        )
    }

    companion object {
        const val DEFAULT_RETRY_AFTER_SECONDS = 30L
    }
}

/** "Online" = the device has a validated connection and the last API call reached the backend. */
class RealNetworkMonitor @Inject constructor(
    connectivityObserver: ConnectivityObserver,
    tracker: ApiActivityTracker,
) : NetworkMonitor {
    override val isOnline: Flow<Boolean> =
        combine(
            connectivityObserver.isOnline.onEach { online -> if (online) tracker.onConnectivityRegained() },
            tracker.backendReachable,
        ) { device, backend -> device && backend }
            .distinctUntilChanged()
}

internal val RemoteException.isApiDisabled: Boolean
    get() = problem?.code == DriverErrorCodes.API_DISABLED

/** The request may or may not have been applied: read the state before doing anything else. */
internal fun RemoteException.isOutcomeUnknown(): Boolean =
    this is RemoteException.Offline || this is RemoteException.ServerError ||
        this is RemoteException.ServerUnavailable || this is RemoteException.InvalidResponse

internal fun RemoteException.toRefreshResult(): RefreshResult = when (this) {
    is RemoteException.Offline -> RefreshResult.OFFLINE
    is RemoteException.Unauthorized, is RemoteException.Forbidden -> RefreshResult.SESSION_EXPIRED
    is RemoteException.RateLimited -> RefreshResult.RATE_LIMITED
    is RemoteException.ServerUnavailable -> RefreshResult.SERVICE_UNAVAILABLE
    is RemoteException.NotFound -> if (isApiDisabled) RefreshResult.SERVICE_UNAVAILABLE else RefreshResult.FAILED
    is RemoteException.NotSupported -> RefreshResult.NOT_SUPPORTED
    else -> RefreshResult.FAILED
}

internal fun RemoteException.toActionRejection(): ActionRejection = when (this) {
    is RemoteException.Offline -> ActionRejection.OFFLINE
    is RemoteException.Unauthorized -> ActionRejection.SESSION_EXPIRED
    is RemoteException.Forbidden -> ActionRejection.FORBIDDEN
    is RemoteException.NotFound -> if (isApiDisabled) ActionRejection.SERVICE_UNAVAILABLE else ActionRejection.NOT_FOUND
    is RemoteException.Conflict -> ActionRejection.CONFLICT
    is RemoteException.Validation -> when (problem?.code) {
        DriverErrorCodes.INVALID_DOCUMENT_SIZE -> ActionRejection.FILE_TOO_LARGE
        DriverErrorCodes.DOCUMENT_REJECTED -> ActionRejection.UNSUPPORTED_FILE
        DriverErrorCodes.INVALID_DOCUMENT -> ActionRejection.PHOTO_REQUIRED
        DriverErrorCodes.INVALID_SLOT -> ActionRejection.PLAN_CHANGED
        else -> ActionRejection.FAILED
    }
    is RemoteException.PayloadTooLarge -> ActionRejection.FILE_TOO_LARGE
    is RemoteException.RateLimited -> ActionRejection.RATE_LIMITED
    is RemoteException.ServerUnavailable -> ActionRejection.SERVICE_UNAVAILABLE
    is RemoteException.NotSupported -> ActionRejection.NOT_SUPPORTED
    is RemoteException.ServerError, is RemoteException.Rejected, is RemoteException.InvalidResponse -> ActionRejection.FAILED
}
