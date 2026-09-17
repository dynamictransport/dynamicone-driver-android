package com.dynamictransport.dynamicone.feature.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.feature.common.LanguagePickerDialog
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG

@Composable
fun LoginRoute(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LoginScreen(
        state = state,
        onUsernameChanged = viewModel::onUsernameChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onLogin = viewModel::onLogin,
        onTwoFactorCodeChanged = viewModel::onTwoFactorCodeChanged,
    )
}

@Composable
fun LoginScreen(
    state: LoginUiState,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onLogin: () -> Unit,
    onTwoFactorCodeChanged: (String) -> Unit = {},
) {
    var showLanguagePicker by rememberSaveable { mutableStateOf(false) }
    var showSupport by rememberSaveable { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            // The brand mark on the screen background, at the size its per-density images are made for (96 dp wide).
            Image(
                painter = painterResource(R.drawable.dynamicone_logo),
                contentDescription = null,
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(LOGO_ASPECT_RATIO)
                    .testTag("app_logo"),
            )
            Column {
                Text(stringResource(R.string.login_welcome), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DriverTheme.colors.muted,
                )
            }
            if (state.sessionExpired) {
                StatusBanner(
                    message = stringResource(
                        if (state.isDemoMode) R.string.login_session_expired else R.string.login_session_expired_real,
                    ),
                    icon = Icons.Filled.LockClock,
                    tone = StatusTone.Warning,
                )
            }
            if (state.isDemoMode) {
                StatusBanner(stringResource(R.string.login_demo_hint), Icons.Filled.Info, StatusTone.Info)
            }
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChanged,
                label = { Text(stringResource(R.string.login_username)) },
                singleLine = true,
                enabled = !state.submitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("login_username"),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChanged,
                label = { Text(stringResource(R.string.login_password)) },
                singleLine = true,
                enabled = !state.submitting,
                visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (state.twoFactorRequired) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onLogin() }),
                trailingIcon = {
                    IconButton(onClick = onTogglePasswordVisibility, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (state.passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (state.passwordVisible) R.string.login_hide_password else R.string.login_show_password,
                            ),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("login_password"),
            )
            if (state.twoFactorRequired) {
                OutlinedTextField(
                    value = state.twoFactorCode,
                    onValueChange = onTwoFactorCodeChanged,
                    label = { Text(stringResource(R.string.login_two_factor_code)) },
                    singleLine = true,
                    enabled = !state.submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onLogin() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login_two_factor_code"),
                )
            }
            state.error?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("login_error"),
                )
            }
            PrimaryActionButton(
                text = stringResource(R.string.login_submit),
                onClick = onLogin,
                icon = Icons.AutoMirrored.Filled.Login,
                loading = state.submitting,
                modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
            )
            // On narrow phones (360 dp) both links do not fit side by side: the second one moves to the next line
            // instead of squeezing its label into one letter per line.
            FlowRow(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { showLanguagePicker = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("login_language"),
                ) {
                    Icon(Icons.Filled.Language, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.login_language), maxLines = 1)
                }
                TextButton(
                    onClick = { showSupport = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("login_support"),
                ) {
                    Icon(Icons.Filled.SupportAgent, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.login_support), maxLines = 1)
                }
            }
        }
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(onDismiss = { showLanguagePicker = false })
    }
    if (showSupport) {
        AlertDialog(
            onDismissRequest = { showSupport = false },
            title = { Text(stringResource(R.string.login_support)) },
            text = { Text(stringResource(R.string.login_support_body)) },
            confirmButton = {
                TextButton(onClick = { showSupport = false }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
}

/** Width to height of branding/dynamicone-mark.png. */
private const val LOGO_ASPECT_RATIO = 572f / 384f
