package com.dynamictransport.dynamicone.feature.profile

import com.dynamictransport.dynamicone.data.demo.UnavailableDemoBackendControls
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.AssignedVehicle
import com.dynamictransport.dynamicone.domain.model.AssignedVehicleState
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.testing.FakeAppInfo
import com.dynamictransport.dynamicone.testing.FakeAuthRepository
import com.dynamictransport.dynamicone.testing.FakeNetworkMonitor
import com.dynamictransport.dynamicone.testing.FakeSyncRepository
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.keepCollecting
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val auth = FakeAuthRepository()

    private fun viewModel() = ProfileViewModel(
        authRepository = auth,
        syncRepository = FakeSyncRepository(),
        networkMonitor = FakeNetworkMonitor(),
        appInfo = FakeAppInfo(),
        demoControls = UnavailableDemoBackendControls,
    )

    @Test
    fun `the profile shows the assigned vehicle and every change of it`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        assertEquals(AssignedVehicleState.Loading, viewModel.uiState.value.assignedVehicle)

        val dyna042 = AssignedVehicleState.Assigned(AssignedVehicle("veh-42", "Dyna042", "WX 12345"))
        auth.assignedVehicle.value = dyna042
        assertEquals(dyna042, viewModel.uiState.value.assignedVehicle)

        val withoutCode = AssignedVehicleState.Assigned(AssignedVehicle("veh-7", null, "WZ 777AA"))
        auth.assignedVehicle.value = withoutCode
        assertEquals(withoutCode, viewModel.uiState.value.assignedVehicle)

        auth.assignedVehicle.value = AssignedVehicleState.NotAssigned
        assertEquals(AssignedVehicleState.NotAssigned, viewModel.uiState.value.assignedVehicle)
    }

    @Test
    fun `another account signing in brings its own vehicle state`() = runTest {
        auth.assignedVehicle.value = AssignedVehicleState.Assigned(AssignedVehicle("veh-42", "Dyna042", "WX 12345"))
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        // Signing out clears the stored answer; the next account starts without one.
        auth.sessionState.value = SessionState.LoggedOut
        auth.assignedVehicle.value = AssignedVehicleState.Loading
        auth.sessionState.value = SessionState.LoggedIn("drv-2", setOf(AppModule.DRIVER))
        assertEquals(AssignedVehicleState.Loading, viewModel.uiState.value.assignedVehicle)

        auth.assignedVehicle.value = AssignedVehicleState.NotAssigned
        assertEquals(AssignedVehicleState.NotAssigned, viewModel.uiState.value.assignedVehicle)
    }

    @Test
    fun `coming back to the profile and retrying read the profile again`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(1, auth.profileRefreshes)

        viewModel.onRetryAssignedVehicle()
        advanceUntilIdle()
        assertEquals(2, auth.profileRefreshes)
    }
}
