package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import com.dynamictransport.dynamicone.domain.model.IncidentCategory
import com.dynamictransport.dynamicone.domain.model.LoginResult
import com.dynamictransport.dynamicone.domain.model.PasswordChangeResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.StopActivity
import com.dynamictransport.dynamicone.domain.model.StopStatus
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import com.dynamictransport.dynamicone.domain.repository.TripActionCommand
import com.dynamictransport.dynamicone.testing.DriverApiJson
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.drainRequests
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import com.dynamictransport.dynamicone.testing.route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Real repositories + production HTTP client stack: session, trip list/detail, accept and the legacy status workflow. */
class RealApiRepositoriesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    @Before
    fun setUp() {
        h = RealApiHarness(folder)
    }

    @After
    fun tearDown() = h.close()

    /** A trip started before trip actions existed: the execution plan says `LegacyStatuses`. */
    private fun routeLegacyTrip(tripStatus: () -> String, reported: () -> List<String>) {
        h.routes.on("GET", "/trips") { jsonResponse(DriverApiJson.page(listOf(DriverApiJson.summary("t1", "Active", tripStatus())))) }
        h.routes.on("GET", "/trips/t1") { jsonResponse(DriverApiJson.detail(DriverApiJson.summary("t1", "Active", tripStatus()))) }
        h.routes.on("GET", "/trips/t1/execution") { jsonResponse(DriverApiJson.execution(workflow = "LegacyStatuses", status = tripStatus())) }
        h.routes.on("GET", "/trips/t1/statuses") {
            // The server lists the newest report first.
            val statuses = reported()
            jsonResponse(
                DriverApiJson.page(
                    statuses.mapIndexed { index, status -> DriverApiJson.status(status, "2026-09-12T06:%02d:00Z".format(statuses.size - index)) },
                ),
            )
        }
    }

    private fun loadTrip() = runBlocking {
        assertEquals(RefreshResult.SUCCESS, h.trips.refreshAll())
        assertEquals(RefreshResult.SUCCESS, h.trips.refreshTrip("t1"))
    }

    // --- Authentication ---

    @Test
    fun `login stores both tokens and starts the session from me`() = runBlocking {
        h.routes.on("POST", "/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        h.routes.on("GET", "/me") { jsonResponse(DriverApiJson.PROFILE) }

        assertEquals(LoginResult.SUCCESS, h.auth.login("  anna.nowak ", "secret", null))

        assertEquals(SessionState.LoggedIn("driver-1"), h.auth.sessionState.value)
        assertEquals("Anna Nowak", h.auth.observeProfile().first()?.displayName)
        assertEquals("access-1", h.tokenStore.tokens?.accessToken)
        assertEquals("refresh-1", h.tokenStore.tokens?.refreshToken)
        assertEquals("anna.nowak", h.auth.lastUsername())
        val requests = h.server.drainRequests()
        assertEquals(listOf("POST /api/driver/v1/auth/login", "GET /api/driver/v1/me"), requests.map { it.route })
        assertEquals("""{"userName":"anna.nowak","password":"secret"}""", requests[0].body.readUtf8())
        assertEquals("Bearer access-1", requests[1].getHeader("Authorization"))
    }

    @Test
    fun `login refusals are mapped from problem codes and keep no tokens`() = runBlocking {
        var next: () -> MockResponse = { problemResponse(401, "invalid_credentials") }
        h.routes.on("POST", "/auth/login") { next() }

        assertEquals(LoginResult.INVALID_CREDENTIALS, h.auth.login("anna.nowak", "wrong"))
        next = { problemResponse(401, "two_factor_required") }
        assertEquals(LoginResult.TWO_FACTOR_REQUIRED, h.auth.login("anna.nowak", "secret", "123456"))
        next = { problemResponse(403, "driver_access_required") }
        assertEquals(LoginResult.APP_ACCESS_REQUIRED, h.auth.login("anna.nowak", "secret"))
        next = { problemResponse(404, "api_disabled") }
        assertEquals(LoginResult.SERVICE_UNAVAILABLE, h.auth.login("anna.nowak", "secret"))
        next = { problemResponse(429, "rate_limit_exceeded", retryAfterSeconds = 60) }
        assertEquals(LoginResult.RATE_LIMITED, h.auth.login("anna.nowak", "secret"))
        // Retry-After: the next attempt is refused on the device.
        assertEquals(LoginResult.RATE_LIMITED, h.auth.login("anna.nowak", "secret"))

        val requests = h.server.drainRequests()
        assertEquals(5, requests.size)
        assertEquals("""{"userName":"anna.nowak","password":"secret","twoFactorCode":"123456"}""", requests[1].body.readUtf8())
        assertNull(h.tokenStore.tokens)
    }

    @Test
    fun `required password change blocks the session until the password is changed`() = runBlocking {
        h.routes.on("POST", "/auth/login") { jsonResponse(DriverApiJson.tokens(mustChangePassword = true)) }
        h.routes.on("POST", "/auth/change-password") { MockResponse().setResponseCode(204) }

        assertEquals(LoginResult.SUCCESS, h.auth.login("anna.nowak", "initial"))
        assertEquals(SessionState.PasswordChangeRequired, h.auth.sessionState.value)

        assertEquals(PasswordChangeResult.SUCCESS, h.auth.changePassword("initial", "N3w-Passw0rd!"))

        val requests = h.server.drainRequests()
        assertEquals(listOf("POST /api/driver/v1/auth/login", "POST /api/driver/v1/auth/change-password"), requests.map { it.route })
        assertEquals("Bearer access-1", requests[1].getHeader("Authorization"))
        assertEquals("""{"currentPassword":"initial","newPassword":"N3w-Passw0rd!"}""", requests[1].body.readUtf8())
        // The backend revoked every session: the driver signs in again with the new password.
        assertEquals(SessionState.LoggedOut, h.auth.sessionState.value)
        assertNull(h.tokenStore.tokens)
    }

    @Test
    fun `rejected new passwords are explained from Identity codes and keep the session`() = runBlocking {
        h.signIn()
        var next: () -> MockResponse = { problemResponse(400, "password_change_failed", errors = listOf("PasswordMismatch")) }
        h.routes.on("POST", "/auth/change-password") { next() }

        assertEquals(PasswordChangeResult.WRONG_CURRENT_PASSWORD, h.auth.changePassword("wrong-current", "N3w-Passw0rd-long"))
        next = { problemResponse(400, "password_change_failed", errors = listOf("PasswordRequiresDigit", "PasswordRequiresUpper")) }
        assertEquals(PasswordChangeResult.WEAK_PASSWORD, h.auth.changePassword("current", "new-password-long"))
        next = { problemResponse(400, "password_change_failed") }
        assertEquals(PasswordChangeResult.REJECTED, h.auth.changePassword("current", "new-password-long"))
        // Outside the documented 12–128 characters nothing is sent.
        assertEquals(PasswordChangeResult.REJECTED, h.auth.changePassword("current", "short"))

        assertEquals(3, h.server.requestCount)
        assertEquals("refresh-1", h.tokenStore.tokens?.refreshToken)
    }

    @Test
    fun `logout removes the push registration, revokes the server session and removes the session data and stored commands`() = runBlocking {
        val installationId = h.sessionPreferences.installationId()
        h.routes.on("POST", "/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        h.routes.on("GET", "/me") { jsonResponse(DriverApiJson.PROFILE) }
        h.routes.on("DELETE", "/me/devices/$installationId") { MockResponse().setResponseCode(204) }
        h.routes.on("POST", "/auth/logout") { MockResponse().setResponseCode(204) }
        h.auth.login("anna.nowak", "secret")
        h.commandDao.insert(h.storedCommand("cmd-1"))

        h.auth.logout()

        // The device is unregistered while the session can still authenticate the call.
        val requests = h.server.drainRequests().takeLast(2)
        assertEquals(listOf("DELETE /api/driver/v1/me/devices/$installationId", "POST /api/driver/v1/auth/logout"), requests.map { it.route })
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer access-1" })
        assertNull(h.tokenStore.tokens)
        assertEquals(SessionState.LoggedOut, h.auth.sessionState.value)
        assertFalse(h.sessionPreferences.sessionExpired.first())
        assertTrue(h.commandDao.all.isEmpty())
    }

    @Test
    fun `rejected refresh ends the session, returns to login and keeps stored commands for the same driver`() = runBlocking {
        h.routes.on("POST", "/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        h.routes.on("GET", "/me") { jsonResponse(DriverApiJson.PROFILE) }
        h.routes.on("GET", "/trips") { problemResponse(401, "unauthorized") }
        h.routes.on("POST", "/auth/refresh") { problemResponse(401, "invalid_refresh_token") }
        h.auth.login("anna.nowak", "secret")
        h.commandDao.insert(h.storedCommand("cmd-1"))

        assertEquals(RefreshResult.SESSION_EXPIRED, h.sync.refreshFromServer())

        withTimeout(5_000) { h.auth.sessionState.first { it == SessionState.LoggedOut } }
        assertNull(h.tokenStore.tokens)
        assertTrue(h.trips.currentTrips().isEmpty())
        assertTrue(h.sessionPreferences.sessionExpired.first())
        assertEquals(listOf("cmd-1"), h.commandDao.all.map { it.commandId })
    }

    @Test
    fun `another driver signing in removes the earlier driver's stored commands`() = runBlocking {
        h.routes.on("POST", "/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        h.routes.on("GET", "/me") { jsonResponse(DriverApiJson.PROFILE) }
        h.commandDao.insert(h.storedCommand("cmd-other", driverId = "driver-0"))
        h.commandDao.insert(h.storedCommand("cmd-own", driverId = "driver-1"))

        assertEquals(LoginResult.SUCCESS, h.auth.login("anna.nowak", "secret"))

        assertEquals(listOf("cmd-own"), h.commandDao.all.map { it.commandId })
    }

    // --- Trips ---

    @Test
    fun `an empty trip list stays empty`() = runBlocking {
        h.signIn()
        h.routes.on("GET", "/trips") { jsonResponse(DriverApiJson.page(emptyList())) }

        assertEquals(RefreshResult.SUCCESS, h.sync.refreshFromServer())

        assertTrue(h.trips.currentTrips().isEmpty())
        assertFalse(h.trips.observeCanLoadMore().first())
        assertEquals(RefreshResult.SUCCESS, h.sync.observeLastRefresh().first())
        assertEquals(h.clock.now, h.sync.observeOverview().first().lastSuccessfulSyncAt)
        assertEquals("/api/driver/v1/trips?page=1&pageSize=25&includeCompleted=false", h.server.takeRequest().path)
        assertEquals("/api/driver/v1/trips?page=1&pageSize=25&includeCompleted=true", h.server.takeRequest().path)
    }

    @Test
    fun `open assignments and completed history are separate reads and history is paged on demand`() = runBlocking {
        h.signIn()
        val origin = DriverApiJson.location("s1", "Rotterdam ECT Delta", 51.95, 4.05)
        val destination = DriverApiJson.location("s3", "Venlo ECT", null, null)
        h.routes.on("GET", "/trips", mapOf("includeCompleted" to "false")) {
            jsonResponse(DriverApiJson.page(listOf(DriverApiJson.summary("t1", "Confirmed", "Assigned", origin, destination, origin))))
        }
        h.routes.on("GET", "/trips", mapOf("includeCompleted" to "true", "page" to "1")) {
            jsonResponse(
                DriverApiJson.page(
                    listOf(DriverApiJson.summary("t1", "Confirmed", "Assigned"), DriverApiJson.summary("t2", "Completed", "Completed")),
                    hasMore = true,
                ),
            )
        }
        h.routes.on("GET", "/trips", mapOf("includeCompleted" to "true", "page" to "2")) {
            jsonResponse(DriverApiJson.page(listOf(DriverApiJson.summary("t3", "Completed", "Completed")), page = 2))
        }

        assertEquals(RefreshResult.SUCCESS, h.trips.refreshAll())
        assertEquals(listOf("t1" to TripState.ASSIGNED, "t2" to TripState.FINISHED), h.trips.currentTrips().map { it.id to it.state })
        assertTrue(h.trips.observeCanLoadMore().first())
        assertEquals(RefreshResult.SUCCESS, h.trips.loadMoreTrips())
        assertEquals(RefreshResult.SUCCESS, h.trips.loadMoreTrips())

        assertEquals(listOf("t1", "t2", "t3"), h.trips.currentTrips().map { it.id })
        assertFalse(h.trips.observeCanLoadMore().first())
        val first = h.trips.currentTrips().first()
        assertEquals("TRP-t1", first.tripNo)
        assertEquals("WX 9234K", first.vehiclePlate)
        assertEquals(setOf(TripActionType.ACCEPT_TRIP), first.serverAllowedActions)
        assertEquals("Rotterdam ECT Delta", first.origin?.name)
        assertEquals(GeoPoint(51.95, 4.05), first.nextStop?.coordinates)
        // A missing master-data location stays missing: never 0,0.
        assertEquals("Venlo ECT", first.destination?.name)
        assertNull(first.destination?.coordinates)
        assertEquals(3, h.server.requestCount)
    }

    @Test
    fun `cancelled or unassigned trips are not kept locally`() = runBlocking {
        h.signIn()
        val assigned = AtomicBoolean(true)
        h.routes.on("GET", "/trips") {
            jsonResponse(DriverApiJson.page(if (assigned.get()) listOf(DriverApiJson.summary("t1", "Active", "Assigned")) else emptyList()))
        }
        h.routes.on("GET", "/trips/t1") { problemResponse(404, "not_found") }
        h.trips.refreshAll()
        assertEquals(1, h.trips.currentTrips().size)

        assigned.set(false)
        h.trips.refreshAll()

        assertTrue(h.trips.currentTrips().isEmpty())
        assertEquals(RefreshResult.FAILED, h.trips.refreshTrip("t1"))
    }

    @Test
    fun `trip detail maps stops, every container, coordinates and the server plan`() = runBlocking {
        h.signIn()
        val stops = """[
            {"id":"s2","sequence":2,"activity":"Deliver","status":"Planned","location":"Düsseldorf Reisholz","address":"Am Trippelsberg 100","latitude":51.17,"longitude":6.86,"plannedArrivalUtc":"2026-09-12T14:00:00Z"},
            {"id":"s1","sequence":1,"activity":"Pickup","status":"Arrived","location":"Rotterdam ECT Delta","address":"Europaweg 875","latitude":null,"longitude":null,"plannedArrivalUtc":"2026-09-12T08:30:00Z","actualArrivalUtc":"2026-09-12T08:41:00Z","reference":"REF-1"}
        ]"""
        h.routes.on("GET", "/trips/t1") {
            jsonResponse(
                DriverApiJson.detail(
                    DriverApiJson.summary("t1", "Active", "InProgress"),
                    stops = stops,
                    containers = """[{"id":"c1","number":"MSKU 458921-7","sealNumber":"781392"},{"id":"c2","number":null,"sealNumber":null}]""",
                    instructions = "Check the seal",
                ),
            )
        }
        h.routes.on("GET", "/trips/t1/execution") {
            jsonResponse(DriverApiJson.execution(actions = listOf(DriverApiJson.action("CompleteStop", "Stop completed", "s1", "c1"))))
        }

        assertEquals(RefreshResult.SUCCESS, h.trips.refreshTrip("t1"))

        val trip = h.trips.currentTrips().single()
        assertEquals(TripWorkflow.TRIP_ACTIONS, trip.workflow)
        assertEquals(TripState.STARTED, trip.state)
        assertEquals(emptySet<TripActionType>(), trip.serverAllowedActions)
        assertEquals("Rotterdam ECT Delta", trip.pickupName)
        assertEquals("Am Trippelsberg 100", trip.deliveryAddress)
        assertEquals(Instant.parse("2026-09-12T08:30:00Z"), trip.plannedStartAt)
        assertEquals(listOf(1, 2), trip.stops.map { it.sequence })
        assertEquals(StopActivity.PICKUP, trip.stops[0].activity)
        assertEquals(StopStatus.ARRIVED, trip.stops[0].status)
        assertNull(trip.stops[0].coordinates)
        assertEquals(GeoPoint(51.17, 6.86), trip.stops[1].coordinates)
        assertEquals(Instant.parse("2026-09-12T08:41:00Z"), trip.stops[0].actualArrivalAt)
        assertEquals(listOf("MSKU 458921-7", null), trip.containers.map { it.number })
        assertEquals("MSKU 458921-7", trip.containerNo)
        assertEquals("781392", trip.sealNo)
        assertEquals("Check the seal", trip.instructions)
        assertEquals("ORD-t1", trip.orderNumber)
        assertEquals(1, h.trips.currentExecution("t1")?.availableActions?.size)
        // Trip actions never read the legacy status history.
        assertTrue(h.server.drainRequests().none { it.route.endsWith("/statuses") })
    }

    @Test
    fun `a stop's TAR is read with the trip and follows later reads, including its removal`() = runBlocking {
        h.signIn()
        var stops = """[
            {"id":"s1","sequence":1,"activity":"Pickup","status":"Planned","location":"ECT Delta","tar":{"code":"001234","startUtc":"2026-10-22T08:00:00Z","endUtc":"2026-10-22T10:00:00Z","timeZone":"Europe/Amsterdam"}},
            {"id":"s2","sequence":2,"activity":"Pickup","status":"Planned","location":"ECT Delta","tar":{"code":"009876","startUtc":"2026-10-22T12:00:00Z","endUtc":"2026-10-22T14:00:00Z","timeZone":"Europe/Amsterdam"}},
            {"id":"s3","sequence":3,"activity":"Deliver","status":"Planned","location":"Venlo","tar":null}
        ]"""
        h.routes.on("GET", "/trips/t1") { jsonResponse(DriverApiJson.detail(DriverApiJson.summary("t1", "Active", "InProgress"), stops = stops)) }
        h.routes.on("GET", "/trips/t1/execution") { jsonResponse(DriverApiJson.execution(actions = emptyList())) }

        assertEquals(RefreshResult.SUCCESS, h.trips.refreshTrip("t1"))
        var trip = h.trips.currentTrips().single()
        // Two stops at the same terminal keep their own TAR, matched by stop id.
        assertEquals(listOf("001234", "009876", null), trip.stops.map { it.tar?.code })
        assertEquals(Instant.parse("2026-10-22T12:00:00Z"), trip.stops[1].tar?.startAt)

        // The planner moved the first window and removed the second.
        stops = """[
            {"id":"s1","sequence":1,"activity":"Pickup","status":"Planned","location":"ECT Delta","tar":{"code":"001234","startUtc":"2026-10-22T09:00:00Z","endUtc":"2026-10-22T11:00:00Z","timeZone":"Europe/Amsterdam"}},
            {"id":"s2","sequence":2,"activity":"Pickup","status":"Planned","location":"ECT Delta","tar":null},
            {"id":"s3","sequence":3,"activity":"Deliver","status":"Planned","location":"Venlo"}
        ]"""
        assertEquals(RefreshResult.SUCCESS, h.trips.refreshTrip("t1"))
        trip = h.trips.currentTrips().single()
        assertEquals(listOf("001234", null, null), trip.stops.map { it.tar?.code })
        assertEquals(Instant.parse("2026-10-22T09:00:00Z"), trip.stops[0].tar?.startAt)

        // A failed read keeps the last successful stops.
        h.routes.on("GET", "/trips/t1") { problemResponse(503, "service_unavailable") }
        assertEquals(RefreshResult.SERVICE_UNAVAILABLE, h.trips.refreshTrip("t1"))
        assertEquals("001234", h.trips.currentTrips().single().stops[0].tar?.code)
    }

    @Test
    fun `legacy trips derive the step from the status history and offer no trip actions`() = runBlocking {
        h.signIn()
        routeLegacyTrip({ "InProgress" }, { listOf("ArrivedAtLoading", "Departed") })

        loadTrip()

        val trip = h.trips.currentTrips().single()
        assertEquals(TripWorkflow.LEGACY_STATUSES, trip.workflow)
        assertEquals(TripState.AT_PICKUP, trip.state)
        assertEquals(setOf(TripActionType.COMPLETE_PICKUP, TripActionType.START_BREAK), trip.serverAllowedActions)
        assertTrue(h.trips.currentExecution("t1")!!.availableActions.isEmpty())
        val history = h.trips.observeActionHistory("t1").first()
        assertEquals(listOf(TripActionType.START_TRIP, TripActionType.ARRIVE_PICKUP), history.map { it.action })
    }

    @Test
    fun `accept is sent once and the plan of the accepted trip is read`() = runBlocking {
        h.signIn()
        val accepted = AtomicBoolean(false)
        val assignment = { if (accepted.get()) "Active" else "Confirmed" }
        h.routes.on("GET", "/trips") { jsonResponse(DriverApiJson.page(listOf(DriverApiJson.summary("t1", assignment(), "Assigned")))) }
        h.routes.on("GET", "/trips/t1") { jsonResponse(DriverApiJson.detail(DriverApiJson.summary("t1", assignment(), "Assigned"))) }
        h.routes.on("GET", "/trips/t1/execution") {
            jsonResponse(DriverApiJson.execution(status = "Assigned", actions = listOf(DriverApiJson.action("Start", "I started the trip"))))
        }
        h.routes.on("POST", "/trips/t1/accept") {
            accepted.set(true)
            jsonResponse(DriverApiJson.COMMAND_OK)
        }
        h.trips.refreshAll()
        assertEquals(TripState.ASSIGNED, h.trips.currentTrips().single().state)

        assertEquals(ActionResult.Queued, h.trips.submitAction(TripActionCommand("t1", TripActionType.ACCEPT_TRIP)))

        val trip = h.trips.currentTrips().single()
        assertEquals(TripState.ACCEPTED, trip.state)
        assertEquals(TripWorkflow.TRIP_ACTIONS, trip.workflow)
        assertEquals(1, h.trips.currentExecution("t1")?.availableActions?.size)
        assertEquals(
            listOf(
                "GET /api/driver/v1/trips",
                "GET /api/driver/v1/trips",
                "POST /api/driver/v1/trips/t1/accept",
                "GET /api/driver/v1/trips/t1",
                "GET /api/driver/v1/trips/t1/execution",
            ),
            h.server.drainRequests().map { it.route },
        )
    }

    @Test
    fun `legacy status updates use the case-sensitive backend names`() = runBlocking {
        h.signIn()
        val departed = AtomicBoolean(false)
        routeLegacyTrip({ if (departed.get()) "InProgress" else "Assigned" }, { if (departed.get()) listOf("Departed") else emptyList() })
        h.routes.on("POST", "/trips/t1/statuses") {
            departed.set(true)
            jsonResponse(DriverApiJson.COMMAND_OK)
        }
        loadTrip()
        assertEquals(TripState.ACCEPTED, h.trips.currentTrips().single().state)

        assertEquals(ActionResult.Queued, h.trips.submitAction(TripActionCommand("t1", TripActionType.START_TRIP)))

        val post = h.server.drainRequests().single { it.method == "POST" }
        assertEquals("""{"status":"Departed"}""", post.body.readUtf8())
        assertEquals(TripState.STARTED, h.trips.currentTrips().single().state)
    }

    @Test
    fun `trips on the trip action workflow never send legacy statuses`() = runBlocking {
        h.signIn()
        h.routes.on("GET", "/trips") { jsonResponse(DriverApiJson.page(listOf(DriverApiJson.summary("t1", "Active", "InProgress")))) }
        h.routes.on("GET", "/trips/t1") { jsonResponse(DriverApiJson.detail(DriverApiJson.summary("t1", "Active", "InProgress"))) }
        h.routes.on("GET", "/trips/t1/execution") { jsonResponse(DriverApiJson.execution()) }
        loadTrip()

        assertEquals(
            ActionResult.Rejected(ActionRejection.NOT_ALLOWED),
            h.trips.submitAction(TripActionCommand("t1", TripActionType.ARRIVE_PICKUP)),
        )
        assertEquals(ActionResult.Rejected(ActionRejection.NOT_ALLOWED), h.trips.reportStatus("t1", "Resting", null))
        assertTrue(h.server.drainRequests().none { it.method == "POST" })
    }

    @Test
    fun `a legacy conflict reloads the trip and never resends the write`() = runBlocking {
        h.signIn()
        val posted = AtomicBoolean(false)
        routeLegacyTrip({ if (posted.get()) "InProgress" else "Assigned" }, { if (posted.get()) listOf("Departed") else emptyList() })
        h.routes.on("POST", "/trips/t1/statuses") {
            // Another device reported the step first.
            posted.set(true)
            problemResponse(409, "workflow_conflict")
        }
        loadTrip()

        assertEquals(
            ActionResult.Rejected(ActionRejection.CONFLICT),
            h.trips.submitAction(TripActionCommand("t1", TripActionType.START_TRIP)),
        )

        val requests = h.server.drainRequests().map { it.route }
        assertEquals(1, requests.count { it.startsWith("POST") })
        assertEquals("GET /api/driver/v1/trips/t1/statuses", requests.last())
        assertEquals(TripState.STARTED, h.trips.currentTrips().single().state)
    }

    @Test
    fun `a legacy completion refused while required evidence is open asks for the documents`() = runBlocking {
        h.signIn()
        val uploaded = AtomicBoolean(false)
        routeLegacyTrip({ "InProgress" }, { listOf("ArrivedAtLoading", "Departed") })
        h.routes.on("GET", "/trips/t1/execution") {
            jsonResponse(
                DriverApiJson.execution(
                    workflow = "LegacyStatuses",
                    documents = listOf(DriverApiJson.slot("Cmr:trip:all", "Cmr", if (uploaded.get()) "PendingReview" else "Missing", required = true)),
                ),
            )
        }
        // The legacy path has no own code for the evidence gate (backend PR #130).
        h.routes.on("POST", "/trips/t1/statuses") { problemResponse(409, "workflow_conflict") }
        loadTrip()
        assertEquals(TripState.AT_PICKUP, h.trips.currentTrips().single().state)

        assertEquals(
            ActionResult.Rejected(ActionRejection.DOCUMENTS_REQUIRED),
            h.trips.submitAction(TripActionCommand("t1", TripActionType.COMPLETE_PICKUP)),
        )

        // With nothing open in the checklist a refusal stays a changed state.
        uploaded.set(true)
        assertEquals(
            ActionResult.Rejected(ActionRejection.CONFLICT),
            h.trips.submitAction(TripActionCommand("t1", TripActionType.COMPLETE_PICKUP)),
        )
        assertEquals(2, h.server.drainRequests().count { it.route == "POST /api/driver/v1/trips/t1/statuses" })
    }

    @Test
    fun `a legacy write with unknown outcome is not retried and the state is read again`() = runBlocking {
        h.signIn()
        routeLegacyTrip({ "Assigned" }, { emptyList() })
        h.routes.on("POST", "/trips/t1/statuses") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        loadTrip()

        assertEquals(
            ActionResult.Rejected(ActionRejection.OFFLINE),
            h.trips.submitAction(TripActionCommand("t1", TripActionType.START_TRIP)),
        )

        val requests = h.server.drainRequests().map { it.route }
        assertEquals(1, requests.count { it == "POST /api/driver/v1/trips/t1/statuses" })
        assertEquals("GET /api/driver/v1/trips/t1/statuses", requests.last())
    }

    @Test
    fun `legacy problem reports need a working trip and always carry a note`() = runBlocking {
        h.signIn()
        assertEquals(ActionResult.Rejected(ActionRejection.TRIP_REQUIRED), h.incidents.report(null, IncidentCategory.DELAY, "Stuck"))
        assertEquals(0, h.server.requestCount)

        routeLegacyTrip({ "InProgress" }, { listOf("Departed") })
        h.routes.on("POST", "/trips/t1/statuses") { jsonResponse(DriverApiJson.COMMAND_OK) }
        loadTrip()

        assertEquals(ActionResult.Queued, h.incidents.report("t1", IncidentCategory.TERMINAL_QUEUE, null))

        val post = h.server.drainRequests().single { it.method == "POST" }
        assertEquals("""{"status":"ProblemReported","note":"[TERMINAL_QUEUE]"}""", post.body.readUtf8())
        assertEquals(1000, RealIncidentRepository.problemNote(IncidentCategory.OTHER, "x".repeat(2_000)).length)
    }

    @Test
    fun `Retry-After is honoured before the next read`() = runBlocking {
        h.signIn()
        val limited = AtomicBoolean(true)
        h.routes.on("GET", "/trips") {
            if (limited.get()) {
                problemResponse(429, "rate_limit_exceeded", retryAfterSeconds = 120)
            } else {
                jsonResponse(DriverApiJson.page(emptyList()))
            }
        }

        assertEquals(RefreshResult.RATE_LIMITED, h.trips.refreshAll())
        limited.set(false)
        assertEquals(RefreshResult.RATE_LIMITED, h.trips.refreshAll())
        assertEquals(1, h.server.requestCount)

        h.clock.now = h.clock.now.plusSeconds(121)
        assertEquals(RefreshResult.SUCCESS, h.trips.refreshAll())
        assertEquals(3, h.server.requestCount)
    }

    @Test
    fun `server failures map to screen states`() = runBlocking {
        h.signIn()
        var next: () -> MockResponse = { problemResponse(403, "forbidden") }
        h.routes.on("GET", "/trips/t1") { next() }

        assertEquals(RefreshResult.SESSION_EXPIRED, h.trips.refreshTrip("t1"))
        next = { problemResponse(404, "not_found") }
        assertEquals(RefreshResult.FAILED, h.trips.refreshTrip("t1"))
        next = { problemResponse(404, "api_disabled") }
        assertEquals(RefreshResult.SERVICE_UNAVAILABLE, h.trips.refreshTrip("t1"))
        next = { problemResponse(503, "service_unavailable") }
        assertEquals(RefreshResult.SERVICE_UNAVAILABLE, h.trips.refreshTrip("t1"))
        next = { problemResponse(500, "request_failed") }
        assertEquals(RefreshResult.FAILED, h.trips.refreshTrip("t1"))
        next = { MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>") }
        assertEquals(RefreshResult.SERVICE_UNAVAILABLE, h.trips.refreshTrip("t1"))
        assertTrue(h.trips.currentTrips().isEmpty())
    }

    @Test
    fun `document content is read only through the content endpoint with the bearer token`() = runBlocking {
        h.signIn()
        h.routes.on("GET", "/trips/t1/documents/d1/content") {
            MockResponse().setHeader("Content-Type", "application/pdf").setBody("%PDF-1.7 content")
        }

        val result = h.documents.openDocument("t1", "d1", null) as DocumentContentResult.Available

        assertEquals("application/pdf", result.contentType)
        assertEquals("%PDF-1.7 content", File(result.filePath).readText())
        assertEquals(File(h.directories.cacheDir, "document_view").path, File(result.filePath).parentFile!!.path)
        val request = h.server.takeRequest()
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
        assertFalse(request.path!!.contains("access"))
    }

    // --- Demo / real separation ---

    @Test
    fun `real mode has no demo data source or shift`() = runBlocking {
        assertTrue(runCatching { UnavailableDriverRemoteDataSource.getTrips() }.exceptionOrNull() is RemoteException.NotSupported)
        assertEquals(Shift.Unavailable, RealShiftRepository().currentShift())
        assertEquals(ActionResult.Rejected(ActionRejection.NOT_SUPPORTED), RealShiftRepository().startShift())
    }
}
