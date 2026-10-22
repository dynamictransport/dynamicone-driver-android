package com.dynamictransport.dynamicone.core.push

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.domain.model.HrDocumentStatus
import com.dynamictransport.dynamicone.domain.model.LocalSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.testing.FakeHrDocumentRepository
import com.dynamictransport.dynamicone.testing.RecordingHrDocumentNotifier
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.hrDocument
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** A push only says that something changed: what is announced depends on the document read from the API (docs/17 §6). */
class HrDocumentSignalHandlerTest {

    private val repository = FakeHrDocumentRepository()
    private val notifier = RecordingHrDocumentNotifier()
    private val handler = HrDocumentSignalHandler(repository, notifier)

    @Test
    fun `a new document, a reminder and an overdue one are announced while they wait for the signature`() = runBlocking {
        repository.pending(hrDocument(id = "doc-1"))

        handler.onSignal("doc-1", "Assigned")
        handler.onSignal("doc-1", "Reminder")
        handler.onSignal("doc-1", "Overdue")

        assertEquals(
            listOf("doc-1" to HrDocumentNotice.NEW, "doc-1" to HrDocumentNotice.REMINDER, "doc-1" to HrDocumentNotice.OVERDUE),
            notifier.shown,
        )
        assertEquals(listOf("doc-1", "doc-1", "doc-1"), repository.refreshedDocuments)
        assertEquals(3, repository.refreshes)
    }

    @Test
    fun `withdrawn, signed or already signed on the phone loses its notification`() = runBlocking {
        repository.pending(hrDocument(id = "doc-1"))
        handler.onSignal("doc-1", "Withdrawn")

        repository.lists.update {
            it.copy(localSignatures = listOf(LocalSignature("sig-1", "doc-1", "tr", TEST_NOW, LocalSignatureState.PENDING, null)))
        }
        handler.onSignal("doc-1", "Reminder")

        repository.lists.update { it.copy(pending = emptyList(), localSignatures = emptyList()) }
        repository.single.value = mapOf("doc-1" to hrDocument(id = "doc-1", status = HrDocumentStatus.SIGNED))
        handler.onSignal("doc-1", "Reminder")

        repository.single.value = emptyMap()
        repository.missing.value = setOf("doc-1")
        handler.onSignal("doc-1", "Assigned")

        assertTrue(notifier.shown.isEmpty())
        assertEquals(listOf("doc-1", "doc-1", "doc-1", "doc-1"), notifier.cancelled)
    }

    @Test
    fun `a document that cannot be read right now is still announced`() = runBlocking {
        repository.documentRefreshResult = RefreshResult.OFFLINE

        handler.onSignal("doc-7", "Assigned")

        assertEquals(listOf("doc-7" to HrDocumentNotice.NEW), notifier.shown)
    }

    @Test
    fun `a signal without a document reads the list only`() = runBlocking {
        handler.onSignal(null, "Assigned")

        assertEquals(1, repository.refreshes)
        assertTrue(repository.refreshedDocuments.isEmpty())
        assertTrue(notifier.shown.isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
class AndroidHrDocumentNotifierTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val notifier = AndroidHrDocumentNotifier(application)
    private val manager: NotificationManager = application.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.ensureChannel()
    }

    @Test
    fun `a document is posted under its tag without its title and opens that document`() {
        notifier.show("doc-1", HrDocumentNotice.REMINDER)
        notifier.show("doc-1", HrDocumentNotice.OVERDUE)

        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        val notification = posted.single()
        assertEquals(AndroidHrDocumentNotifier.CHANNEL_ID, notification.channelId)
        assertEquals("Signing deadline passed", notification.extras.getString("android.title"))
        assertEquals("Please read and sign the document.", notification.extras.getCharSequence("android.text").toString())
        val tap = shadowOf(notification.contentIntent).savedIntent
        assertEquals(TYPE_HR_DOCUMENT, tap.getStringExtra(EXTRA_TYPE))
        assertEquals("doc-1", tap.getStringExtra(EXTRA_DOCUMENT_ID))

        notifier.cancel("doc-1")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `a tapped document notification opens that document once`() {
        val navigation = NotificationNavigation()
        val tap = Intent().putExtra(EXTRA_TYPE, TYPE_HR_DOCUMENT).putExtra(EXTRA_DOCUMENT_ID, "doc-1")

        navigation.handle(tap)
        assertEquals("doc-1", navigation.documentRequest.value)
        navigation.consumeDocument()
        assertNull(navigation.documentRequest.value)

        navigation.handle(Intent().putExtra(EXTRA_TYPE, TYPE_HR_DOCUMENT))
        assertNull(navigation.documentRequest.value)
    }
}
