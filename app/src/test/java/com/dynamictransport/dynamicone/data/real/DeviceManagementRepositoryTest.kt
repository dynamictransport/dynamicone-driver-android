package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.mdm.DeviceCommandExecutor
import com.dynamictransport.dynamicone.core.mdm.DeviceCommandLedger
import com.dynamictransport.dynamicone.core.mdm.DeviceCommandReporter
import com.dynamictransport.dynamicone.core.mdm.DeviceCredentialStore
import com.dynamictransport.dynamicone.core.mdm.DeviceManagementStateStore
import com.dynamictransport.dynamicone.core.mdm.DevicePolicyApplier
import com.dynamictransport.dynamicone.core.mdm.DeviceRestriction
import com.dynamictransport.dynamicone.core.network.ApiProblem
import com.dynamictransport.dynamicone.core.network.MdmErrorCodes
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.domain.model.CheckInResult
import com.dynamictransport.dynamicone.domain.model.DeviceCheckIn
import com.dynamictransport.dynamicone.domain.model.DeviceCommandTypes
import com.dynamictransport.dynamicone.domain.model.DeviceCredential
import com.dynamictransport.dynamicone.domain.model.DevicePolicy
import com.dynamictransport.dynamicone.domain.model.EnrollResult
import com.dynamictransport.dynamicone.testing.FakeAppInfo
import com.dynamictransport.dynamicone.testing.FakeDeviceAlerts
import com.dynamictransport.dynamicone.testing.FakeDeviceCheckInScheduler
import com.dynamictransport.dynamicone.testing.FakeDeviceManagementGateway
import com.dynamictransport.dynamicone.testing.FakeDevicePolicyController
import com.dynamictransport.dynamicone.testing.FakeDevicePushTokenSource
import com.dynamictransport.dynamicone.testing.FakeRemoteSessionCommands
import com.dynamictransport.dynamicone.testing.FixedDeviceStatusCollector
import com.dynamictransport.dynamicone.testing.InMemoryPreferencesDataStore
import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.NoOpLogger
import com.dynamictransport.dynamicone.testing.TEST_DEVICE_ID
import com.dynamictransport.dynamicone.testing.TEST_DEVICE_SECRET
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.XorCryptoBox
import com.dynamictransport.dynamicone.testing.command
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/29 §2–§3: enrolment, check-in, policy, commands and the retired device, without Android. */
class DeviceManagementRepositoryTest {

    private val deviceStore = InMemoryPreferencesDataStore()
    private val sessionStore = InMemoryPreferencesDataStore()
    private val clock = MutableClock()
    private val gateway = FakeDeviceManagementGateway()
    private val controller = FakeDevicePolicyController()
    private val alerts = FakeDeviceAlerts()
    private val scheduler = FakeDeviceCheckInScheduler()
    private val pushTokens = FakeDevicePushTokenSource()
    private val credentials = DeviceCredentialStore(deviceStore, XorCryptoBox())
    private val state = DeviceManagementStateStore(deviceStore)
    private val ledger = DeviceCommandLedger(deviceStore, clock)
    private val sessionPreferences = SessionPreferences(sessionStore)

    private val policy = DevicePolicy(3, blockFactoryReset = true, blockUnknownSources = true, blockUsbFileTransfer = false, blockSafeBoot = true, blockAddUser = true)

    private fun repository(appInfo: FakeAppInfo = FakeAppInfo(isDeviceManagementEnabled = true)) = RealDeviceManagementRepository(
        gateway = gateway,
        credentials = credentials,
        state = state,
        ledger = ledger,
        sessionPreferences = sessionPreferences,
        statusCollector = FixedDeviceStatusCollector(),
        policyApplier = DevicePolicyApplier(controller, state, NoOpLogger),
        executor = DeviceCommandExecutor(ledger, DeviceCommandReporter(ledger, gateway), controller, alerts, FakeRemoteSessionCommands(), NoOpLogger),
        pushTokens = pushTokens,
        scheduler = scheduler,
        appInfo = appInfo,
        clock = clock,
        logger = NoOpLogger,
    )

    private suspend fun enrolled() = repository().also { credentials.write(DeviceCredential(TEST_DEVICE_ID, TEST_DEVICE_SECRET)) }

    @Test
    fun `enrolment stores the credential, schedules the check-in and checks in at once`() = runBlocking {
        val repository = repository()
        assertFalse(repository.isEnrolled())

        assertEquals(EnrollResult.ENROLLED, repository.enroll("k7q2m9xd"))

        assertEquals("K7Q2-M9XD", gateway.enrolledCode)
        assertEquals(listOf("enroll", "check-in"), gateway.calls)
        assertTrue(repository.isEnrolled())
        assertTrue(repository.enrolled.first())
        assertEquals(TEST_DEVICE_SECRET, credentials.read()?.deviceSecret)
        assertEquals(listOf(900L to true), scheduler.periodic)
        assertEquals(TEST_NOW, repository.lastCheckInAt.first())
        assertEquals("fcm-token-1", gateway.lastPushToken)
        // The device owner cannot be uninstalled from now on.
        assertTrue(controller.uninstallBlocked)
    }

    @Test
    fun `a code that cannot be valid never reaches the server`() = runBlocking {
        assertEquals(EnrollResult.INVALID_CODE, repository().enroll("K7Q2"))

        assertTrue(gateway.calls.isEmpty())
        assertFalse(controller.uninstallBlocked)
    }

    @Test
    fun `a code the server refuses leaves the phone as it was`() = runBlocking {
        val repository = repository()
        gateway.enrollFailure = RemoteException.NotFound(ApiProblem(404, MdmErrorCodes.ENROLLMENT_CODE_INVALID, null, null))
        assertEquals(EnrollResult.INVALID_CODE, repository.enroll("K7Q2-M9XD"))

        gateway.enrollFailure = RemoteException.Offline()
        assertEquals(EnrollResult.OFFLINE, repository.enroll("K7Q2-M9XD"))

        gateway.enrollFailure = RemoteException.ServerError(500)
        assertEquals(EnrollResult.FAILED, repository.enroll("K7Q2-M9XD"))

        assertFalse(repository.isEnrolled())
        assertFalse(controller.uninstallBlocked)
        assertTrue(scheduler.periodic.isEmpty())
    }

    @Test
    fun `the first check-in failing after enrolment is left to the worker`() = runBlocking {
        gateway.checkInFailure = RemoteException.Offline()

        assertEquals(EnrollResult.ENROLLED, repository().enroll("K7Q2-M9XD"))

        assertEquals(1, scheduler.requests)
    }

    @Test
    fun `check-in applies the policy and reports its version the next time`() = runBlocking {
        val repository = enrolled()
        gateway.checkIns += DeviceCheckIn(900, policy, emptyList())

        assertEquals(CheckInResult.DONE, repository.checkIn())
        assertNull(gateway.lastPolicyVersionApplied)
        assertEquals(true, controller.restrictions[DeviceRestriction.FACTORY_RESET])
        assertEquals(false, controller.restrictions[DeviceRestriction.USB_FILE_TRANSFER])

        repository.checkIn()
        assertEquals(3, gateway.lastPolicyVersionApplied)
    }

    @Test
    fun `a phone that is not device owner checks in but applies nothing`() = runBlocking {
        controller.isDeviceOwner = false
        val repository = enrolled()
        gateway.checkIns += DeviceCheckIn(900, policy, emptyList())

        repository.checkIn()
        repository.checkIn()

        assertTrue(controller.restrictions.isEmpty())
        assertNull(gateway.lastPolicyVersionApplied)
    }

    @Test
    fun `a finished command is followed by one more check-in and never runs twice`() = runBlocking {
        val repository = enrolled()
        val lock = command("c-1", DeviceCommandTypes.LOCK)
        // The server returns the command until the result arrived; here even once more after it.
        gateway.checkIns += DeviceCheckIn(900, null, listOf(lock))
        gateway.checkIns += DeviceCheckIn(900, null, listOf(lock))
        gateway.checkIns += DeviceCheckIn(900, null, emptyList())

        assertEquals(CheckInResult.DONE, repository.checkIn())

        assertEquals(listOf("lock"), controller.calls)
        assertEquals(listOf("check-in", "result:c-1:succeeded", "check-in", "result:c-1:succeeded"), gateway.calls)
    }

    @Test
    fun `a changed interval reschedules the periodic check-in, never below fifteen minutes`() = runBlocking {
        val repository = enrolled()
        gateway.checkIns += DeviceCheckIn(3_600, null, emptyList())
        gateway.checkIns += DeviceCheckIn(60, null, emptyList())

        repository.checkIn()
        assertEquals(listOf(3_600L to true), scheduler.periodic)
        assertEquals(3_600L, state.checkInSeconds())

        repository.checkIn()
        assertEquals(listOf(3_600L to true, 900L to true), scheduler.periodic)
    }

    @Test
    fun `a retired device forgets its credential, stops checking in and lifts the restrictions`() = runBlocking {
        val repository = enrolled()
        gateway.checkIns += DeviceCheckIn(900, policy, emptyList())
        repository.checkIn()
        assertTrue(controller.uninstallBlocked)

        gateway.checkInFailure = RemoteException.Unauthorized(ApiProblem(401, MdmErrorCodes.DEVICE_NOT_ENROLLED, null, null))
        assertEquals(CheckInResult.RETIRED, repository.checkIn())

        assertFalse(repository.isEnrolled())
        assertNull(credentials.read())
        assertEquals(1, scheduler.stops)
        assertTrue(controller.restrictions.values.none { it })
        assertFalse(controller.uninstallBlocked)
        assertNull(state.policyVersionApplied())
        assertEquals(CheckInResult.NOT_ENROLLED, repository.checkIn())
    }

    @Test
    fun `another 401 or an outage keeps the enrolment`() = runBlocking {
        val repository = enrolled()

        gateway.checkInFailure = RemoteException.Unauthorized(ApiProblem(401, "something_else", null, null))
        assertEquals(CheckInResult.FAILED, repository.checkIn())
        gateway.checkInFailure = RemoteException.Offline()
        assertEquals(CheckInResult.OFFLINE, repository.checkIn())
        gateway.checkInFailure = RemoteException.ServerUnavailable(503)
        assertEquals(CheckInResult.OFFLINE, repository.checkIn())

        assertTrue(repository.isEnrolled())
        assertEquals(0, scheduler.stops)
    }

    @Test
    fun `signing out does not touch the device credential`() = runBlocking {
        val repository = enrolled()

        sessionPreferences.clearLoggedInUser(expired = false)

        assertTrue(repository.isEnrolled())
        assertEquals(CheckInResult.DONE, repository.checkIn())
    }

    @Test
    fun `in the demo and with the flag off the phone is not managed`() = runBlocking {
        credentials.write(DeviceCredential(TEST_DEVICE_ID, TEST_DEVICE_SECRET))

        for (appInfo in listOf(FakeAppInfo(isDeviceManagementEnabled = false), FakeAppInfo(isDemoMode = true, isDeviceManagementEnabled = true))) {
            val repository = repository(appInfo)
            assertFalse(repository.isEnrolled())
            assertFalse(repository.enrolled.first())
            assertEquals(EnrollResult.FAILED, repository.enroll("K7Q2-M9XD"))
            assertEquals(CheckInResult.NOT_ENROLLED, repository.checkIn())
        }
        assertTrue(gateway.calls.isEmpty())
    }
}
