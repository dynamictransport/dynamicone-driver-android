package com.dynamictransport.dynamicone.core.tar

import com.dynamictransport.dynamicone.core.common.ApplicationScope
import com.dynamictransport.dynamicone.core.database.TarWarningDao
import com.dynamictransport.dynamicone.core.database.TarWarningEntity
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.StopStatus
import com.dynamictransport.dynamicone.domain.model.TarWarning
import com.dynamictransport.dynamicone.domain.model.TarWarningRules
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.repository.ServerClock
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Sets one device alarm per warning; [cancel] of an unknown key is a no-op. */
interface TarWarningScheduler {
    fun schedule(key: String, triggerAt: Instant)

    fun cancel(key: String)
}

/** Sound and notification of one warning; a later warning of the same stop replaces the earlier one. */
interface TarWarningNotifier {
    fun ensureChannel()

    fun notify(warning: TarWarning)

    fun cancelAll()
}

/**
 * Warns the driver 60, 30, 15 and 5 minutes before a TAR window ends (docs/26 §6). The alarms live on the device
 * (AlarmManager) so they ring without network; they follow every trip read: a moved window re-arms them, a removed
 * TAR, a completed stop or a trip no longer assigned cancels them. When one goes off the trip is checked again
 * before anything rings.
 */
@Singleton
class TarWarningController @Inject constructor(
    private val trips: TripRepository,
    private val dao: TarWarningDao,
    private val scheduler: TarWarningScheduler,
    private val notifier: TarWarningNotifier,
    private val serverClock: ServerClock,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var started = false

    /** Follows the trips for the life of the process; stored alarms survive until the data says otherwise. */
    fun start() {
        if (started) return
        started = true
        notifier.ensureChannel()
        scope.launch {
            combine(trips.observeTrips(), serverClock.offset, ::Pair).collectLatest { (current, offset) ->
                mutex.withLock { reconcileLocked(current, offset) }
            }
        }
    }

    /** The trips known right now against the stored warnings (what [start] does on every change). */
    suspend fun reconcile() = mutex.withLock { reconcileLocked(trips.currentTrips(), serverClock.offset.value) }

    /** Device data only (restart, time change): every warning still ahead is set again. */
    suspend fun rearm() = mutex.withLock {
        val now = clock.instant()
        for (row in dao.all()) {
            if (row.firedAt != null) continue
            if (row.triggerAt.isAfter(now)) scheduler.schedule(row.key, row.triggerAt) else scheduler.cancel(row.key)
        }
    }

    /** The session ended: nothing of it may ring later. */
    suspend fun clear() = mutex.withLock {
        for (row in dao.all()) scheduler.cancel(row.key)
        dao.deleteAll()
        notifier.cancelAll()
    }

    /**
     * The device alarm went off. The warning rings only if the stop still has this TAR window and is still open;
     * a trip this process does not know yet is read first, and rings from the stored data when the server cannot
     * be reached.
     */
    suspend fun onFired(key: String) {
        val row = mutex.withLock { dao.get(key)?.takeIf { it.firedAt == null }?.also { dao.markFired(key, clock.instant()) } } ?: return
        var trip = trips.currentTrips().firstOrNull { it.id == row.tripId }
        var serverUnreachable = false
        if (trip == null || trip.stops.isEmpty()) {
            val result = trips.refreshTrip(row.tripId)
            trip = trips.currentTrips().firstOrNull { it.id == row.tripId }
            serverUnreachable = result != RefreshResult.SUCCESS
        }
        val stillValid = when {
            trip == null -> serverUnreachable
            trip.state.isTerminal -> false
            trip.stops.isEmpty() -> serverUnreachable
            else -> trip.stops.firstOrNull { it.id == row.stopId }?.let { stop ->
                stop.status != StopStatus.COMPLETED && stop.status != StopStatus.SKIPPED &&
                    stop.tar?.code == row.code && stop.tar.endAt == row.endAt
            } ?: false
        }
        // Never after the window: the expired state is on the trip screen, a late warning would mislead.
        if (!stillValid || !serverClock.now().isBefore(row.endAt)) return
        notifier.notify(row.toWarning())
    }

    private suspend fun reconcileLocked(current: List<Trip>, offset: Duration) {
        val desired = TarWarningRules.plan(current, clock.instant().plus(offset)).associateBy { it.key }
        val listed = current.associateBy { it.id }
        val rows = dao.all().associateBy { it.key }
        for (row in rows.values) {
            val wanted = desired[row.key]
            val trip = listed[row.tripId]
            // A trip known only from the list (no stops read yet) and an empty list say nothing about this warning.
            val undecided = if (trip == null) current.isEmpty() else trip.stops.isEmpty() && !trip.state.isTerminal
            when {
                wanted != null -> Unit
                undecided -> Unit
                else -> {
                    scheduler.cancel(row.key)
                    dao.delete(row.key)
                }
            }
        }
        for (warning in desired.values) {
            val row = rows[warning.key]
            val triggerAt = warning.triggerAt.minus(offset)
            val sameWindow = row != null && row.endAt == warning.endAt && row.code == warning.code
            if (sameWindow && row.firedAt != null) continue
            if (sameWindow && row.triggerAt == triggerAt) continue
            dao.upsert(warning.toEntity(triggerAt, clock.instant()))
            scheduler.schedule(warning.key, triggerAt)
        }
    }

    private fun TarWarning.toEntity(triggerAt: Instant, now: Instant) = TarWarningEntity(
        key = key,
        tripId = tripId,
        tripNo = tripNo,
        stopId = stopId,
        stopName = stopName,
        code = code,
        endAt = endAt,
        timeZone = timeZone.id,
        leadMinutes = leadMinutes,
        triggerAt = triggerAt,
        firedAt = null,
        updatedAt = now,
    )

    private fun TarWarningEntity.toWarning() = TarWarning(
        tripId = tripId,
        tripNo = tripNo,
        stopId = stopId,
        stopName = stopName,
        code = code,
        endAt = endAt,
        timeZone = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.of("UTC")),
        leadMinutes = leadMinutes,
    )
}
