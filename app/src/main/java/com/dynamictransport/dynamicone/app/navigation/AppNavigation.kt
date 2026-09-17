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
import com.dynamictransport.dynamicone.domain.model.SessionState
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
import com.dynamictransport.dynamicone.feature.profile.ProfileRoute
import com.dynamictransport.dynamicone.feature.shift.ShiftRoute
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

@Serializable
data object ShiftDestination

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

/** Bottom navigation: 5 destinations at most (CLAUDE.md §7). */
enum class TopLevelDestination(
    val destination: Any,
    @param:StringRes val label: Int,
    val icon: ImageVector,
) {
    HOME(HomeDestination, R.string.nav_home, Icons.Filled.Home),
    TRIPS(TripsDestination, R.string.nav_trips, Icons.Filled.LocalShipping),
    DOCUMENTS(DocumentsDestination, R.string.nav_documents, Icons.Filled.Description),
    MESSAGES(MessagesDestination, R.string.nav_messages, Icons.Filled.Forum),
    PROFILE(ProfileDestination, R.string.nav_profile, Icons.Filled.Person),
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

        // The backend blocks every driver call until the password is changed.
        SessionState.PasswordChangeRequired -> ChangePasswordRoute()

        is SessionState.LoggedIn -> key(current.driverId) { MainNavigation() }
    }
}

@Composable
private fun MainNavigation(
    badgeViewModel: MessageBadgeViewModel = hiltViewModel(),
    routingViewModel: NotificationRoutingViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTopLevel = backStackEntry?.destination.topLevelDestination()
    val unreadMessages by badgeViewModel.unreadCount.collectAsStateWithLifecycle()
    val openRequest by routingViewModel.openRequest.collectAsStateWithLifecycle()

    // A tapped message notification opens its conversation (also when it was tapped before signing in).
    LaunchedEffect(openRequest) {
        val request = openRequest ?: return@LaunchedEffect
        val tripId = request.tripId
        if (tripId == null) navController.navigate(GeneralMessagesDestination) else navController.navigate(TripMessagesDestination(tripId))
        routingViewModel.onOpenRequestHandled()
    }

    // Android 13+: new messages can only be announced once the driver allows notifications.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (routingViewModel.shouldAskForNotificationPermission()) {
            routingViewModel.onNotificationPermissionAsked()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        bottomBar = {
            if (currentTopLevel != null) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = destination == currentTopLevel,
                            onClick = { navController.navigateToTopLevel(destination) },
                            icon = {
                                if (destination == TopLevelDestination.MESSAGES && unreadMessages > 0) {
                                    UnreadIcon(destination.icon, unreadMessages)
                                } else {
                                    Icon(destination.icon, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(destination.label), maxLines = 1) },
                            modifier = Modifier.testTag("nav_${destination.name}"),
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HomeDestination,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable<HomeDestination> {
                HomeRoute(
                    onOpenTrip = { navController.navigate(TripDetailDestination(it)) },
                    onOpenTrips = { navController.navigateToTopLevel(TopLevelDestination.TRIPS) },
                    onOpenDocuments = { navController.navigateToTopLevel(TopLevelDestination.DOCUMENTS) },
                    onOpenEarnings = { navController.navigate(PayrollDestination) },
                    onReportIncident = { navController.navigate(ReportIncidentDestination(it)) },
                    onOpenShift = { navController.navigate(ShiftDestination) },
                )
            }
            composable<TripsDestination> {
                TripsRoute(onOpenTrip = { navController.navigate(TripDetailDestination(it)) })
            }
            composable<DocumentsDestination> { entry ->
                DocumentsEntry(entry = entry, navController = navController, onBack = null)
            }
            composable<MessagesDestination> {
                ConversationsRoute(
                    onOpenConversation = { tripId ->
                        if (tripId == null) navController.navigate(GeneralMessagesDestination) else navController.navigate(TripMessagesDestination(tripId))
                    },
                )
            }
            composable<ProfileDestination> {
                ProfileRoute(
                    onOpenPayroll = { navController.navigate(PayrollDestination) },
                    onOpenSignedDocuments = { navController.navigate(SignedDocumentsDestination) },
                    onOpenChangePassword = { navController.navigate(ChangePasswordDestination) },
                )
            }
            composable<ShiftDestination> {
                ShiftRoute(onBack = { navController.popBackStack() })
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

private fun NavDestination?.topLevelDestination(): TopLevelDestination? {
    val hierarchy = this?.hierarchy ?: return null
    return when {
        hierarchy.any { it.hasRoute<HomeDestination>() } -> TopLevelDestination.HOME
        hierarchy.any { it.hasRoute<TripsDestination>() } -> TopLevelDestination.TRIPS
        hierarchy.any { it.hasRoute<DocumentsDestination>() } -> TopLevelDestination.DOCUMENTS
        hierarchy.any { it.hasRoute<MessagesDestination>() } -> TopLevelDestination.MESSAGES
        hierarchy.any { it.hasRoute<ProfileDestination>() } -> TopLevelDestination.PROFILE
        else -> null
    }
}

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.destination) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
