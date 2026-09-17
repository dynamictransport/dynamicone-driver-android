package com.dynamictransport.driver.data.real

import com.dynamictransport.driver.testing.FakeAppInfo
import com.dynamictransport.driver.testing.RealApiHarness
import com.dynamictransport.driver.testing.TEST_JSON
import com.dynamictransport.driver.testing.problemResponse
import com.dynamictransport.driver.testing.route
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
        val demo = RealPushRegistrar(h.api, h.tokenManager, h.sessionPreferences, h.tracker, FakeAppInfo(isDemoMode = true))
        demo.register("fcm-token")
        demo.unregister()

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
