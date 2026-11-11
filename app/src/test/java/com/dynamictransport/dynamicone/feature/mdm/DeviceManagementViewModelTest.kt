package com.dynamictransport.dynamicone.feature.mdm

import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.CheckInResult
import com.dynamictransport.dynamicone.domain.model.EnrollResult
import com.dynamictransport.dynamicone.testing.FakeDeviceManagementRepository
import com.dynamictransport.dynamicone.testing.FakeDevicePolicyController
import com.dynamictransport.dynamicone.testing.FakeRemoteControlAccess
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.keepCollecting
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeviceManagementViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeDeviceManagementRepository()
    private val policy = FakeDevicePolicyController(isDeviceOwner = false)
    private val remoteControl = FakeRemoteControlAccess()

    private fun viewModel() = DeviceManagementViewModel(repository, policy, remoteControl)

    @Test
    fun `a phone that is not enrolled offers the code and enrols only with a complete one`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        assertFalse(viewModel.uiState.value.enrolled)
        assertFalse(viewModel.uiState.value.canEnrol)

        viewModel.onCodeChanged("k7q2-m9x")
        assertEquals("K7Q2-M9X", viewModel.uiState.value.code)
        assertFalse(viewModel.uiState.value.canEnrol)
        viewModel.onEnrol()
        assertEquals(R.string.mdm_error_code, viewModel.uiState.value.error)
        assertTrue(repository.codes.isEmpty())

        viewModel.onCodeChanged("k7q2-m9xd")
        assertNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.canEnrol)
        viewModel.onEnrol()

        assertEquals(listOf("K7Q2-M9XD"), repository.codes)
        assertTrue(viewModel.uiState.value.enrolled)
        assertEquals(TEST_NOW, viewModel.uiState.value.lastCheckInAt)
        assertEquals("", viewModel.uiState.value.code)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `each enrolment failure has its own plain message and keeps the typed code`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onCodeChanged("K7Q2-M9XD")

        val expected = mapOf(
            EnrollResult.INVALID_CODE to R.string.mdm_error_code,
            EnrollResult.OFFLINE to R.string.mdm_error_offline,
            EnrollResult.FAILED to R.string.mdm_error_failed,
        )
        for ((result, message) in expected) {
            repository.enrollResult = result
            viewModel.onEnrol()
            assertEquals(message, viewModel.uiState.value.error)
            assertEquals("K7Q2-M9XD", viewModel.uiState.value.code)
            assertFalse(viewModel.uiState.value.enrolled)
        }
    }

    @Test
    fun `the screen shows device owner and remote help as the phone reports them now`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        assertFalse(viewModel.uiState.value.deviceOwner)
        assertFalse(viewModel.uiState.value.accessibilityEnabled)

        // Changed outside the app (adb, system settings); read again when the screen comes back.
        policy.isDeviceOwner = true
        remoteControl.isEnabled = true
        viewModel.refresh()

        assertTrue(viewModel.uiState.value.deviceOwner)
        assertTrue(viewModel.uiState.value.accessibilityEnabled)
    }

    @Test
    fun `remote help is switched on by the app where allowed and left to the settings otherwise`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        assertFalse(viewModel.onEnableAccessibility())
        assertFalse(viewModel.uiState.value.accessibilityEnabled)

        remoteControl.canSelfEnable = true
        assertTrue(viewModel.onEnableAccessibility())
        assertTrue(viewModel.uiState.value.accessibilityEnabled)
    }

    @Test
    fun `checking in now reports an outage and nothing else`() = runTest {
        repository.enrolled.value = true
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onCheckInNow()
        assertEquals(1, repository.checkIns)
        assertNull(viewModel.uiState.value.error)

        repository.checkInResult = CheckInResult.OFFLINE
        viewModel.onCheckInNow()
        assertEquals(R.string.mdm_error_offline, viewModel.uiState.value.error)
    }
}
