package com.dynamictransport.driver.feature.auth

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.driver.R
import com.dynamictransport.driver.core.design.component.DriverTopBar
import com.dynamictransport.driver.core.design.component.PrimaryActionButton
import com.dynamictransport.driver.core.design.component.SecondaryActionButton
import com.dynamictransport.driver.core.design.component.StatusBanner
import com.dynamictransport.driver.core.design.component.StatusTone
import com.dynamictransport.driver.core.design.theme.Dimens
import com.dynamictransport.driver.domain.model.PasswordChangeResult
import com.dynamictransport.driver.domain.repository.AuthRepository
import com.dynamictransport.driver.domain.rules.DriverInputRules
import com.dynamictransport.driver.feature.common.ActionBar
import com.dynamictransport.driver.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.driver.feature.common.SingleFlight
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ChangePasswordUiState(
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val submitting: Boolean = false,
    @param:StringRes val error: Int? = null,
)

/**
 * Required password change (the backend blocks every driver call until it is done) and the voluntary change
 * from the profile. The server applies its Identity rules on top of the documented 12-128 character length.
 */
@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val form = MutableStateFlow(ChangePasswordUiState())
    private val singleFlight = SingleFlight()

    val uiState: StateFlow<ChangePasswordUiState> =
        combine(form, singleFlight.busy) { state, busy -> state.copy(submitting = busy) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), form.value)

    fun onCurrentPasswordChanged(value: String) = form.update { it.copy(currentPassword = value, error = null) }

    fun onNewPasswordChanged(value: String) = form.update { it.copy(newPassword = value, error = null) }

    fun onConfirmPasswordChanged(value: String) = form.update { it.copy(confirmPassword = value, error = null) }

    fun onSubmit() {
        val current = form.value
        val validationError = when {
            current.currentPassword.isEmpty() || current.newPassword.isEmpty() || current.confirmPassword.isEmpty() ->
                R.string.password_error_empty
            current.newPassword != current.confirmPassword -> R.string.password_error_mismatch
            current.newPassword.length !in DriverInputRules.PASSWORD_LENGTH -> R.string.password_error_length
            else -> null
        }
        if (validationError != null) {
            form.update { it.copy(error = validationError) }
            return
        }
        singleFlight.launch(viewModelScope) {
            val result = authRepository.changePassword(current.currentPassword, current.newPassword)
            // On success the session ends and the login screen replaces this one.
            form.update { if (result == PasswordChangeResult.SUCCESS) ChangePasswordUiState() else it.copy(error = result.errorRes()) }
        }
    }

    fun onLogout() {
        singleFlight.launch(viewModelScope) { authRepository.logout() }
    }
}

@StringRes
private fun PasswordChangeResult.errorRes(): Int? = when (this) {
    PasswordChangeResult.SUCCESS -> null
    PasswordChangeResult.REJECTED -> R.string.password_error_rejected
    PasswordChangeResult.WRONG_CURRENT_PASSWORD -> R.string.password_error_wrong_current
    PasswordChangeResult.WEAK_PASSWORD -> R.string.password_error_weak
    PasswordChangeResult.SESSION_EXPIRED -> R.string.refresh_session_expired
    PasswordChangeResult.RATE_LIMITED -> R.string.refresh_rate_limited
    PasswordChangeResult.OFFLINE -> R.string.sync_offline_real
    PasswordChangeResult.NOT_SUPPORTED -> R.string.feature_not_connected
    PasswordChangeResult.FAILED -> R.string.error_failed
}

/** [onBack] is set for the voluntary change from the profile; the required change has no way back. */
@Composable
fun ChangePasswordRoute(onBack: (() -> Unit)? = null, viewModel: ChangePasswordViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChangePasswordScreen(
        state = state,
        onBack = onBack,
        onCurrentPasswordChanged = viewModel::onCurrentPasswordChanged,
        onNewPasswordChanged = viewModel::onNewPasswordChanged,
        onConfirmPasswordChanged = viewModel::onConfirmPasswordChanged,
        onSubmit = viewModel::onSubmit,
        onLogout = viewModel::onLogout,
    )
}

@Composable
fun ChangePasswordScreen(
    state: ChangePasswordUiState,
    onCurrentPasswordChanged: (String) -> Unit,
    onNewPasswordChanged: (String) -> Unit,
    onConfirmPasswordChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onLogout: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val voluntary = onBack != null
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.password_change_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
        bottomBar = {
            ActionBar {
                state.error?.let {
                    Text(
                        text = stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("password_error"),
                    )
                }
                PrimaryActionButton(
                    text = stringResource(R.string.password_change_submit),
                    onClick = onSubmit,
                    icon = Icons.Filled.LockReset,
                    loading = state.submitting,
                    modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                )
                if (!voluntary) {
                    SecondaryActionButton(
                        text = stringResource(R.string.profile_logout),
                        onClick = onLogout,
                        icon = Icons.AutoMirrored.Filled.Logout,
                        enabled = !state.submitting,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
        ) {
            StatusBanner(
                message = stringResource(if (voluntary) R.string.password_change_voluntary_body else R.string.password_change_required_body),
                icon = Icons.Filled.Lock,
                tone = if (voluntary) StatusTone.Info else StatusTone.Warning,
            )
            PasswordField(state.currentPassword, onCurrentPasswordChanged, R.string.password_current, !state.submitting, "password_current")
            PasswordField(state.newPassword, onNewPasswordChanged, R.string.password_new, !state.submitting, "password_new")
            PasswordField(state.confirmPassword, onConfirmPasswordChanged, R.string.password_confirm, !state.submitting, "password_confirm")
            Text(
                text = stringResource(R.string.password_rules),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    enabled: Boolean,
    tag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}
