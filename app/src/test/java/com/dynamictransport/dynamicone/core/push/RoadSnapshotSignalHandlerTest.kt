package com.dynamictransport.dynamicone.core.push

import com.dynamictransport.dynamicone.core.snapshot.SnapshotCaptureCoordinator
import com.dynamictransport.dynamicone.domain.model.SnapshotCamera
import com.dynamictransport.dynamicone.domain.model.SnapshotDecision
import com.dynamictransport.dynamicone.domain.model.SnapshotFailure
import com.dynamictransport.dynamicone.domain.model.SnapshotOutcome
import com.dynamictransport.dynamicone.domain.model.SnapshotRequest
import com.dynamictransport.dynamicone.domain.model.SnapshotRequestStatus
import com.dynamictransport.dynamicone.domain.repository.RoadSnapshotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.Instant

/** The push side of docs/24 §1: on screen the photo is taken at once, otherwise the driver is asked with one tap. */
class RoadSnapshotSignalHandlerTest {

    private val request = SnapshotRequest(
        "req-1", "trp-1", SnapshotRequestStatus.PENDING,
        Instant.parse("2026-10-21T13:00:00Z"), Instant.parse("2026-10-21T13:10:00Z"), "Ayşe Yılmaz", SnapshotCamera.EXTERNAL,
    )
    private val repository = FakeRoadSnapshotRepository()
    private val coordinator = SnapshotCaptureCoordinator()
    private val notifier = RecordingSnapshotNotifier()
    private val handler = RoadSnapshotSignalHandler(repository, coordinator, notifier)

    @Test
    fun `with the app on screen the request goes straight to the capture host`() = runBlocking {
        repository.decision = SnapshotDecision.Capture(request)
        coordinator.setHostVisible(true)

        handler.onSignal("trp-1", "req-1")

        assertEquals(request, coordinator.pendingCapture.value)
        assertEquals(emptyList<String>(), notifier.shown)
    }

    @Test
    fun `with the app in the background the driver gets the notification`() = runBlocking {
        repository.decision = SnapshotDecision.Capture(request)
        coordinator.setHostVisible(false)

        handler.onSignal("trp-1", "req-1")

        assertNull(coordinator.pendingCapture.value)
        assertEquals(listOf("req-1"), notifier.shown)
    }

    @Test
    fun `an ignored request removes a notification that may still be up`() = runBlocking {
        repository.decision = SnapshotDecision.Ignore

        handler.onSignal("trp-1", "req-1")

        assertNull(coordinator.pendingCapture.value)
        assertEquals(listOf("req-1"), notifier.cancelled)
    }

    @Test
    fun `a push without ids is ignored without touching the server`() = runBlocking {
        handler.onSignal(null, "req-1")
        handler.onSignal("trp-1", " ")

        assertEquals(0, repository.signals)
    }

    private class FakeRoadSnapshotRepository : RoadSnapshotRepository {
        var decision: SnapshotDecision = SnapshotDecision.Ignore
        var signals = 0

        override fun observeLatest(): Flow<SnapshotOutcome?> = flowOf(null)

        override suspend fun onRequestSignal(tripId: String, requestId: String): SnapshotDecision {
            signals++
            return decision
        }

        override suspend fun pendingRequest(requestId: String): SnapshotRequest? = null
        override suspend fun storePhoto(requestId: String, source: File, capturedAt: Instant, camera: SnapshotCamera): Boolean = true
        override suspend fun reportFailure(requestId: String, failure: SnapshotFailure) = Unit
        override suspend fun sendPending(): Boolean = true
    }

    private class RecordingSnapshotNotifier : RoadSnapshotNotifier {
        val shown = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override fun ensureChannel() = Unit

        override fun show(request: SnapshotRequest) {
            shown += request.requestId
        }

        override fun cancel(requestId: String) {
            cancelled += requestId
        }
    }
}
