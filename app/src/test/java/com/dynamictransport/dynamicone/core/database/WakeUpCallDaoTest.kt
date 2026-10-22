package com.dynamictransport.dynamicone.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class WakeUpCallDaoTest {

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = database.wakeUpCallDao()

    @After
    fun tearDown() = database.close()

    private fun call(id: String, at: String, driverId: String = "driver-1", state: WakeUpRowState = WakeUpRowState.RINGING) = WakeUpCallEntity(
        callId = id,
        driverId = driverId,
        reason = "call_office",
        requestedByDisplayName = "Ayşe Yılmaz",
        requestedAt = Instant.parse(at),
        expiresAt = Instant.parse(at).plusSeconds(300),
        tripId = null,
        tripNo = null,
        state = state,
        acknowledgedAt = null,
        resultAt = null,
        updatedAt = Instant.parse(at),
    )

    @Test
    fun `ringing calls and results to send are listed per driver oldest first`() = runBlocking {
        dao.upsert(call("late", "2026-10-22T06:00:00Z"))
        dao.upsert(call("early", "2026-10-22T05:00:00Z"))
        dao.upsert(call("answered", "2026-10-22T04:00:00Z", state = WakeUpRowState.ACKNOWLEDGED_PENDING))
        dao.upsert(call("missed", "2026-10-22T03:00:00Z", state = WakeUpRowState.EXPIRED_PENDING))
        dao.upsert(call("done", "2026-10-22T02:00:00Z", state = WakeUpRowState.ACKNOWLEDGED))
        dao.upsert(call("other", "2026-10-22T07:00:00Z", driverId = "driver-2"))

        assertEquals(listOf("early", "late"), dao.ringing("driver-1").map { it.callId })
        assertEquals(listOf("missed", "answered"), dao.pendingSends("driver-1").map { it.callId })
        assertEquals("late", dao.observeLatest("driver-1").first()?.callId)
        assertEquals("other", dao.observeLatest("driver-2").first()?.callId)
    }

    @Test
    fun `the result marks the row once and old rows can be removed`() = runBlocking {
        dao.upsert(call("call-1", "2026-10-22T05:00:00Z"))
        dao.upsert(call("call-0", "2026-10-10T05:00:00Z", state = WakeUpRowState.STOPPED))
        val at = Instant.parse("2026-10-22T05:01:30Z")

        dao.markResult("call-1", WakeUpRowState.ACKNOWLEDGED_PENDING, acknowledgedAt = at, resultAt = at, updatedAt = at)
        val row = dao.get("call-1")!!
        assertEquals(WakeUpRowState.ACKNOWLEDGED_PENDING, row.state)
        assertEquals(at, row.acknowledgedAt)
        assertEquals(at, row.resultAt)

        dao.updateState("call-1", WakeUpRowState.ACKNOWLEDGED, at.plusSeconds(5))
        assertEquals(WakeUpRowState.ACKNOWLEDGED, dao.get("call-1")!!.state)
        assertEquals(at, dao.get("call-1")!!.acknowledgedAt)

        val old = dao.olderThan(Instant.parse("2026-10-15T00:00:00Z"))
        assertEquals(listOf("call-0"), old.map { it.callId })
        dao.delete(old.map { it.callId })
        assertNull(dao.get("call-0"))
        assertEquals("call-1", dao.observeLatest("driver-1").first()?.callId)
    }
}
