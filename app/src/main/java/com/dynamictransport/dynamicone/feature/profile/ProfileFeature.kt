package com.dynamictransport.dynamicone.feature.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.AssignedVehicleState
import com.dynamictransport.dynamicone.domain.model.FailedAction
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.domain.model.SyncOverview
import com.dynamictransport.dynamicone.domain.model.UserProfile
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.AuthRepository
import com.dynamictransport.dynamicone.domain.repository.DemoBackendControls
import com.dynamictransport.dynamicone.domain.repository.NetworkMonitor
import com.dynamictransport.dynamicone.domain.repository.SyncRepository
import com.dynamictransport.dynamicone.feature.common.AppLanguage
import com.dynamictransport.dynamicone.feature.common.AssignedVehicleRow
import com.dynamictransport.dynamicone.feature.common.ConnectivityBadge
import com.dynamictransport.dynamicone.feature.common.DemoBadge
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.LanguageOptions
import com.dynamictransport.dynamicone.feature.common.LocalImageThumbnail
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.labelRes
import com.dynamictransport.dynamicone.feature.common.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val profile: UserProfile? = null,
    /** The person's HR photo on the phone (docs/21); null shows the placeholder. */
    val photoPath: String? = null,
    val modules: Set<AppModule> = emptySet(),
    /** Shown for accounts with the driver module only. */
    val assignedVehicle: AssignedVehicleState = AssignedVehicleState.Loading,
    val overview: SyncOverview = SyncOverview.Empty,
    val isOnline: Boolean = true,
    val versionName: String = "",
    val demoControlsAvailable: Boolean = false,
    val simulateOffline: Boolean = false,
    val busy: Boolean = false,
    val showLogoutConfirmation: Boolean = false,
    val isDemoMode: Boolean = false,
    /** Driver daily shift: the wake-up alarm settings (docs/16). */
    val restAlarmAvailable: Boolean = false,
) {
    val unsentCount: Int get() = overview.pendingCount + overview.failedCount
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncRepository: SyncRepository,
    networkMonitor: NetworkMonitor,
    appInfo: AppInfo,
    private val demoControls: DemoBackendControls,
) : ViewModel() {

    private val showLogoutConfirmation = MutableStateFlow(false)
    private val singleFlight = SingleFlight()

    private val flags = combine(singleFlight.busy, showLogoutConfirmation, ::Pair)
    private val account = combine(
        authRepository.observeProfile(),
        authRepository.sessionState,
        authRepository.observeAssignedVehicle(),
        authRepository.observePhoto(),
        ::AccountParts,
    )

    val uiState: StateFlow<ProfileUiState> = combine(
        account,
        syncRepository.observeOverview(),
        networkMonitor.isOnline,
        demoControls.simulateOffline,
        flags,
    ) { (profile, session, vehicle, photo), overview, online, simulateOffline, (busy, confirmLogout) ->
        ProfileUiState(
            profile = profile,
            photoPath = photo,
            modules = (session as? SessionState.LoggedIn)?.modules.orEmpty(),
            assignedVehicle = vehicle,
            overview = overview,
            isOnline = online,
            versionName = appInfo.versionName,
            demoControlsAvailable = demoControls.isAvailable,
            simulateOffline = simulateOffline,
            busy = busy,
            showLogoutConfirmation = confirmLogout,
            isDemoMode = appInfo.isDemoMode,
            restAlarmAvailable = appInfo.isDriverShiftEnabled && AppModule.DRIVER in (session as? SessionState.LoggedIn)?.modules.orEmpty(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ProfileUiState(
            modules = (authRepository.sessionState.value as? SessionState.LoggedIn)?.modules.orEmpty(),
            versionName = appInfo.versionName,
            isDemoMode = appInfo.isDemoMode,
        ),
    )

    fun onRetryAll() {
        viewModelScope.launch { syncRepository.retryFailed() }
    }

    fun onAcknowledgeFailure(clientActionId: String) {
        viewModelScope.launch { syncRepository.acknowledgeFailure(clientActionId) }
    }

    /** Demo: unsent actions stay on the phone. Real API: unconfirmed commands are removed. The driver is told first. */
    fun onLogoutRequested() {
        if (uiState.value.unsentCount > 0) showLogoutConfirmation.value = true else logout()
    }

    fun onLogoutConfirmed() {
        showLogoutConfirmation.value = false
        logout()
    }

    fun onLogoutDismissed() {
        showLogoutConfirmation.value = false
    }

    fun onSimulateOfflineChanged(enabled: Boolean) = demoControls.setSimulateOffline(enabled)

    fun onResetDemoData() {
        singleFlight.launch(viewModelScope) { demoControls.resetDemoData() }
    }

    /** Back on the profile: the vehicle assignment (and name or modules) may have changed on the server. */
    fun onResume() = refreshProfile()

    fun onRetryAssignedVehicle() = refreshProfile()

    private fun refreshProfile() {
        viewModelScope.launch { authRepository.refreshProfile() }
    }

    private fun logout() {
        singleFlight.launch(viewModelScope) { authRepository.logout() }
    }
}

private data class AccountParts(
    val profile: UserProfile?,
    val session: SessionState,
    val vehicle: AssignedVehicleState,
    val photo: String?,
)

/** An area of the account's modules opened from the profile; [key] identifies it for the navigation. */
data class ProfileLink(
    val key: String,
    @param:StringRes val label: Int,
    val icon: ImageVector,
)

@Composable
fun ProfileRoute(
    links: List<ProfileLink>,
    onOpenLink: (String) -> Unit,
    onOpenPayroll: () -> Unit,
    onOpenSignedDocuments: () -> Unit,
    onOpenChangePassword: () -> Unit,
    onOpenRestAlarm: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    ProfileScreen(
        state = state,
        links = links,
        onOpenLink = onOpenLink,
        onOpenPayroll = onOpenPayroll,
        onOpenSignedDocuments = onOpenSignedDocuments,
        onOpenChangePassword = onOpenChangePassword,
        onOpenRestAlarm = onOpenRestAlarm,
        onRetryAll = viewModel::onRetryAll,
        onAcknowledgeFailure = viewModel::onAcknowledgeFailure,
        onLogoutRequested = viewModel::onLogoutRequested,
        onLogoutConfirmed = viewModel::onLogoutConfirmed,
        onLogoutDismissed = viewModel::onLogoutDismissed,
        onSimulateOfflineChanged = viewModel::onSimulateOfflineChanged,
        onResetDemoData = viewModel::onResetDemoData,
        onRetryAssignedVehicle = viewModel::onRetryAssignedVehicle,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onRetryAll: () -> Unit,
    onAcknowledgeFailure: (String) -> Unit,
    onLogoutRequested: () -> Unit,
    onLogoutConfirmed: () -> Unit,
    onLogoutDismissed: () -> Unit,
    onSimulateOfflineChanged: (Boolean) -> Unit,
    onResetDemoData: () -> Unit,
    onOpenPayroll: () -> Unit = {},
    onOpenSignedDocuments: () -> Unit = {},
    onOpenChangePassword: () -> Unit = {},
    links: List<ProfileLink> = emptyList(),
    onOpenLink: (String) -> Unit = {},
    onOpenRestAlarm: () -> Unit = {},
    onRetryAssignedVehicle: () -> Unit = {},
) {
    var language by remember { mutableStateOf(AppLanguage.current()) }

    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.nav_profile),
                actions = { ConnectivityBadge(state.isOnline, Modifier.padding(end = 12.dp)) },
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
            state.profile?.let { profile ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val photoPath = state.photoPath
                        if (photoPath != null) {
                            // Decorative: the name next to it says whose photo it is.
                            LocalImageThumbnail(
                                path = photoPath,
                                shape = CircleShape,
                                targetPx = PHOTO_DECODE_PX,
                                modifier = Modifier.size(56.dp).testTag(PROFILE_PHOTO_TAG),
                            )
                        } else {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(DriverTheme.colors.neutralContainer, CircleShape),
                            ) {
                                Icon(Icons.Filled.Person, contentDescription = null, tint = DriverTheme.colors.brand)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
                            profile.jobTitle?.let { title ->
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = DriverTheme.colors.muted,
                                    // Shown as HR wrote it: never translated or shortened, only cut after two lines.
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag(PROFILE_JOB_TITLE_TAG),
                                )
                            }
                            // Not part of the Driver API profile: shown only when the source provides it.
                            profile.personnelNo?.let { personnelNo ->
                                Text(
                                    text = stringResource(
                                        R.string.value_separator,
                                        stringResource(R.string.profile_personnel_no),
                                        personnelNo,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = DriverTheme.colors.muted,
                                )
                            }
                        }
                    }
                    // Only drivers have a fleet vehicle; office accounts never get one (docs/15).
                    if (AppModule.DRIVER in state.modules) {
                        Spacer(Modifier.size(8.dp))
                        AssignedVehicleRow(state = state.assignedVehicle, onRetry = onRetryAssignedVehicle)
                    }
                }
            }

            val driver = AppModule.DRIVER in state.modules
            SectionCard(modifier = Modifier.testTag("profile_account")) {
                Text(stringResource(R.string.profile_account), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Pay and signed documents are Driver API data.
                    if (driver) {
                        SecondaryActionButton(
                            text = stringResource(R.string.payroll_title),
                            onClick = onOpenPayroll,
                            icon = Icons.Filled.Payments,
                            modifier = Modifier.testTag("profile_payroll"),
                        )
                        SecondaryActionButton(
                            text = stringResource(R.string.signed_documents_title),
                            onClick = onOpenSignedDocuments,
                            icon = Icons.Filled.PictureAsPdf,
                            modifier = Modifier.testTag("profile_signed_documents"),
                        )
                        if (state.restAlarmAvailable) {
                            SecondaryActionButton(
                                text = stringResource(R.string.rest_alarm_settings_title),
                                onClick = onOpenRestAlarm,
                                icon = Icons.Filled.Alarm,
                                modifier = Modifier.testTag("profile_rest_alarm"),
                            )
                        }
                    }
                    SecondaryActionButton(
                        text = stringResource(R.string.profile_change_password),
                        onClick = onOpenChangePassword,
                        icon = Icons.Filled.LockReset,
                        modifier = Modifier.testTag("profile_change_password"),
                    )
                }
            }

            // Areas of the account's modules that have no place in the bottom bar.
            if (links.isNotEmpty()) {
                SectionCard(modifier = Modifier.testTag("profile_more")) {
                    Text(stringResource(R.string.profile_more), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        links.forEach { link ->
                            SecondaryActionButton(
                                text = stringResource(link.label),
                                onClick = { onOpenLink(link.key) },
                                icon = link.icon,
                                modifier = Modifier.testTag("profile_link_${link.key}"),
                            )
                        }
                    }
                }
            }

            // The sync centre lists driver actions; attendance punches show their state on the attendance screen.
            if (driver) SyncCenter(state.overview, onRetryAll, onAcknowledgeFailure)

            SectionCard {
                Text(stringResource(R.string.profile_language), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(8.dp))
                LanguageOptions(
                    selected = language,
                    onSelect = {
                        language = it
                        AppLanguage.apply(it)
                    },
                )
            }

            if (state.demoControlsAvailable) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.profile_demo_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        DemoBadge()
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .toggleable(
                                value = state.simulateOffline,
                                onValueChange = onSimulateOfflineChanged,
                                role = Role.Switch,
                            )
                            .testTag("demo_simulate_offline"),
                    ) {
                        Text(
                            text = stringResource(R.string.profile_demo_offline),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = state.simulateOffline, onCheckedChange = null)
                    }
                    SecondaryActionButton(
                        text = stringResource(R.string.profile_demo_reset),
                        onClick = onResetDemoData,
                        icon = Icons.Filled.RestartAlt,
                        enabled = !state.busy,
                    )
                }
            }

            SecondaryActionButton(
                text = stringResource(R.string.profile_logout),
                onClick = onLogoutRequested,
                icon = Icons.AutoMirrored.Filled.Logout,
                enabled = !state.busy,
                modifier = Modifier.testTag("logout"),
            )
            Text(
                text = stringResource(R.string.profile_version, state.versionName),
                style = MaterialTheme.typography.bodySmall,
                color = DriverTheme.colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (state.showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = onLogoutDismissed,
            title = { Text(stringResource(R.string.profile_logout_pending_title)) },
            text = {
                Text(
                    pluralStringResource(
                        if (state.isDemoMode) R.plurals.profile_logout_pending_body else R.plurals.profile_logout_pending_body_real,
                        state.unsentCount,
                        state.unsentCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onLogoutConfirmed) { Text(stringResource(R.string.profile_logout)) }
            },
            dismissButton = {
                TextButton(onClick = onLogoutDismissed) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SyncCenter(
    overview: SyncOverview,
    onRetryAll: () -> Unit,
    onAcknowledgeFailure: (String) -> Unit,
) {
    val locale = currentLocale()
    SectionCard {
        Text(stringResource(R.string.profile_sync_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = overview.lastSuccessfulSyncAt
                ?.let { stringResource(R.string.sync_last, DriverFormats.dayTime(it, locale)) }
                ?: stringResource(R.string.sync_never),
            style = MaterialTheme.typography.bodySmall,
            color = DriverTheme.colors.muted,
        )
        Spacer(Modifier.size(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (overview.pendingCount == 0 && overview.failures.isEmpty()) {
                StatusBanner(stringResource(R.string.profile_sync_all_sent), Icons.Filled.CloudDone, StatusTone.Positive)
            }
            if (overview.pendingCount > 0) {
                StatusBanner(
                    pluralStringResource(R.plurals.sync_pending_count, overview.pendingCount, overview.pendingCount),
                    Icons.Filled.CloudUpload,
                    StatusTone.Info,
                )
            }
            overview.failures.forEach { failure ->
                FailureRow(failure = failure, onAcknowledge = { onAcknowledgeFailure(failure.clientActionId) })
            }
            if (overview.failures.isNotEmpty()) {
                SecondaryActionButton(
                    text = stringResource(R.string.profile_retry_all),
                    onClick = onRetryAll,
                    icon = Icons.Filled.Refresh,
                )
            }
        }
    }
}

@Composable
private fun FailureRow(failure: FailedAction, onAcknowledge: () -> Unit) {
    val locale = currentLocale()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(Icons.Filled.SyncProblem, contentDescription = null, tint = DriverTheme.colors.danger)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(failure.labelRes()), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(failure.errorCode.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = DriverTheme.colors.muted,
            )
            Text(
                text = DriverFormats.dayTime(failure.createdAt, locale),
                style = MaterialTheme.typography.bodySmall,
                color = DriverTheme.colors.muted,
            )
        }
        TextButton(onClick = onAcknowledge, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.profile_dismiss))
        }
    }
}

const val PROFILE_PHOTO_TAG = "profile_photo"
const val PROFILE_JOB_TITLE_TAG = "profile_job_title"

/** 56dp on the densest screens. */
private const val PHOTO_DECODE_PX = 224
