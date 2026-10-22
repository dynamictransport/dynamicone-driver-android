package com.dynamictransport.dynamicone.core.wakeup

import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/** The alarm stream while a wake-up call rings (docs/25 U4): loud enough to wake, then back as it was. */
interface AlarmVolumeBooster {
    fun raise()

    fun restore()
}

@Singleton
class AndroidAlarmVolumeBooster @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AlarmVolumeBooster {

    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)

    /** The driver's own level, kept while a call rings; null when nothing was changed. */
    private var previous: Int? = null

    override fun raise() {
        if (previous != null) return
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = ceil(max * MIN_LEVEL).toInt().coerceIn(0, max)
            if (current >= target) return
            audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            previous = current
        } catch (_: SecurityException) {
            // Total silence (Do Not Disturb) refuses volume changes: the call stays as loud as the driver set it.
        }
    }

    override fun restore() {
        val level = previous ?: return
        previous = null
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, level, 0)
        } catch (_: SecurityException) {
            // Nothing to do: the system keeps the level it allows.
        }
    }

    companion object {
        /** Share of the maximum the alarm stream is raised to at least. */
        const val MIN_LEVEL = 0.7
    }
}
