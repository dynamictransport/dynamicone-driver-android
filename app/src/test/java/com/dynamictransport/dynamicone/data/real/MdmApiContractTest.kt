package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.DriverHttpClients
import com.dynamictransport.dynamicone.core.network.MdmApi
import com.dynamictransport.dynamicone.core.network.MdmErrorCodes
import com.dynamictransport.dynamicone.core.network.MdmHttpClients
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.DeviceCommandResult
import com.dynamictransport.dynamicone.domain.model.DeviceCommandTypes
import com.dynamictransport.dynamicone.domain.model.DeviceCredential
import com.dynamictransport.dynamicone.testing.TEST_DEVICE_ID
import com.dynamictransport.dynamicone.testing.TEST_DEVICE_SECRET
import com.dynamictransport.dynamicone.testing.TEST_JSON
import com.dynamictransport.dynamicone.testing.deviceStatus
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant

/**
 * The MDM endpoints as docs/29 §3 describes them, through the production client stack: paths below
 * `/api/driver/v1/mdm/`, the request bodies, the device credential header and the error codes.
 */
class MdmApiContractTest {

    private val server = MockWebServer()
    private var credential: DeviceCredential? = DeviceCredential(TEST_DEVICE_ID, TEST_DEVICE_SECRET)
    private lateinit var gateway: ApiDeviceManagementGateway

    @Before
    fun setUp() {
        server.start()
        val client = MdmHttpClients.device(DriverHttpClients.base(appVersion = "0.3.0", logRequests = false)) { credential?.authorization }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/api/driver/v1/mdm/"))
            .client(client)
            .addConverterFactory(TEST_JSON.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MdmApi::class.java)
        gateway = ApiDeviceManagementGateway(api)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun RecordedRequest.json(): JsonObject = TEST_JSON.parseToJsonElement(body.readUtf8()).jsonObject

    @Test
    fun `enrolment sends the code and the device facts without any credential`() = runBlocking {
        server.enqueue(jsonResponse("""{"deviceId":"$TEST_DEVICE_ID","deviceSecret":"$TEST_DEVICE_SECRET","checkInSeconds":900}"""))

        val enrollment = gateway.enroll("K7Q2-M9XD", "install-1", deviceStatus())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/driver/v1/mdm/enroll", request.requestUrl?.encodedPath)
        assertNull("enrolment must not carry a credential", request.getHeader("Authorization"))
        val body = request.json()
        assertEquals("K7Q2-M9XD", body.getValue("enrollmentCode").jsonPrimitive.content)
        assertEquals("install-1", body.getValue("installationId").jsonPrimitive.content)
        assertEquals("HAMMER", body.getValue("manufacturer").jsonPrimitive.content)
        assertEquals("Iron 5", body.getValue("model").jsonPrimitive.content)
        assertEquals("SN-0001", body.getValue("serialNumber").jsonPrimitive.content)
        assertEquals("14", body.getValue("osVersion").jsonPrimitive.content)
        assertEquals(34, body.getValue("sdkInt").jsonPrimitive.int)
        assertEquals("0.3.0", body.getValue("appVersion").jsonPrimitive.content)
        assertEquals(4, body.getValue("appVersionCode").jsonPrimitive.int)
        assertTrue(body.getValue("isDeviceOwner").jsonPrimitive.boolean)
        assertEquals(TEST_DEVICE_ID, enrollment.credential.deviceId)
        assertEquals(TEST_DEVICE_SECRET, enrollment.credential.deviceSecret)
        assertEquals(900L, enrollment.checkInSeconds)
    }

    @Test
    fun `a phone that is not device owner enrols without a serial number`() = runBlocking {
        server.enqueue(jsonResponse("""{"deviceId":"$TEST_DEVICE_ID","deviceSecret":"$TEST_DEVICE_SECRET","checkInSeconds":900}"""))

        gateway.enroll("K7Q2-M9XD", "install-1", deviceStatus(isDeviceOwner = false, serialNumber = null))

        val body = server.takeRequest().json()
        assertFalse(body.getValue("isDeviceOwner").jsonPrimitive.boolean)
        assertFalse("serialNumber" in body)
    }

    @Test
    fun `an unknown code is a 404 with enrollment_code_invalid`() = runBlocking {
        server.enqueue(problemResponse(404, MdmErrorCodes.ENROLLMENT_CODE_INVALID))

        try {
            gateway.enroll("K7Q2-M9XD", "install-1", deviceStatus())
            fail("expected NotFound")
        } catch (e: RemoteException.NotFound) {
            assertEquals(MdmErrorCodes.ENROLLMENT_CODE_INVALID, e.problem?.code)
        }
    }

    @Test
    fun `the credential header is Device with the id as 32 hex digits and the secret`() = runBlocking {
        server.enqueue(jsonResponse("""{"checkInSeconds":900,"commands":[]}"""))

        gateway.checkIn(deviceStatus(), pushToken = "fcm-1", policyVersionApplied = 3)

        val request = server.takeRequest()
        assertEquals("Device 6f1d2c3b4a594e6f8a7b9c0d1e2f3a4b.$TEST_DEVICE_SECRET", request.getHeader("Authorization"))
        assertEquals("android", request.getHeader("X-Platform"))
    }

    @Test
    fun `check-in reports the device state and reads the policy and the commands`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {
                  "checkInSeconds": 1800,
                  "policy": {"version": 3, "blockFactoryReset": true, "blockUnknownSources": true, "blockUsbFileTransfer": false,
                             "blockSafeBoot": true, "blockAddUser": true},
                  "commands": [
                    {"id": "c-1", "type": "ring", "payload": {"seconds": 60}, "expiresAt": "2026-11-11T12:00:00Z"},
                    {"id": "c-2", "type": "remoteSession.request",
                     "payload": {"sessionId": "s-1", "requestedBy": "Erkan Barlaz", "expiresAt": "2026-11-11T12:01:30Z"},
                     "expiresAt": "2026-11-11T12:01:30Z"},
                    {"id": "c-3", "type": "sync", "payload": {}, "expiresAt": null},
                    {"type": "lock"}
                  ]
                }
                """.trimIndent(),
            ),
        )

        val reply = gateway.checkIn(deviceStatus(), pushToken = "fcm-1", policyVersionApplied = 3)

        val request = server.takeRequest()
        assertEquals("/api/driver/v1/mdm/check-in", request.requestUrl?.encodedPath)
        val body = request.json()
        assertEquals("0.3.0", body.getValue("appVersion").jsonPrimitive.content)
        assertEquals(4, body.getValue("appVersionCode").jsonPrimitive.int)
        assertEquals("2026-08-05", body.getValue("securityPatch").jsonPrimitive.content)
        assertTrue(body.getValue("isDeviceOwner").jsonPrimitive.boolean)
        assertFalse(body.getValue("accessibilityEnabled").jsonPrimitive.boolean)
        assertEquals(81, body.getValue("batteryPercent").jsonPrimitive.int)
        assertFalse(body.getValue("isCharging").jsonPrimitive.boolean)
        assertEquals("wifi", body.getValue("networkType").jsonPrimitive.content)
        assertEquals(41_200L, body.getValue("storageFreeMb").jsonPrimitive.long)
        assertEquals(118_000L, body.getValue("storageTotalMb").jsonPrimitive.long)
        assertEquals("fcm-1", body.getValue("pushToken").jsonPrimitive.content)
        assertEquals("tr-TR", body.getValue("locale").jsonPrimitive.content)
        assertEquals(3, body.getValue("policyVersionApplied").jsonPrimitive.int)

        assertEquals(1_800L, reply.checkInSeconds)
        val policy = reply.policy!!
        assertEquals(3, policy.version)
        assertTrue(policy.blockFactoryReset && policy.blockUnknownSources && policy.blockSafeBoot && policy.blockAddUser)
        assertFalse(policy.blockUsbFileTransfer)
        // A command without an id cannot be answered: it is dropped.
        assertEquals(listOf("c-1", "c-2", "c-3"), reply.commands.map { it.id })
        assertEquals(DeviceCommandTypes.RING, reply.commands[0].type)
        assertEquals("60", reply.commands[0].payload["seconds"])
        assertEquals(Instant.parse("2026-11-11T12:00:00Z"), reply.commands[0].expiresAt)
        assertEquals("Erkan Barlaz", reply.commands[1].payload["requestedBy"])
        assertEquals("s-1", reply.commands[1].payload["sessionId"])
        assertNull(reply.commands[2].expiresAt)
    }

    @Test
    fun `a retired device gets 401 device_not_enrolled`() = runBlocking {
        server.enqueue(problemResponse(401, MdmErrorCodes.DEVICE_NOT_ENROLLED))

        try {
            gateway.checkIn(deviceStatus(), pushToken = null, policyVersionApplied = null)
            fail("expected Unauthorized")
        } catch (e: RemoteException.Unauthorized) {
            assertEquals(MdmErrorCodes.DEVICE_NOT_ENROLLED, e.problem?.code)
        }
        // No token refresh, no second attempt: this client knows nothing about the account session.
        assertEquals(1, server.requestCount)
        val body = server.takeRequest().json()
        assertFalse("pushToken" in body)
        assertFalse("policyVersionApplied" in body)
    }

    @Test
    fun `a command result is posted to its command with status and detail`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        gateway.postCommandResult("c-1", DeviceCommandResult.unsupported("not_device_owner"))
        gateway.postCommandResult("c-2", DeviceCommandResult.succeeded())

        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/api/driver/v1/mdm/commands/c-1/result", first.requestUrl?.encodedPath)
        assertTrue(first.getHeader("Authorization")!!.startsWith("Device "))
        val body = first.json()
        assertEquals("unsupported", body.getValue("status").jsonPrimitive.content)
        assertEquals("not_device_owner", body.getValue("detail").jsonPrimitive.content)
        val second = server.takeRequest().json()
        assertEquals("succeeded", second.getValue("status").jsonPrimitive.content)
        assertFalse("detail" in second)
    }

    @Test
    fun `a result for a command of another device is a 404 with command_not_found`() = runBlocking {
        server.enqueue(problemResponse(404, MdmErrorCodes.COMMAND_NOT_FOUND))

        try {
            gateway.postCommandResult("c-9", DeviceCommandResult.succeeded())
            fail("expected NotFound")
        } catch (e: RemoteException.NotFound) {
            assertEquals(MdmErrorCodes.COMMAND_NOT_FOUND, e.problem?.code)
        }
    }

    @Test
    fun `declining a remote session posts to the session`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        gateway.declineRemoteSession("s-1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/driver/v1/mdm/remote-sessions/s-1/decline", request.requestUrl?.encodedPath)
        assertTrue(request.getHeader("Authorization")!!.startsWith("Device "))
    }

    @Test
    fun `without a stored credential no authorization is invented`() = runBlocking {
        credential = null
        server.enqueue(problemResponse(401, MdmErrorCodes.DEVICE_NOT_ENROLLED))

        try {
            gateway.checkIn(deviceStatus(), pushToken = null, policyVersionApplied = null)
            fail("expected Unauthorized")
        } catch (_: RemoteException.Unauthorized) {
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }
}
