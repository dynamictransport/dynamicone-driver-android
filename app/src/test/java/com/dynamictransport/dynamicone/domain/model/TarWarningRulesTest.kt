package com.dynamictransport.dynamicone.domain.model

import com.dynamictransport.dynamicone.testing.testStop
import com.dynamictransport.dynamicone.testing.testTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TarWarningRulesTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")
    private val end = Instant.parse("2026-10-22T10:00:00Z")
    private val tar = TerminalAppointment("001234", end.minusSeconds(7_200), end, amsterdam)

    @Test
    fun `four warnings before the end, only the ones still ahead`() {
        val trip = testTrip(state = TripState.STARTED, stops = listOf(testStop("s1", 1, StopActivity.PICKUP, "ECT").copy(tar = tar)))

        val all = TarWarningRules.plan(listOf(trip), end.minusSeconds(3 * 3_600))
        assertEquals(listOf(60L, 30L, 15L, 5L), all.map { it.leadMinutes })
        assertEquals(end.minusSeconds(3_600), all[0].triggerAt)
        assertEquals("trip-1/s1/60", all[0].key)
        assertEquals("001234", all[0].code)

        // Twenty minutes left: only the 15- and 5-minute warnings; the earlier ones are not rung late.
        assertEquals(listOf(15L, 5L), TarWarningRules.plan(listOf(trip), end.minusSeconds(20 * 60)).map { it.leadMinutes })
        // Exactly at a lead the warning is due, not ahead.
        assertEquals(listOf(5L), TarWarningRules.plan(listOf(trip), end.minusSeconds(15 * 60)).map { it.leadMinutes })
        assertTrue(TarWarningRules.plan(listOf(trip), end).isEmpty())
    }

    @Test
    fun `completed or skipped stops, finished trips and stops without a TAR give no warning`() {
        val early = end.minusSeconds(5 * 3_600)
        val done = testStop("s1", 1, StopActivity.PICKUP, "ECT", status = StopStatus.COMPLETED).copy(tar = tar)
        val skipped = testStop("s2", 2, StopActivity.PICKUP, "ECT", status = StopStatus.SKIPPED).copy(tar = tar)
        val plain = testStop("s3", 3, StopActivity.DELIVER, "Venlo")
        assertTrue(TarWarningRules.plan(listOf(testTrip(state = TripState.STARTED, stops = listOf(done, skipped, plain))), early).isEmpty())

        val open = testStop("s4", 4, StopActivity.PICKUP, "ECT", status = StopStatus.ARRIVED).copy(tar = tar)
        assertTrue(TarWarningRules.plan(listOf(testTrip(state = TripState.FINISHED, stops = listOf(open))), early).isEmpty())
        assertEquals(4, TarWarningRules.plan(listOf(testTrip(state = TripState.STARTED, stops = listOf(open))), early).size)
    }

    @Test
    fun `two stops at the same terminal keep their own warnings`() {
        val second = tar.copy(code = "009876", startAt = end.plusSeconds(3_600), endAt = end.plusSeconds(7_200))
        val trip = testTrip(
            state = TripState.STARTED,
            stops = listOf(
                testStop("s1", 1, StopActivity.PICKUP, "ECT").copy(tar = tar),
                testStop("s2", 2, StopActivity.PICKUP, "ECT").copy(tar = second),
            ),
        )
        val warnings = TarWarningRules.plan(listOf(trip), end.minusSeconds(5 * 3_600))
        assertEquals(8, warnings.size)
        assertEquals(8, warnings.map { it.key }.toSet().size)
        assertEquals(setOf("001234", "009876"), warnings.map { it.code }.toSet())
    }
}
