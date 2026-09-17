package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.ApiProblemParser
import com.dynamictransport.dynamicone.core.network.AttendanceDaysResponseDto
import com.dynamictransport.dynamicone.core.network.AttendancePunchResultDto
import com.dynamictransport.dynamicone.core.network.AttendanceStatusDto
import com.dynamictransport.dynamicone.core.network.MobileProfileResponseDto
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.AttendanceDayState
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.AttendancePunchSource
import com.dynamictransport.dynamicone.domain.model.LocalPunchState
import com.dynamictransport.dynamicone.domain.model.LoginResult
import com.dynamictransport.dynamicone.domain.model.PunchFailure
import com.dynamictransport.dynamicone.domain.model.PunchResult
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.testing.DriverApiJson
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.RoutingDispatcher.Companion.MOBILE_API
import com.dynamictransport.dynamicone.testing.TEST_JSON
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The backend's raw Mobile API v1 responses (src/test/resources/mobile-v1, produced by the backend's HTTP tests) against
 * the app's JSON settings and mappers. A format change on either side breaks this test.
 */
class MobileApiContractTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    @Before
    fun setUp() {
        h = RealApiHarness(folder, mobileAccount = true)
    }

    @After
    fun tearDown() = h.close()

    private fun example(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("mobile-v1/$name")) { "missing example $name" }.readText()

    @Test
    fun `me of an office account`() {
        val profile = AccountMapping.profile(TEST_JSON.decodeFromString(MobileProfileResponseDto.serializer(), example("me-200.json")))!!

        assertEquals("d614387e-c113-400e-87ab-4738de4593ea", profile.id)
        assertEquals(setOf(AppModule.OFFICE_ATTENDANCE), profile.modules)
        assertNull(profile.driverId)
        assertNull(profile.preferredLanguage)
    }

    @Test
    fun `status while checked in and after a finished day`() {
        val checkedIn = AttendanceMapping.status(TEST_JSON.decodeFromString(AttendanceStatusDto.serializer(), example("status-200.json")))!!
        assertTrue(checkedIn.checkedIn)
        assertEquals(AttendanceDirection.OUT, checkedIn.nextDirection)
        assertEquals(LocalDate.of(2026, 9, 17), checkedIn.workDate)
        assertEquals(ZoneId.of("Europe/Warsaw"), checkedIn.zone)
        val last = checkedIn.lastPunch!!
        assertEquals(AttendanceDirection.IN, last.direction)
        assertEquals(Instant.parse("2026-09-17T14:04:15.9223902Z"), last.occurredAt)
        assertEquals(AttendancePunchSource.MOBILE_NFC, last.source)
        assertEquals("Office NFC", last.pointName)

        val checkedOut = AttendanceMapping.status(
            TEST_JSON.decodeFromString(AttendanceStatusDto.serializer(), example("status-checked-out-200.json")),
        )!!
        assertFalse(checkedOut.checkedIn)
        assertEquals(AttendanceDirection.IN, checkedOut.nextDirection)
        assertEquals(LocalDate.of(2026, 9, 16), checkedOut.lastPunch?.workDate)
    }

    @Test
    fun `punch result and its replay are the same`() {
        val first = TEST_JSON.decodeFromString(AttendancePunchResultDto.serializer(), example("punch-201.json"))
        val replay = TEST_JSON.decodeFromString(AttendancePunchResultDto.serializer(), example("punch-replay-200.json"))

        assertEquals(first, replay)
        assertNotNull(first.punch?.let(AttendanceMapping::punch))
        assertNotNull(first.status?.let(AttendanceMapping::status))
    }

    @Test
    fun `days with every day state, terminal and correction punches`() {
        val month = AttendanceMapping.month(
            YearMonth.of(2026, 9),
            TEST_JSON.decodeFromString(AttendanceDaysResponseDto.serializer(), example("days-200.json")),
        )!!

        assertEquals((17 downTo 13).map { LocalDate.of(2026, 9, it) }, month.days.map { it.workDate })
        assertEquals(
            listOf(
                AttendanceDayState.OPEN,
                AttendanceDayState.COMPLETE,
                AttendanceDayState.INCOMPLETE,
                AttendanceDayState.NEEDS_REVIEW,
                AttendanceDayState.COMPLETE,
            ),
            month.days.map { it.state },
        )
        assertEquals(listOf(null, 480, null, null, 480), month.days.map { it.workedMinutes })
        // Every punch is readable, including a correction without receipt time, command id or point name.
        assertEquals(listOf(1, 2, 1, 2, 2), month.days.map { it.punches.size })
        val terminalDay = month.days.last()
        assertEquals(listOf(AttendancePunchSource.TERMINAL, AttendancePunchSource.CORRECTION), terminalDay.punches.map { it.source })
        assertNull(terminalDay.punches[1].commandId)
        assertNull(terminalDay.punches[1].pointName)
    }

    @Test
    fun `direction mismatch problem`() {
        val problem = ApiProblemParser.parse(400, Headers.headersOf(), example("direction-mismatch-400.json"))

        assertEquals("attendance_tag_direction_mismatch", problem.code)
        assertEquals("0HNOKOI7LVK88", problem.traceId)
    }

    @Test
    fun `the HTTP stack records, replays and rejects punches with the backend's bodies`() = runBlocking {
        h.signInOffice()
        h.routes.on("POST", "$MOBILE_API/attendance/punches") { jsonResponse(example("punch-201.json"), code = 201) }

        val recorded = h.attendance.recordPunch(AttendanceDirection.IN, "04A224B2C35E80")

        assertTrue(recorded is PunchResult.Recorded)
        assertEquals(LocalPunchState.SYNCED, h.attendanceDao.all.single().state)
        assertTrue(h.attendance.observeStatus().first()?.checkedIn == true)

        h.routes.on("POST", "$MOBILE_API/attendance/punches") { jsonResponse(example("punch-replay-200.json")) }
        assertTrue(h.attendance.resend("punch-1") is PunchResult.Recorded)

        h.routes.on("POST", "$MOBILE_API/attendance/punches") {
            MockResponse().setResponseCode(400).setHeader("Content-Type", "application/problem+json").setBody(example("direction-mismatch-400.json"))
        }
        assertEquals(PunchResult.Failed(PunchFailure.WRONG_TAG_DIRECTION), h.attendance.recordPunch(AttendanceDirection.OUT, "04A224B2C35E80"))
    }

    @Test
    fun `a stored session ends when the account has no mobile module left`() = runBlocking {
        h.signInOffice()
        h.routes.on("GET", "$MOBILE_API/me") { problemResponse(403, "mobile_access_required") }
        h.routes.on("POST", "$MOBILE_API/auth/logout") { MockResponse().setResponseCode(204) }

        h.auth.restoreSession()

        val deadline = System.currentTimeMillis() + 5_000
        while (h.auth.sessionState.value != SessionState.LoggedOut && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(SessionState.LoggedOut, h.auth.sessionState.value)
        assertNull(h.tokenStore.tokens)
    }

    @Test
    fun `office staff sign in with the backend's me body`() = runBlocking {
        h.routes.on("POST", "$MOBILE_API/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        h.routes.on("GET", "$MOBILE_API/me") { jsonResponse(example("me-200.json")) }

        assertEquals(LoginResult.SUCCESS, h.auth.login("office.user", "secret"))
        assertEquals(
            SessionState.LoggedIn("d614387e-c113-400e-87ab-4738de4593ea", setOf(AppModule.OFFICE_ATTENDANCE)),
            h.auth.sessionState.value,
        )
    }
}
