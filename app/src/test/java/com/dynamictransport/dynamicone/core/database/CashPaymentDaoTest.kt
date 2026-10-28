package com.dynamictransport.dynamicone.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.domain.model.CashSignFailure
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class CashPaymentDaoTest {

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = database.cashPaymentDao()

    @After
    fun tearDown() = database.close()

    private fun signature(
        id: String,
        signedAt: String,
        paymentId: String = "pay-1",
        userId: String = "user-1",
        state: LocalSignatureState = LocalSignatureState.PENDING,
    ) = CashSignatureEntity(
        commandId = id,
        userId = userId,
        scope = "DISBURSEMENTS",
        paymentId = paymentId,
        formHash = "F".repeat(64),
        strokesJson = """[[{"x":0.1,"y":0.5},{"x":0.3,"y":0.3}]]""",
        aspectRatio = 2.5f,
        signedAt = Instant.parse(signedAt),
        state = state,
        failure = null,
        createdAt = Instant.parse(signedAt),
        updatedAt = Instant.parse(signedAt),
    )

    @Test
    fun `waiting signatures of one account are sent oldest first`() = runBlocking {
        dao.insertSignature(signature("late", "2026-10-28T09:00:00Z", paymentId = "pay-2"))
        dao.insertSignature(signature("early", "2026-10-28T07:00:00Z", state = LocalSignatureState.SENDING))
        dao.insertSignature(signature("rejected", "2026-10-28T06:00:00Z", paymentId = "pay-3", state = LocalSignatureState.FAILED))
        dao.insertSignature(signature("other", "2026-10-28T05:00:00Z", userId = "user-2"))

        assertEquals(listOf("early", "late"), dao.getUnsent("user-1").map { it.commandId })
        assertEquals(listOf("rejected", "early", "late"), dao.observeSignatures("user-1").first().map { it.commandId })
        assertEquals("early", dao.getWaitingSignature("user-1", "pay-1")?.commandId)
        assertNull(dao.getWaitingSignature("user-1", "pay-3"))
    }

    @Test
    fun `a rejected signature is replaced, a confirmed one deleted, other accounts' rows removed`() = runBlocking {
        dao.insertSignature(signature("a", "2026-10-28T07:00:00Z"))
        dao.updateSignatureState("a", LocalSignatureState.FAILED, CashSignFailure.FORM_CHANGED, Instant.parse("2026-10-28T07:01:00Z"))
        assertEquals(CashSignFailure.FORM_CHANGED, dao.getSignature("a")?.failure)

        dao.deleteFailedSignatures("user-1", "pay-1")
        assertNull(dao.getSignature("a"))

        dao.insertSignature(signature("b", "2026-10-28T08:00:00Z"))
        dao.insertSignature(signature("c", "2026-10-28T08:00:00Z", userId = "user-2"))
        dao.deleteSignaturesOf("user-1", "pay-1")
        assertNull(dao.getSignature("b"))
        dao.deleteSignaturesForOtherUsers("user-1")
        assertNull(dao.getSignature("c"))

        dao.insertSignature(signature("d", "2026-10-28T09:00:00Z"))
        dao.deleteAllSignatures()
        assertTrue(dao.getUnsent("user-1").isEmpty())
    }
}
