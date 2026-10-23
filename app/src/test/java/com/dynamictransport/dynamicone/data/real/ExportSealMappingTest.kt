package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.DriverAvailableActionDto
import com.dynamictransport.dynamicone.core.network.DriverDocumentSlotDto
import com.dynamictransport.dynamicone.core.network.DriverExecutionResponseDto
import com.dynamictransport.dynamicone.domain.model.DocumentSlotState
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.ExecutionBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/27 (backend TH-2026-0519): the export loading options and the seal gate of the execution plan. */
class ExportSealMappingTest {

    private val sealSlot1 = DriverDocumentSlotDto(slot = "Seal:s1:c1", type = "Seal", stopId = "s1", containerId = "c1", required = true, state = "Missing")
    private val sealSlot2 = DriverDocumentSlotDto(slot = "Seal:s1:c2", type = "Seal", stopId = "s1", containerId = "c2", required = true, state = "PendingReview", documentId = "d2", revision = "r1")
    private val cmr = DriverDocumentSlotDto(slot = "Cmr:trip:all", type = "Cmr", required = true, state = "Missing")

    @Test
    fun `before the loading confirmation the plan offers ConfirmLoading and hides the stop completion`() {
        val execution = DriverApiMapping.execution(
            "t1",
            DriverExecutionResponseDto(
                version = "v1",
                workflow = "TripActions",
                availableActions = listOf(
                    DriverAvailableActionDto("ConfirmLoading", "Confirm loading completed", "s1", null, canExecute = true, stopName = " Antwerp PSA "),
                    DriverAvailableActionDto("CompleteStop", "Container loading completed", "s1", "c1", canExecute = false, blockingReason = "loading_confirmation_required", blockingDocumentSlots = listOf("Seal:s1:c1", "Cmr:trip:all")),
                    DriverAvailableActionDto("Rest", "Report a break"),
                ),
                documents = listOf(cmr, sealSlot1),
            ),
        )
        val loading = execution.loadingConfirmation!!
        assertEquals(ExecutionActionCode.CONFIRM_LOADING, loading.code)
        assertEquals("s1", loading.stopId)
        assertNull(loading.containerId)
        assertEquals("Antwerp PSA", loading.stopName)
        assertTrue(loading.isExportLoadingStep)
        assertFalse(loading.isProgress)
        assertEquals("s1", execution.exportLoadingStopId)
        assertTrue(execution.sealEntries.isEmpty())

        val completion = execution.availableActions.single { it.code == ExecutionActionCode.COMPLETE_STOP }
        assertEquals(ExecutionBlock.LOADING_CONFIRMATION_REQUIRED, completion.blockingReason)
        assertTrue(completion.blockingReason!!.isExportSealGate)
        assertFalse(completion.blockedByTrailer)
        assertTrue(completion.isBlocked)
        // The seal photo of the export loading stop belongs to the seal cards; other evidence stays listed.
        assertEquals(listOf("Cmr:trip:all"), execution.blockingSlots.map { it.key })
    }

    @Test
    fun `after the confirmation every container gets a seal entry with its own photo slot and recorded seal`() {
        val execution = DriverApiMapping.execution(
            "t1",
            DriverExecutionResponseDto(
                version = "v2",
                workflow = "TripActions",
                availableActions = listOf(
                    DriverAvailableActionDto("RecordSealNumber", "Save seal number", "s1", "c1", canExecute = true, sealNumber = null, containerNumber = "MSKU 458921-7", stopName = "Antwerp PSA"),
                    DriverAvailableActionDto("RecordSealNumber", "Save seal number", "s1", "c2", canExecute = true, sealNumber = " SEAL-002 ", containerNumber = "TGHU 902114-6", stopName = "Antwerp PSA"),
                    DriverAvailableActionDto("CompleteStop", "Container loading completed", "s1", "c1", canExecute = false, blockingReason = "seal_number_required"),
                    DriverAvailableActionDto("CompleteStop", "Container loading completed", "s1", "c2", canExecute = true),
                    DriverAvailableActionDto("Problem", "Report a problem"),
                ),
                documents = listOf(sealSlot1, sealSlot2),
            ),
        )
        assertNull(execution.loadingConfirmation)
        assertEquals("s1", execution.exportLoadingStopId)
        val (first, second) = execution.sealEntries
        assertEquals("c1", first.containerId)
        assertEquals("MSKU 458921-7", first.action.containerNumber)
        assertNull(first.recordedSealNumber)
        assertFalse(first.sealNumberRecorded)
        assertEquals("Seal:s1:c1", first.photoSlot?.key)
        assertEquals(DocumentSlotState.MISSING, first.photoSlot?.state)
        assertFalse(first.photoDone)
        assertFalse(first.complete)

        assertEquals("SEAL-002", second.recordedSealNumber)
        assertEquals("Seal:s1:c2", second.photoSlot?.key)
        assertEquals("d2", second.photoSlot?.documentId)
        assertEquals("r1", second.photoSlot?.revision)
        assertTrue(second.photoDone)
        assertTrue(second.complete)

        val blocked = execution.availableActions.first { it.code == ExecutionActionCode.COMPLETE_STOP }
        assertEquals(ExecutionBlock.SEAL_NUMBER_REQUIRED, blocked.blockingReason)
        assertTrue(execution.blockingSlots.isEmpty())
    }

    @Test
    fun `a photo alone never records the seal number and a seal number alone never counts as a photo`() {
        fun entry(seal: String?, state: String) = DriverApiMapping.execution(
            "t1",
            DriverExecutionResponseDto(
                version = "v3",
                workflow = "TripActions",
                availableActions = listOf(DriverAvailableActionDto("RecordSealNumber", "Save seal number", "s1", "c1", sealNumber = seal)),
                documents = listOf(sealSlot1.copy(state = state)),
            ),
        ).sealEntries.single()

        assertFalse(entry(null, "PendingReview").sealNumberRecorded)
        assertTrue(entry(null, "PendingReview").photoDone)
        assertFalse(entry(null, "PendingReview").complete)

        assertTrue(entry("SEAL-1", "Missing").sealNumberRecorded)
        assertFalse(entry("SEAL-1", "Missing").photoDone)
        assertFalse(entry("SEAL-1", "Missing").complete)

        assertFalse(entry("SEAL-1", "Rejected").photoDone)
        assertTrue(entry("SEAL-1", "Approved").complete)
        assertTrue(entry("SEAL-1", "NotRequired").complete)
    }

    @Test
    fun `a stop whose completion is blocked by the seal gate is an export loading stop even without options`() {
        val execution = DriverApiMapping.execution(
            "t1",
            DriverExecutionResponseDto(
                version = "v4",
                workflow = "TripActions",
                availableActions = listOf(
                    DriverAvailableActionDto("CompleteStop", "Container loading completed", "s1", null, canExecute = false, blockingReason = "seal_number_required"),
                ),
            ),
        )
        assertEquals("s1", execution.exportLoadingStopId)

        val other = DriverApiMapping.execution(
            "t1",
            DriverExecutionResponseDto(
                version = "v5",
                workflow = "TripActions",
                availableActions = listOf(
                    DriverAvailableActionDto("CompleteStop", "I picked up the full container", "s1", "c1", canExecute = false, blockingDocumentSlots = listOf("Seal:s1:c1")),
                    DriverAvailableActionDto("Start", "I started the trip", canExecute = false, blockingReason = "trailer_confirmation_required"),
                    DriverAvailableActionDto("CompleteStop", "Stop completed", "s2", null, canExecute = false, blockingReason = "something_new"),
                ),
                documents = listOf(sealSlot1),
            ),
        )
        assertNull(other.exportLoadingStopId)
        assertEquals(ExecutionBlock.TRAILER_CONFIRMATION_REQUIRED, other.availableActions[1].blockingReason)
        assertTrue(other.availableActions[1].blockedByTrailer)
        assertEquals(ExecutionBlock.UNKNOWN, other.availableActions[2].blockingReason)
        // Outside the export loading stop the seal photo is ordinary step evidence.
        assertEquals(listOf("Seal:s1:c1"), other.blockingSlots.map { it.key })
    }

    @Test
    fun `action names of the new codes round-trip exactly`() {
        assertEquals("ConfirmLoading", DriverApiMapping.actionName(ExecutionActionCode.CONFIRM_LOADING))
        assertEquals("RecordSealNumber", DriverApiMapping.actionName(ExecutionActionCode.RECORD_SEAL_NUMBER))
        assertEquals(ExecutionActionCode.CONFIRM_LOADING, DriverApiMapping.actionCode("ConfirmLoading"))
        assertEquals(ExecutionActionCode.RECORD_SEAL_NUMBER, DriverApiMapping.actionCode("RecordSealNumber"))
        assertNull(DriverApiMapping.actionCode("recordsealnumber"))
    }
}
