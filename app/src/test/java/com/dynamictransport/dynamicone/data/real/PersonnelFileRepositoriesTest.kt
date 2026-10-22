package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.PersonnelDocumentPageDto
import com.dynamictransport.dynamicone.core.network.VehicleDocumentsResponseDto
import com.dynamictransport.dynamicone.core.security.TokenPair
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.AssignedVehicle
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.PersonnelDocumentType
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.RequirementStatus
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.domain.model.UserProfile
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentType
import com.dynamictransport.dynamicone.domain.model.VehicleRequirementStatus
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.RoutingDispatcher.Companion.MOBILE_API
import com.dynamictransport.dynamicone.testing.TEST_JSON
import com.dynamictransport.dynamicone.testing.drainRequests
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import com.dynamictransport.dynamicone.testing.route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Personnel and vehicle files against the Mobile API contract with the production HTTP stack and a MockWebServer. */
class PersonnelFileRepositoriesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness

    private val driverAccount = UserProfile(
        id = "user-7",
        displayName = "Anna Nowak",
        personnelNo = null,
        preferredLanguage = null,
        modules = setOf(AppModule.DRIVER, AppModule.HR_DOCUMENTS),
        driverId = "driver-7",
        assignedVehicle = AssignedVehicle("veh-1", "Dyna042", "WX 12345"),
    )

    @Before
    fun setUp() {
        h = RealApiHarness(folder, mobileAccount = true)
        signIn(driverAccount)
    }

    @After
    fun tearDown() = h.close()

    private fun signIn(profile: UserProfile) = runBlocking {
        h.tokenManager.store(TokenPair("access-1", "refresh-1", h.clock.now.plusSeconds(900), null))
        h.sessionPreferences.setLoggedInUser(profile)
    }

    private fun document(id: String, type: String, expiresOn: String? = null, displayName: String = "Name of $type", current: Boolean = true) =
        """{"id":"$id","type":"$type","displayName":"$displayName","fileName":"$id.pdf","contentType":"application/pdf","fileSizeBytes":2048,""" +
            """"sha256":"AB","issuedOn":"2024-01-02","validFrom":"2024-01-03","expiresOn":${expiresOn?.let { "\"$it\"" } ?: "null"},""" +
            """"isCurrent":$current,"verificationStatus":"Verified","uploadedAtUtc":"2026-09-01T08:30:00Z"}"""

    private fun page(items: List<String>, page: Int = 1, hasMore: Boolean = false) =
        """{"items":[${items.joinToString(",")}],"page":$page,"pageSize":50,"hasMore":$hasMore}"""

    private val requirementsJson = """{"items":[
        {"type":"Passport","status":"Valid","documentId":"d-pass","expiresOn":"2030-05-01"},
        {"type":"DrivingLicence","status":"ExpiringSoon","documentId":"d-lic","expiresOn":"2026-10-15"},
        {"type":"Code95","status":"Expired","documentId":"d-95","expiresOn":"2026-01-31"},
        {"type":"Visa","status":"Missing","documentId":null,"expiresOn":null},
        {"type":"A1Certificate","status":"Incomplete","documentId":"d-a1","expiresOn":null},
        {"type":"FutureType","status":"Reviewed","documentId":"d-x","expiresOn":"not a date"}
      ],"validCount":9,"expiringSoonCount":1,"expiredCount":1,"missingOrIncompleteCount":4}"""

    private fun vehicleJson(vehicleId: String?, withFiles: Boolean = true) = if (vehicleId == null) {
        """{"assignedVehicle":null,"items":[],"requirements":[]}"""
    } else {
        """{"assignedVehicle":{"vehicleId":"$vehicleId","companyCode":"Dyna$vehicleId","registrationNumber":"WX $vehicleId"},""" +
            (
                if (withFiles) {
                    """"items":[{"id":"f-ins-$vehicleId","type":"InsurancePolicy","fileName":"policy.pdf","contentType":"application/pdf","fileSizeBytes":10,"sha256":"AA","uploadedAtUtc":"2026-08-01T10:00:00Z"},""" +
                        """{"id":"f-extra-$vehicleId","type":"TachographCalibration","fileName":"cal.png","contentType":"image/png","fileSizeBytes":10,"sha256":"BB","uploadedAtUtc":"2026-08-02T10:00:00Z"}],"""
                } else {
                    """"items":[],"""
                }
                ) +
            """"requirements":[{"type":"InsurancePolicy","status":"Available","documentId":"f-ins-$vehicleId","expiresOn":null},""" +
            """{"type":"TransportLicense","status":"Missing","documentId":null,"expiresOn":null},""" +
            """{"type":"RegistrationCertificate","status":"Missing","documentId":null,"expiresOn":null}]}"""
    }

    // --- Personnel file history ---

    @Test
    fun `the history keeps every field, names unknown types safely and skips items without an id`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/documents", mapOf("page" to "1", "pageSize" to "50")) {
            jsonResponse(
                page(
                    listOf(
                        document("d-1", "Passport", expiresOn = "2031-03-04"),
                        document("d-2", "OnboardingChecklist", displayName = "Onboarding checklist", current = false),
                        document("d-3", "Visa").replace("\"id\":\"d-3\"", "\"id\":null"),
                    ),
                ),
            )
        }

        assertEquals(RefreshResult.SUCCESS, h.myDocuments.refreshHistory())

        val history = h.myDocuments.observeHistory().first()!!
        assertEquals(listOf("d-1", "d-2"), history.documents.map { it.id })
        assertFalse(history.canLoadMore)
        val passport = history.documents[0]
        assertEquals(PersonnelDocumentType.PASSPORT, passport.type)
        assertEquals(LocalDate.of(2031, 3, 4), passport.expiresOn)
        assertEquals(LocalDate.of(2024, 1, 2), passport.issuedOn)
        assertEquals(Instant.parse("2026-09-01T08:30:00Z"), passport.uploadedAt)
        assertEquals("application/pdf", passport.contentType)
        assertTrue(passport.isCurrent)
        val other = history.documents[1]
        assertEquals(PersonnelDocumentType.OTHER, other.type)
        assertEquals("Onboarding checklist", other.displayName)
        assertFalse(other.isCurrent)
    }

    @Test
    fun `an unknown type or extra field never breaks parsing`() {
        val dto = TEST_JSON.decodeFromString(
            PersonnelDocumentPageDto.serializer(),
            """{"items":[{"id":"d-9","type":"SomethingNew","newField":{"a":1},"isCurrent":null}],"page":1,"pageSize":50,"hasMore":false,"total":1}""",
        )
        val mapped = PersonnelFileMapping.document(dto.items!!.single())!!
        assertEquals(PersonnelDocumentType.OTHER, mapped.type)
        assertNull(mapped.displayName)
        assertFalse(mapped.isCurrent)
    }

    @Test
    fun `an empty personnel file is an empty list, not a failure`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/documents") { jsonResponse(page(emptyList())) }

        assertEquals(RefreshResult.SUCCESS, h.myDocuments.refreshHistory())

        assertEquals(emptyList<Any>(), h.myDocuments.observeHistory().first()!!.documents)
    }

    @Test
    fun `further pages are appended without duplicates until the server has no more`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/documents", mapOf("page" to "1")) {
            jsonResponse(page(listOf(document("d-1", "Passport"), document("d-2", "Visa")), hasMore = true))
        }
        h.routes.on("GET", "$MOBILE_API/me/documents", mapOf("page" to "2")) {
            jsonResponse(page(listOf(document("d-2", "Visa"), document("d-3", "Code95")), page = 2, hasMore = false))
        }

        h.myDocuments.refreshHistory()
        assertTrue(h.myDocuments.observeHistory().first()!!.canLoadMore)
        assertEquals(RefreshResult.SUCCESS, h.myDocuments.loadMoreHistory())

        val history = h.myDocuments.observeHistory().first()!!
        assertEquals(listOf("d-1", "d-2", "d-3"), history.documents.map { it.id })
        assertFalse(history.canLoadMore)
        h.server.drainRequests()
        // Nothing more to load: no request.
        assertEquals(RefreshResult.SUCCESS, h.myDocuments.loadMoreHistory())
        assertTrue(h.server.drainRequests().isEmpty())
    }

    @Test
    fun `a failed next page keeps what was read`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/documents", mapOf("page" to "1")) { jsonResponse(page(listOf(document("d-1", "Passport")), hasMore = true)) }
        h.routes.on("GET", "$MOBILE_API/me/documents", mapOf("page" to "2")) { problemResponse(503, "service_unavailable") }
        h.myDocuments.refreshHistory()

        assertEquals(RefreshResult.SERVICE_UNAVAILABLE, h.myDocuments.loadMoreHistory())

        val history = h.myDocuments.observeHistory().first()!!
        assertEquals(listOf("d-1"), history.documents.map { it.id })
        assertTrue(history.canLoadMore)
    }

    // --- Required documents ---

    @Test
    fun `requirements keep the server's counts and statuses, unknown ones stay unknown`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/documents/requirements") { jsonResponse(requirementsJson) }

        assertEquals(RefreshResult.SUCCESS, h.myDocuments.refreshRequirements())

        val requirements = h.myDocuments.observeRequirements().first()!!
        assertEquals(9, requirements.validCount)
        assertEquals(1, requirements.expiringSoonCount)
        assertEquals(1, requirements.expiredCount)
        assertEquals(4, requirements.missingOrIncompleteCount)
        assertEquals(
            listOf(
                RequirementStatus.VALID, RequirementStatus.EXPIRING_SOON, RequirementStatus.EXPIRED,
                RequirementStatus.MISSING, RequirementStatus.INCOMPLETE, RequirementStatus.UNKNOWN,
            ),
            requirements.items.map { it.status },
        )
        val missing = requirements.items[3]
        assertEquals(PersonnelDocumentType.VISA, missing.type)
        assertNull(missing.documentId)
        assertEquals(LocalDate.of(2026, 1, 31), requirements.items[2].expiresOn)
        // A date that is not yyyy-MM-dd is no date.
        assertNull(requirements.items[5].expiresOn)
        assertEquals(PersonnelDocumentType.OTHER, requirements.items[5].type)
    }

    // --- Errors ---

    @Test
    fun `403 without the module is access removed, not an expired session`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/documents") { problemResponse(403, "hr_documents_access_required") }
        h.routes.on("GET", "$MOBILE_API/me/documents/requirements") { problemResponse(403, "driver_access_required") }

        assertEquals(RefreshResult.ACCESS_REQUIRED, h.myDocuments.refreshHistory())
        assertEquals(RefreshResult.ACCESS_REQUIRED, h.myDocuments.refreshRequirements())
        assertTrue(h.auth.sessionState.value !is SessionState.LoggedOut)
    }

    @Test
    fun `a switched off API and a passing server problem are temporary, an unknown route is not connected`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/documents") { problemResponse(404, "api_disabled") }
        assertEquals(RefreshResult.SERVICE_UNAVAILABLE, h.myDocuments.refreshHistory())

        h.routes.on("GET", "$MOBILE_API/me/documents") { problemResponse(503, "service_unavailable") }
        assertEquals(RefreshResult.SERVICE_UNAVAILABLE, h.myDocuments.refreshHistory())

        h.routes.on("GET", "$MOBILE_API/me/documents") { problemResponse(404, "request_failed") }
        assertEquals(RefreshResult.NOT_SUPPORTED, h.myDocuments.refreshHistory())
        assertNull(h.myDocuments.observeHistory().first())
    }

    @Test
    fun `429 waits for Retry-After before asking again`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/documents/requirements") { problemResponse(429, "rate_limit_exceeded", retryAfterSeconds = 60) }

        assertEquals(RefreshResult.RATE_LIMITED, h.myDocuments.refreshRequirements())
        h.server.drainRequests()
        h.routes.on("GET", "$MOBILE_API/me/documents/requirements") { jsonResponse(requirementsJson) }

        assertEquals(RefreshResult.RATE_LIMITED, h.myDocuments.refreshRequirements())
        assertTrue(h.server.drainRequests().isEmpty())

        h.clock.now = h.clock.now.plusSeconds(61)
        assertEquals(RefreshResult.SUCCESS, h.myDocuments.refreshRequirements())
    }

    @Test
    fun `a rejected token refresh ends the session`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/documents") { problemResponse(401, "unauthorized") }
        h.routes.on("POST", "$MOBILE_API/auth/refresh") { problemResponse(401, "invalid_refresh_token") }
        h.auth.restoreSession()

        assertEquals(RefreshResult.SESSION_EXPIRED, h.myDocuments.refreshHistory())

        withTimeout(5_000) { h.auth.sessionState.first { it == SessionState.LoggedOut } }
        assertNull(h.tokenStore.tokens)
    }

    // --- Files ---

    @Test
    fun `a file is fetched with the bearer token, never a token in the URL, and kept only in the private folder`() = runBlocking {
        val bytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3)
        h.routes.on("GET", "$MOBILE_API/me/documents/d-1/content") {
            MockResponse()
                .setHeader("Content-Type", "image/webp")
                .setHeader("ETag", "\"AB\"")
                .setHeader("Cache-Control", "private, no-store")
                .setHeader("Content-Disposition", "attachment; filename=\"passport.webp\"")
                .setBody(Buffer().write(bytes))
        }

        val result = h.myDocuments.open("d-1", contentTypeHint = "application/pdf") as DocumentContentResult.Available

        assertEquals("image/webp", result.contentType)
        val file = File(result.filePath)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(ProtectedFileDownloader.PRIVATE_VIEW_DIRECTORY, file.parentFile!!.name)
        assertTrue(file.name.endsWith(".webp"))
        val request = h.server.drainRequests().single { it.route == "GET $MOBILE_API/me/documents/d-1/content" }
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
        assertFalse(request.requestUrl.toString().contains("access-1"))

        // Closing the viewer removes it.
        DownloadedFileCleaner(h.downloader).discard(result.filePath)
        assertFalse(file.exists())
    }

    @Test
    fun `a gone file, a storage outage and a removed access each say what happened`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/documents/d-1/content") { problemResponse(404, "document_not_found") }
        h.routes.on("GET", "$MOBILE_API/me/documents/d-2/content") { problemResponse(503, "document_storage_unavailable") }
        h.routes.on("GET", "$MOBILE_API/me/documents/d-3/content") { problemResponse(403, "hr_documents_access_required") }
        h.routes.on("GET", "$MOBILE_API/me/documents/d-4/content") { problemResponse(404, "api_disabled") }

        assertEquals(DocumentContentResult.Unavailable(ActionRejection.NOT_FOUND), h.myDocuments.open("d-1", null))
        assertEquals(DocumentContentResult.Unavailable(ActionRejection.SERVICE_UNAVAILABLE), h.myDocuments.open("d-2", null))
        assertEquals(DocumentContentResult.Unavailable(ActionRejection.FORBIDDEN), h.myDocuments.open("d-3", null))
        assertEquals(DocumentContentResult.Unavailable(ActionRejection.SERVICE_UNAVAILABLE), h.myDocuments.open("d-4", null))
    }

    @Test
    fun `signing out removes the lists and every viewed file`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/documents") { jsonResponse(page(listOf(document("d-1", "Passport")))) }
        h.routes.on("GET", "$MOBILE_API/me/assigned-vehicle/documents") { jsonResponse(vehicleJson("1")) }
        h.routes.on("GET", "$MOBILE_API/me/documents/d-1/content") {
            MockResponse().setHeader("Content-Type", "application/pdf").setBody("%PDF-1.7")
        }
        h.routes.on("POST", "$MOBILE_API/auth/logout") { MockResponse().setResponseCode(204) }
        h.myDocuments.refreshHistory()
        h.vehicleDocuments.refresh()
        val viewed = File((h.myDocuments.open("d-1", null) as DocumentContentResult.Available).filePath)

        h.auth.logout()

        assertFalse(viewed.exists())
        assertNull(h.myDocuments.observeHistory().first())
        assertNull(h.vehicleDocuments.observe().first())
    }

    @Test
    fun `another account never sees the previous account's files`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/documents") { jsonResponse(page(listOf(document("d-1", "Passport")))) }
        h.myDocuments.refreshHistory()

        signIn(driverAccount.copy(id = "user-8", driverId = "driver-8"))

        assertNull(h.myDocuments.observeHistory().first())
    }

    // --- Vehicle files ---

    @Test
    fun `vehicle files map the three types, keep extra files and invent no dates`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/assigned-vehicle/documents") { jsonResponse(vehicleJson("1")) }

        assertEquals(RefreshResult.SUCCESS, h.vehicleDocuments.refresh())

        val snapshot = h.vehicleDocuments.observe().first()!!
        assertFalse(snapshot.stale)
        val documents = snapshot.documents
        assertEquals("1", documents.vehicle?.vehicleId)
        assertEquals("Dyna1", documents.vehicle?.companyCode)
        assertEquals(
            listOf(VehicleDocumentType.INSURANCE_POLICY, VehicleDocumentType.TRANSPORT_LICENSE, VehicleDocumentType.REGISTRATION_CERTIFICATE),
            documents.requirements.map { it.type },
        )
        assertEquals(
            listOf(VehicleRequirementStatus.AVAILABLE, VehicleRequirementStatus.MISSING, VehicleRequirementStatus.MISSING),
            documents.requirements.map { it.status },
        )
        assertEquals(listOf("f-extra-1"), documents.otherDocuments.map { it.id })
        assertEquals(VehicleDocumentType.OTHER, documents.otherDocuments.single().type)
    }

    @Test
    fun `no assigned vehicle is an empty answer`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/assigned-vehicle/documents") { jsonResponse(vehicleJson(null)) }

        assertEquals(RefreshResult.SUCCESS, h.vehicleDocuments.refresh())

        val documents = h.vehicleDocuments.observe().first()!!.documents
        assertNull(documents.vehicle)
        assertTrue(documents.documents.isEmpty())
        assertTrue(documents.requirements.isEmpty())
        // A null vehicle with stray items still shows nothing.
        val stray = TEST_JSON.decodeFromString(
            VehicleDocumentsResponseDto.serializer(),
            """{"assignedVehicle":null,"items":[{"id":"x","type":"InsurancePolicy"}],"requirements":[]}""",
        )
        assertTrue(PersonnelFileMapping.vehicleDocuments(stray).documents.isEmpty())
    }

    @Test
    fun `a new assignment replaces the previous vehicle's files`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/assigned-vehicle/documents") { jsonResponse(vehicleJson("1")) }
        h.vehicleDocuments.refresh()

        h.routes.on("GET", "$MOBILE_API/me/assigned-vehicle/documents") { jsonResponse(vehicleJson("2", withFiles = false)) }
        h.vehicleDocuments.refresh()

        val documents = h.vehicleDocuments.observe().first()!!.documents
        assertEquals("2", documents.vehicle?.vehicleId)
        assertTrue(documents.documents.isEmpty())
    }

    @Test
    fun `after a failed read the old vehicle's files disappear once me names another vehicle`() = runBlocking {
        signIn(driverAccount.copy(assignedVehicle = AssignedVehicle("1", "Dyna1", "WX 1")))
        h.routes.on("GET", "$MOBILE_API/me/assigned-vehicle/documents") { jsonResponse(vehicleJson("1")) }
        h.vehicleDocuments.refresh()
        h.routes.on("GET", "$MOBILE_API/me/assigned-vehicle/documents") { problemResponse(503, "service_unavailable") }

        assertEquals(RefreshResult.SERVICE_UNAVAILABLE, h.vehicleDocuments.refresh())
        val stale = h.vehicleDocuments.observe().first()
        assertNotNull(stale)
        assertTrue(stale!!.stale)

        h.sessionPreferences.updateProfile(driverAccount.copy(assignedVehicle = AssignedVehicle("2", "Dyna2", "WX 2")))
        assertNull(h.vehicleDocuments.observe().first())

        h.sessionPreferences.updateProfile(driverAccount.copy(assignedVehicle = null))
        assertNull(h.vehicleDocuments.observe().first())
    }

    @Test
    fun `a vehicle file is fetched from its own endpoint into the private folder`() = runBlocking {
        h.routes.on("GET", "$MOBILE_API/me/assigned-vehicle/documents/f-ins-1/content") {
            MockResponse().setHeader("Content-Type", "application/pdf").setBody("%PDF-1.7")
        }
        h.routes.on("GET", "$MOBILE_API/me/assigned-vehicle/documents/gone/content") { problemResponse(404, "document_not_found") }

        val result = h.vehicleDocuments.open("f-ins-1", null) as DocumentContentResult.Available
        assertEquals(ProtectedFileDownloader.PRIVATE_VIEW_DIRECTORY, File(result.filePath).parentFile!!.name)
        assertEquals(DocumentContentResult.Unavailable(ActionRejection.NOT_FOUND), h.vehicleDocuments.open("gone", null))
    }
}
