package com.dynamictransport.dynamicone.core.alarm

import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.domain.repository.DriverTimesRepository
import com.dynamictransport.dynamicone.domain.repository.RestAlarmSettingsRepository
import com.dynamictransport.dynamicone.domain.repository.ShiftRepository
import com.dynamictransport.dynamicone.domain.rules.RestAlarmMode
import com.dynamictransport.dynamicone.domain.rules.RestAlarmOffReason
import com.dynamictransport.dynamicone.domain.rules.RestAlarmPlan
import com.dynamictransport.dynamicone.domain.rules.RestAlarmRules
import com.dynamictransport.dynamicone.domain.rules.RestAlarmSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** The alarm that rings right now; the alarm screen shows it. */
data class RingingAlarm(val rest: RestWindow, val test: Boolean)

/** What the driver screens show about the wake-up alarm. */
data class RestAlarmStatus(
    val plan: RestAlarmPlan,
    val mode: RestAlarmMode,
    val lead: Duration,
    val permissions: RestAlarmPermissionState,
)

/**
 * Keeps the phone's wake-up alarm in line with the tachograph summary, the shift and the driver's settings (docs/16 §1).
 * The alarm is set on the phone (AlarmManager) so it rings without network; it is reset whenever one of them changes,
 * after a restart and when the driver snoozes, stops or switches it off for one rest.
 */
@Singleton
class RestAlarmController @Inject constructor(
    private val driverTimes: DriverTimesRepository,
    private val shifts: ShiftRepository,
    private val settings: RestAlarmSettingsRepository,
    private val scheduler: RestAlarmScheduler,
    private val notifier: RestAlarmNotifier,
    private val permissionReader: RestAlarmPermissions,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val permissions = MutableStateFlow(permissionReader.read())
    private val ringingAlarm = MutableStateFlow<RingingAlarm?>(null)
    private var armed: RestAlarmPlan.Scheduled? = null
    private var started = false

    val permissionState: StateFlow<RestAlarmPermissionState> = permissions.asStateFlow()

    /** The alarm that rings in this process; null once it was answered or silenced (the alarm screen closes). */
    val ringing: StateFlow<RingingAlarm?> = ringingAlarm.asStateFlow()

    val status: Flow<RestAlarmStatus> = combine(
        driverTimes.observeDriverTimes(),
        shifts.observeShift(),
        settings.settings,
        permissions,
    ) { times, shift, current, granted ->
        RestAlarmStatus(RestAlarmRules.plan(times, shift, current, clock.instant()), current.mode, current.lead, granted)
    }

    /** Follows data changes for the life of the process. */
    fun start() {
        if (started) return
        started = true
        notifier.ensureChannels()
        scope.launch {
            combine(driverTimes.observeDriverTimes(), shifts.observeShift(), settings.settings, ::Triple).collectLatest { (times, shift, current) ->
                mutex.withLock { apply(RestAlarmRules.plan(times, shift, current, clock.instant()), force = false) }
            }
        }
    }

    /** From stored data only (restart, time change, background read). [force] sets the alarm again even if unchanged. */
    suspend fun reconcile(force: Boolean = false) = mutex.withLock {
        apply(RestAlarmRules.plan(driverTimes.current(), shifts.currentShift(), settings.current(), clock.instant()), force)
    }

    /** The phone's alarm went off. It rings only if the rest still calls for it. */
    suspend fun onAlarmFired(rest: RestWindow, snooze: Boolean) = mutex.withLock {
        val current = settings.current()
        val ring = RestAlarmRules.shouldRing(rest, driverTimes.current(), shifts.currentShift(), current, snooze)
        armed = null
        if (ring) ringLocked(rest, current.mode)
        apply(RestAlarmRules.plan(driverTimes.current(), shifts.currentShift(), settings.current(), clock.instant()), force = false)
    }

    /** Rings again in [RestAlarmRules.SNOOZE]. */
    suspend fun snooze(rest: RestWindow) = mutex.withLock {
        silence()
        settings.update { it.copy(rung = rest, snoozedUntil = clock.instant().plus(RestAlarmRules.SNOOZE)) }
        apply(RestAlarmRules.plan(driverTimes.current(), shifts.currentShift(), settings.current(), clock.instant()), force = true)
    }

    /** The driver answered the alarm (stop, or opened the shift start). */
    suspend fun stop(rest: RestWindow?) = mutex.withLock {
        silence()
        settings.update { it.copy(rung = rest ?: it.rung, snoozedUntil = null) }
        apply(RestAlarmRules.plan(driverTimes.current(), shifts.currentShift(), settings.current(), clock.instant()), force = false)
    }

    /** No alarm for this rest (e.g. weekly rest at home); [restore] undoes it. */
    suspend fun dismiss(rest: RestWindow) = mutex.withLock {
        silence()
        settings.update { it.copy(dismissed = rest, snoozedUntil = null) }
        apply(RestAlarmRules.plan(driverTimes.current(), shifts.currentShift(), settings.current(), clock.instant()), force = false)
    }

    suspend fun restore() = mutex.withLock {
        settings.update { it.copy(dismissed = null) }
        apply(RestAlarmRules.plan(driverTimes.current(), shifts.currentShift(), settings.current(), clock.instant()), force = false)
    }

    suspend fun setMode(mode: RestAlarmMode) = settings.update { it.copy(mode = mode) }

    suspend fun setLead(lead: Duration) {
        if (lead in RestAlarmSettings.LEAD_OPTIONS) settings.update { it.copy(lead = lead) }
    }

    /** The driver hears and sees the alarm as it will ring. */
    suspend fun ringTest() {
        notifier.ensureChannels()
        val now = clock.instant()
        notifier.ring(RestWindow(now, now), settings.current().mode, test = true)
        ringingAlarm.value = RingingAlarm(RestWindow(now, now), test = true)
    }

    fun stopTest() = silence()

    /** The permission screens may have changed something. */
    fun refreshPermissions() {
        val current = permissionReader.read()
        if (current == permissions.value) return
        permissions.value = current
        // An exact alarm can be set once it is allowed (and falls back to an inexact one when it is withdrawn).
        scope.launch { reconcile(force = true) }
    }

    /** Stops the sound and closes the alarm screen. */
    private fun silence() {
        notifier.cancel()
        ringingAlarm.value = null
    }

    private suspend fun ringLocked(rest: RestWindow, mode: RestAlarmMode) {
        settings.update { it.copy(rung = rest, snoozedUntil = null) }
        notifier.ring(rest, mode, test = false)
        ringingAlarm.value = RingingAlarm(rest, test = false)
    }

    private suspend fun apply(plan: RestAlarmPlan, force: Boolean) {
        when (plan) {
            is RestAlarmPlan.Scheduled -> if (force || plan != armed) {
                scheduler.schedule(plan.triggerAt, plan.rest, plan.snoozed)
                armed = plan
            }
            is RestAlarmPlan.RingNow -> {
                scheduler.cancel()
                armed = null
                ringLocked(plan.rest, settings.current().mode)
            }
            is RestAlarmPlan.Off -> {
                // Also an alarm set before this process started (it would not ring, but it would wake the phone).
                scheduler.cancel()
                armed = null
                // The driver is up or switched it off: a ringing alarm stops too.
                if (plan.reason == RestAlarmOffReason.SHIFT_STARTED || plan.reason == RestAlarmOffReason.DISMISSED) silence()
            }
        }
    }
}
