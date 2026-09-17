package com.dynamictransport.driver.core.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.driver.domain.model.DriverMessage
import com.dynamictransport.driver.domain.model.MessageDirection
import com.dynamictransport.driver.testing.TEST_NOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AndroidMessageNotifierTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val notifier = AndroidMessageNotifier(application)
    private val manager: NotificationManager = application.getSystemService(NotificationManager::class.java)

    private fun notification(tripId: String?) = MessageNotification(
        tripId = tripId,
        tripNo = tripId?.let { "DT-2026-000012-01" },
        latest = DriverMessage("m1", tripId, "Gate 4", MessageDirection.TO_DRIVER, TEST_NOW, null),
        unreadCount = 2,
    )

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.ensureChannel()
    }

    @Test
    fun `a trip message is posted under its conversation tag and opens that conversation`() {
        notifier.show(notification("t1"))

        val posted = shadowOf(manager).getNotification("message-t1", AndroidMessageNotifier.NOTIFICATION_ID)
        assertEquals(AndroidMessageNotifier.CHANNEL_ID, posted.channelId)
        assertEquals("Trip DT-2026-000012-01", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Office: Gate 4", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val tap = shadowOf(posted.contentIntent).savedIntent
        assertEquals(TYPE_DRIVER_MESSAGE, tap.getStringExtra(EXTRA_TYPE))
        assertEquals("t1", tap.getStringExtra(EXTRA_TRIP_ID))

        // Opening the conversation removes its notification.
        notifier.cancel("t1")
        assertNull(shadowOf(manager).getNotification("message-t1", AndroidMessageNotifier.NOTIFICATION_ID))
    }

    @Test
    fun `a message from a named sender shows the sender's name`() {
        val general = notification(null)
        notifier.show(general.copy(latest = general.latest.copy(senderName = "Ayşe Yılmaz")))

        val posted = shadowOf(manager).getNotification("message-general", AndroidMessageNotifier.NOTIFICATION_ID)
        assertEquals("Ayşe Yılmaz: Gate 4", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `nothing is posted while the notification permission is refused`() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifier.show(notification(null))

        assertFalse(notifier.canNotify())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
class NotificationNavigationTest {

    private val navigation = NotificationNavigation()

    @Test
    fun `a tapped message notification opens its conversation once`() {
        navigation.handle(Intent().putExtra(EXTRA_TYPE, TYPE_DRIVER_MESSAGE).putExtra(EXTRA_TRIP_ID, "t1"))
        assertEquals(ConversationOpenRequest("t1"), navigation.pending.value)
        navigation.consume()
        assertNull(navigation.pending.value)

        // The general conversation has an empty trip id (app notification) or none (system notification).
        navigation.handle(Intent().putExtra(EXTRA_TYPE, TYPE_DRIVER_MESSAGE).putExtra(EXTRA_TRIP_ID, ""))
        assertEquals(ConversationOpenRequest(null), navigation.pending.value)
    }

    @Test
    fun `other launches open nothing`() {
        navigation.handle(Intent(Intent.ACTION_MAIN))
        navigation.handle(null)

        assertNull(navigation.pending.value)
    }
}
