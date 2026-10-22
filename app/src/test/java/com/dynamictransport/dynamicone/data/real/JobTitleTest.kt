package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.MobileProfileResponseDto
import com.dynamictransport.dynamicone.domain.model.LoginResult
import com.dynamictransport.dynamicone.testing.DriverApiJson
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.RoutingDispatcher.Companion.MOBILE_API
import com.dynamictransport.dynamicone.testing.TEST_JSON
import com.dynamictransport.dynamicone.testing.jsonResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The HR job title from `me` (docs/22_EMPLOYEE_JOB_TITLE.md): shown as sent, kept for offline starts, gone after logout. */
class JobTitleTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    @Before
    fun setUp() {
        h = RealApiHarness(folder, mobileAccount = true)
    }

    @After
    fun tearDown() = h.close()

    private fun me(jobTitle: String?) =
        """{"userId":"user-9","employeeId":"e-1","driverId":null,"displayName":"Ayşe Yılmaz","modules":["officeAttendance"],"jobTitle":${jobTitle?.let { "\"$it\"" } ?: "null"}}"""

    private fun mapped(body: String) = AccountMapping.profile(TEST_JSON.decodeFromString(MobileProfileResponseDto.serializer(), body))

    private fun signIn(jobTitle: String?) = runBlocking {
        h.routes.on("POST", "$MOBILE_API/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        h.routes.on("GET", "$MOBILE_API/me") { jsonResponse(me(jobTitle)) }
        assertEquals(LoginResult.SUCCESS, h.auth.login("ayse.yilmaz", "secret"))
    }

    @Test
    fun `me maps the title trimmed and treats a blank or missing one as none`() {
        assertEquals("Operations Manager", mapped(me("  Operations Manager "))?.jobTitle)
        assertNull(mapped(me(null))?.jobTitle)
        assertNull(mapped(me("   "))?.jobTitle)
        // A backend without docs/22 sends no field at all.
        assertNull(mapped("""{"userId":"u","modules":["officeAttendance"]}""")?.jobTitle)
    }

    @Test
    fun `a changed title in a newer me replaces the shown one`() {
        signIn("Dispatcher")
        assertEquals("Dispatcher", runBlocking { h.auth.observeProfile().first() }?.jobTitle)

        h.routes.on("GET", "$MOBILE_API/me") { jsonResponse(me(null)) }
        runBlocking { h.auth.refreshProfile() }

        assertNull(runBlocking { withTimeout(5_000) { h.auth.observeProfile().first { it?.jobTitle == null } } }?.jobTitle)
        assertNull(runBlocking { h.sessionPreferences.jobTitle() })
    }

    @Test
    fun `a restored session shows the stored title without network`() {
        signIn("Operations Manager")

        h.routes.on("GET", "$MOBILE_API/me") { MockResponse().setResponseCode(503) }
        val restarted = h.restartedAuth()
        runBlocking { restarted.restoreSession() }

        assertEquals("Operations Manager", runBlocking { restarted.observeProfile().first() }?.jobTitle)
    }

    @Test
    fun `logout forgets the title`() {
        signIn("Operations Manager")
        h.routes.on("POST", "$MOBILE_API/auth/logout") { MockResponse().setResponseCode(204) }
        runBlocking { h.auth.logout() }

        assertNull(runBlocking { h.sessionPreferences.jobTitle() })
    }
}
