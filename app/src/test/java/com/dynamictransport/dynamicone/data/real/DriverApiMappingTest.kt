package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.DriverAvailableActionDto
import com.dynamictransport.dynamicone.core.network.DriverDocumentSlotDto
import com.dynamictransport.dynamicone.core.network.DriverExecutionResponseDto
import com.dynamictransport.dynamicone.core.network.DriverLocationDto
import com.dynamictransport.dynamicone.core.network.DriverMessageDto
import com.dynamictransport.dynamicone.core.network.DriverPayrollResponseDto
import com.dynamictransport.dynamicone.core.network.DriverProfileResponseDto
import com.dynamictransport.dynamicone.core.network.DriverStatusNames
import com.dynamictransport.dynamicone.core.network.DriverStatusResponseDto
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.DocumentSlotState
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import com.dynamictransport.dynamicone.domain.model.StopActivity
import com.dynamictransport.dynamicone.domain.model.StopStatus
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.YearMonth

class DriverApiMappingTest {

    @Test
    fun `confirmed assignment can only be accepted`() {
        val derived = DriverApiMapping.deriveState("Confirmed", "Assigned", emptySet())
        assertEquals(TripState.ASSIGNED, derived.state)
        assertEquals(setOf(TripActionType.ACCEPT_TRIP), derived.allowedActions)
        assertEquals(derived, DriverApiMapping.baseState("Confirmed", "Assigned"))
    }

    @Test
    fun `trip action trips get no local step from their status`() {
        assertEquals(DriverApiMapping.DerivedState(TripState.ACCEPTED, emptySet()), DriverApiMapping.baseState("Active", "Assigned"))
        assertEquals(DriverApiMapping.DerivedState(TripState.STARTED, emptySet()), DriverApiMapping.baseState("Active", "InProgress"))
        assertEquals(TripState.FINISHED, DriverApiMapping.baseState("Completed", "Completed").state)
        assertEquals(TripState.CANCELLED, DriverApiMapping.baseState("Active", "Cancelled").state)
        assertEquals(TripState.UNKNOWN, DriverApiMapping.baseState("Proposed", "Planned").state)
    }

    @Test
    fun `legacy active assignment follows the backend status order`() {
        val progression = listOf(
            emptySet<String>() to (TripState.ACCEPTED to TripActionType.START_TRIP),
            setOf("Departed") to (TripState.STARTED to TripActionType.ARRIVE_PICKUP),
            setOf("Departed", "ArrivedAtLoading") to (TripState.AT_PICKUP to TripActionType.COMPLETE_PICKUP),
            setOf("Departed", "ArrivedAtLoading", "LoadingCompleted") to (TripState.EN_ROUTE_DELIVERY to TripActionType.ARRIVE_DELIVERY),
            setOf("Departed", "ArrivedAtLoading", "LoadingCompleted", "ArrivedAtDelivery") to
                (TripState.AT_DELIVERY to TripActionType.COMPLETE_DELIVERY),
        )
        progression.forEach { (reported, expected) ->
            val derived = DriverApiMapping.deriveState("Active", "InProgress", reported)
            assertEquals(expected.first, derived.state)
            assertEquals(setOf(expected.second, TripActionType.START_BREAK), derived.allowedActions)
        }

        val delivered = DriverApiMapping.deriveState("Active", "InProgress", DriverStatusNames.PROGRESSION.toSet())
        assertEquals(TripState.DELIVERY_COMPLETED, delivered.state)
        assertEquals(setOf(TripActionType.START_BREAK), delivered.allowedActions)

        // Breaks and problem reports never move the trip forward.
        val withReports = DriverApiMapping.deriveState("Active", "InProgress", setOf("Departed", "Resting", "ProblemReported"))
        assertEquals(TripState.STARTED, withReports.state)
        assertTrue(DriverApiMapping.deriveState("Active", "Planned", emptySet()).allowedActions.isEmpty())
    }

    @Test
    fun `status names map both ways with exact casing`() {
        DriverStatusNames.PROGRESSION.forEach { name ->
            assertEquals(name, DriverApiMapping.statusForAction(DriverApiMapping.actionForStatus(name)!!))
        }
        assertEquals("Resting", DriverApiMapping.statusForAction(TripActionType.START_BREAK))
        assertNull(DriverApiMapping.statusForAction(TripActionType.REJECT_TRIP))
        assertNull(DriverApiMapping.actionForStatus("departed"))
    }

    @Test
    fun `history uses the server report time and skips unknown statuses`() {
        val history = DriverApiMapping.history(
            listOf(
                DriverStatusResponseDto("2", "ArrivedAtLoading", null, "2026-09-12T08:41:00Z"),
                DriverStatusResponseDto("3", "ProblemReported", "[DELAY]", "2026-09-12T08:50:00Z"),
                DriverStatusResponseDto("1", "Departed", null, "2026-09-12T06:02:00+00:00"),
            ),
        )
        assertEquals(listOf(TripActionType.START_TRIP, TripActionType.ARRIVE_PICKUP), history.map { it.action })
        assertEquals(Instant.parse("2026-09-12T06:02:00Z"), history.first().occurredAt)
    }

    @Test
    fun `execution keeps known options unchanged and needs its version`() {
        val execution = DriverApiMapping.execution(
            "t1",
            DriverExecutionResponseDto(
                version = "A1B2",
                status = "InProgress",
                workflow = "TripActions",
                availableActions = listOf(
                    DriverAvailableActionDto("CompleteStop", " I picked up the empty container ", "s1", "c1"),
                    DriverAvailableActionDto("Teleport", "Unknown", null, null),
                    DriverAvailableActionDto("Rest", "Report a break", null, null),
                ),
            ),
        )
        assertEquals(TripWorkflow.TRIP_ACTIONS, execution.workflow)
        assertEquals("A1B2", execution.version)
        assertEquals(listOf(ExecutionActionCode.COMPLETE_STOP, ExecutionActionCode.REST), execution.availableActions.map { it.code })
        assertEquals("I picked up the empty container", execution.availableActions[0].label)
        assertEquals("s1" to "c1", execution.availableActions[0].stopId to execution.availableActions[0].containerId)

        val legacy = DriverApiMapping.execution(
            "t1",
            DriverExecutionResponseDto(version = "x", workflow = "LegacyStatuses", availableActions = listOf(DriverAvailableActionDto("Rest"))),
        )
        assertEquals(TripWorkflow.LEGACY_STATUSES, legacy.workflow)
        assertTrue(legacy.availableActions.isEmpty())
        assertEquals(TripWorkflow.UNRESOLVED, DriverApiMapping.execution("t1", DriverExecutionResponseDto(version = "x", workflow = "Robots")).workflow)
        assertTrue(runCatching { DriverApiMapping.execution("t1", DriverExecutionResponseDto(version = " ")) }.exceptionOrNull() is RemoteException.InvalidResponse)
    }

    @Test
    fun `missing, partial or out of range coordinates stay missing`() {
        assertEquals(GeoPoint(51.9, 4.1), DriverApiMapping.location(DriverLocationDto("s1", "Rotterdam", 51.9, 4.1, "Available"))?.coordinates)
        assertNull(DriverApiMapping.location(DriverLocationDto("s1", "Rotterdam", null, null, "Missing"))?.coordinates)
        assertNull(DriverApiMapping.location(DriverLocationDto("s1", "Rotterdam", 51.9, null, "Available"))?.coordinates)
        assertNull(DriverApiMapping.location(DriverLocationDto("s1", "Rotterdam", 51.9, 4.1, "Missing"))?.coordinates)
        assertNull(DriverApiMapping.location(DriverLocationDto("s1", "Nowhere", 91.0, 4.1, "Available"))?.coordinates)
        assertNull(DriverApiMapping.location(null))
        assertEquals("Rotterdam", DriverApiMapping.location(DriverLocationDto("s1", "Rotterdam", null, null, "Missing"))?.name)
    }

    @Test
    fun `document categories and slot states are mapped exactly, A1 is the customs type`() {
        assertEquals(DocumentType.CUSTOMS, DriverApiMapping.documentType("Customs"))
        assertNull(DriverApiMapping.documentType("A1"))
        assertEquals(DocumentType.CONTAINER_PHOTO, DriverApiMapping.documentType("Container"))
        assertEquals(DocumentType.SEAL_PHOTO, DriverApiMapping.documentType("Seal"))
        assertNull(DriverApiMapping.documentType("cmr"))
        assertEquals(DocumentSlotState.PENDING_REVIEW, DriverApiMapping.slotState("PendingReview"))
        assertEquals(DocumentSlotState.UNKNOWN, DriverApiMapping.slotState("Reviewed"))
        assertNull(DriverApiMapping.slot(DriverDocumentSlotDto(slot = null, type = "Cmr")))
        val slot = DriverApiMapping.slot(DriverDocumentSlotDto(slot = "Cmr:trip:all", type = "Cmr", required = null, state = "Missing"))!!
        assertNull(slot.required)
        assertEquals("Cmr:trip:all", slot.key)
    }

    @Test
    fun `payroll states other than published or unpublished are invalid`() {
        val month = YearMonth.of(2026, 9)
        assertTrue(runCatching { DriverApiMapping.payroll(month, DriverPayrollResponseDto(state = "Draft")) }.exceptionOrNull() is RemoteException.InvalidResponse)
        assertTrue(runCatching { DriverApiMapping.payroll(month, DriverPayrollResponseDto(state = null)) }.exceptionOrNull() is RemoteException.InvalidResponse)
    }

    @Test
    fun `messages with an unknown direction are not shown`() {
        assertNull(DriverApiMapping.message(DriverMessageDto(id = "m1", body = "Hi", direction = "System")))
        assertEquals("t1", DriverApiMapping.message(DriverMessageDto(id = "m1", tripId = "t1", body = "Hi", direction = "ToDriver"))?.tripId)
    }

    @Test
    fun `a message carries its sender's name only when the server sends one`() {
        val named = DriverMessageDto(id = "m1", body = "Hi", direction = "ToDriver", senderDisplayName = "  Ayşe Yılmaz ")
        assertEquals("Ayşe Yılmaz", DriverApiMapping.message(named)?.senderName)
        assertNull(DriverApiMapping.message(named.copy(senderDisplayName = " "))?.senderName)
        assertNull(DriverApiMapping.message(named.copy(senderDisplayName = null))?.senderName)
    }

    @Test
    fun `stop enums and profile without a linked driver`() {
        assertEquals(StopActivity.EMPTY_RETURN, DriverApiMapping.stopActivity("EmptyReturn"))
        assertEquals(StopActivity.OTHER, DriverApiMapping.stopActivity("Customs"))
        assertEquals(StopStatus.EN_ROUTE, DriverApiMapping.stopStatus("EnRoute"))
        assertEquals(StopStatus.UNKNOWN, DriverApiMapping.stopStatus(null))
        assertNull(DriverApiMapping.driver(DriverProfileResponseDto(userId = "user-1", driverId = null, displayName = "No Driver")))
    }
}
