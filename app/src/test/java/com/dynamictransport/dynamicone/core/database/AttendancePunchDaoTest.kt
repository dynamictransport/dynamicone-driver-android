package com.dynamictransport.dynamicone.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.LocalPunchState
import com.dynamictransport.dynamicone.domain.model.PunchFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AttendancePunchDaoTest {

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = database.attendancePunchDao()

    @After
    fun tearDown() = database.close()

    private fun punch(id: String, scannedAt: String, userId: String = "user-1", state: LocalPunchState = LocalPunchState.PENDING) =
        AttendancePunchEntity(
            commandId = id,
            userId = userId,
            direction = AttendanceDirection.IN,
            tagUid = "04A224B2C35E80",
            scannedAt = Instant.parse(scannedAt),
            scannedElapsedMillis = 1_000,
            bootCount = 3,
            state = state,
            failure = null,
            occurredAt = null,
            pointName = null,
            createdAt = Instant.parse(scannedAt),
            updatedAt = Instant.parse(scannedAt),
        )

    @Test
    fun `unsent punches of one account are sent oldest first`() = runBlocking {
        dao.insert(punch("late", "2026-09-17T09:00:00Z"))
        dao.insert(punch("early", "2026-09-17T07:00:00Z", state = LocalPunchState.SENDING))
        dao.insert(punch("done", "2026-09-17T06:00:00Z", state = LocalPunchState.SYNCED))
        dao.insert(punch("other", "2026-09-17T05:00:00Z", userId = "user-2"))

        assertEquals(listOf("early", "late"), dao.getUnsent("user-1").map { it.commandId })
        assertEquals(listOf("late", "early", "done"), dao.observe("user-1").first().map { it.commandId })
    }

    @Test
    fun `states, recorded times and clean-up`() = runBlocking {
        dao.insert(punch("a", "2026-09-10T07:00:00Z"))
        dao.insert(punch("b", "2026-09-17T07:00:00Z"))
        dao.insert(punch("c", "2026-09-17T08:00:00Z", userId = "user-2"))

        dao.updateState("b", LocalPunchState.FAILED, PunchFailure.WRONG_TAG_DIRECTION, Instant.parse("2026-09-17T07:00:05Z"))
        assertEquals(PunchFailure.WRONG_TAG_DIRECTION, dao.get("b")?.failure)

        dao.markSynced("a", Instant.parse("2026-09-10T06:59:58Z"), "Entrance", Instant.parse("2026-09-10T07:00:01Z"))
        val synced = dao.get("a")
        assertEquals(LocalPunchState.SYNCED, synced?.state)
        assertEquals("Entrance", synced?.pointName)
        assertNull(synced?.failure)

        dao.deleteSyncedBefore(Instant.parse("2026-09-14T00:00:00Z"))
        assertNull(dao.get("a"))

        dao.deleteForOtherUsers("user-1")
        assertNull(dao.get("c"))
        dao.delete("b")
        assertEquals(emptyList<AttendancePunchEntity>(), dao.observe("user-1").first())
    }
}
