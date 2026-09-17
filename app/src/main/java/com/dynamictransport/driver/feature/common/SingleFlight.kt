package com.dynamictransport.driver.feature.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one driver action at a time. Taps arriving while an action is still being stored are
 * ignored, so a nervous double tap can never produce two business actions.
 */
class SingleFlight {
    private val running = AtomicBoolean(false)
    private val busyState = MutableStateFlow(false)

    val busy: StateFlow<Boolean> = busyState.asStateFlow()

    fun launch(scope: CoroutineScope, block: suspend () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        busyState.value = true
        scope.launch {
            try {
                block()
            } finally {
                running.set(false)
                busyState.value = false
            }
        }
        return true
    }
}
