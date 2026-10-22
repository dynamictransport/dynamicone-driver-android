package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.UserProfile
import com.dynamictransport.dynamicone.domain.model.VehiclePosition
import com.dynamictransport.dynamicone.domain.model.VehiclePositionState
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** `GET assigned-vehicle/position` (docs/19, proposed contract) through the production HTTP stack. */
class VehiclePositionRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    @Before
    fun setUp() {
        h = RealApiHarness(folder)
        h.signIn("driver-1")
    }

    @After
    fun tearDown() = h.close()

    private val route = "/assigned-vehicle/position"

    private fun answer(status: String, position: String? = null, vehicleId: String? = "veh-49") =
        """{"status":"$status","vehicle":${vehicleId?.let { """{"vehicleId":"$it","companyCode":"Dyna049","registrationNumber":"PZ8W582"}""" } ?: "null"},"position":${position ?: "null"}}"""

    private fun point(latitude: Double = 50.294512, longitude: Double = 18.671433, time: String? = "2026-09-19T10:58:12Z") =
        """{"latitude":$latitude,"longitude":$longitude,"positionTimeUtc":${time?.let { "\"$it\"" } ?: "null"},"receivedAtUtc":"2026-09-19T10:59:03Z"}"""

    private val known = VehiclePositionState.Known(
        "veh-49",
        VehiclePosition(GeoPoint(50.294512, 18.671433), Instant.parse("2026-09-19T10:58:12Z"), Instant.parse("2026-09-19T10:59:03Z")),
    )

    private suspend fun state() = h.vehiclePosition.observePosition().first()

    @Test
    fun `the last position of the assigned vehicle is read with UTC times`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(answer("Available", point())) }
        assertEquals(VehiclePositionState.Loading, state())

        assertEquals(RefreshResult.SUCCESS, h.vehiclePosition.refresh())

        assertEquals(known, state())
    }

    @Test
    fun `each status of the contract has its own state and nothing is invented`() = runBlocking {
        val cases = listOf(
            answer("NotAssigned", vehicleId = null) to VehiclePositionState.NotAssigned,
            answer("NotLinked") to VehiclePositionState.NotLinked,
            answer("NoData") to VehiclePositionState.NoData,
            // A status this version does not know, and an "Available" without a usable point, show no position.
            answer("Moving", point()) to VehiclePositionState.NoData,
            answer("Available", null) to VehiclePositionState.NoData,
            answer("Available", point(latitude = 91.0)) to VehiclePositionState.NoData,
            answer("Available", point(longitude = -181.0)) to VehiclePositionState.NoData,
            answer("Available", point(time = null)) to VehiclePositionState.NoData,
        )
        cases.forEach { (body, expected) ->
            h.routes.on("GET", route) { jsonResponse(body) }
            assertEquals(body, RefreshResult.SUCCESS, h.vehiclePosition.refresh())
            assertEquals(body, expected, state())
        }
    }

    @Test
    fun `a server without the endpoint hides the position and is not asked again until the app starts anew`() = runBlocking {
        val reads = AtomicInteger()
        h.routes.on("GET", route) {
            reads.incrementAndGet()
            problemResponse(404, "not_found")
        }

        assertEquals(RefreshResult.NOT_SUPPORTED, h.vehiclePosition.refresh())
        assertEquals(RefreshResult.NOT_SUPPORTED, h.vehiclePosition.refresh())

        assertEquals(VehiclePositionState.NotSupported, state())
        assertEquals(1, reads.get())

        // A new session (sign-in, app start) asks again.
        h.vehiclePosition.clear()
        h.routes.on("GET", route) { jsonResponse(answer("Available", point())) }
        assertEquals(RefreshResult.SUCCESS, h.vehiclePosition.refresh())
        assertEquals(known, state())
    }

    @Test
    fun `a disabled API is a failure, not a missing endpoint`() = runBlocking {
        h.routes.on("GET", route) { problemResponse(404, "api_disabled") }

        h.vehiclePosition.refresh()

        assertEquals(VehiclePositionState.Unavailable, state())
    }

    @Test
    fun `a failed read keeps the last answer and without one shows the failure`() = runBlocking {
        h.routes.on("GET", route) { problemResponse(503, "service_unavailable") }
        h.vehiclePosition.refresh()
        assertEquals(VehiclePositionState.Unavailable, state())

        h.routes.on("GET", route) { jsonResponse(answer("Available", point())) }
        h.vehiclePosition.refresh()
        assertEquals(known, state())

        h.routes.on("GET", route) { problemResponse(503, "service_unavailable") }
        h.vehiclePosition.refresh()
        assertEquals(known, state())
    }

    @Test
    fun `another driver never sees the previous driver's vehicle position`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(answer("Available", point())) }
        h.vehiclePosition.refresh()
        assertEquals(known, state())

        h.sessionPreferences.setLoggedInUser(UserProfile("driver-2", "Jan Kowalski", null, null, setOf(AppModule.DRIVER), "driver-2"))

        assertEquals(VehiclePositionState.Loading, state())
    }

    @Test
    fun `signing out removes the position from memory`() = runBlocking {
        h.routes.on("GET", route) { jsonResponse(answer("Available", point())) }
        h.routes.on("POST", "/auth/logout") { MockResponse().setResponseCode(204) }
        h.auth.restoreSession()
        h.vehiclePosition.refresh()
        assertEquals(known, state())

        h.auth.logout()
        h.signIn("driver-1")

        assertEquals(VehiclePositionState.Loading, state())
    }

    @Test
    fun `overlapping reads share one request`() = runBlocking {
        val reads = AtomicInteger()
        h.routes.on("GET", route) {
            reads.incrementAndGet()
            jsonResponse(answer("Available", point())).setHeadersDelay(300, TimeUnit.MILLISECONDS)
        }

        val first = async(Dispatchers.IO) { h.vehiclePosition.refresh() }
        val second = async(Dispatchers.IO) { h.vehiclePosition.refresh() }
        first.await()
        second.await()

        assertEquals(1, reads.get())
        assertEquals(known, state())
    }
}
