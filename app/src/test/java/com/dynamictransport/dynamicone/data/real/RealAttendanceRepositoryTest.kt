package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.database.AttendancePunchEntity
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.AttendanceDayState
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.LocalPunchState
import com.dynamictransport.dynamicone.domain.model.LoginResult
import com.dynamictransport.dynamicone.domain.model.PunchFailure
import com.dynamictransport.dynamicone.domain.model.PunchResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.testing.DriverApiJson
import com.dynamictransport.dynamicone.testing.MobileApiJson
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.RoutingDispatcher.Companion.MOBILE_API
import com.dynamictransport.dynamicone.testing.drainRequests
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import com.dynamictransport.dynamicone.testing.route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Office attendance against the Mobile API contract (docs/15_MOBILE_APP_API.md §5) with the production HTTP stack. */
class RealAttendanceRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    private val tag = "04A224B2C35E80"
    private val checkedInStatus = MobileApiJson.status(
        state = "CheckedIn",
        nextAction = "CheckOut",
        workDate = "2026-09-12",
        lastPunch = MobileApiJson.punch("p-1", "punch-1", "In", "2026-09-12T07:59:58Z"),
    )

    @Before
    fun setUp() {
        h = RealApiHarness(folder, mobileAccount = true)
    }

    @After
    fun tearDown() = h.close()

    private fun recordedResponse(commandId: String = "punch-1", direction: String = "In") = jsonResponse(
        MobileApiJson.punchResult(MobileApiJson.punch("p-1", commandId, direction, "2026-09-12T07:59:58Z"), checkedInStatus),
        code = 201,
    )

    @Test
    fun `a scan is stored first and sent with its command id, tag, times and installation`() = runBlocking {
        h.signInOffice()
        h.routes.onRequest("POST", "$MOBILE_API/attendance/punches") { recordedResponse() }
        h.uptime.elapsedMillis = 5_000_000L

        val result = h.attendance.recordPunch(AttendanceDirection.IN, "04:a2:24:b2:c3:5e:80")

        assertTrue(result is PunchResult.Recorded)
        val request = h.server.drainRequests().single()
        assertEquals("POST /api/mobile/v1/attendance/punches", request.route)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("punch-1", body.getValue("commandId").jsonPrimitive.content)
        assertEquals("In", body.getValue("direction").jsonPrimitive.content)
        assertEquals(tag, body.getValue("tagUid").jsonPrimitive.content)
        assertEquals("2026-09-12T08:00:00Z", body.getValue("scannedAtUtc").jsonPrimitive.content)
        assertEquals(0L, body.getValue("elapsedSinceScanMs").jsonPrimitive.long)
        assertEquals(h.sessionPreferences.installationId(), body.getValue("installationId").jsonPrimitive.content)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))

        val stored = h.attendanceDao.all.single()
        assertEquals(LocalPunchState.SYNCED, stored.state)
        assertEquals(Instant.parse("2026-09-12T07:59:58Z"), stored.occurredAt)
        val status = h.attendance.observeStatus().first()
        assertTrue(status?.checkedIn == true)
        assertEquals(AttendanceDirection.OUT, status?.nextDirection)
    }

    @Test
    fun `without a connection the punch waits on the phone and is sent later with the same command id`() = runBlocking {
        h.signInOffice()
        h.routes.on("POST", "$MOBILE_API/attendance/punches") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }

        val offline = h.attendance.recordPunch(AttendanceDirection.IN, tag)

        assertEquals(PunchResult.Saved("punch-1"), offline)
        assertEquals(LocalPunchState.PENDING, h.attendanceDao.all.single().state)
        assertEquals(1, h.attendanceScheduler.requests)
        assertEquals(1, h.attendance.observeLocalPunches().first().size)

        // Back online 90 seconds later on the same boot: the server can place the scan in time.
        h.server.drainRequests()
        h.uptime.elapsedMillis += 90_000
        h.routes.onRequest("POST", "$MOBILE_API/attendance/punches") { recordedResponse() }

        assertTrue(h.attendance.sendPending())

        val resent = Json.parseToJsonElement(h.server.drainRequests().single().body.readUtf8()).jsonObject
        assertEquals("punch-1", resent.getValue("commandId").jsonPrimitive.content)
        assertEquals("2026-09-12T08:00:00Z", resent.getValue("scannedAtUtc").jsonPrimitive.content)
        assertEquals(90_000L, resent.getValue("elapsedSinceScanMs").jsonPrimitive.long)
        assertEquals(LocalPunchState.SYNCED, h.attendanceDao.all.single().state)
    }

    @Test
    fun `after a restart of the phone the elapsed time is not sent`() = runBlocking {
        h.signInOffice()
        h.routes.on("POST", "$MOBILE_API/attendance/punches") { problemResponse(503, "service_unavailable") }
        h.attendance.recordPunch(AttendanceDirection.OUT, tag)
        h.server.drainRequests()

        h.uptime.boot = 8
        h.uptime.elapsedMillis = 10_000
        h.routes.onRequest("POST", "$MOBILE_API/attendance/punches") { recordedResponse(direction = "Out") }
        assertTrue(h.attendance.sendPending())

        val body = Json.parseToJsonElement(h.server.drainRequests().single().body.readUtf8()).jsonObject
        assertEquals(JsonNull, body["elapsedSinceScanMs"] ?: JsonNull)
    }

    @Test
    fun `a send interrupted by an app restart goes out again with the same command id`() = runBlocking {
        h.signInOffice()
        // Left behind by a process that was killed while sending: the server's answer is unknown.
        h.attendanceDao.insert(
            AttendancePunchEntity(
                commandId = "punch-0",
                userId = "user-9",
                direction = AttendanceDirection.IN,
                tagUid = tag,
                scannedAt = Instant.parse("2026-09-12T07:55:00Z"),
                scannedElapsedMillis = 500_000L,
                bootCount = 7,
                state = LocalPunchState.SENDING,
                failure = null,
                occurredAt = null,
                pointName = null,
                createdAt = Instant.parse("2026-09-12T07:55:00Z"),
                updatedAt = Instant.parse("2026-09-12T07:55:00Z"),
            ),
        )
        h.routes.onRequest("POST", "$MOBILE_API/attendance/punches") { recordedResponse(commandId = "punch-0") }

        assertTrue(h.attendance.sendPending())

        val body = Json.parseToJsonElement(h.server.drainRequests().single().body.readUtf8()).jsonObject
        assertEquals("punch-0", body.getValue("commandId").jsonPrimitive.content)
        assertEquals("2026-09-12T07:55:00Z", body.getValue("scannedAtUtc").jsonPrimitive.content)
        // The monotonic clock of the same boot still says how long ago the scan was.
        assertEquals(500_000L, body.getValue("elapsedSinceScanMs").jsonPrimitive.long)
        assertEquals(LocalPunchState.SYNCED, h.attendanceDao.all.single().state)
    }

    @Test
    fun `sending stops at the first punch the server cannot take and is retried later`() = runBlocking {
        h.signInOffice()
        h.routes.on("POST", "$MOBILE_API/attendance/punches") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        h.attendance.recordPunch(AttendanceDirection.IN, tag)
        h.attendance.recordPunch(AttendanceDirection.OUT, tag)
        h.server.drainRequests()

        assertFalse(h.attendance.sendPending())

        assertEquals(1, h.server.drainRequests().size)
        assertTrue(h.attendanceDao.all.all { it.state == LocalPunchState.PENDING })
    }

    @Test
    fun `a wrong tag is rejected, shown as failed and can be resent unchanged or removed`() = runBlocking {
        h.signInOffice()
        h.routes.on("POST", "$MOBILE_API/attendance/punches") { problemResponse(400, "attendance_tag_direction_mismatch") }

        val result = h.attendance.recordPunch(AttendanceDirection.IN, tag)

        assertEquals(PunchResult.Failed(PunchFailure.WRONG_TAG_DIRECTION), result)
        val failed = h.attendance.observeLocalPunches().first().single()
        assertEquals(LocalPunchState.FAILED, failed.state)
        assertEquals(PunchFailure.WRONG_TAG_DIRECTION, failed.failure)
        assertEquals(0, h.attendanceScheduler.requests)

        h.server.drainRequests()
        h.routes.onRequest("POST", "$MOBILE_API/attendance/punches") { recordedResponse() }
        assertTrue(h.attendance.resend("punch-1") is PunchResult.Recorded)
        val resent = Json.parseToJsonElement(h.server.drainRequests().single().body.readUtf8()).jsonObject
        assertEquals("punch-1", resent.getValue("commandId").jsonPrimitive.content)

        // Only rejected punches can be removed.
        h.attendance.dismiss("punch-1")
        assertEquals(1, h.attendanceDao.all.size)
    }

    @Test
    fun `only 400 and 409 are final answers, everything else keeps the punch for a later attempt`() = runBlocking {
        h.signInOffice()
        // docs/15 §5.3: 400/409 are rejections with a reason; 401, 403, 404, 429 and 5xx mean "not sent".
        val cases = listOf(
            problemResponse(400, "attendance_tag_unknown") to PunchFailure.TAG_UNKNOWN,
            problemResponse(400, "attendance_punch_too_old") to PunchFailure.TIME_REJECTED,
            problemResponse(400, "attendance_time_invalid") to PunchFailure.TIME_REJECTED,
            problemResponse(409, "command_conflict") to PunchFailure.CONFLICT,
            problemResponse(404, "api_disabled") to null,
            problemResponse(404, "not_found") to null,
            problemResponse(429, "rate_limited") to null,
            problemResponse(500, "server_error") to null,
            problemResponse(503, "service_unavailable") to null,
        )
        cases.forEach { (response, expected) ->
            h.routes.on("POST", "$MOBILE_API/attendance/punches") { response }
            val result = h.attendance.recordPunch(AttendanceDirection.IN, tag)
            if (expected == null) {
                assertEquals("$response", null, (result as? PunchResult.Saved)?.hold)
            } else {
                assertEquals(PunchResult.Failed(expected), result)
            }
            h.attendanceDao.all.forEach { if (it.state == LocalPunchState.FAILED) h.attendance.dismiss(it.commandId) }
        }
        assertTrue(h.attendanceDao.all.all { it.state == LocalPunchState.PENDING })
    }

    @Test
    fun `an account without attendance access keeps its punch and is told why`() = runBlocking {
        h.signInOffice()
        h.routes.on("POST", "$MOBILE_API/attendance/punches") { problemResponse(403, "attendance_access_required") }

        // docs/15 §5.3: 403 means "not sent", not "rejected": the same command id goes out again later.
        val result = h.attendance.recordPunch(AttendanceDirection.IN, tag)

        assertEquals(PunchResult.Saved("punch-1", PunchFailure.ACCESS_REQUIRED), result)
        val stored = h.attendanceDao.all.single()
        assertEquals(LocalPunchState.PENDING, stored.state)
        assertEquals(PunchFailure.ACCESS_REQUIRED, stored.failure)
        assertEquals(1, h.attendanceScheduler.requests)

        // The office enables attendance again: the waiting punch is recorded and loses the reason.
        h.server.drainRequests()
        h.routes.onRequest("POST", "$MOBILE_API/attendance/punches") { recordedResponse() }
        assertTrue(h.attendance.sendPending())
        val sent = Json.parseToJsonElement(h.server.drainRequests().single().body.readUtf8()).jsonObject
        assertEquals("punch-1", sent.getValue("commandId").jsonPrimitive.content)
        assertEquals(LocalPunchState.SYNCED, h.attendanceDao.all.single().state)
        assertNull(h.attendanceDao.all.single().failure)
    }

    @Test
    fun `a busy server is left alone for the time it asked for`() = runBlocking {
        h.signInOffice()
        h.routes.on("POST", "$MOBILE_API/attendance/punches") { problemResponse(429, "rate_limited", retryAfterSeconds = 120) }

        assertEquals(PunchResult.Saved("punch-1"), h.attendance.recordPunch(AttendanceDirection.IN, tag))

        assertEquals(listOf(120L), h.attendanceScheduler.delaysSeconds)
        assertEquals(LocalPunchState.PENDING, h.attendanceDao.all.single().state)
        assertNull(h.attendanceDao.all.single().failure)
    }

    @Test
    fun `a lost answer is sent again with the same command and a longer elapsed time`() = runBlocking {
        h.signInOffice()
        h.uptime.elapsedMillis = 1_000_000L
        // The server recorded the punch but the answer never arrived.
        h.routes.on("POST", "$MOBILE_API/attendance/punches") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }

        assertEquals(PunchResult.Saved("punch-1"), h.attendance.recordPunch(AttendanceDirection.IN, tag))
        val first = Json.parseToJsonElement(h.server.drainRequests().single().body.readUtf8()).jsonObject

        // Two minutes later the same command goes out again; the server answers 200 with the first result.
        h.uptime.elapsedMillis += 120_000
        h.clock.now = h.clock.now.plusSeconds(120)
        h.routes.onRequest("POST", "$MOBILE_API/attendance/punches") {
            jsonResponse(MobileApiJson.punchResult(MobileApiJson.punch("p-1", "punch-1", "In", "2026-09-12T07:59:58Z"), checkedInStatus))
        }
        val replayed = h.attendance.sendPending()

        assertTrue(replayed)
        val second = Json.parseToJsonElement(h.server.drainRequests().single().body.readUtf8()).jsonObject
        // Everything the server compares stays the same (docs/15 §5.3); only the elapsed time grows.
        listOf("commandId", "direction", "tagUid", "scannedAtUtc", "installationId").forEach { field ->
            assertEquals(field, first.getValue(field), second.getValue(field))
        }
        assertEquals(0L, first.getValue("elapsedSinceScanMs").jsonPrimitive.long)
        assertEquals(120_000L, second.getValue("elapsedSinceScanMs").jsonPrimitive.long)
        val stored = h.attendanceDao.all.single()
        assertEquals(LocalPunchState.SYNCED, stored.state)
        // The official time is the server's first one, not the time of the second attempt.
        assertEquals(Instant.parse("2026-09-12T07:59:58Z"), stored.occurredAt)
    }

    @Test
    fun `an expired access token is refreshed and the same command is sent once more`() = runBlocking {
        h.signInOffice()
        val punches = java.util.concurrent.atomic.AtomicInteger()
        h.routes.onRequest("POST", "$MOBILE_API/attendance/punches") { request ->
            punches.incrementAndGet()
            if (request.getHeader("Authorization") == "Bearer access-2") recordedResponse() else problemResponse(401, "unauthorized")
        }
        h.routes.on("POST", "$MOBILE_API/auth/refresh") { jsonResponse(DriverApiJson.tokens(access = "access-2", refresh = "refresh-2")) }

        val result = h.attendance.recordPunch(AttendanceDirection.IN, tag)

        assertTrue(result is PunchResult.Recorded)
        assertEquals(2, punches.get())
        val bodies = h.server.drainRequests().filter { it.route.endsWith("/attendance/punches") }.map {
            Json.parseToJsonElement(it.body.readUtf8()).jsonObject.getValue("commandId").jsonPrimitive.content
        }
        assertEquals(listOf("punch-1", "punch-1"), bodies)
        assertEquals("access-2", h.tokenStore.tokens?.accessToken)
    }

    @Test
    fun `nothing is stored without a session or with an unreadable tag`() = runBlocking {
        assertEquals(PunchResult.NotLoggedIn, h.attendance.recordPunch(AttendanceDirection.IN, tag))
        h.signInOffice()
        assertEquals(PunchResult.UnreadableTag, h.attendance.recordPunch(AttendanceDirection.IN, "12"))

        assertTrue(h.attendanceDao.all.isEmpty())
        assertTrue(h.server.drainRequests().isEmpty())
    }

    @Test
    fun `status and month are read from the server and a missing API is not supported`() = runBlocking {
        h.signInOffice()
        h.routes.on("GET", "$MOBILE_API/attendance/status") { jsonResponse(checkedInStatus) }
        h.routes.on("GET", "$MOBILE_API/attendance/days", mapOf("from" to "2026-09-01", "to" to "2026-09-30")) {
            jsonResponse(
                """{"timeZoneId":"Europe/Warsaw","days":[""" +
                    """{"workDate":"2026-09-10","state":"Complete","workedMinutes":492,"punches":[""" +
                    MobileApiJson.punch("p-3", null, "Out", "2026-09-10T15:10:00Z") + "," +
                    MobileApiJson.punch("p-2", "c-2", "In", "2026-09-10T06:58:00Z") + "]}," +
                    """{"workDate":"2026-09-11","state":"Incomplete","workedMinutes":null,"punches":[""" +
                    MobileApiJson.punch("p-4", "c-4", "In", "2026-09-11T07:02:00Z") + "]}]}",
            )
        }

        assertEquals(RefreshResult.SUCCESS, h.attendance.refreshStatus())
        assertEquals(LocalDate.of(2026, 9, 12), h.attendance.observeStatus().first()?.workDate)

        val september = YearMonth.of(2026, 9)
        assertEquals(RefreshResult.SUCCESS, h.attendance.refreshMonth(september))
        val month = h.attendance.observeMonth(september).first()!!
        assertEquals(listOf(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 10)), month.days.map { it.workDate })
        assertEquals(AttendanceDayState.INCOMPLETE, month.days[0].state)
        assertNull(month.days[0].workedMinutes)
        assertEquals(492, month.days[1].workedMinutes)
        assertEquals(listOf(AttendanceDirection.IN, AttendanceDirection.OUT), month.days[1].punches.map { it.direction })

        h.routes.on("GET", "$MOBILE_API/attendance/status") { MockResponse().setResponseCode(404) }
        assertEquals(RefreshResult.NOT_SUPPORTED, h.attendance.refreshStatus())
        // An account whose office access was removed is told that, not that the server has no attendance.
        h.routes.on("GET", "$MOBILE_API/attendance/status") { problemResponse(403, "attendance_access_required") }
        assertEquals(RefreshResult.ACCESS_REQUIRED, h.attendance.refreshStatus())
        h.routes.on("GET", "$MOBILE_API/attendance/days", mapOf("from" to "2026-09-01", "to" to "2026-09-30")) {
            problemResponse(403, "attendance_access_required")
        }
        assertEquals(RefreshResult.ACCESS_REQUIRED, h.attendance.refreshMonth(september))
    }

    // --- Mobile API sign-in and modules ---

    @Test
    fun `office staff sign in through the Mobile API and get the attendance module only`() = runBlocking {
        h.routes.on("POST", "$MOBILE_API/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        h.routes.on("GET", "$MOBILE_API/me") { jsonResponse(MobileApiJson.profile(listOf("officeAttendance", "futureModule"))) }

        assertEquals(LoginResult.SUCCESS, h.auth.login("ayse.yilmaz", "secret"))

        assertEquals(SessionState.LoggedIn("user-9", setOf(AppModule.OFFICE_ATTENDANCE)), h.auth.sessionState.value)
        assertEquals("Ayşe Yılmaz", h.auth.observeProfile().first()?.displayName)
        assertNull(h.sessionPreferences.driverId())
        assertEquals(
            listOf("POST /api/mobile/v1/auth/login", "GET /api/mobile/v1/me"),
            h.server.drainRequests().map { it.route },
        )
    }

    @Test
    fun `the driver module needs a driver id`() = runBlocking {
        h.routes.on("POST", "$MOBILE_API/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        h.routes.on("POST", "$MOBILE_API/auth/logout") { MockResponse().setResponseCode(204) }
        h.routes.on("GET", "$MOBILE_API/me") { jsonResponse(MobileApiJson.profile(listOf("driver"), driverId = null)) }

        assertEquals(LoginResult.APP_ACCESS_REQUIRED, h.auth.login("no.modules", "secret"))

        assertFalse(h.auth.sessionState.value is SessionState.LoggedIn)
        assertNull(h.tokenStore.tokens)
        assertTrue(h.server.drainRequests().any { it.route == "POST /api/mobile/v1/auth/logout" })
    }

    @Test
    fun `an account with both modules opens the driver and the office screens and keeps its push device`() = runBlocking {
        h.routes.on("POST", "$MOBILE_API/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        h.routes.on("GET", "$MOBILE_API/me") {
            jsonResponse(MobileApiJson.profile(listOf("driver", "officeAttendance"), driverId = "driver-1"))
        }
        h.routes.on("POST", "/me/devices") { MockResponse().setResponseCode(204) }

        assertEquals(LoginResult.SUCCESS, h.auth.login("both.modules", "secret"))

        assertEquals(
            SessionState.LoggedIn("user-9", setOf(AppModule.DRIVER, AppModule.OFFICE_ATTENDANCE)),
            h.auth.sessionState.value,
        )
        assertEquals("driver-1", h.sessionPreferences.driverId())

        // docs/15 §3: the Driver API device registration works with the Mobile API token.
        h.server.drainRequests()
        h.pushRegistrar.register("fcm-token")
        val device = h.server.drainRequests().single()
        assertEquals("POST /api/driver/v1/me/devices", device.route)
        assertEquals("Bearer access-1", device.getHeader("Authorization"))
    }

    @Test
    fun `a session stored by the Driver API version refreshes on the Mobile API and gains its modules`() = runBlocking {
        // Installed app before the Mobile API: the session key was the driver id and the only module was driver.
        h.signIn("driver-1")
        h.commandDao.insert(h.storedCommand("cmd-1"))
        val refreshBodies = mutableListOf<String>()
        h.routes.onRequest("POST", "$MOBILE_API/auth/refresh") { request ->
            refreshBodies += request.body.readUtf8()
            jsonResponse(DriverApiJson.tokens(access = "access-2", refresh = "refresh-2"))
        }
        h.routes.onRequest("GET", "$MOBILE_API/me") { request ->
            if (request.getHeader("Authorization") == "Bearer access-2") {
                jsonResponse(MobileApiJson.profile(listOf("driver", "officeAttendance"), driverId = "driver-1"))
            } else {
                problemResponse(401, "unauthorized")
            }
        }

        h.auth.restoreSession()

        // The refresh token of the Driver session is accepted by the Mobile API: nobody has to sign in again.
        val session = withTimeout(5_000) {
            h.auth.sessionState.first { (it as? SessionState.LoggedIn)?.userId == "user-9" }
        } as SessionState.LoggedIn
        assertEquals(setOf(AppModule.DRIVER, AppModule.OFFICE_ATTENDANCE), session.modules)
        assertEquals(listOf("""{"refreshToken":"refresh-1"}"""), refreshBodies)
        assertEquals("user-9", h.sessionPreferences.userId())
        assertEquals("driver-1", h.sessionPreferences.driverId())
        // The driver's unsent commands belong to the same driver and survive the update.
        assertEquals(listOf("cmd-1"), h.commandDao.all.map { it.commandId })
    }

    @Test
    fun `an account without mobile access is told so`() = runBlocking {
        h.routes.on("POST", "$MOBILE_API/auth/login") { problemResponse(403, "mobile_access_required") }

        assertEquals(LoginResult.APP_ACCESS_REQUIRED, h.auth.login("someone", "secret"))
    }

    @Test
    fun `a stored office session opens offline with its modules`() = runBlocking {
        h.signInOffice()

        h.auth.restoreSession()

        assertEquals(SessionState.LoggedIn("user-9", setOf(AppModule.OFFICE_ATTENDANCE)), h.auth.sessionState.value)
    }

    @Test
    fun `punches stay with their account and never go out with another account's session`() = runBlocking {
        h.signInOffice()
        h.routes.on("POST", "$MOBILE_API/attendance/punches") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        h.attendance.recordPunch(AttendanceDirection.IN, tag)
        h.routes.on("POST", "$MOBILE_API/auth/logout") { MockResponse().setResponseCode(204) }

        // Signing out keeps the unsent punch for the same account.
        h.auth.logout()
        assertEquals(1, h.attendanceDao.all.size)
        assertTrue(h.attendance.observeLocalPunches().first().isEmpty())

        h.routes.on("POST", "$MOBILE_API/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        h.routes.on("GET", "$MOBILE_API/me") { jsonResponse(MobileApiJson.profile(listOf("officeAttendance"), userId = "user-10")) }
        assertEquals(LoginResult.SUCCESS, h.auth.login("someone.else", "secret"))

        assertTrue(h.attendanceDao.all.isEmpty())
        // No push device calls for an account without the driver module.
        assertTrue(h.server.drainRequests().none { it.route.contains("/me/devices") })
    }
}
