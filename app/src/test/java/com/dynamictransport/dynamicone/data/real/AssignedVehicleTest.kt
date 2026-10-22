package com.dynamictransport.dynamicone.data.real

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.dynamictransport.dynamicone.core.network.DriverProfileResponseDto
import com.dynamictransport.dynamicone.core.network.DriverTripSummaryResponseDto
import com.dynamictransport.dynamicone.core.network.MobileProfileResponseDto
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.AssignedVehicle
import com.dynamictransport.dynamicone.domain.model.AssignedVehicleState
import com.dynamictransport.dynamicone.domain.model.LoginResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import com.dynamictransport.dynamicone.testing.DriverApiJson
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.RoutingDispatcher.Companion.MOBILE_API
import com.dynamictransport.dynamicone.testing.TEST_JSON
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `me.assignedVehicle` and the trip's `vehicleCompanyCode` (backend PR #133) through the production HTTP stack: the
 * vehicle of the fleet assignment is shown as the server gives it, replaced by every newer answer and never kept for
 * another account.
 */
class AssignedVehicleTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    @Before
    fun setUp() {
        h = RealApiHarness(folder)
    }

    @After
    fun tearDown() = h.close()

    private fun vehicle(id: String, code: String?, plate: String) =
        """{"vehicleId":"$id","companyCode":${code?.let { "\"$it\"" } ?: "null"},"registrationNumber":"$plate"}"""

    private fun driverMe(vehicle: String?, userId: String = "user-1", driverId: String = "driver-1", name: String = "Anna Nowak") =
        """{"userId":"$userId","driverId":"$driverId","displayName":"$name","preferredLanguageCode":"pl","assignedVehicle":${vehicle ?: "null"}}"""

    private fun tripSummary(id: String, companyCode: String?) =
        """{"id":"$id","tripNumber":"TRP-$id","orderNumber":"ORD-$id","status":"InProgress","assignmentStatus":"Active","vehicleRegistration":"WX 9234K","vehicleCompanyCode":${companyCode?.let { "\"$it\"" } ?: "null"},"assignedFromUtc":"2026-09-12T05:00:00Z","origin":null,"destination":null,"nextStop":null}"""

    private fun signIn(me: String) = runBlocking {
        h.routes.on("POST", "/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        h.routes.on("GET", "/me") { jsonResponse(me) }
        assertEquals(LoginResult.SUCCESS, h.auth.login("anna.nowak", "secret"))
    }

    private suspend fun vehicleState() = h.auth.observeAssignedVehicle().first()

    private suspend fun awaitVehicle(expected: AssignedVehicleState) = withTimeout(5_000) {
        h.auth.observeAssignedVehicle().first { it == expected }
    }

    private fun assigned(id: String, code: String?, plate: String) = AssignedVehicleState.Assigned(AssignedVehicle(id, code, plate))

    // --- Contract ---

    @Test
    fun `both profile answers map the vehicle and treat an empty DYNA code as missing`() {
        val body = """{"vehicleId":"8d2f","companyCode":"Dyna042","registrationNumber":"WX 12345"}"""
        val driver = TEST_JSON.decodeFromString(DriverProfileResponseDto.serializer(), driverMe(body))
        assertEquals(AssignedVehicle("8d2f", "Dyna042", "WX 12345"), AccountMapping.profile(driver)?.assignedVehicle)

        val mobile = TEST_JSON.decodeFromString(
            MobileProfileResponseDto.serializer(),
            """{"userId":"user-1","employeeId":"e-1","driverId":"driver-1","displayName":"Anna Nowak","preferredLanguageCode":null,"modules":["driver"],"assignedVehicle":$body}""",
        )
        assertEquals(AssignedVehicle("8d2f", "Dyna042", "WX 12345"), AccountMapping.profile(mobile)?.assignedVehicle)

        listOf("null", "\"\"", "\"  \"").forEach { code ->
            val withoutCode = TEST_JSON.decodeFromString(
                DriverProfileResponseDto.serializer(),
                driverMe("""{"vehicleId":"8d2f","companyCode":$code,"registrationNumber":"WX 12345"}"""),
            )
            assertEquals(AssignedVehicle("8d2f", null, "WX 12345"), AccountMapping.profile(withoutCode)?.assignedVehicle)
        }
        assertNull(AccountMapping.profile(TEST_JSON.decodeFromString(DriverProfileResponseDto.serializer(), driverMe(null)))?.assignedVehicle)
        // A backend without PR #133 sends no field at all: nothing is assigned as far as the app can tell.
        assertNull(AccountMapping.profile(TEST_JSON.decodeFromString(DriverProfileResponseDto.serializer(), DriverApiJson.PROFILE))?.assignedVehicle)
    }

    @Test
    fun `the trip vehicle keeps its own DYNA code next to the plate`() {
        val summary = TEST_JSON.decodeFromString(DriverTripSummaryResponseDto.serializer(), tripSummary("t1", "Dyna007"))
        val trip = DriverApiMapping.trip(summary, DriverApiMapping.baseState("Active", "InProgress"), TripWorkflow.UNRESOLVED)
        assertEquals("Dyna007", trip.vehicleCompanyCode)
        assertEquals("WX 9234K", trip.vehiclePlate)

        val blank = TEST_JSON.decodeFromString(DriverTripSummaryResponseDto.serializer(), tripSummary("t1", " "))
        assertNull(DriverApiMapping.trip(blank, DriverApiMapping.baseState("Active", "InProgress"), TripWorkflow.UNRESOLVED).vehicleCompanyCode)
    }

    // --- Session ---

    @Test
    fun `sign-in shows the DYNA code and the plate of the assigned vehicle`() = runBlocking {
        signIn(driverMe(vehicle("veh-42", "Dyna042", "WX 12345")))

        assertEquals(assigned("veh-42", "Dyna042", "WX 12345"), vehicleState())
        assertEquals(AssignedVehicle("veh-42", "Dyna042", "WX 12345"), h.auth.observeProfile().first()?.assignedVehicle)
        assertEquals(assigned("veh-42", "Dyna042", "WX 12345"), h.sessionPreferences.assignedVehicle())
    }

    @Test
    fun `a vehicle without DYNA code keeps its plate`() = runBlocking {
        signIn(driverMe(vehicle("veh-42", null, "WX 12345")))

        assertEquals(assigned("veh-42", null, "WX 12345"), vehicleState())
    }

    @Test
    fun `no assigned vehicle is its own state and a trip vehicle never stands in for it`() = runBlocking {
        signIn(driverMe(null))
        h.routes.on("GET", "/trips") { jsonResponse(DriverApiJson.page(listOf(tripSummary("t1", "Dyna007")))) }

        assertEquals(RefreshResult.SUCCESS, h.trips.refreshAll())

        assertEquals("Dyna007", h.trips.currentTrips().single().vehicleCompanyCode)
        assertEquals(AssignedVehicleState.NotAssigned, vehicleState())
    }

    @Test
    fun `a changed or ended assignment replaces the vehicle when the profile is read again`() = runBlocking {
        signIn(driverMe(vehicle("veh-42", "Dyna042", "WX 12345")))

        h.routes.on("GET", "/me") { jsonResponse(driverMe(vehicle("veh-7", "Dyna007", "WZ 777AA"))) }
        h.auth.refreshProfile()
        assertEquals(assigned("veh-7", "Dyna007", "WZ 777AA"), vehicleState())

        h.routes.on("GET", "/me") { jsonResponse(driverMe(null)) }
        h.auth.refreshProfile()
        assertEquals(AssignedVehicleState.NotAssigned, vehicleState())
        assertNull(h.auth.observeProfile().first()?.assignedVehicle)
        // Nothing of the earlier vehicle is left to reappear after a restart.
        assertEquals(AssignedVehicleState.NotAssigned, h.sessionPreferences.assignedVehicle())
    }

    @Test
    fun `another account never sees the vehicle of the account before`() = runBlocking {
        signIn(driverMe(vehicle("veh-42", "Dyna042", "WX 12345")))
        h.routes.on("POST", "/auth/logout") { MockResponse().setResponseCode(204) }
        h.routes.on("DELETE", "/me/devices/${h.sessionPreferences.installationId()}") { MockResponse().setResponseCode(204) }

        h.auth.logout()
        assertEquals(AssignedVehicleState.Loading, vehicleState())
        assertNull(h.sessionPreferences.assignedVehicle())

        signIn(driverMe(null, userId = "user-2", driverId = "driver-2", name = "Jan Kowalski"))
        assertEquals(SessionState.LoggedIn("driver-2"), h.auth.sessionState.value)
        assertEquals(AssignedVehicleState.NotAssigned, vehicleState())
    }

    @Test
    fun `an answer that arrives after the account changed is dropped`() = runBlocking {
        signIn(driverMe(vehicle("veh-42", "Dyna042", "WX 12345")))
        h.routes.on("POST", "/auth/logout") { MockResponse().setResponseCode(204) }
        val slowReadStarted = CountDownLatch(1)
        val reads = AtomicInteger()
        h.routes.onRequest("GET", "/me") {
            if (reads.incrementAndGet() == 1) {
                slowReadStarted.countDown()
                // The first account's read is still on its way while the next account signs in.
                jsonResponse(driverMe(vehicle("veh-9", "Dyna009", "WX 99999"))).setHeadersDelay(700, TimeUnit.MILLISECONDS)
            } else {
                jsonResponse(driverMe(vehicle("veh-7", "Dyna007", "WZ 777AA"), userId = "user-2", driverId = "driver-2"))
            }
        }

        val oldRead = async(Dispatchers.IO) { h.auth.refreshProfile() }
        assertTrue(slowReadStarted.await(5, TimeUnit.SECONDS))
        h.auth.logout()
        assertEquals(LoginResult.SUCCESS, h.auth.login("jan.kowalski", "secret"))
        oldRead.await()

        assertEquals(SessionState.LoggedIn("driver-2"), h.auth.sessionState.value)
        assertEquals(assigned("veh-7", "Dyna007", "WZ 777AA"), vehicleState())
        assertEquals(assigned("veh-7", "Dyna007", "WZ 777AA"), h.sessionPreferences.assignedVehicle())
    }

    @Test
    fun `screens coming back at the same time share one read`() = runBlocking {
        signIn(driverMe(vehicle("veh-42", "Dyna042", "WX 12345")))
        val reads = AtomicInteger()
        h.routes.onRequest("GET", "/me") {
            reads.incrementAndGet()
            jsonResponse(driverMe(vehicle("veh-7", "Dyna007", "WZ 777AA"))).setHeadersDelay(300, TimeUnit.MILLISECONDS)
        }

        val first = async(Dispatchers.IO) { h.auth.refreshProfile() }
        val second = async(Dispatchers.IO) { h.auth.refreshProfile() }
        first.await()
        second.await()

        assertEquals(1, reads.get())
        assertEquals(assigned("veh-7", "Dyna007", "WZ 777AA"), vehicleState())
    }

    // --- Offline, failures and restart ---

    @Test
    fun `a failed read keeps the last answer of the same account`() = runBlocking {
        signIn(driverMe(vehicle("veh-42", "Dyna042", "WX 12345")))
        h.routes.on("GET", "/me") { problemResponse(503, "service_unavailable") }

        h.auth.refreshProfile()

        assertEquals(assigned("veh-42", "Dyna042", "WX 12345"), vehicleState())
    }

    @Test
    fun `after a restart without signal the stored vehicle is shown`() = runBlocking {
        signIn(driverMe(vehicle("veh-42", "Dyna042", "WX 12345")))
        val failedRead = CountDownLatch(1)
        h.routes.onRequest("GET", "/me") {
            failedRead.countDown()
            problemResponse(503, "service_unavailable")
        }

        h.auth.restoreSession()
        assertTrue(failedRead.await(5, TimeUnit.SECONDS))
        delay(200)

        assertEquals(assigned("veh-42", "Dyna042", "WX 12345"), vehicleState())
        assertEquals(AssignedVehicle("veh-42", "Dyna042", "WX 12345"), h.auth.observeProfile().first()?.assignedVehicle)
    }

    @Test
    fun `without any answer a failed read is unavailable and a retry loads the vehicle`() = runBlocking {
        // A session stored by an app version that did not know the vehicle yet.
        h.signIn()
        h.sessionStore.edit { it.remove(booleanPreferencesKey("assigned_vehicle")) }
        h.routes.on("GET", "/me") { problemResponse(503, "service_unavailable") }
        assertEquals(AssignedVehicleState.Loading, vehicleState())

        h.auth.restoreSession()
        awaitVehicle(AssignedVehicleState.Unavailable)

        h.routes.on("GET", "/me") { jsonResponse(driverMe(vehicle("veh-42", "Dyna042", "WX 12345"))) }
        withTimeout(5_000) {
            // The retry of the screen; the failed read of the restore may still be finishing.
            while (vehicleState() !is AssignedVehicleState.Assigned) {
                h.auth.refreshProfile()
                delay(50)
            }
        }
        assertEquals(assigned("veh-42", "Dyna042", "WX 12345"), vehicleState())
    }
}

/** The Mobile API `me` (`app.mobileApi=true`) carries the same vehicle; office accounts never get one. */
class MobileAssignedVehicleTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    @Before
    fun setUp() {
        h = RealApiHarness(folder, mobileAccount = true)
    }

    @After
    fun tearDown() = h.close()

    private fun signIn(me: String) = runBlocking {
        h.routes.on("POST", "$MOBILE_API/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        h.routes.on("GET", "$MOBILE_API/me") { jsonResponse(me) }
        assertEquals(LoginResult.SUCCESS, h.auth.login("anna.nowak", "secret"))
    }

    @Test
    fun `a driver gets the DYNA code and the plate`() = runBlocking {
        signIn(
            """{"userId":"user-1","employeeId":"e-1","driverId":"driver-1","displayName":"Anna Nowak","preferredLanguageCode":"pl","modules":["driver"],"assignedVehicle":{"vehicleId":"veh-42","companyCode":"Dyna042","registrationNumber":"WX 12345"}}""",
        )

        assertEquals(SessionState.LoggedIn("user-1", setOf(AppModule.DRIVER)), h.auth.sessionState.value)
        assertEquals(
            AssignedVehicleState.Assigned(AssignedVehicle("veh-42", "Dyna042", "WX 12345")),
            h.auth.observeAssignedVehicle().first(),
        )
    }

    @Test
    fun `an office account has no vehicle`() = runBlocking {
        signIn(
            """{"userId":"user-9","employeeId":"e-9","driverId":null,"displayName":"Ayşe Yılmaz","preferredLanguageCode":"tr","modules":["officeAttendance"],"assignedVehicle":null}""",
        )

        assertEquals(AssignedVehicleState.NotAssigned, h.auth.observeAssignedVehicle().first())
    }
}
