package com.dynamictransport.dynamicone.core.network

import com.dynamictransport.dynamicone.core.security.SessionEvent
import com.dynamictransport.dynamicone.core.security.TokenManager
import com.dynamictransport.dynamicone.core.security.TokenPair
import com.dynamictransport.dynamicone.testing.DriverApiJson
import com.dynamictransport.dynamicone.testing.DriverApiTestClient
import com.dynamictransport.dynamicone.testing.InMemoryTokenStore
import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class NetworkLayerTest {

    private val server = MockWebServer()
    private val tokenStore = InMemoryTokenStore()
    private val tokenManager = TokenManager(tokenStore)
    private val clock = MutableClock()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun api() = DriverApiTestClient(server, tokenManager, clock).api

    private fun storeTokens(access: String = "access-1", refresh: String = "refresh-1") = runBlocking {
        tokenManager.store(TokenPair(access, refresh, TEST_NOW.plusSeconds(900), null))
    }

    @Test
    fun `protected calls carry the bearer token and client headers, login does not`() = runBlocking {
        storeTokens()
        server.enqueue(jsonResponse(DriverApiJson.tokens()))
        server.enqueue(jsonResponse(DriverApiJson.PROFILE))
        val api = api()

        api.login(DriverLoginRequestDto("first.last", "secret"))
        val profile = api.me()

        val login = server.takeRequest()
        assertNull(login.getHeader("Authorization"))
        assertNull(login.getHeader("Cookie"))
        assertEquals("""{"userName":"first.last","password":"secret"}""", login.body.readUtf8())
        val me = server.takeRequest()
        assertEquals("Bearer access-1", me.getHeader("Authorization"))
        assertNull(me.getHeader("Cookie"))
        assertEquals("android", me.getHeader("X-Platform"))
        assertEquals("1.2.3", me.getHeader("X-App-Version"))
        assertEquals("driver-1", profile.driverId)
    }

    @Test
    fun `concurrent 401s share one refresh and both tokens are replaced together`() {
        val refreshCalls = AtomicInteger()
        val refreshBodies = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/driver/v1/auth/refresh" -> {
                    refreshCalls.incrementAndGet()
                    refreshBodies += request.body.readUtf8()
                    Thread.sleep(300)
                    jsonResponse(DriverApiJson.tokens(access = "access-2", refresh = "refresh-2"))
                }
                "/api/driver/v1/me" ->
                    if (request.getHeader("Authorization") == "Bearer access-2") {
                        jsonResponse(DriverApiJson.PROFILE)
                    } else {
                        problemResponse(401, "unauthorized")
                    }
                else -> MockResponse().setResponseCode(404)
            }
        }
        storeTokens()
        val api = api()

        val profiles = runBlocking(Dispatchers.IO) { (1..4).map { async { api.me() } }.awaitAll() }

        assertEquals(4, profiles.size)
        assertEquals(1, refreshCalls.get())
        assertEquals(listOf("""{"refreshToken":"refresh-1"}"""), refreshBodies)
        assertEquals("access-2", tokenStore.tokens?.accessToken)
        assertEquals("refresh-2", tokenStore.tokens?.refreshToken)
    }

    @Test
    fun `concurrent Mobile API calls share one refresh of the stored Driver session token`() {
        val refreshCalls = AtomicInteger()
        val refreshBodies = Collections.synchronizedList(mutableListOf<String>())
        val status = """{"state":"CheckedOut","nextAction":"CheckIn","workDate":"2026-09-12","timeZoneId":"Europe/Warsaw","lastPunch":null}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/mobile/v1/auth/refresh" -> {
                    refreshCalls.incrementAndGet()
                    refreshBodies += request.body.readUtf8()
                    Thread.sleep(300)
                    jsonResponse(DriverApiJson.tokens(access = "access-2", refresh = "refresh-2"))
                }
                "/api/mobile/v1/attendance/status" ->
                    if (request.getHeader("Authorization") == "Bearer access-2") jsonResponse(status) else problemResponse(401, "unauthorized")
                else -> MockResponse().setResponseCode(404)
            }
        }
        // Stored by the Driver API version of the app: the Mobile API accepts the same refresh token (docs/15 §3).
        storeTokens()
        val mobileApi = DriverApiTestClient(server, tokenManager, clock, "/api/mobile/v1/").mobileApi

        val answers = runBlocking(Dispatchers.IO) { (1..4).map { async { mobileApi.attendanceStatus() } }.awaitAll() }

        assertEquals(4, answers.size)
        assertEquals(1, refreshCalls.get())
        assertEquals(listOf("""{"refreshToken":"refresh-1"}"""), refreshBodies)
        assertEquals("refresh-2", tokenStore.tokens?.refreshToken)
    }

    @Test
    fun `rejected refresh clears the session once and does not loop`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<SessionEvent>())
        val collector = launchCollector(events)
        storeTokens()
        server.enqueue(problemResponse(401, "unauthorized"))
        server.enqueue(problemResponse(401, "invalid_refresh_token"))

        val error = runCatching { apiCall { api().me() } }.exceptionOrNull()

        assertTrue(error is RemoteException.Unauthorized)
        assertEquals(2, server.requestCount)
        waitFor { events.isNotEmpty() }
        assertEquals(listOf(SessionEvent.EXPIRED), events)
        assertNull(tokenStore.tokens)
        assertNull(tokenManager.currentAccessToken())
        collector.cancel()
    }

    @Test
    fun `refresh with unknown outcome is not retried and keeps the session`() = runBlocking {
        storeTokens()
        server.enqueue(problemResponse(401, "unauthorized"))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val error = runCatching { apiCall { api().me() } }.exceptionOrNull()

        assertNotNull(error)
        assertEquals(2, server.requestCount)
        assertEquals("refresh-1", tokenStore.tokens?.refreshToken)
    }

    @Test
    fun `password change requirement is reported from any protected call`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<SessionEvent>())
        val collector = launchCollector(events)
        storeTokens()
        server.enqueue(problemResponse(403, "password_change_required"))

        val error = runCatching { apiCall { api().trips(1, 25, true) } }.exceptionOrNull()

        assertEquals("password_change_required", (error as RemoteException.Forbidden).problem?.code)
        waitFor { events.isNotEmpty() }
        assertEquals(listOf(SessionEvent.PASSWORD_CHANGE_REQUIRED), events)
        assertEquals("refresh-1", tokenStore.tokens?.refreshToken)
        collector.cancel()
    }

    @Test
    fun `problem details keep status, code, trace id and Retry-After`() {
        val problem = ApiProblemParser.parse(
            429,
            headersOf("Retry-After", "42"),
            """{"type":"about:blank","title":"Too many requests","status":429,"code":"rate_limit_exceeded","traceId":"00-abc","instance":"/api/driver/v1/trips"}""",
        )
        assertEquals(ApiProblem(429, "rate_limit_exceeded", "Too many requests", "00-abc", retryAfterSeconds = 42), problem)

        val notJson = ApiProblemParser.parse(503, headersOf(), "<html>Service Unavailable</html>")
        assertEquals(503, notJson.status)
        assertNull(notJson.code)
    }

    @Test
    fun `http statuses map to remote failures`() {
        assertTrue(httpError(400) is RemoteException.Validation)
        assertTrue(httpError(401) is RemoteException.Unauthorized)
        assertTrue(httpError(403) is RemoteException.Forbidden)
        assertTrue(httpError(404) is RemoteException.NotFound)
        assertTrue(httpError(409) is RemoteException.Conflict)
        assertTrue(httpError(413) is RemoteException.PayloadTooLarge)
        val limited = httpError(429, ApiProblem(429, "rate_limit_exceeded", null, null, retryAfterSeconds = 30))
        assertEquals(30L, (limited as RemoteException.RateLimited).retryAfterSeconds)
        assertTrue(httpError(503) is RemoteException.ServerUnavailable)
        assertTrue(httpError(500) is RemoteException.ServerError)
        assertTrue(httpError(422) is RemoteException.Rejected)
    }

    @Test
    fun `token responses need both tokens and never print them`() {
        assertNull(DriverTokenResponseDto(accessToken = "secret-access", expiresIn = 900, refreshToken = null).toTokenPair(clock))

        val pair = DriverTokenResponseDto(
            accessToken = "secret-access",
            expiresIn = 900,
            refreshToken = "secret-refresh",
            refreshTokenExpiresAtUtc = "2026-10-12T08:00:00Z",
            mustChangePassword = true,
        ).toTokenPair(clock)!!

        assertEquals(TEST_NOW.plusSeconds(900), pair.accessTokenExpiresAt)
        assertEquals(Instant.parse("2026-10-12T08:00:00Z"), pair.refreshTokenExpiresAt)
        assertTrue(pair.mustChangePassword)
        assertFalse(pair.toString().contains("secret"))
        assertFalse(DriverLoginRequestDto("first.last", "secret-password").toString().contains("secret"))
        assertFalse(DriverRefreshRequestDto("secret-refresh").toString().contains("secret"))
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollector(events: MutableList<SessionEvent>) =
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).let { scope ->
            val job = scope.async { tokenManager.sessionEvents.collect { events += it } }
            // Give the collector time to subscribe before events are emitted.
            Thread.sleep(100)
            job
        }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }
}
