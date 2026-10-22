package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.security.TokenPair
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.UserProfile
import com.dynamictransport.dynamicone.testing.FakeAppInfo
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.RoutingDispatcher.Companion.MOBILE_API
import com.dynamictransport.dynamicone.testing.TEST_JSON
import com.dynamictransport.dynamicone.testing.drainRequests
import com.dynamictransport.dynamicone.testing.problemResponse
import com.dynamictransport.dynamicone.testing.route
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

/** Push device registration against the Driver API contract (TH-2026-0444). */
class RealPushRegistrarTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    @Before
    fun setUp() {
        h = RealApiHarness(folder)
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun `a signed-in driver's device registers its installation, token, app version and language`() = runBlocking {
        h.signIn()
        h.routes.on("POST", "/me/devices") { MockResponse().setResponseCode(204) }
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            h.pushRegistrar.register("fcm-token_1:abc")
        } finally {
            Locale.setDefault(previous)
        }

        val request = h.server.takeRequest()
        assertEquals("POST /api/driver/v1/me/devices", request.route)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
        val body = TEST_JSON.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(h.sessionPreferences.installationId(), body.getValue("installationId").jsonPrimitive.content)
        assertEquals("Android", body.getValue("platform").jsonPrimitive.content)
        assertEquals("fcm-token_1:abc", body.getValue("pushToken").jsonPrimitive.content)
        assertEquals("0.1.0-test", body.getValue("appVersion").jsonPrimitive.content)
        assertEquals("tr", body.getValue("locale").jsonPrimitive.content)
        // Only a vehicle tablet with a camera may receive road snapshot requests (docs/24 §3).
        assertEquals("vehicleTablet", body.getValue("deviceKind").jsonPrimitive.content)
        assertEquals("Bridgestone Mobility Solutions PRO_X", body.getValue("deviceModel").jsonPrimitive.content)
        assertEquals("true", body.getValue("hasCamera").jsonPrimitive.content)
    }

    @Test
    fun `unregistering removes this installation`() = runBlocking {
        h.signIn()
        val installationId = h.sessionPreferences.installationId()
        h.routes.on("DELETE", "/me/devices/$installationId") { MockResponse().setResponseCode(204) }

        h.pushRegistrar.unregister()

        assertEquals("DELETE /api/driver/v1/me/devices/$installationId", h.server.takeRequest().route)
    }

    @Test
    fun `nothing is sent without a session or in demo mode`() = runBlocking {
        h.pushRegistrar.register("fcm-token")
        h.pushRegistrar.unregister()

        h.signIn()
        val demo = RealPushRegistrar(h.api, h.tokenManager, h.sessionPreferences, h.tracker, FakeAppInfo(isDemoMode = true), h.mobileApi, h.deviceProfile)
        demo.register("fcm-token")
        demo.unregister()

        assertEquals(0, h.server.requestCount)
    }

    @Test
    fun `an office account with documents to sign registers through the Mobile API`() = runBlocking {
        h.tokenManager.store(TokenPair("access-1", "refresh-1", h.clock.now.plusSeconds(900), null))
        h.sessionPreferences.setLoggedInUser(
            UserProfile("user-9", "Ayşe Yılmaz", null, null, setOf(AppModule.OFFICE_ATTENDANCE, AppModule.HR_DOCUMENTS), null),
        )
        val installationId = h.sessionPreferences.installationId()
        h.routes.on("POST", "$MOBILE_API/me/devices") { MockResponse().setResponseCode(204) }
        h.routes.on("DELETE", "$MOBILE_API/me/devices/$installationId") { MockResponse().setResponseCode(204) }

        h.pushRegistrar.register("fcm-token")
        h.pushRegistrar.unregister()

        val requests = h.server.drainRequests()
        assertEquals(listOf("POST /api/mobile/v1/me/devices", "DELETE /api/mobile/v1/me/devices/$installationId"), requests.map { it.route })
        val body = TEST_JSON.parseToJsonElement(requests.first().body.readUtf8()).jsonObject
        assertEquals("fcm-token", body.getValue("pushToken").jsonPrimitive.content)
    }

    @Test
    fun `an office account without a module that sends pushes registers nowhere`() = runBlocking {
        h.signInOffice()

        h.pushRegistrar.register("fcm-token")

        assertEquals(0, h.server.requestCount)
    }

    @Test
    fun `a rejected registration keeps the session`() = runBlocking {
        h.signIn()
        h.routes.on("POST", "/me/devices") { problemResponse(400, "invalid_device") }

        h.pushRegistrar.register("fcm-token")

        assertEquals(1, h.server.requestCount)
        assertTrue(h.tokenManager.hasSession())
    }
}
