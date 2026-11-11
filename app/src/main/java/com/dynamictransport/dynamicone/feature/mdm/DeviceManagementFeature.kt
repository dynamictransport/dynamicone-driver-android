package com.dynamictransport.dynamicone.feature.mdm

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.PrimaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.component.toneContainer
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.core.mdm.DevicePolicyController
import com.dynamictransport.dynamicone.core.remote.RemoteControlAccess
import com.dynamictransport.dynamicone.domain.model.CheckInResult
import com.dynamictransport.dynamicone.domain.model.EnrollResult
import com.dynamictransport.dynamicone.domain.model.EnrollmentCode
import com.dynamictransport.dynamicone.domain.repository.DeviceManagementRepository
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.currentLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import javax.inject.Inject

data class DeviceManagementUiState(
    val enrolled: Boolean = false,
    val deviceOwner: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val lastCheckInAt: Instant? = null,
    val code: String = "",
    val busy: Boolean = false,
    @param:StringRes val error: Int? = null,
) {
    /** The whole code is typed (docs/29 §3.1: 8 characters, the dash does not count). */
    val canEnrol: Boolean get() = !busy && EnrollmentCode.normalize(code) != null
}

/** Device management (docs/29): enrolment with the office's code and the state of this phone. No account is needed. */
@HiltViewModel
class DeviceManagementViewModel @Inject constructor(
    private val repository: DeviceManagementRepository,
    private val policy: DevicePolicyController,
    private val remoteControl: RemoteControlAccess,
) : ViewModel() {

    private data class Form(val code: String = "", @param:StringRes val error: Int? = null)

    private data class Facts(val deviceOwner: Boolean = false, val accessibilityEnabled: Boolean = false)

    private val form = MutableStateFlow(Form())
    private val facts = MutableStateFlow(readFacts())
    private val singleFlight = SingleFlight()

    val uiState: StateFlow<DeviceManagementUiState> = combine(
        repository.enrolled,
        repository.lastCheckInAt,
        form,
        facts,
        singleFlight.busy,
    ) { enrolled, lastCheckIn, form, facts, busy ->
        DeviceManagementUiState(
            enrolled = enrolled,
            deviceOwner = facts.deviceOwner,
            accessibilityEnabled = facts.accessibilityEnabled,
            lastCheckInAt = lastCheckIn,
            code = form.code,
            busy = busy,
            error = form.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceManagementUiState())

    fun onCodeChanged(value: String) = form.update { Form(code = value.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(MAX_CODE_INPUT)) }

    fun onEnrol() {
        val code = form.value.code
        if (EnrollmentCode.normalize(code) == null) {
            form.update { it.copy(error = R.string.mdm_error_code) }
            return
        }
        singleFlight.launch(viewModelScope) {
            form.update { it.copy(error = null) }
            val error = when (repository.enroll(code)) {
                EnrollResult.ENROLLED -> null
                EnrollResult.INVALID_CODE -> R.string.mdm_error_code
                EnrollResult.OFFLINE -> R.string.mdm_error_offline
                EnrollResult.FAILED -> R.string.mdm_error_failed
            }
            form.update { if (error == null) Form() else it.copy(error = error) }
            refresh()
        }
    }

    fun onCheckInNow() {
        singleFlight.launch(viewModelScope) {
            val error = when (repository.checkIn()) {
                CheckInResult.OFFLINE -> R.string.mdm_error_offline
                CheckInResult.FAILED -> R.string.mdm_error_failed
                else -> null
            }
            form.update { it.copy(error = error) }
            refresh()
        }
    }

    /**
     * The service is switched on by the app itself where the phone allows it (docs/29 §4.2).
     * @return false when the driver has to do it in the system settings.
     */
    fun onEnableAccessibility(): Boolean {
        val enabled = remoteControl.selfEnable()
        refresh()
        return enabled
    }

    /** Back from the system settings: read what is switched on now. */
    fun refresh() {
        facts.value = readFacts()
    }

    private fun readFacts() = Facts(deviceOwner = policy.isDeviceOwner, accessibilityEnabled = remoteControl.isEnabled)

    private companion object {
        const val MAX_CODE_INPUT = 12
    }
}

@Composable
fun DeviceManagementRoute(onBack: () -> Unit, viewModel: DeviceManagementViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    DeviceManagementScreen(
        state = state,
        onBack = onBack,
        onCodeChanged = viewModel::onCodeChanged,
        onEnrol = viewModel::onEnrol,
        onCheckInNow = viewModel::onCheckInNow,
        onEnableAccessibility = { if (!viewModel.onEnableAccessibility()) openAccessibilitySettings(context) },
    )
}

private fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // A phone without that settings screen: the office switches the service on over adb.
    }
}

@Composable
fun DeviceManagementScreen(
    state: DeviceManagementUiState,
    onBack: () -> Unit,
    onCodeChanged: (String) -> Unit,
    onEnrol: () -> Unit,
    onCheckInNow: () -> Unit,
    onEnableAccessibility: () -> Unit,
) {
    val locale = currentLocale()
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.mdm_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .readableWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.mdm_intro), style = MaterialTheme.typography.bodyLarge)
            SectionCard(modifier = Modifier.testTag("mdm_status")) {
                Text(stringResource(R.string.mdm_status_title), style = MaterialTheme.typography.titleMedium)
                StateRow(
                    label = stringResource(R.string.mdm_status_enrolled),
                    value = stringResource(if (state.enrolled) R.string.mdm_status_enrolled_yes else R.string.mdm_status_enrolled_no),
                    ok = state.enrolled,
                    tag = "mdm_enrolled",
                )
                StateRow(
                    label = stringResource(R.string.mdm_status_owner),
                    value = stringResource(if (state.deviceOwner) R.string.mdm_status_owner_yes else R.string.mdm_status_owner_no),
                    ok = state.deviceOwner,
                    tag = "mdm_owner",
                )
                StateRow(
                    label = stringResource(R.string.mdm_status_accessibility),
                    value = stringResource(if (state.accessibilityEnabled) R.string.mdm_status_on else R.string.mdm_status_off),
                    ok = state.accessibilityEnabled,
                    tag = "mdm_accessibility",
                )
                if (state.enrolled) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = state.lastCheckInAt
                            ?.let { stringResource(R.string.mdm_last_check_in, DriverFormats.dayTime(it, locale)) }
                            ?: stringResource(R.string.mdm_last_check_in_never),
                        style = MaterialTheme.typography.bodySmall,
                        color = DriverTheme.colors.muted,
                        modifier = Modifier.testTag("mdm_last_check_in"),
                    )
                }
            }
            state.error?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("mdm_error"),
                )
            }
            if (!state.enrolled) {
                SectionCard(modifier = Modifier.testTag("mdm_enrol")) {
                    Text(stringResource(R.string.mdm_enrol_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.mdm_enrol_hint), style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = onCodeChanged,
                        label = { Text(stringResource(R.string.mdm_enrol_code)) },
                        singleLine = true,
                        enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (state.canEnrol) onEnrol() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mdm_code"),
                    )
                    Spacer(Modifier.size(12.dp))
                    PrimaryActionButton(
                        text = stringResource(R.string.mdm_enrol_submit),
                        onClick = onEnrol,
                        icon = Icons.Filled.PhonelinkSetup,
                        enabled = state.canEnrol,
                        loading = state.busy,
                        modifier = Modifier.testTag(PRIMARY_ACTION_TAG),
                    )
                }
            } else {
                SecondaryActionButton(
                    text = stringResource(R.string.mdm_check_in_now),
                    onClick = onCheckInNow,
                    icon = Icons.Filled.Sync,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("mdm_check_in_now"),
                )
            }
            if (!state.accessibilityEnabled) {
                SectionCard(modifier = Modifier.testTag("mdm_accessibility_card")) {
                    Text(stringResource(R.string.mdm_accessibility_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.mdm_accessibility_hint), style = MaterialTheme.typography.bodyMedium, color = DriverTheme.colors.muted)
                    Spacer(Modifier.size(12.dp))
                    SecondaryActionButton(
                        text = stringResource(R.string.mdm_accessibility_open),
                        onClick = onEnableAccessibility,
                        icon = Icons.Filled.TouchApp,
                        modifier = Modifier.testTag("mdm_accessibility_open"),
                    )
                }
            }
        }
    }
}

/** State by icon and words, never by colour alone. */
@Composable
private fun StateRow(label: String, value: String, ok: Boolean, tag: String) {
    val colors = toneContainer(if (ok) StatusTone.Positive else StatusTone.Info)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(tag),
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Info,
            contentDescription = null,
            tint = colors.content,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
        }
    }
}
