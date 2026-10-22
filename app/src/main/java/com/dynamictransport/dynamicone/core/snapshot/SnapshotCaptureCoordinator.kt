package com.dynamictransport.dynamicone.core.snapshot

import com.dynamictransport.dynamicone.domain.model.SnapshotFailure
import com.dynamictransport.dynamicone.domain.model.SnapshotRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a road snapshot request from the push (any thread, app possibly in the background) to the capture host
 * on screen, and tells the push side whether the app is visible right now (docs/24 §1: the camera works only
 * from the foreground).
 */
@Singleton
class SnapshotCaptureCoordinator @Inject constructor() {

    private val hostVisible = MutableStateFlow(false)

    /** True while the capture host is resumed on screen: a request can be taken without the driver. */
    val isAppVisible: Boolean get() = hostVisible.value

    private val pending = MutableStateFlow<SnapshotRequest?>(null)

    /** The request the host takes next; cleared with [consume]. */
    val pendingCapture: StateFlow<SnapshotRequest?> = pending.asStateFlow()

    private val lastResult = MutableStateFlow<CaptureNotice?>(null)

    /** What the host last did, for the short banner over the current screen. */
    val notice: StateFlow<CaptureNotice?> = lastResult.asStateFlow()

    fun setHostVisible(visible: Boolean) {
        hostVisible.value = visible
    }

    /** Only one request at a time; a newer one replaces an older one that was not taken yet. */
    fun capture(request: SnapshotRequest) {
        pending.value = request
    }

    fun consume(requestId: String) {
        pending.update { current -> current?.takeIf { it.requestId != requestId } }
    }

    fun announce(notice: CaptureNotice) {
        lastResult.value = notice
    }

    fun clearNotice(notice: CaptureNotice) {
        lastResult.update { current -> current?.takeIf { it != notice } }
    }
}

/** Shown for a few seconds after a capture attempt. */
data class CaptureNotice(val requestId: String, val requestedByDisplayName: String?, val failure: SnapshotFailure?, val sequence: Long)
