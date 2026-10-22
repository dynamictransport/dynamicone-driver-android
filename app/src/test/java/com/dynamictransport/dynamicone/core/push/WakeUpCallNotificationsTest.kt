package com.dynamictransport.dynamicone.core.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.app.WakeUpCallActivity
import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.wakeUpCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Instant

/** The data of a `driver.wakeup` push (docs/25 §4) as the service reads it. */
class WakeUpSignalTest {

    @Test
    fun `a ring push carries the call, its reason and its deadline`() {
        val signal = WakeUpSignal.from(
            mapOf("type" to "driver.wakeup", "event" to "ring", "callId" to " call-1 ", "reason" to "call_office", "expiresAt" to "2026-10-22T05:10:00Z"),
        )

        assertTrue(signal.isRing)
        assertFalse(signal.isStop)
        assertEquals("call-1", signal.callId)
        assertEquals("call_office", signal.reason)
        assertEquals(Instant.parse("2026-10-22T05:10:00Z"), signal.expiresAt)
    }

    @Test
    fun `a push without an event rings, a stop push stops, an unreadable deadline is dropped`() {
        assertTrue(WakeUpSignal.from(mapOf("callId" to "call-1")).isRing)
        val stop = WakeUpSignal.from(mapOf("event" to "STOP", "callId" to "call-1", "expiresAt" to "yesterday"))
        assertTrue(stop.isStop)
        assertFalse(stop.isRing)
        assertNull(stop.expiresAt)
        assertNull(WakeUpSignal.from(mapOf("event" to "ring", "callId" to "  ")).callId)
    }
}

@RunWith(AndroidJUnit4::class)
class AndroidWakeUpCallNotifierTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val clock = MutableClock(Instant.parse("2026-10-22T05:05:10Z"))
    private val notifier = AndroidWakeUpCallNotifier(application, clock)
    private val manager: NotificationManager = application.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.ensureChannel()
    }

    @Test
    fun `the call rings as an insistent alarm that opens the call screen and offers the answer`() {
        notifier.ring(wakeUpCall(tripNo = "DT-2026-000012-01"))

        val posted = shadowOf(manager).getNotification(AndroidWakeUpCallNotifier.NOTIFICATION_ID)
        assertEquals(AndroidWakeUpCallNotifier.CHANNEL_ID, posted.channelId)
        assertEquals("Ayşe Yılmaz is calling you", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Please call the office. Trip DT-2026-000012-01", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.CATEGORY_ALARM, posted.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, posted.visibility)
        assertTrue(posted.flags and Notification.FLAG_INSISTENT != 0)
        assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
        // Ends by itself at the deadline: 4 min 50 s from now.
        assertEquals(290_000L, posted.timeoutAfter)
        val screen = shadowOf(posted.fullScreenIntent).savedIntent
        assertEquals(WakeUpCallActivity::class.java.name, screen.component?.className)
        assertEquals("call-1", screen.getStringExtra(EXTRA_CALL_ID))
        assertEquals("I'M AWAKE", posted.actions.single().title.toString())
        val answer = shadowOf(posted.actions.single().actionIntent).savedIntent
        assertEquals(ACTION_WAKE_UP_ACKNOWLEDGE, answer.action)
        assertEquals("call-1", answer.getStringExtra(EXTRA_CALL_ID))

        notifier.cancel()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `a call from the office without a name and without a trip reads as the office`() {
        notifier.ring(wakeUpCall(requestedBy = null))

        val posted = shadowOf(manager).getNotification(AndroidWakeUpCallNotifier.NOTIFICATION_ID)
        assertEquals("The office is calling you", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Please call the office.", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `a call past its deadline is not posted`() {
        clock.now = Instant.parse("2026-10-22T05:10:00Z")

        notifier.ring(wakeUpCall())

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `nothing is posted while the notification permission is refused`() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifier.ring(wakeUpCall())

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
