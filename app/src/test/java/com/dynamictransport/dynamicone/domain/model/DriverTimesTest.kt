package com.dynamictransport.dynamicone.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Tachograph summary age rules (docs/16 §3): 15 min marks last known values, 24 h hides clock times. */
class DriverTimesTest {

    private val now: Instant = Instant.parse("2026-10-26T10:00:00Z")

    private fun times(reference: Instant?) = DriverTimes(DriverTimesAvailability.AVAILABLE, referenceTime = reference)

    @Test
    fun freshSnapshotIsNeitherStaleNorOutdated() {
        val times = times(now.minus(Duration.ofMinutes(10)))

        assertFalse(times.isStale(now))
        assertFalse(times.isOutdated(now))
    }

    @Test
    fun snapshotOlderThanFifteenMinutesIsStaleButNotOutdated() {
        val times = times(now.minus(Duration.ofHours(23)))

        assertTrue(times.isStale(now))
        assertFalse(times.isOutdated(now))
    }

    @Test
    fun exactlyOneDayOldIsNotYetOutdated() {
        assertFalse(times(now.minus(DriverTimes.OUTDATED_AFTER)).isOutdated(now))
    }

    @Test
    fun olderThanOneDayIsOutdated() {
        assertTrue(times(now.minus(DriverTimes.OUTDATED_AFTER).minusSeconds(1)).isOutdated(now))
        assertTrue(times(Instant.parse("2026-08-21T13:30:00Z")).isOutdated(now))
    }

    @Test
    fun unknownReferenceTimeIsOutdated() {
        assertTrue(times(null).isStale(now))
        assertTrue(times(null).isOutdated(now))
    }
}
