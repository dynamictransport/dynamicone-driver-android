package com.dynamictransport.dynamicone.feature.auth

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.LoginResult
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.AuthRepository
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    /** The backend asked for an authenticator code. The app offers no enrollment or recovery. */
    val twoFactorRequired: Boolean = false,
    val twoFactorCode: String = "",
    val submitting: Boolean = false,
    @param:StringRes val error: Int? = null,
    val sessionExpired: Boolean = false,
    val isDemoMode: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    appInfo: AppInfo,
) : ViewModel() {

    private val form = MutableStateFlow(LoginUiState(isDemoMode = appInfo.isDemoMode))
    private val singleFlight = SingleFlight()

    val uiState: StateFlow<LoginUiState> =
        combine(form, singleFlight.busy, authRepository.sessionExpired) { state, busy, expired ->
            state.copy(submitting = busy, sessionExpired = expired)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), form.value)

    init {
        viewModelScope.launch {
            val lastUsername = authRepository.lastUsername()
            if (!lastUsername.isNullOrBlank()) {
                form.update { if (it.username.isEmpty()) it.copy(username = lastUsername) else it }
            }
        }
    }

    fun onUsernameChanged(value: String) =
        form.update { it.copy(username = value, twoFactorRequired = false, twoFactorCode = "", error = null) }

    fun onPasswordChanged(value: String) = form.update { it.copy(password = value, error = null) }

    fun onTogglePasswordVisibility() = form.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun onTwoFactorCodeChanged(value: String) =
        form.update { it.copy(twoFactorCode = value.filter(Char::isDigit).take(TWO_FACTOR_CODE_LENGTH), error = null) }

    fun onLogin() {
        val current = form.value
        if (current.username.isBlank() || current.password.isEmpty()) {
            form.update { it.copy(error = R.string.login_error_empty) }
            return
        }
        if (current.twoFactorRequired && current.twoFactorCode.length != TWO_FACTOR_CODE_LENGTH) {
            form.update { it.copy(error = R.string.login_error_two_factor_format) }
            return
        }
        singleFlight.launch(viewModelScope) {
            form.update { it.copy(error = null) }
            val code = current.twoFactorCode.takeIf { current.twoFactorRequired }
            val result = authRepository.login(current.username, current.password, code)
            val succeeded = result == LoginResult.SUCCESS
            form.update {
                it.copy(
                    password = if (succeeded) "" else it.password,
                    twoFactorCode = if (succeeded) "" else it.twoFactorCode,
                    twoFactorRequired = !succeeded && (it.twoFactorRequired || result == LoginResult.TWO_FACTOR_REQUIRED),
                    error = result.errorRes(codeSent = code != null),
                )
            }
        }
    }

    private companion object {
        const val TWO_FACTOR_CODE_LENGTH = 6
    }
}

@StringRes
private fun LoginResult.errorRes(codeSent: Boolean): Int? = when (this) {
    LoginResult.SUCCESS -> null
    LoginResult.INVALID_CREDENTIALS -> R.string.login_error_invalid
    LoginResult.TWO_FACTOR_REQUIRED -> if (codeSent) R.string.login_error_two_factor_invalid else R.string.login_two_factor_hint
    LoginResult.DRIVER_ACCESS_REQUIRED -> R.string.login_error_driver_access
    LoginResult.RATE_LIMITED -> R.string.login_error_rate_limited
    LoginResult.SERVICE_UNAVAILABLE -> R.string.refresh_service_unavailable
    LoginResult.OFFLINE -> R.string.login_error_offline
    LoginResult.FAILED -> R.string.login_error_failed
}
