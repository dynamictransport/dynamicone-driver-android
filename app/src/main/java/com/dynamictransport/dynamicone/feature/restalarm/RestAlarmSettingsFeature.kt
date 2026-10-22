package com.dynamictransport.dynamicone.feature.restalarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.alarm.RestAlarmController
import com.dynamictransport.dynamicone.core.alarm.RestAlarmPermissionState
import com.dynamictransport.dynamicone.core.alarm.RestAlarmSettingsScreens
import com.dynamictransport.dynamicone.core.design.component.ChoiceRow
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.component.toneContainer
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.repository.RestAlarmSettingsRepository
import com.dynamictransport.dynamicone.domain.rules.RestAlarmMode
import com.dynamictransport.dynamicone.domain.rules.RestAlarmSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject

data class RestAlarmSettingsUiState(
    val mode: RestAlarmMode = RestAlarmMode.ALARM,
    val lead: Duration = Duration.ZERO,
    val permissions: RestAlarmPermissionState = RestAlarmPermissionState(),
)

@HiltViewModel
class RestAlarmSettingsViewModel @Inject constructor(
    private val controller: RestAlarmController,
    settings: RestAlarmSettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<RestAlarmSettingsUiState> =
        combine(settings.settings, controller.permissionState) { current, permissions ->
            RestAlarmSettingsUiState(current.mode, current.lead, permissions)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RestAlarmSettingsUiState())

    fun onModeSelected(mode: RestAlarmMode) {
        viewModelScope.launch { controller.setMode(mode) }
    }

    fun onLeadSelected(lead: Duration) {
        viewModelScope.launch { controller.setLead(lead) }
    }

    fun onTest() {
        viewModelScope.launch { controller.ringTest() }
    }

    /** Back from a system settings screen: read what is allowed now. */
    fun onResume() = controller.refreshPermissions()
}

@Composable
fun RestAlarmSettingsRoute(onBack: () -> Unit, viewModel: RestAlarmSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    RestAlarmSettingsScreen(
        state = state,
        onBack = onBack,
        onModeSelected = viewModel::onModeSelected,
        onLeadSelected = viewModel::onLeadSelected,
        onTest = viewModel::onTest,
        onOpenNotifications = { RestAlarmSettingsScreens.openNotifications(context) },
        onOpenExactAlarms = { RestAlarmSettingsScreens.openExactAlarms(context) },
        onOpenFullScreen = { RestAlarmSettingsScreens.openFullScreen(context) },
    )
}

@Composable
fun RestAlarmSettingsScreen(
    state: RestAlarmSettingsUiState,
    onBack: () -> Unit,
    onModeSelected: (RestAlarmMode) -> Unit,
    onLeadSelected: (Duration) -> Unit,
    onTest: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenExactAlarms: () -> Unit,
    onOpenFullScreen: () -> Unit,
) {
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.rest_alarm_settings_title),
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
                .readableWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.rest_alarm_settings_intro), style = MaterialTheme.typography.bodyLarge)
            SectionCard(modifier = Modifier.testTag("rest_alarm_mode")) {
                Text(stringResource(R.string.rest_alarm_settings_mode), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceRow(
                        label = stringResource(R.string.rest_alarm_mode_alarm),
                        selected = state.mode == RestAlarmMode.ALARM,
                        onSelect = { onModeSelected(RestAlarmMode.ALARM) },
                        icon = Icons.Filled.Alarm,
                        modifier = Modifier.testTag("rest_alarm_mode_alarm"),
                    )
                    ChoiceRow(
                        label = stringResource(R.string.rest_alarm_mode_notification),
                        selected = state.mode == RestAlarmMode.NOTIFICATION,
                        onSelect = { onModeSelected(RestAlarmMode.NOTIFICATION) },
                        icon = Icons.Filled.Notifications,
                        modifier = Modifier.testTag("rest_alarm_mode_notification"),
                    )
                }
            }
            SectionCard(modifier = Modifier.testTag("rest_alarm_lead")) {
                Text(stringResource(R.string.rest_alarm_settings_when), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RestAlarmSettings.LEAD_OPTIONS.forEach { lead ->
                        ChoiceRow(
                            label = if (lead.isZero) {
                                stringResource(R.string.rest_alarm_lead_at_end)
                            } else {
                                stringResource(R.string.rest_alarm_lead_before, lead.toMinutes().toInt())
                            },
                            selected = state.lead == lead,
                            onSelect = { onLeadSelected(lead) },
                            modifier = Modifier.testTag("rest_alarm_lead_${lead.toMinutes()}"),
                        )
                    }
                }
            }
            SectionCard(modifier = Modifier.testTag("rest_alarm_permissions")) {
                Text(stringResource(R.string.rest_alarm_settings_permissions), style = MaterialTheme.typography.titleMedium)
                PermissionRow(R.string.rest_alarm_permission_notifications, state.permissions.notifications, onOpenNotifications, "notifications")
                PermissionRow(R.string.rest_alarm_permission_exact, state.permissions.exactAlarms, onOpenExactAlarms, "exact")
                if (state.mode == RestAlarmMode.ALARM) {
                    PermissionRow(R.string.rest_alarm_permission_full_screen, state.permissions.fullScreen, onOpenFullScreen, "full_screen")
                }
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.rest_alarm_settings_dnd), style = MaterialTheme.typography.bodySmall, color = DriverTheme.colors.muted)
            }
            SecondaryActionButton(
                text = stringResource(R.string.rest_alarm_try),
                onClick = onTest,
                icon = Icons.Filled.Alarm,
                modifier = Modifier.testTag("rest_alarm_try"),
            )
        }
    }
}

@Composable
private fun PermissionRow(label: Int, granted: Boolean, onOpen: () -> Unit, tag: String) {
    val colors = toneContainer(if (granted) StatusTone.Positive else StatusTone.Warning)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("rest_alarm_permission_$tag"),
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = colors.content,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(if (granted) R.string.rest_alarm_permission_on else R.string.rest_alarm_permission_off),
                style = MaterialTheme.typography.bodySmall,
                color = DriverTheme.colors.muted,
            )
        }
        if (!granted) {
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.rest_alarm_permission_open), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
