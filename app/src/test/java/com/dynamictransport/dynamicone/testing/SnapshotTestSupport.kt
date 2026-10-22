package com.dynamictransport.dynamicone.testing

import com.dynamictransport.dynamicone.core.common.DeviceProfile
import com.dynamictransport.dynamicone.core.database.SnapshotRequestDao
import com.dynamictransport.dynamicone.core.database.SnapshotRequestEntity
import com.dynamictransport.dynamicone.core.database.SnapshotRowState
import com.dynamictransport.dynamicone.core.sync.SnapshotSyncScheduler
import com.dynamictransport.dynamicone.data.media.SnapshotPhotoPreparer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.Instant

class InMemorySnapshotRequestDao : SnapshotRequestDao {
    private val rows = MutableStateFlow<Map<String, SnapshotRequestEntity>>(emptyMap())

    val all: List<SnapshotRequestEntity> get() = rows.value.values.sortedBy { it.requestedAt }

    override suspend fun get(requestId: String): SnapshotRequestEntity? = rows.value[requestId]

    override fun observeLatest(driverId: String): Flow<SnapshotRequestEntity?> =
        rows.map { map -> map.values.filter { it.driverId == driverId }.maxByOrNull { it.requestedAt } }

    override suspend fun pendingSends(driverId: String): List<SnapshotRequestEntity> = all.filter {
        it.driverId == driverId && it.state in setOf(SnapshotRowState.CAPTURED, SnapshotRowState.SENDING, SnapshotRowState.FAILED_PENDING)
    }

    override suspend fun upsert(row: SnapshotRequestEntity) = update(row.requestId) { row }

    override suspend fun updateState(requestId: String, state: SnapshotRowState, updatedAt: Instant) =
        update(requestId) { it?.copy(state = state, updatedAt = updatedAt) }

    override suspend fun markCaptured(requestId: String, state: SnapshotRowState, photoPath: String?, capturedAt: Instant, camera: String, updatedAt: Instant) =
        update(requestId) { it?.copy(state = state, photoPath = photoPath, capturedAt = capturedAt, camera = camera, updatedAt = updatedAt) }

    override suspend fun markFailed(requestId: String, state: SnapshotRowState, reason: String, failedAt: Instant, updatedAt: Instant) =
        update(requestId) { it?.copy(state = state, failureReason = reason, failedAt = failedAt, updatedAt = updatedAt) }

    override suspend fun clearPhoto(requestId: String, updatedAt: Instant) = update(requestId) { it?.copy(photoPath = null, updatedAt = updatedAt) }

    override suspend fun olderThan(before: Instant): List<SnapshotRequestEntity> = all.filter { it.requestedAt.isBefore(before) }

    override suspend fun delete(requestIds: List<String>) {
        rows.value = rows.value - requestIds.toSet()
    }

    private fun update(requestId: String, transform: (SnapshotRequestEntity?) -> SnapshotRequestEntity?) {
        val next = transform(rows.value[requestId])
        rows.value = if (next == null) rows.value - requestId else rows.value + (requestId to next)
    }
}

class RecordingSnapshotSyncScheduler : SnapshotSyncScheduler {
    val delaysSeconds = mutableListOf<Long>()
    val requests: Int get() = delaysSeconds.size

    override fun requestSend(delaySeconds: Long) {
        delaysSeconds += delaySeconds
    }
}

/** Copies the bytes as they are (no Android bitmaps on the JVM); [usable] = false stands for a photo the tablet cannot encode. */
class FakeSnapshotPhotoPreparer : SnapshotPhotoPreparer {
    var usable = true

    override fun prepare(source: File, target: File): Boolean {
        if (!usable) {
            source.delete()
            return false
        }
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        source.delete()
        return true
    }
}

class FakeDeviceProfile(
    override val kind: String = "vehicleTablet",
    override val model: String? = "Bridgestone Mobility Solutions PRO_X",
    override val hasCamera: Boolean = true,
) : DeviceProfile
