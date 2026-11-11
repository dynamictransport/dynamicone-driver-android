package com.dynamictransport.dynamicone.core.remote

import android.content.Intent
import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.core.mdm.DeviceCheckInScheduler
import com.dynamictransport.dynamicone.core.mdm.DeviceCommandExecutor
import com.dynamictransport.dynamicone.core.mdm.DeviceCommandReporter
import com.dynamictransport.dynamicone.core.mdm.RemoteRequestOutcome
import com.dynamictransport.dynamicone.core.mdm.RemoteSessionCommands
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.DeviceCommandResult
import com.dynamictransport.dynamicone.domain.model.RemoteSessionRequest
import com.dynamictransport.dynamicone.domain.repository.DeviceManagementGateway
import com.dynamictransport.dynamicone.domain.repository.ServerClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** Starts and stops the screen stream; the Android part is the foreground service. */
interface ScreenStreamLauncher {
    /** [resultCode] and [permission] are the driver's answer to the system's screen capture dialog. */
    fun start(sessionId: String, resultCode: Int, permission: Intent)

    fun stop()
}

/** The consent prompt and the notice of a running session. */
interface RemoteSessionNotifier {
    fun ensureChannels()

    /** Full screen over the lock screen, like a wake-up call, until the driver answers or the request expires. */
    fun showRequest(request: RemoteSessionRequest, remaining: Duration)

    fun cancelRequest()
}

/**
 * A remote session from the office's request to its end (docs/29 §4). Nothing is shown to the office before the
 * driver accepted on this phone; one request or session at a time.
 */
@Singleton
class RemoteSessionController @Inject constructor(
    private val gateway: DeviceManagementGateway,
    private val reporter: DeviceCommandReporter,
    private val notifier: RemoteSessionNotifier,
    private val streams: ScreenStreamLauncher,
    private val scheduler: DeviceCheckInScheduler,
    private val clock: ServerClock,
    @param:ApplicationScope private val scope: CoroutineScope,
) : RemoteSessionCommands {

    private val lock = Any()
    private val _pending = MutableStateFlow<RemoteSessionRequest?>(null)
    private val _activeSessionId = MutableStateFlow<String?>(null)
    private var timeout: Job? = null

    /** The request waiting for the driver's answer. */
    val pending: StateFlow<RemoteSessionRequest?> = _pending

    /** The session whose screen the office sees now. */
    val activeSessionId: StateFlow<String?> = _activeSessionId

    override suspend fun request(request: RemoteSessionRequest): RemoteRequestOutcome {
        val remaining = Duration.between(clock.now(), request.expiresAt)
        if (remaining.isNegative || remaining.isZero) {
            declineOnServer(request.sessionId)
            return RemoteRequestOutcome.EXPIRED
        }
        val prompted = synchronized(lock) {
            if (_pending.value != null || _activeSessionId.value != null) {
                false
            } else {
                _pending.value = request
                timeout = scope.launch {
                    delay(remaining.toMillis())
                    // In a coroutine of its own: ending the wait cancels this one.
                    scope.launch { declineAndReport(request.sessionId, DeviceCommandExecutor.DETAIL_TIMEOUT) }
                }
                true
            }
        }
        if (!prompted) {
            declineOnServer(request.sessionId)
            return RemoteRequestOutcome.BUSY
        }
        notifier.showRequest(request, remaining)
        return RemoteRequestOutcome.PROMPTED
    }

    /** The driver tapped Decline, or closed the system's screen capture dialog. */
    suspend fun decline(sessionId: String) {
        declineAndReport(sessionId, DeviceCommandExecutor.DETAIL_DECLINED)
    }

    /**
     * The driver accepted and allowed the screen capture: the stream starts.
     * @return false when the request is no longer waiting (it expired meanwhile).
     */
    fun accept(sessionId: String, resultCode: Int, permission: Intent): Boolean {
        val request = take(sessionId) ?: return false
        synchronized(lock) { _activeSessionId.value = sessionId }
        streams.start(sessionId, resultCode, permission)
        scope.launch {
            reporter.report(request.commandId, DeviceCommandResult.succeeded(DeviceCommandExecutor.DETAIL_ACCEPTED))
            scheduler.requestCheckIn()
        }
        return true
    }

    override suspend fun end(sessionId: String) {
        // Withdrawn before the driver answered: the prompt goes away, there is nothing to decline.
        take(sessionId)?.let { request ->
            reporter.report(request.commandId, DeviceCommandResult.succeeded(DeviceCommandExecutor.DETAIL_TIMEOUT))
        }
        if (_activeSessionId.value == sessionId) streams.stop()
    }

    override suspend fun declineUnanswered(sessionId: String) = declineOnServer(sessionId)

    /** The stream is over, whoever ended it. */
    fun onStreamEnded(sessionId: String) {
        synchronized(lock) {
            if (_activeSessionId.value == sessionId) _activeSessionId.value = null
        }
    }

    private suspend fun declineAndReport(sessionId: String, detail: String) {
        val request = take(sessionId) ?: return
        // docs/29 §4 step 3: the decline first, then the command's result.
        declineOnServer(sessionId)
        reporter.report(request.commandId, DeviceCommandResult.succeeded(detail))
        scheduler.requestCheckIn()
    }

    /** Ends the wait for [sessionId]; null when it is not the pending request any more. */
    private fun take(sessionId: String): RemoteSessionRequest? {
        val request = synchronized(lock) {
            val current = _pending.value?.takeIf { it.sessionId == sessionId } ?: return null
            _pending.value = null
            timeout?.cancel()
            timeout = null
            current
        }
        notifier.cancelRequest()
        return request
    }

    private suspend fun declineOnServer(sessionId: String) {
        try {
            gateway.declineRemoteSession(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RemoteException) {
            // The request expires on the server by itself after 90 seconds.
        }
    }
}
