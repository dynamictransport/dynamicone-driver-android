package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.MobileProfileResponseDto
import com.dynamictransport.dynamicone.domain.model.LoginResult
import com.dynamictransport.dynamicone.testing.DriverApiJson
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.RoutingDispatcher.Companion.MOBILE_API
import com.dynamictransport.dynamicone.testing.TEST_JSON
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * The person's HR photo (docs/21_EMPLOYEE_PHOTO.md) through the production HTTP stack: downloaded only when `me` names
 * a new SHA-256, kept only when the bytes match it, shown offline, and never kept after the session.
 */
class EmployeePhotoTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness
    private val photoRequests = AtomicInteger()

    /** What `me/photo` answers; replaced by the tests. */
    @Volatile
    private var photoResponse: () -> MockResponse = { photo(PHOTO_A) }

    @Before
    fun setUp() {
        h = RealApiHarness(folder, mobileAccount = true)
        h.routes.on("GET", "$MOBILE_API/me/photo") {
            photoRequests.incrementAndGet()
            photoResponse()
        }
    }

    @After
    fun tearDown() = h.close()

    private fun me(photoSha256: String?, userId: String = "user-9") =
        """{"userId":"$userId","employeeId":"e-1","driverId":null,"displayName":"Ayşe Yılmaz","modules":["officeAttendance"],"photoSha256":${photoSha256?.let { "\"$it\"" } ?: "null"}}"""

    private fun photo(bytes: ByteArray) = MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))

    private fun answerMe(body: String) = h.routes.on("GET", "$MOBILE_API/me") { jsonResponse(body) }

    private fun signIn(photoSha256: String?) = runBlocking {
        h.routes.on("POST", "$MOBILE_API/auth/login") { jsonResponse(DriverApiJson.tokens()) }
        answerMe(me(photoSha256))
        assertEquals(LoginResult.SUCCESS, h.auth.login("ayse.yilmaz", "secret"))
    }

    private fun awaitPhoto(predicate: (String?) -> Boolean): String? = runBlocking {
        withTimeout(5_000) { h.auth.observePhoto().first(predicate) }
    }

    /** Lets background work that should not happen get its chance before asserting it did not. */
    private fun settle() = runBlocking { delay(300) }

    private fun storedFiles(): List<File> = File(h.directories.filesDir, "employee_photo").listFiles().orEmpty().toList()

    @Test
    fun `me maps a well-formed SHA-256 and ignores anything else`() {
        fun mapped(value: String?) = AccountMapping.profile(TEST_JSON.decodeFromString(MobileProfileResponseDto.serializer(), me(value)))?.photoSha256
        assertEquals(SHA_A, mapped(SHA_A.lowercase()))
        assertNull(mapped(null))
        assertNull(mapped(""))
        assertNull(mapped("not-a-hash"))
        // A backend without docs/21 sends no field at all.
        assertNull(AccountMapping.profile(TEST_JSON.decodeFromString(MobileProfileResponseDto.serializer(), """{"userId":"u","modules":["officeAttendance"]}"""))?.photoSha256)
    }

    @Test
    fun `without a photo in me the photo is never requested`() {
        signIn(photoSha256 = null)
        settle()

        assertEquals(0, photoRequests.get())
        assertNull(runBlocking { h.auth.observePhoto().first() })
    }

    @Test
    fun `the photo is downloaded once and kept while me names the same one`() {
        signIn(SHA_A)

        val path = awaitPhoto { it != null }!!
        assertArrayEquals(PHOTO_A, File(path).readBytes())
        assertEquals(1, photoRequests.get())

        runBlocking { h.auth.refreshProfile() }
        settle()
        assertEquals(1, photoRequests.get())
        assertEquals(path, runBlocking { h.auth.observePhoto().first() })
    }

    @Test
    fun `a new photo replaces the old one`() {
        signIn(SHA_A)
        val first = awaitPhoto { it != null }!!

        photoResponse = { photo(PHOTO_B) }
        answerMe(me(SHA_B))
        runBlocking { h.auth.refreshProfile() }

        val second = awaitPhoto { it != null && it != first }!!
        assertArrayEquals(PHOTO_B, File(second).readBytes())
        assertFalse(File(first).exists())
        assertEquals(listOf(File(second).name), storedFiles().map { it.name })
    }

    @Test
    fun `bytes that do not match the announced hash are never shown`() {
        photoResponse = { photo(PHOTO_B) }
        signIn(SHA_A)
        settle()

        assertEquals(1, photoRequests.get())
        assertNull(runBlocking { h.auth.observePhoto().first() })
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `an unreadable image is not kept`() {
        h.photoImages.readable = false
        signIn(SHA_A)
        settle()

        assertEquals(1, h.photoImages.calls)
        assertNull(runBlocking { h.auth.observePhoto().first() })
    }

    @Test
    fun `a removed photo is removed from the phone`() {
        signIn(SHA_A)
        awaitPhoto { it != null }

        answerMe(me(null))
        runBlocking { h.auth.refreshProfile() }

        assertNull(awaitPhoto { it == null })
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `photo_not_found removes the photo, other failures keep it`() {
        signIn(SHA_A)
        val kept = awaitPhoto { it != null }

        // Offline or a server error: the photo on the phone stays.
        photoResponse = { MockResponse().setResponseCode(500) }
        answerMe(me(SHA_B))
        runBlocking { h.auth.refreshProfile() }
        settle()
        assertEquals(kept, runBlocking { h.auth.observePhoto().first() })

        photoResponse = { problemResponse(404, "photo_not_found") }
        runBlocking { h.auth.refreshProfile() }
        assertNull(awaitPhoto { it == null })
    }

    @Test
    fun `signing out removes the photo`() {
        signIn(SHA_A)
        awaitPhoto { it != null }

        h.routes.on("POST", "$MOBILE_API/auth/logout") { MockResponse().setResponseCode(204) }
        runBlocking { h.auth.logout() }

        assertNull(runBlocking { h.auth.observePhoto().first() })
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a restored session shows the stored photo without network`() {
        signIn(SHA_A)
        val path = awaitPhoto { it != null }

        // The app restarts offline: a new repository over the same files and session.
        h.routes.on("GET", "$MOBILE_API/me") { MockResponse().setResponseCode(503) }
        val restarted = h.restartedAuth()
        runBlocking { restarted.restoreSession() }

        assertEquals(path, runBlocking { withTimeout(5_000) { restarted.observePhoto().first { it != null } } })
        assertEquals(1, photoRequests.get())
    }

    private companion object {
        val PHOTO_A = "photo A bytes".toByteArray()
        val PHOTO_B = "photo B bytes".toByteArray()
        val SHA_A = sha256(PHOTO_A)
        val SHA_B = sha256(PHOTO_B)

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
    }
}
