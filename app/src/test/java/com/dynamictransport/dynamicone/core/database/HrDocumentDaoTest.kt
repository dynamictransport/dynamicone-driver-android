package com.dynamictransport.dynamicone.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.SignatureFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class HrDocumentDaoTest {

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = database.hrDocumentDao()

    @After
    fun tearDown() = database.close()

    private fun signature(
        id: String,
        signedAt: String,
        documentId: String = "doc-1",
        userId: String = "user-1",
        state: LocalSignatureState = LocalSignatureState.PENDING,
    ) = HrSignatureEntity(
        commandId = id,
        userId = userId,
        documentId = documentId,
        languageCode = "tr",
        contentSha256 = "A".repeat(64),
        strokesJson = """[[{"x":0.1,"y":0.5},{"x":0.3,"y":0.3}]]""",
        aspectRatio = 2.5f,
        signedAt = Instant.parse(signedAt),
        signedElapsedMillis = 1_000,
        bootCount = 3,
        state = state,
        failure = null,
        createdAt = Instant.parse(signedAt),
        updatedAt = Instant.parse(signedAt),
    )

    @Test
    fun `waiting signatures of one account are sent oldest first`() = runBlocking {
        dao.insertSignature(signature("late", "2026-09-18T09:00:00Z", documentId = "doc-2"))
        dao.insertSignature(signature("early", "2026-09-18T07:00:00Z", state = LocalSignatureState.SENDING))
        dao.insertSignature(signature("rejected", "2026-09-18T06:00:00Z", documentId = "doc-3", state = LocalSignatureState.FAILED))
        dao.insertSignature(signature("other", "2026-09-18T05:00:00Z", userId = "user-2"))

        assertEquals(listOf("early", "late"), dao.getUnsent("user-1").map { it.commandId })
        assertEquals(listOf("rejected", "early", "late"), dao.observeSignatures("user-1").first().map { it.commandId })
        assertEquals("early", dao.getWaitingSignature("user-1", "doc-1")?.commandId)
        assertNull(dao.getWaitingSignature("user-1", "doc-3"))
    }

    @Test
    fun `states, replacement of rejected ones and clean-up`() = runBlocking {
        dao.insertSignature(signature("a", "2026-09-18T07:00:00Z"))
        dao.updateSignatureState("a", LocalSignatureState.FAILED, SignatureFailure.DOCUMENT_CHANGED, Instant.parse("2026-09-18T07:01:00Z"))
        val failed = dao.getSignature("a")
        assertEquals(LocalSignatureState.FAILED, failed?.state)
        assertEquals(SignatureFailure.DOCUMENT_CHANGED, failed?.failure)

        dao.insertSignature(signature("b", "2026-09-18T08:00:00Z"))
        dao.deleteFailedSignatures("user-1", "doc-1")
        assertEquals(listOf("b"), dao.observeSignatures("user-1").first().map { it.commandId })

        dao.insertSignature(signature("c", "2026-09-18T08:00:00Z", userId = "user-2"))
        dao.deleteSignaturesOf("user-1", "doc-1")
        assertEquals(emptyList<String>(), dao.observeSignatures("user-1").first().map { it.commandId })
        dao.deleteSignaturesForOtherUsers("user-1")
        assertNull(dao.getSignature("c"))
    }

    @Test
    fun `the stored list belongs to one account`() = runBlocking {
        val updated = Instant.parse("2026-09-18T07:00:00Z")
        dao.upsertCache(HrDocumentCacheEntity("user-1", "[]", updated))
        dao.upsertCache(HrDocumentCacheEntity("user-1", """[{"id":"doc-1"}]""", updated.plusSeconds(60)))
        dao.upsertCache(HrDocumentCacheEntity("user-2", "[]", updated))

        assertEquals("""[{"id":"doc-1"}]""", dao.getCache("user-1")?.pendingJson)
        dao.deleteCacheForOtherUsers("user-1")
        assertNull(dao.getCache("user-2"))
        dao.deleteAllCache()
        assertNull(dao.getCache("user-1"))
    }
}
