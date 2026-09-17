package com.dynamictransport.dynamicone.core.common

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Monotonic time since the phone started. Unlike the wall clock the user cannot change it, so the server can place an
 * offline scan in time as "received minus elapsed" (docs/15_MOBILE_APP_API.md §5.4).
 */
interface DeviceUptime {
    fun elapsedRealtimeMillis(): Long

    /** Changes with every restart; null when the platform does not provide it. */
    fun bootCount(): Int?
}

class AndroidDeviceUptime @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DeviceUptime {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()
}
