package com.dynamictransport.dynamicone.core.wakeup

import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.core.push.WakeUpCallNotifier
import com.dynamictransport.dynamicone.domain.model.WakeUpCall
import com.dynamictransport.dynamicone.domain.repository.WakeUpCallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rings a wake-up call on this device and stops it (docs/25 §2): the driver's answer, a `stop` push, or the call's
 * deadline. One call rings at a time; a newer call replaces the ringing one. After a restart a call that was
 * ringing rings again while its deadline holds.
 */
@Singleton
class WakeUpCallController @Inject constructor(
    private val repository: WakeUpCallRepository,
    private val notifier: WakeUpCallNotifier,
    private val volume: AlarmVolumeBooster,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val ringingCall = MutableStateFlow<WakeUpCall?>(null)
    private var deadline: Job? = null
    private var started = false

    /** The call that rings in this process; null once it was answered or silenced (the call screen closes). */
    val ringing: StateFlow<WakeUpCall?> = ringingCall.asStateFlow()

    /** Process start: the channel exists, and a call cut short by a restart rings again or is reported as missed. */
    fun start() {
        if (started) return
        started = true
        notifier.ensureChannel()
        scope.launch {
            for (call in repository.ringingCalls()) {
                if (call.isExpiredAt(clock.instant())) expire(call.callId) else ring(call)
            }
        }
    }

    suspend fun ring(call: WakeUpCall) {
        mutex.withLock {
            val remaining = Duration.between(clock.instant(), call.expiresAt)
            if (remaining.isNegative || remaining.isZero) {
                repository.expire(call.callId)
                return
            }
            deadline?.cancel()
            notifier.ring(call)
            volume.raise()
            ringingCall.value = call
            deadline = scope.launch {
                delay(remaining.toMillis())
                expire(call.callId)
            }
        }
        // Best effort: the cockpit sees "ringing" (docs/25 §6).
        scope.launch { repository.reportRinging(call.callId) }
    }

    /** The driver answered on the screen or the notification. */
    suspend fun acknowledge(callId: String) = mutex.withLock {
        repository.acknowledge(callId)
        silenceIf(callId)
    }

    /** A `stop` push: answered on another device or cancelled by the planner. */
    suspend fun stop(callId: String) = mutex.withLock {
        repository.onStopSignal(callId)
        silenceIf(callId)
    }

    private suspend fun expire(callId: String) = mutex.withLock {
        repository.expire(callId)
        silenceIf(callId)
    }

    private fun silenceIf(callId: String) {
        if (ringingCall.value?.callId != callId) return
        deadline?.cancel()
        deadline = null
        notifier.cancel()
        volume.restore()
        ringingCall.value = null
    }
}
