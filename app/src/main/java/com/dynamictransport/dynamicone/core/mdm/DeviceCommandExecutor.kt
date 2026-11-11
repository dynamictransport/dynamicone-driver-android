package com.dynamictransport.dynamicone.core.mdm

import com.dynamictransport.dynamicone.core.common.AppLogger
import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.DeviceCommand
import com.dynamictransport.dynamicone.domain.model.DeviceCommandResult
import com.dynamictransport.dynamicone.domain.model.DeviceCommandTypes
import com.dynamictransport.dynamicone.domain.model.RemoteSessionRequest
import com.dynamictransport.dynamicone.domain.repository.DeviceManagementGateway
import kotlinx.coroutines.CancellationException
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/** Stores a command's result and sends it to the server (docs/29 §3.3). */
@Singleton
class DeviceCommandReporter @Inject constructor(
    private val ledger: DeviceCommandLedger,
    private val gateway: DeviceManagementGateway,
) {
    /** The result is kept first: when the post fails, the next check-in returns the command and it is posted again. */
    suspend fun report(commandId: String, result: DeviceCommandResult): Boolean {
        val trimmed = result.copy(detail = result.detail?.take(DeviceCommandResult.MAX_DETAIL_LENGTH))
        ledger.finish(commandId, trimmed)
        return post(commandId, trimmed)
    }

    suspend fun post(commandId: String, result: DeviceCommandResult): Boolean = try {
        gateway.postCommandResult(commandId, result)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: RemoteException) {
        false
    }
}

/** How the driver's phone answered a `remoteSession.request`. */
enum class RemoteRequestOutcome {
    /** The consent screen is up; the command's result follows the driver's answer. */
    PROMPTED,

    /** Another request or session is running: this one was declined. */
    BUSY,

    /** The request was already over when it arrived: declined. */
    EXPIRED,
}

/** The remote session side of the commands (docs/29 §4), implemented by `core/remote`. */
interface RemoteSessionCommands {
    suspend fun request(request: RemoteSessionRequest): RemoteRequestOutcome

    /** The office closed [sessionId]: the stream stops if it is this session. */
    suspend fun end(sessionId: String)

    /** A request whose consent screen was lost with the process: the server hears a decline. */
    suspend fun declineUnanswered(sessionId: String)
}

/**
 * Runs the commands of a check-in (docs/29 §3.4). Every command id runs once: the id is stored before the command
 * starts, a command that comes back is only answered again with the stored result.
 */
@Singleton
class DeviceCommandExecutor @Inject constructor(
    private val ledger: DeviceCommandLedger,
    private val reporter: DeviceCommandReporter,
    private val policy: DevicePolicyController,
    private val alerts: DeviceAlerts,
    private val remoteSessions: RemoteSessionCommands,
    private val logger: AppLogger,
) {
    /** Commands waiting for the driver in this process (the remote session consent screen). */
    private val waiting: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private sealed interface Outcome {
        data class Done(val result: DeviceCommandResult) : Outcome

        /** The result is reported later, by whoever finishes the command. */
        data object Deferred : Outcome

        /** Reported and executed by the handler itself. */
        data object Reported : Outcome
    }

    /** @return how many commands were finished by this call; the caller checks in again when there were any. */
    suspend fun execute(commands: List<DeviceCommand>): Int {
        var finished = 0
        for (command in commands) {
            val handled = ledger.find(command.id)
            when {
                handled?.result != null -> reporter.post(command.id, handled.result)
                handled != null -> if (command.id !in waiting) {
                    reporter.report(command.id, interrupted(command))
                    finished++
                }
                else -> {
                    ledger.begin(command.id, command.expiresAt)
                    logger.event("device_command", mapOf("type" to command.type))
                    when (val outcome = run(command)) {
                        is Outcome.Done -> {
                            reporter.report(command.id, outcome.result)
                            finished++
                        }
                        Outcome.Reported -> finished++
                        Outcome.Deferred -> waiting += command.id
                    }
                }
            }
        }
        return finished
    }

    private suspend fun run(command: DeviceCommand): Outcome = try {
        when (command.type) {
            DeviceCommandTypes.SYNC -> Outcome.Done(DeviceCommandResult.succeeded())
            DeviceCommandTypes.RING -> Outcome.Done(ring(command))
            DeviceCommandTypes.MESSAGE -> Outcome.Done(message(command))
            DeviceCommandTypes.LOCK -> Outcome.Done(lock())
            DeviceCommandTypes.REBOOT -> reboot(command)
            DeviceCommandTypes.WIPE -> wipe(command)
            DeviceCommandTypes.REMOTE_SESSION_REQUEST -> remoteSessionRequest(command)
            DeviceCommandTypes.REMOTE_SESSION_END -> Outcome.Done(remoteSessionEnd(command))
            else -> Outcome.Done(DeviceCommandResult.unsupported(DETAIL_UNKNOWN_TYPE))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        // The role the command needs was taken away between the check and the call.
        Outcome.Done(DeviceCommandResult.unsupported(e.javaClass.simpleName))
    } catch (e: RuntimeException) {
        logger.warn("device command ${command.type}", e)
        Outcome.Done(DeviceCommandResult.failed(e.javaClass.simpleName))
    }

    private fun ring(command: DeviceCommand): DeviceCommandResult {
        val seconds = (command.payload["seconds"]?.toDoubleOrNull()?.toInt() ?: DEFAULT_RING_SECONDS).coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS)
        return if (alerts.ring(seconds)) DeviceCommandResult.succeeded() else DeviceCommandResult.failed(DETAIL_NOTIFICATIONS_OFF)
    }

    private fun message(command: DeviceCommand): DeviceCommandResult {
        val title = command.payload["title"].orEmpty().trim()
        val body = command.payload["body"].orEmpty().trim()
        if (title.isEmpty() && body.isEmpty()) return DeviceCommandResult.failed(DETAIL_INVALID_PAYLOAD)
        return if (alerts.showMessage(command.id, title, body)) DeviceCommandResult.succeeded() else DeviceCommandResult.failed(DETAIL_NOTIFICATIONS_OFF)
    }

    private fun lock(): DeviceCommandResult {
        if (!policy.isDeviceOwner && !policy.isAdminActive) return DeviceCommandResult.unsupported(DETAIL_NOT_DEVICE_ADMIN)
        policy.lockNow()
        return DeviceCommandResult.succeeded()
    }

    /**
     * The restart ends the process before anything can be posted: `succeeded` is stored first and reaches the server
     * right after the call, or with the first check-in after the restart.
     */
    private suspend fun reboot(command: DeviceCommand): Outcome {
        if (!policy.isDeviceOwner) return Outcome.Done(DeviceCommandResult.unsupported(DETAIL_NOT_DEVICE_OWNER))
        ledger.finish(command.id, DeviceCommandResult.succeeded())
        policy.reboot()
        return Outcome.Done(DeviceCommandResult.succeeded())
    }

    /** docs/29 §3.4: `succeeded` is posted first, then the phone is erased. */
    private suspend fun wipe(command: DeviceCommand): Outcome {
        if (!policy.isDeviceOwner) return Outcome.Done(DeviceCommandResult.unsupported(DETAIL_NOT_DEVICE_OWNER))
        reporter.report(command.id, DeviceCommandResult.succeeded())
        try {
            policy.wipeData()
        } catch (e: RuntimeException) {
            // The phone refused: the office must not believe it was erased.
            logger.warn("device wipe", e)
            reporter.report(command.id, DeviceCommandResult.failed(e.javaClass.simpleName))
        }
        return Outcome.Reported
    }

    private suspend fun remoteSessionRequest(command: DeviceCommand): Outcome {
        val sessionId = command.payload["sessionId"]?.takeIf { it.isNotBlank() }
        val expiresAt = (command.payload["expiresAt"]?.toInstantOrNull() ?: command.expiresAt)
        if (sessionId == null || expiresAt == null) return Outcome.Done(DeviceCommandResult.failed(DETAIL_INVALID_PAYLOAD))
        val request = RemoteSessionRequest(
            commandId = command.id,
            sessionId = sessionId,
            requestedBy = command.payload["requestedBy"]?.trim()?.takeIf { it.isNotEmpty() },
            expiresAt = expiresAt,
        )
        return when (remoteSessions.request(request)) {
            RemoteRequestOutcome.PROMPTED -> Outcome.Deferred
            RemoteRequestOutcome.BUSY -> Outcome.Done(DeviceCommandResult.succeeded(DETAIL_BUSY))
            RemoteRequestOutcome.EXPIRED -> Outcome.Done(DeviceCommandResult.succeeded(DETAIL_TIMEOUT))
        }
    }

    private suspend fun remoteSessionEnd(command: DeviceCommand): DeviceCommandResult {
        val sessionId = command.payload["sessionId"]?.takeIf { it.isNotBlank() } ?: return DeviceCommandResult.failed(DETAIL_INVALID_PAYLOAD)
        remoteSessions.end(sessionId)
        return DeviceCommandResult.succeeded()
    }

    /** The process ended while the command ran: it is not run a second time. */
    private suspend fun interrupted(command: DeviceCommand): DeviceCommandResult {
        if (command.type != DeviceCommandTypes.REMOTE_SESSION_REQUEST) return DeviceCommandResult.failed(DETAIL_INTERRUPTED)
        command.payload["sessionId"]?.takeIf { it.isNotBlank() }?.let { remoteSessions.declineUnanswered(it) }
        return DeviceCommandResult.succeeded(DETAIL_TIMEOUT)
    }

    companion object {
        const val DEFAULT_RING_SECONDS = 60
        const val MIN_RING_SECONDS = 5
        const val MAX_RING_SECONDS = 600

        const val DETAIL_UNKNOWN_TYPE = "unknown_type"
        const val DETAIL_NOT_DEVICE_OWNER = "not_device_owner"
        const val DETAIL_NOT_DEVICE_ADMIN = "not_device_admin"
        const val DETAIL_NOTIFICATIONS_OFF = "notifications_off"
        const val DETAIL_INVALID_PAYLOAD = "invalid_payload"
        const val DETAIL_INTERRUPTED = "interrupted"
        const val DETAIL_BUSY = "busy"
        const val DETAIL_TIMEOUT = "timeout"
        const val DETAIL_DECLINED = "declined"
        const val DETAIL_ACCEPTED = "accepted"
    }
}
