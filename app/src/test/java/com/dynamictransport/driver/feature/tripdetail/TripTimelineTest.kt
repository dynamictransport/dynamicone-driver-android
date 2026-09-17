package com.dynamictransport.driver.feature.tripdetail

import com.dynamictransport.driver.domain.model.SyncState
import com.dynamictransport.driver.domain.model.TripActionRecord
import com.dynamictransport.driver.domain.model.TripActionType
import com.dynamictransport.driver.domain.model.TripState
import com.dynamictransport.driver.testing.TEST_NOW
import com.dynamictransport.driver.testing.testTrip
import org.junit.Assert.assertEquals
import org.junit.Test

class TripTimelineTest {

    @Test
    fun `steps are done up to the current state and the next one is highlighted`() {
        val trip = testTrip(state = TripState.AT_PICKUP)
        val history = listOf(
            TripActionRecord(TripActionType.ACCEPT_TRIP, TEST_NOW, SyncState.SYNCED),
            TripActionRecord(TripActionType.START_TRIP, TEST_NOW.plusSeconds(60), SyncState.PENDING),
        )
        val steps = TripTimeline.build(trip, history)

        assertEquals(
            listOf(StepStatus.DONE, StepStatus.DONE, StepStatus.DONE, StepStatus.CURRENT, StepStatus.UPCOMING, StepStatus.UPCOMING),
            steps.map { it.status },
        )
        assertEquals(TEST_NOW.plusSeconds(60), steps[2].occurredAt)
        assertEquals(SyncState.PENDING, steps[2].syncState)
    }

    @Test
    fun `cancelled trips have no progress timeline`() {
        assertEquals(emptyList<TimelineStep>(), TripTimeline.build(testTrip(state = TripState.CANCELLED), emptyList()))
    }
}
