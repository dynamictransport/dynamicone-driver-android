package com.dynamictransport.dynamicone.core.remote

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The real size of the screen as it is turned now, and its density. */
internal fun Context.realDisplayMetrics(): DisplayMetrics {
    val metrics = DisplayMetrics()
    val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
    if (display == null) {
        metrics.setTo(resources.displayMetrics)
    } else {
        // Deprecated for app windows; a service has no window and needs the size of the whole screen.
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
    }
    return metrics
}

/** Whether the office can operate the phone (docs/29 §4.2): the accessibility service is switched on per device. */
interface RemoteControlAccess {
    val isEnabled: Boolean

    /** The app holds `WRITE_SECURE_SETTINGS` (granted over adb) and can switch its service on by itself. */
    val canSelfEnable: Boolean

    /** @return true when the service is on afterwards. */
    fun selfEnable(): Boolean
}

@Singleton
class AndroidRemoteControlAccess @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : RemoteControlAccess {

    private val component = ComponentName(context, RemoteControlService::class.java)

    override val isEnabled: Boolean
        get() = enabledServices().any { ComponentName.unflattenFromString(it) == component }

    override val canSelfEnable: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    override fun selfEnable(): Boolean {
        if (isEnabled) return true
        if (!canSelfEnable) return false
        return try {
            val services = (enabledServices() + component.flattenToString()).joinToString(SEPARATOR)
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            isEnabled
        } catch (_: SecurityException) {
            false
        }
    }

    private fun enabledServices(): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }

    private companion object {
        const val SEPARATOR = ":"
    }
}

/** Performs one input on the phone; implemented by the accessibility service. */
fun interface RemoteInputInjector {
    fun inject(input: RemoteInput)
}

/**
 * The only way from the stream to the accessibility service (docs/29 §4.2): input passes while a session the driver
 * accepted is streaming and the service is on, and is dropped at any other time.
 */
@Singleton
class RemoteInputDispatcher @Inject constructor() : RemoteInputSink {

    @Volatile
    private var injector: RemoteInputInjector? = null

    @Volatile
    private var activeSessionId: String? = null

    val isServiceConnected: Boolean get() = injector != null

    fun attach(injector: RemoteInputInjector) {
        this.injector = injector
    }

    fun detach(injector: RemoteInputInjector) {
        if (this.injector === injector) this.injector = null
    }

    fun onSessionStarted(sessionId: String) {
        activeSessionId = sessionId
    }

    fun onSessionEnded(sessionId: String) {
        if (activeSessionId == sessionId) activeSessionId = null
    }

    override fun dispatch(input: RemoteInput) {
        if (activeSessionId == null) return
        injector?.inject(input)
    }
}

/** A finger stroke in screen pixels. */
data class GestureStroke(val startX: Float, val startY: Float, val endX: Float, val endY: Float, val durationMillis: Long)

object RemoteGestures {
    const val TAP_MILLIS = 60L

    /** Null for input that is not a touch. */
    fun stroke(input: RemoteInput, width: Int, height: Int, longPressMillis: Long): GestureStroke? {
        fun x(fraction: Float) = (fraction.coerceIn(0f, 1f) * width).coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())

        fun y(fraction: Float) = (fraction.coerceIn(0f, 1f) * height).coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())

        return when (input) {
            is RemoteInput.Tap -> GestureStroke(x(input.x), y(input.y), x(input.x), y(input.y), TAP_MILLIS)
            is RemoteInput.LongPress -> GestureStroke(x(input.x), y(input.y), x(input.x), y(input.y), longPressMillis)
            is RemoteInput.Swipe -> GestureStroke(x(input.x1), y(input.y1), x(input.x2), y(input.y2), input.durationMillis.coerceAtLeast(1))
            is RemoteInput.Key, is RemoteInput.Text -> null
        }
    }
}

/**
 * Taps, swipes, keys and text for the office during a remote session (docs/29 §4.2). The service reads nothing and
 * reacts to nothing on its own: it only performs what [RemoteInputDispatcher] lets through.
 */
@AndroidEntryPoint
class RemoteControlService : AccessibilityService(), RemoteInputInjector {

    @Inject
    lateinit var dispatcher: RemoteInputDispatcher

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        dispatcher.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        dispatcher.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        dispatcher.detach(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun inject(input: RemoteInput) {
        main.post {
            when (input) {
                is RemoteInput.Key -> performGlobalAction(
                    when (input.key) {
                        RemoteKey.BACK -> GLOBAL_ACTION_BACK
                        RemoteKey.HOME -> GLOBAL_ACTION_HOME
                        RemoteKey.RECENTS -> GLOBAL_ACTION_RECENTS
                        RemoteKey.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                    },
                )
                is RemoteInput.Text -> setText(input.value)
                else -> touch(input)
            }
        }
    }

    private fun touch(input: RemoteInput) {
        val metrics = realDisplayMetrics()
        val longPress = ViewConfiguration.getLongPressTimeout() + LONG_PRESS_EXTRA_MILLIS
        val stroke = RemoteGestures.stroke(input, metrics.widthPixels, metrics.heightPixels, longPress) ?: return
        val path = Path().apply {
            moveTo(stroke.startX, stroke.startY)
            if (stroke.endX != stroke.startX || stroke.endY != stroke.startY) lineTo(stroke.endX, stroke.endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, stroke.durationMillis))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** docs/29 §4.1 `text`: set on the focused editable field. */
    private fun setText(value: String) {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (!focused.isEditable) return
        focused.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) },
        )
    }

    private companion object {
        const val LONG_PRESS_EXTRA_MILLIS = 250L
    }
}
