package com.dynamictransport.dynamicone.core.mdm

import com.dynamictransport.dynamicone.domain.model.DeviceCredential
import com.dynamictransport.dynamicone.domain.model.DevicePolicy
import com.dynamictransport.dynamicone.domain.model.EnrollmentCode
import com.dynamictransport.dynamicone.testing.FakeDevicePolicyController
import com.dynamictransport.dynamicone.testing.InMemoryPreferencesDataStore
import com.dynamictransport.dynamicone.testing.NoOpLogger
import com.dynamictransport.dynamicone.testing.TEST_DEVICE_ID
import com.dynamictransport.dynamicone.testing.TEST_DEVICE_SECRET
import com.dynamictransport.dynamicone.testing.XorCryptoBox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/29 §2 and §3.5: the stored device credential and the five restrictions. */
class DevicePolicyAndCredentialTest {

    private val dataStore = InMemoryPreferencesDataStore()
    private val controller = FakeDevicePolicyController()
    private val state = DeviceManagementStateStore(dataStore)
    private val applier = DevicePolicyApplier(controller, state, NoOpLogger)

    private val policy = DevicePolicy(
        version = 3,
        blockFactoryReset = true,
        blockUnknownSources = true,
        blockUsbFileTransfer = false,
        blockSafeBoot = true,
        blockAddUser = true,
    )

    @Test
    fun `the device owner sets each restriction as the policy says and remembers the version`() = runBlocking {
        assertTrue(applier.apply(policy))

        assertEquals(
            mapOf(
                DeviceRestriction.FACTORY_RESET to true,
                DeviceRestriction.UNKNOWN_SOURCES to true,
                DeviceRestriction.USB_FILE_TRANSFER to false,
                DeviceRestriction.SAFE_BOOT to true,
                DeviceRestriction.ADD_USER to true,
            ),
            controller.restrictions,
        )
        assertEquals(3, state.policyVersionApplied())
    }

    @Test
    fun `a newer policy lifts what it no longer blocks`() = runBlocking {
        applier.apply(policy)

        applier.apply(policy.copy(version = 4, blockFactoryReset = false, blockUsbFileTransfer = true))

        assertEquals(false, controller.restrictions[DeviceRestriction.FACTORY_RESET])
        assertEquals(true, controller.restrictions[DeviceRestriction.USB_FILE_TRANSFER])
        assertEquals(4, state.policyVersionApplied())
    }

    @Test
    fun `without device owner nothing is applied and no version is reported`() = runBlocking {
        controller.isDeviceOwner = false

        assertFalse(applier.apply(policy))
        applier.onEnrolled()

        assertTrue(controller.restrictions.isEmpty())
        assertFalse(controller.uninstallBlocked)
        assertTrue(controller.grantedPermissions.isEmpty())
        assertNull(state.policyVersionApplied())
    }

    @Test
    fun `a restriction the phone refuses leaves the version unreported`() = runBlocking {
        controller.failure = SecurityException("refused")

        assertFalse(applier.apply(policy))

        assertNull(state.policyVersionApplied())
    }

    @Test
    fun `an enrolled device owner cannot be uninstalled until the device is retired`() {
        applier.onEnrolled()
        assertTrue(controller.uninstallBlocked)

        runBlocking { applier.apply(policy) }
        applier.clear()

        assertFalse(controller.uninstallBlocked)
        assertEquals(DeviceRestriction.entries.toSet(), controller.restrictions.keys)
        assertTrue(controller.restrictions.values.none { it })
    }

    @Test
    fun `the restrictions are the five of the contract`() {
        assertEquals(
            listOf("no_factory_reset", "no_install_unknown_sources", "no_usb_file_transfer", "no_safe_boot", "no_add_user"),
            DeviceRestriction.entries.map { it.key },
        )
    }

    @Test
    fun `the credential is stored encrypted and read back by a new process`() = runBlocking {
        DeviceCredentialStore(dataStore, XorCryptoBox()).write(DeviceCredential(TEST_DEVICE_ID, TEST_DEVICE_SECRET))

        val raw = dataStore.data.first().asMap().values.joinToString()
        assertFalse(raw.contains(TEST_DEVICE_SECRET))
        assertFalse(raw.contains(TEST_DEVICE_ID))
        val restarted = DeviceCredentialStore(dataStore, XorCryptoBox())
        assertTrue(restarted.enrolled.first())
        assertEquals("Device 6f1d2c3b4a594e6f8a7b9c0d1e2f3a4b.$TEST_DEVICE_SECRET", restarted.currentAuthorization())
        assertFalse(restarted.read().toString().contains(TEST_DEVICE_SECRET))

        restarted.clear()
        assertNull(DeviceCredentialStore(dataStore, XorCryptoBox()).read())
        assertFalse(restarted.enrolled.first())
        assertNull(restarted.currentAuthorization())
    }

    @Test
    fun `the enrolment code ignores dash, spaces and case and rejects anything else`() {
        assertEquals("K7Q2-M9XD", EnrollmentCode.normalize("k7q2m9xd"))
        assertEquals("K7Q2-M9XD", EnrollmentCode.normalize(" K7Q2-M9XD "))
        assertEquals("K7Q2-M9XD", EnrollmentCode.normalize("k7q2 m9xd"))
        assertNull(EnrollmentCode.normalize("K7Q2-M9X"))
        assertNull(EnrollmentCode.normalize("K7Q2-M9XDD"))
        // 0, 1, I and O are not in the alphabet.
        assertNull(EnrollmentCode.normalize("K7Q2-M9X0"))
        assertNull(EnrollmentCode.normalize("K7Q2-M9XI"))
        assertNull(EnrollmentCode.normalize(""))
    }
}
