package com.dynamictransport.dynamicone.feature

import androidx.lifecycle.SavedStateHandle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.IncidentCategory
import com.dynamictransport.dynamicone.domain.model.LoginResult
import com.dynamictransport.dynamicone.domain.model.PasswordChangeResult
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.usecase.ReportIncidentUseCase
import com.dynamictransport.dynamicone.feature.auth.ChangePasswordViewModel
import com.dynamictransport.dynamicone.feature.auth.LoginViewModel
import com.dynamictransport.dynamicone.feature.incident.ReportIncidentViewModel
import com.dynamictransport.dynamicone.feature.tripdetail.TRIP_ID_ARG
import com.dynamictransport.dynamicone.testing.FakeAppInfo
import com.dynamictransport.dynamicone.testing.FakeAuthRepository
import com.dynamictransport.dynamicone.testing.FakeIncidentRepository
import com.dynamictransport.dynamicone.testing.FakeTripRepository
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.keepCollecting
import com.dynamictransport.dynamicone.testing.testTrip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val auth = FakeAuthRepository()

    private fun viewModel() = LoginViewModel(auth, FakeAppInfo(isDemoMode = true))

    @Test
    fun `empty credentials show an error without calling the backend`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)

        viewModel.onLogin()

        assertEquals(R.string.login_error_empty, viewModel.uiState.value.error)
        assertEquals(0, auth.loginCalls)
    }

    @Test
    fun `wrong password shows a human readable message and keeps the input`() = runTest {
        auth.loginResult = LoginResult.INVALID_CREDENTIALS
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onUsernameChanged("driver.1027")
        viewModel.onPasswordChanged("secret")

        viewModel.onLogin()
        advanceUntilIdle()

        assertEquals(R.string.login_error_invalid, viewModel.uiState.value.error)
        assertEquals("secret", viewModel.uiState.value.password)
    }

    @Test
    fun `offline sign in explains that internet is needed`() = runTest {
        auth.loginResult = LoginResult.OFFLINE
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onUsernameChanged("driver.1027")
        viewModel.onPasswordChanged("secret")

        viewModel.onLogin()
        advanceUntilIdle()

        assertEquals(R.string.login_error_offline, viewModel.uiState.value.error)
    }

    @Test
    fun `double tap signs in once and success clears the password`() = runTest {
        val gate = CompletableDeferred<Unit>()
        auth.loginGate = gate
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onUsernameChanged("driver.1027")
        viewModel.onPasswordChanged("secret")

        viewModel.onLogin()
        viewModel.onLogin()
        assertTrue(viewModel.uiState.value.submitting)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, auth.loginCalls)
        assertEquals("", viewModel.uiState.value.password)
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `last username is prefilled and expired sessions are explained`() = runTest {
        auth.lastUsernameValue = "driver.1027"
        auth.sessionExpired.value = true
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        advanceUntilIdle()

        assertEquals("driver.1027", viewModel.uiState.value.username)
        assertTrue(viewModel.uiState.value.sessionExpired)
        assertTrue(viewModel.uiState.value.isDemoMode)
    }

    @Test
    fun `two factor requirement shows the code field and the next attempt sends the code`() = runTest {
        auth.loginResult = LoginResult.TWO_FACTOR_REQUIRED
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onUsernameChanged("first.last")
        viewModel.onPasswordChanged("secret")

        viewModel.onLogin()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.twoFactorRequired)
        assertEquals(R.string.login_two_factor_hint, viewModel.uiState.value.error)
        assertNull(auth.lastTwoFactorCode)

        viewModel.onLogin()
        assertEquals(R.string.login_error_two_factor_format, viewModel.uiState.value.error)
        assertEquals(1, auth.loginCalls)

        auth.loginResult = LoginResult.SUCCESS
        viewModel.onTwoFactorCodeChanged("12 34-56")
        viewModel.onLogin()
        advanceUntilIdle()

        assertEquals("123456", auth.lastTwoFactorCode)
        assertEquals(2, auth.loginCalls)
        assertFalse(viewModel.uiState.value.twoFactorRequired)
    }

    @Test
    fun `backend refusals map to specific messages`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        viewModel.onUsernameChanged("first.last")
        viewModel.onPasswordChanged("secret")

        mapOf(
            LoginResult.APP_ACCESS_REQUIRED to R.string.login_error_app_access,
            LoginResult.RATE_LIMITED to R.string.login_error_rate_limited,
            LoginResult.SERVICE_UNAVAILABLE to R.string.refresh_service_unavailable,
        ).forEach { (result, message) ->
            auth.loginResult = result
            viewModel.onLogin()
            advanceUntilIdle()
            assertEquals(message, viewModel.uiState.value.error)
        }
    }
}

class ChangePasswordViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val auth = FakeAuthRepository()

    @Test
    fun `new passwords must match before the backend is called`() = runTest {
        val viewModel = ChangePasswordViewModel(auth)
        keepCollecting(viewModel.uiState)
        viewModel.onCurrentPasswordChanged("old")
        viewModel.onNewPasswordChanged("new-password-1")
        viewModel.onConfirmPasswordChanged("new-password-2")

        viewModel.onSubmit()

        assertEquals(R.string.password_error_mismatch, viewModel.uiState.value.error)
        assertTrue(auth.passwordChanges.isEmpty())
    }

    @Test
    fun `rejected password is explained and the form is kept`() = runTest {
        auth.passwordChangeResult = PasswordChangeResult.REJECTED
        val viewModel = ChangePasswordViewModel(auth)
        keepCollecting(viewModel.uiState)
        viewModel.onCurrentPasswordChanged("old")
        viewModel.onNewPasswordChanged("new-password")
        viewModel.onConfirmPasswordChanged("new-password")

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(listOf("old" to "new-password"), auth.passwordChanges)
        assertEquals(R.string.password_error_rejected, viewModel.uiState.value.error)
        assertEquals("new-password", viewModel.uiState.value.newPassword)
    }
}

class ReportIncidentViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val trips = FakeTripRepository(
        listOf(testTrip(id = "running", state = TripState.STARTED), testTrip(id = "new", state = TripState.ASSIGNED)),
    )
    private val incidents = FakeIncidentRepository()

    private fun viewModel(tripId: String? = null, demo: Boolean = true) = ReportIncidentViewModel(
        savedStateHandle = SavedStateHandle(if (tripId == null) emptyMap<String, Any?>() else mapOf(TRIP_ID_ARG to tripId)),
        tripRepository = trips,
        reportIncident = ReportIncidentUseCase(incidents),
        appInfo = FakeAppInfo(isDemoMode = demo),
    )

    @Test
    fun `category is required before sending`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        assertFalse(viewModel.uiState.value.canSubmit)

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(R.string.error_reason_required, viewModel.uiState.value.error)
        assertTrue(incidents.reports.isEmpty())
    }

    @Test
    fun `report is linked to the running trip and the note stays optional`() = runTest {
        val viewModel = viewModel()
        keepCollecting(viewModel.uiState)
        advanceUntilIdle()
        assertEquals("TRP-RUNNING", viewModel.uiState.value.tripNo)

        viewModel.onCategorySelected(IncidentCategory.VEHICLE_BREAKDOWN)
        viewModel.onNoteChanged("  Flat tyre  ")
        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(
            FakeIncidentRepository.Report("running", IncidentCategory.VEHICLE_BREAKDOWN, "Flat tyre"),
            incidents.reports.single(),
        )
        assertTrue(viewModel.uiState.value.completed)
        assertFalse(viewModel.uiState.value.sentToServer)
    }

    @Test
    fun `trip opened by the driver is used for the report`() = runTest {
        val viewModel = viewModel(tripId = "new")
        keepCollecting(viewModel.uiState)
        advanceUntilIdle()

        viewModel.onCategorySelected(IncidentCategory.DELAY)
        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals("new", incidents.reports.single().tripId)
    }

    @Test
    fun `real api reports are confirmed as sent`() = runTest {
        val viewModel = viewModel(demo = false)
        keepCollecting(viewModel.uiState)

        assertTrue(viewModel.uiState.value.sentToServer)
    }
}
