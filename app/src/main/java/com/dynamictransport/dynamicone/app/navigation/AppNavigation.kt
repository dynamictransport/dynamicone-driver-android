package com.dynamictransport.dynamicone.app.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.app.AppViewModel
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.feature.attendance.AttendanceRecordsRoute
import com.dynamictransport.dynamicone.feature.attendance.AttendanceRoute
import com.dynamictransport.dynamicone.feature.auth.ChangePasswordRoute
import com.dynamictransport.dynamicone.feature.auth.LoginRoute
import com.dynamictransport.dynamicone.feature.documents.DocumentsRoute
import com.dynamictransport.dynamicone.feature.documents.scan.DocumentScanRoute
import com.dynamictransport.dynamicone.feature.documents.scan.SCAN_RESULT_KEY
import com.dynamictransport.dynamicone.feature.home.HomeRoute
import com.dynamictransport.dynamicone.feature.incident.ReportIncidentRoute
import com.dynamictransport.dynamicone.feature.messages.ConversationsRoute
import com.dynamictransport.dynamicone.feature.messages.MessageBadgeViewModel
import com.dynamictransport.dynamicone.feature.messages.MessagesRoute
import com.dynamictransport.dynamicone.feature.payroll.PayrollRoute
import com.dynamictransport.dynamicone.feature.personnel.SignedDocumentsRoute
import com.dynamictransport.dynamicone.feature.profile.ProfileLink
import com.dynamictransport.dynamicone.feature.profile.ProfileRoute
import com.dynamictransport.dynamicone.feature.restalarm.RestAlarmSettingsRoute
import com.dynamictransport.dynamicone.feature.shift.ShiftRoute
import com.dynamictransport.dynamicone.feature.shiftstart.StartShiftRoute
import com.dynamictransport.dynamicone.feature.tripdetail.RejectTripRoute
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailRoute
import com.dynamictransport.dynamicone.feature.trips.TripsRoute
import kotlinx.serialization.Serializable

// Centralised, typed navigation destinations. Argument names match TRIP_ID_ARG ("tripId").

@Serializable
data object HomeDestination

@Serializable
data object TripsDestination

@Serializable
data object DocumentsDestination

/** Conversation list (bottom navigation). */
@Serializable
data object MessagesDestination

@Serializable
data object ProfileDestination

/** Office attendance: state and the NFC check-in / check-out. */
@Serializable
data object AttendanceDestination

/** The account's own attendance records by month. */
@Serializable
data object AttendanceRecordsDestination

@Serializable
data object ShiftDestination

/** Daily shift start at the vehicle: tag and daily check (docs/16). */
@Serializable
data object StartShiftDestination

/** The wake-up alarm's settings. */
@Serializable
data object RestAlarmSettingsDestination

@Serializable
data object PayrollDestination

@Serializable
data object SignedDocumentsDestination

/** Voluntary password change from the profile. */
@Serializable
data object ChangePasswordDestination

@Serializable
data class TripDetailDestination(val tripId: String)

@Serializable
data class TripDocumentsDestination(val tripId: String)

/** General conversation with the office. */
@Serializable
data object GeneralMessagesDestination

@Serializable
data class TripMessagesDestination(val tripId: String)

/** In-app document scanner. Argument names match DOCUMENT_TYPE_ARG and SOURCE_PATH_ARG. */
@Serializable
data class DocumentScanDestination(val documentType: String, val sourcePath: String? = null)

@Serializable
data class RejectTripDestination(val tripId: String)

@Serializable
data class ReportIncidentDestination(val tripId: String? = null)

private val AppSection.destination: Any
    get() = when (this) {
        AppSection.HOME -> HomeDestination
        AppSection.TRIPS -> TripsDestination
        AppSection.DOCUMENTS -> DocumentsDestination
        AppSection.MESSAGES -> MessagesDestination
        AppSection.ATTENDANCE -> AttendanceDestination
        AppSection.ATTENDANCE_RECORDS -> AttendanceRecordsDestination
        AppSection.PROFILE -> ProfileDestination
    }

@get:StringRes
private val AppSection.label: Int
    get() = when (this) {
        AppSection.HOME -> R.string.nav_home
        AppSection.TRIPS -> R.string.nav_trips
        AppSection.DOCUMENTS -> R.string.nav_documents
        AppSection.MESSAGES -> R.string.nav_messages
        AppSection.ATTENDANCE -> R.string.nav_attendance
        AppSection.ATTENDANCE_RECORDS -> R.string.nav_attendance_records
        AppSection.PROFILE -> R.string.nav_profile
    }

private val AppSection.icon: ImageVector
    get() = when (this) {
        AppSection.HOME -> Icons.Filled.Home
        AppSection.TRIPS -> Icons.Filled.LocalShipping
        AppSection.DOCUMENTS -> Icons.Filled.Description
        AppSection.MESSAGES -> Icons.Filled.Forum
        AppSection.ATTENDANCE -> Icons.Filled.Badge
        AppSection.ATTENDANCE_RECORDS -> Icons.AutoMirrored.Filled.EventNote
        AppSection.PROFILE -> Icons.Filled.Person
    }

@Composable
fun DynamicOneApp(appViewModel: AppViewModel) {
    val session by appViewModel.sessionState.collectAsStateWithLifecycle()
    when (val current = session) {
        // The system splash stays on screen while the session is resolved.
        SessionState.Loading -> Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )

        SessionState.LoggedOut -> LoginRoute()

        // The backend blocks every call until the password is changed.
        SessionState.PasswordChangeRequired -> ChangePasswordRoute()

        // A change of the account's modules rebuilds the navigation.
        is SessionState.LoggedIn -> key(current.userId, current.modules) { MainNavigation(current.modules) }
    }
}

@Composable
private fun MainNavigation(modules: Set<AppModule>) {
    val layout = remember(modules) { AppShell.layout(modules) }
    val driver = AppModule.DRIVER in modules
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentSection = backStackEntry?.destination.section()?.takeIf { it in layout.bottomBar }
    val unreadMessages = if (driver) rememberUnreadMessages() else 0

    if (driver) DriverNotificationEffects(navController)

    Scaffold(
        bottomBar = {
            if (currentSection != null) {
                NavigationBar {
                    layout.bottomBar.forEach { section ->
                        NavigationBarItem(
                            selected = section == currentSection,
                            onClick = { navController.navigateToTopLevel(section) },
                            icon = {
                                if (section == AppSection.MESSAGES && unreadMessages > 0) {
                                    UnreadIcon(section.icon, unreadMessages)
                                } else {
                                    Icon(section.icon, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(section.label), maxLines = 1) },
                            modifier = Modifier.testTag("nav_${section.name}"),
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = layout.start.destination,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            // An area outside the bar opens like a detail screen, with a back button.
            val openSection: (AppSection) -> Unit = { section ->
                if (section in layout.bottomBar) navController.navigateToTopLevel(section) else navController.navigate(section.destination)
            }
            val backUnlessInBar: (AppSection) -> (() -> Unit)? = { section ->
                if (section in layout.bottomBar) null else ({ navController.popBackStack() })
            }

            composable<HomeDestination> {
                HomeRoute(
                    onOpenTrip = { navController.navigate(TripDetailDestination(it)) },
                    onOpenTrips = { openSection(AppSection.TRIPS) },
                    onOpenDocuments = { openSection(AppSection.DOCUMENTS) },
                    onOpenEarnings = { navController.navigate(PayrollDestination) },
                    onReportIncident = { navController.navigate(ReportIncidentDestination(it)) },
                    onOpenShift = { navController.navigate(ShiftDestination) },
                    onStartShift = { navController.navigate(StartShiftDestination) { launchSingleTop = true } },
                    onOpenRestAlarmSettings = { navController.navigate(RestAlarmSettingsDestination) },
                )
            }
            composable<TripsDestination> {
                TripsRoute(onOpenTrip = { navController.navigate(TripDetailDestination(it)) })
            }
            composable<DocumentsDestination> { entry ->
                DocumentsEntry(entry = entry, navController = navController, onBack = backUnlessInBar(AppSection.DOCUMENTS))
            }
            composable<MessagesDestination> {
                ConversationsRoute(
                    onOpenConversation = { tripId ->
                        if (tripId == null) navController.navigate(GeneralMessagesDestination) else navController.navigate(TripMessagesDestination(tripId))
                    },
                )
            }
            composable<AttendanceDestination> {
                AttendanceRoute(onOpenRecords = { openSection(AppSection.ATTENDANCE_RECORDS) })
            }
            composable<AttendanceRecordsDestination> {
                AttendanceRecordsRoute(onBack = backUnlessInBar(AppSection.ATTENDANCE_RECORDS))
            }
            composable<ProfileDestination> {
                ProfileRoute(
                    links = layout.overflow.map { ProfileLink(it.name, it.label, it.icon) },
                    onOpenLink = { key -> openSection(AppSection.valueOf(key)) },
                    onOpenPayroll = { navController.navigate(PayrollDestination) },
                    onOpenSignedDocuments = { navController.navigate(SignedDocumentsDestination) },
                    onOpenChangePassword = { navController.navigate(ChangePasswordDestination) },
                    onOpenRestAlarm = { navController.navigate(RestAlarmSettingsDestination) },
                )
            }
            composable<ShiftDestination> {
                ShiftRoute(
                    onBack = { navController.popBackStack() },
                    onStartDailyShift = { navController.navigate(StartShiftDestination) { launchSingleTop = true } },
                    onOpenRestAlarmSettings = { navController.navigate(RestAlarmSettingsDestination) },
                )
            }
            composable<StartShiftDestination> {
                StartShiftRoute(onClose = { navController.popBackStack() })
            }
            composable<RestAlarmSettingsDestination> {
                RestAlarmSettingsRoute(onBack = { navController.popBackStack() })
            }
            composable<PayrollDestination> {
                PayrollRoute(onBack = { navController.popBackStack() })
            }
            composable<SignedDocumentsDestination> {
                SignedDocumentsRoute(onBack = { navController.popBackStack() })
            }
            composable<ChangePasswordDestination> {
                ChangePasswordRoute(onBack = { navController.popBackStack() })
            }
            composable<TripDetailDestination> {
                TripDetailRoute(
                    onBack = { navController.popBackStack() },
                    onOpenDocuments = { navController.navigate(TripDocumentsDestination(it)) },
                    onOpenMessages = { navController.navigate(TripMessagesDestination(it)) },
                    onReject = { navController.navigate(RejectTripDestination(it)) },
                    onReportIncident = { navController.navigate(ReportIncidentDestination(it)) },
                    onStartShift = { navController.navigate(StartShiftDestination) { launchSingleTop = true } },
                )
            }
            composable<TripDocumentsDestination> { entry ->
                DocumentsEntry(entry = entry, navController = navController, onBack = { navController.popBackStack() })
            }
            composable<GeneralMessagesDestination> {
                MessagesRoute(onBack = { navController.popBackStack() })
            }
            composable<TripMessagesDestination> {
                MessagesRoute(onBack = { navController.popBackStack() })
            }
            composable<DocumentScanDestination> {
                DocumentScanRoute(
                    onClose = { navController.popBackStack() },
                    onAccepted = { imagePath ->
                        navController.previousBackStackEntry?.savedStateHandle?.set(SCAN_RESULT_KEY, imagePath)
                        navController.popBackStack()
                    },
                )
            }
            composable<RejectTripDestination> {
                RejectTripRoute(
                    onBack = { navController.popBackStack() },
                    onRejected = { navController.popBackStack(HomeDestination, inclusive = false) },
                )
            }
            composable<ReportIncidentDestination> {
                ReportIncidentRoute(
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun rememberUnreadMessages(badgeViewModel: MessageBadgeViewModel = hiltViewModel()): Int {
    val unread by badgeViewModel.unreadCount.collectAsStateWithLifecycle()
    return unread
}

/** Driver module: message notifications open their conversation, and new messages need the notification permission. */
@Composable
private fun DriverNotificationEffects(
    navController: NavHostController,
    routingViewModel: NotificationRoutingViewModel = hiltViewModel(),
) {
    val openRequest by routingViewModel.openRequest.collectAsStateWithLifecycle()
    val startShiftRequest by routingViewModel.startShiftRequest.collectAsStateWithLifecycle()

    // A tapped message notification opens its conversation (also when it was tapped before signing in).
    LaunchedEffect(openRequest) {
        val request = openRequest ?: return@LaunchedEffect
        val tripId = request.tripId
        if (tripId == null) navController.navigate(GeneralMessagesDestination) else navController.navigate(TripMessagesDestination(tripId))
        routingViewModel.onOpenRequestHandled()
    }

    // The wake-up alarm opens the daily shift start.
    LaunchedEffect(startShiftRequest) {
        if (!startShiftRequest) return@LaunchedEffect
        navController.navigate(StartShiftDestination) { launchSingleTop = true }
        routingViewModel.onStartShiftRequestHandled()
    }

    // Android 13+: new messages can only be announced once the driver allows notifications.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (routingViewModel.shouldAskForNotificationPermission()) {
            routingViewModel.onNotificationPermissionAsked()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Navigation icon with the number of unread messages; read out as "N unread". */
@Composable
private fun UnreadIcon(icon: ImageVector, count: Int) {
    val description = pluralStringResource(R.plurals.messages_unread, count, count)
    BadgedBox(
        badge = {
            Badge(modifier = Modifier.testTag("nav_messages_unread")) {
                Text(if (count > 99) "99+" else count.toString())
            }
        },
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null)
    }
}

/** Documents screen plus the hand-over of photos accepted by the scanner (kept in the entry's saved state). */
@Composable
private fun DocumentsEntry(entry: NavBackStackEntry, navController: NavHostController, onBack: (() -> Unit)?) {
    val results = entry.savedStateHandle
    val acceptedScan by remember(results) { results.getStateFlow<String?>(SCAN_RESULT_KEY, null) }.collectAsStateWithLifecycle()
    DocumentsRoute(
        onBack = onBack,
        onScan = { request ->
            navController.navigate(DocumentScanDestination(request.documentType.name, request.sourcePath)) {
                launchSingleTop = true
            }
        },
        acceptedScan = acceptedScan,
        onAcceptedScanConsumed = { results.remove<String>(SCAN_RESULT_KEY) },
    )
}

private fun NavDestination?.section(): AppSection? {
    val hierarchy = this?.hierarchy ?: return null
    return when {
        hierarchy.any { it.hasRoute<HomeDestination>() } -> AppSection.HOME
        hierarchy.any { it.hasRoute<TripsDestination>() } -> AppSection.TRIPS
        hierarchy.any { it.hasRoute<DocumentsDestination>() } -> AppSection.DOCUMENTS
        hierarchy.any { it.hasRoute<MessagesDestination>() } -> AppSection.MESSAGES
        hierarchy.any { it.hasRoute<AttendanceDestination>() } -> AppSection.ATTENDANCE
        hierarchy.any { it.hasRoute<AttendanceRecordsDestination>() } -> AppSection.ATTENDANCE_RECORDS
        hierarchy.any { it.hasRoute<ProfileDestination>() } -> AppSection.PROFILE
        else -> null
    }
}

private fun NavHostController.navigateToTopLevel(section: AppSection) {
    navigate(section.destination) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
