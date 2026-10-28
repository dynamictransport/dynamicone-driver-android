package com.dynamictransport.dynamicone.app.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
import androidx.navigation.toRoute
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.app.AppViewModel
import com.dynamictransport.dynamicone.core.design.layout.isWideScreen
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.CashPaymentScope
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.feature.attendance.AttendanceRecordsRoute
import com.dynamictransport.dynamicone.feature.attendance.AttendanceRoute
import com.dynamictransport.dynamicone.feature.auth.ChangePasswordRoute
import com.dynamictransport.dynamicone.feature.auth.LoginRoute
import com.dynamictransport.dynamicone.feature.cashpayments.CashPaymentDetailRoute
import com.dynamictransport.dynamicone.feature.cashpayments.CashPaymentsRoute
import com.dynamictransport.dynamicone.feature.documents.DocumentsRoute
import com.dynamictransport.dynamicone.feature.documents.scan.DocumentScanRoute
import com.dynamictransport.dynamicone.feature.documents.scan.SCAN_RESULT_KEY
import com.dynamictransport.dynamicone.feature.home.HomeRoute
import com.dynamictransport.dynamicone.feature.hrdocuments.HrDocumentDetailRoute
import com.dynamictransport.dynamicone.feature.hrdocuments.HrDocumentsRoute
import com.dynamictransport.dynamicone.feature.hrdocuments.PendingSignaturesBanner
import com.dynamictransport.dynamicone.feature.hrdocuments.PendingSignaturesViewModel
import com.dynamictransport.dynamicone.feature.incident.ReportIncidentRoute
import com.dynamictransport.dynamicone.feature.messages.ConversationsRoute
import com.dynamictransport.dynamicone.feature.messages.MessageBadgeViewModel
import com.dynamictransport.dynamicone.feature.messages.MessagesRoute
import com.dynamictransport.dynamicone.feature.payroll.PayrollRoute
import com.dynamictransport.dynamicone.feature.personnel.SignedDocumentsRoute
import com.dynamictransport.dynamicone.feature.personnelfiles.DocumentViewerRoute
import com.dynamictransport.dynamicone.feature.personnelfiles.FileToView
import com.dynamictransport.dynamicone.feature.personnelfiles.MyDocumentsRoute
import com.dynamictransport.dynamicone.feature.personnelfiles.VehicleDocumentsRoute
import com.dynamictransport.dynamicone.feature.personnelfiles.ViewedFileSource
import com.dynamictransport.dynamicone.feature.profile.ProfileLink
import com.dynamictransport.dynamicone.feature.profile.ProfileRoute
import com.dynamictransport.dynamicone.feature.restalarm.RestAlarmSettingsRoute
import com.dynamictransport.dynamicone.feature.shift.ShiftRoute
import com.dynamictransport.dynamicone.feature.shiftstart.StartShiftRoute
import com.dynamictransport.dynamicone.feature.trailer.ConfirmTrailerRoute
import com.dynamictransport.dynamicone.feature.tripdetail.RejectTripRoute
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailRoute
import com.dynamictransport.dynamicone.feature.trips.TripsRoute
import kotlinx.serialization.Serializable

const val NAV_BAR_TAG = "nav_bar"
const val NAV_RAIL_TAG = "nav_rail"

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

/** Personnel documents to sign and signed ones (docs/17). */
@Serializable
data object HrDocumentsDestination

/** One personnel document: reading and signing. Argument name matches HR_DOCUMENT_ID_ARG ("documentId"). */
@Serializable
data class HrDocumentDetailDestination(val documentId: String)

/** Files of the own personnel file: required documents (driver) and history (`hrDocuments`). */
@Serializable
data object MyDocumentsDestination

/** Files of the driver's assigned vehicle. */
@Serializable
data object VehicleDocumentsDestination

/** Cash approved by finance that the account hands over to personnel (docs/28). */
@Serializable
data object CashDisbursementsDestination

/** The account's own cash payments and receipts (docs/28). */
@Serializable
data object MyCashPaymentsDestination

/**
 * One cash payment: the approved form, the recipient's signature and the receipt. Argument names match
 * CASH_SCOPE_ARG ("scope", a [CashPaymentScope] wire name) and CASH_PAYMENT_ID_ARG ("paymentId").
 */
@Serializable
data class CashPaymentDetailDestination(val scope: String, val paymentId: String)

/**
 * One file in the in-app viewer (personnel, vehicle, signed document or signed proof). Argument names match
 * VIEWER_SOURCE_ARG, VIEWER_DOCUMENT_ID_ARG and VIEWER_CONTENT_TYPE_ARG; [source] is a [ViewedFileSource] name, [title]
 * the name shown in the bar.
 */
@Serializable
data class DocumentViewerDestination(
    val source: String,
    val documentId: String,
    val title: String,
    val contentType: String? = null,
)

/** Voluntary password change from the profile. */
@Serializable
data object ChangePasswordDestination

@Serializable
data class TripDetailDestination(val tripId: String)

/** [slot]: checklist key to select first, e.g. evidence that blocks the current step (docs/18). */
@Serializable
data class TripDocumentsDestination(val tripId: String, val slot: String? = null)

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

/** Trailer choice of an accepted trip before it starts (docs/20). */
@Serializable
data class ConfirmTrailerDestination(val tripId: String)

private val AppSection.destination: Any
    get() = when (this) {
        AppSection.HOME -> HomeDestination
        AppSection.TRIPS -> TripsDestination
        AppSection.DOCUMENTS -> DocumentsDestination
        AppSection.MESSAGES -> MessagesDestination
        AppSection.ATTENDANCE -> AttendanceDestination
        AppSection.ATTENDANCE_RECORDS -> AttendanceRecordsDestination
        AppSection.HR_DOCUMENTS -> HrDocumentsDestination
        AppSection.MY_DOCUMENTS -> MyDocumentsDestination
        AppSection.VEHICLE_DOCUMENTS -> VehicleDocumentsDestination
        AppSection.CASH_DISBURSEMENTS -> CashDisbursementsDestination
        AppSection.MY_CASH_PAYMENTS -> MyCashPaymentsDestination
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
        AppSection.HR_DOCUMENTS -> R.string.nav_hr_documents
        AppSection.MY_DOCUMENTS -> R.string.nav_my_documents
        AppSection.VEHICLE_DOCUMENTS -> R.string.vehicle_documents_title
        AppSection.CASH_DISBURSEMENTS -> R.string.nav_cash_disbursements
        AppSection.MY_CASH_PAYMENTS -> R.string.nav_my_cash_payments
        AppSection.PROFILE -> R.string.nav_profile
    }

/** The profile lists areas without a place in the bar by their full name ("Documents" alone would mean trip documents). */
@get:StringRes
private val AppSection.linkLabel: Int
    get() = when (this) {
        AppSection.HR_DOCUMENTS -> R.string.hr_documents_title
        AppSection.MY_DOCUMENTS -> R.string.my_documents_title
        AppSection.CASH_DISBURSEMENTS -> R.string.cash_disbursements_title
        AppSection.MY_CASH_PAYMENTS -> R.string.my_cash_payments_title
        else -> label
    }

private val AppSection.icon: ImageVector
    get() = when (this) {
        AppSection.HOME -> Icons.Filled.Home
        AppSection.TRIPS -> Icons.Filled.LocalShipping
        AppSection.DOCUMENTS -> Icons.Filled.Description
        AppSection.MESSAGES -> Icons.Filled.Forum
        AppSection.ATTENDANCE -> Icons.Filled.Badge
        AppSection.ATTENDANCE_RECORDS -> Icons.AutoMirrored.Filled.EventNote
        AppSection.HR_DOCUMENTS -> Icons.Filled.Draw
        AppSection.MY_DOCUMENTS -> Icons.Filled.AssignmentInd
        AppSection.VEHICLE_DOCUMENTS -> Icons.Filled.Topic
        AppSection.CASH_DISBURSEMENTS -> Icons.Filled.Payments
        AppSection.MY_CASH_PAYMENTS -> Icons.Filled.AccountBalanceWallet
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
    val hrDocuments = AppModule.HR_DOCUMENTS in modules
    val unreadMessages = if (driver) rememberUnreadMessages() else 0
    val documentsToSign = if (hrDocuments && AppSection.HR_DOCUMENTS in layout.bottomBar) rememberDocumentsToSign() else 0

    if (driver || hrDocuments) NotificationEffects(navController, driver = driver, hrDocuments = hrDocuments)

    // Phones: the bar at the bottom. Wide screens (a tablet in the truck): a rail on the left, which leaves the
    // short landscape height to the screens; its items sit at mid height, where the hand reaches a mounted tablet.
    val wide = isWideScreen()
    val sectionIcon: @Composable (AppSection) -> Unit = { section ->
        when {
            section == AppSection.MESSAGES && unreadMessages > 0 ->
                CountIcon(section.icon, unreadMessages, pluralStringResource(R.plurals.messages_unread, unreadMessages, unreadMessages), "nav_messages_unread")
            section == AppSection.HR_DOCUMENTS && documentsToSign > 0 ->
                CountIcon(section.icon, documentsToSign, pluralStringResource(R.plurals.hr_badge, documentsToSign, documentsToSign), "nav_hr_documents_count")
            else -> Icon(section.icon, contentDescription = null)
        }
    }

    Scaffold(
        bottomBar = {
            if (!wide && currentSection != null) {
                NavigationBar(modifier = Modifier.testTag(NAV_BAR_TAG)) {
                    layout.bottomBar.forEach { section ->
                        NavigationBarItem(
                            selected = section == currentSection,
                            onClick = { navController.navigateToTopLevel(section) },
                            icon = { sectionIcon(section) },
                            label = { NavLabel(stringResource(section.label)) },
                            modifier = Modifier.testTag("nav_${section.name}"),
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            if (wide && currentSection != null) {
                NavigationRail(modifier = Modifier.testTag(NAV_RAIL_TAG)) {
                    Spacer(Modifier.weight(1f))
                    layout.bottomBar.forEach { section ->
                        NavigationRailItem(
                            selected = section == currentSection,
                            onClick = { navController.navigateToTopLevel(section) },
                            icon = { sectionIcon(section) },
                            label = { NavLabel(stringResource(section.label)) },
                            modifier = Modifier.testTag("nav_${section.name}"),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            NavHost(
                navController = navController,
                startDestination = layout.start.destination,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                // An area outside the bar opens like a detail screen, with a back button.
                val openSection: (AppSection) -> Unit = { section ->
                    if (section in layout.bottomBar) navController.navigateToTopLevel(section) else navController.navigate(section.destination)
                }
                val backUnlessInBar: (AppSection) -> (() -> Unit)? = { section ->
                    if (section in layout.bottomBar) null else ({ navController.popBackStack() })
                }

                // The card of documents to sign on the first screen of the account (docs/17 §12).
                val openDocumentsToSign: (String?) -> Unit = { documentId ->
                    if (documentId != null) navController.navigate(HrDocumentDetailDestination(documentId)) else openSection(AppSection.HR_DOCUMENTS)
                }
                val documentsToSignBanner: (@Composable () -> Unit)? =
                    if (hrDocuments) ({ PendingSignaturesBanner(onOpen = openDocumentsToSign) }) else null

                composable<HomeDestination> {
                    HomeRoute(
                        documentsToSignBanner = documentsToSignBanner,
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
                    AttendanceRoute(
                        onOpenRecords = { openSection(AppSection.ATTENDANCE_RECORDS) },
                        documentsToSignBanner = documentsToSignBanner,
                    )
                }
                composable<AttendanceRecordsDestination> {
                    AttendanceRecordsRoute(onBack = backUnlessInBar(AppSection.ATTENDANCE_RECORDS))
                }
                composable<HrDocumentsDestination> {
                    HrDocumentsRoute(
                        onOpenDocument = { navController.navigate(HrDocumentDetailDestination(it)) { launchSingleTop = true } },
                        onBack = backUnlessInBar(AppSection.HR_DOCUMENTS),
                    )
                }
                val openFile: (ViewedFileSource) -> (FileToView) -> Unit = { source ->
                    { file ->
                        navController.navigate(DocumentViewerDestination(source.name, file.documentId, file.title, file.contentType)) {
                            launchSingleTop = true
                        }
                    }
                }
                composable<HrDocumentDetailDestination> {
                    HrDocumentDetailRoute(onBack = { navController.popBackStack() }, onOpenReceipt = openFile(ViewedFileSource.HR_RECEIPT))
                }
                composable<MyDocumentsDestination> {
                    MyDocumentsRoute(onBack = backUnlessInBar(AppSection.MY_DOCUMENTS), onOpenFile = openFile(ViewedFileSource.PERSONNEL))
                }
                composable<VehicleDocumentsDestination> {
                    VehicleDocumentsRoute(onBack = backUnlessInBar(AppSection.VEHICLE_DOCUMENTS), onOpenFile = openFile(ViewedFileSource.VEHICLE))
                }
                // Cash hand-over: the officer's queue and the own payments share the screens; the scope picks the endpoints.
                val openCashPayment: (CashPaymentScope) -> (String) -> Unit = { scope ->
                    { paymentId ->
                        navController.navigate(CashPaymentDetailDestination(scope.wireName, paymentId)) { launchSingleTop = true }
                    }
                }
                composable<CashDisbursementsDestination> {
                    CashPaymentsRoute(
                        scope = CashPaymentScope.DISBURSEMENTS,
                        onOpenPayment = openCashPayment(CashPaymentScope.DISBURSEMENTS),
                        onBack = backUnlessInBar(AppSection.CASH_DISBURSEMENTS),
                    )
                }
                composable<MyCashPaymentsDestination> {
                    CashPaymentsRoute(
                        scope = CashPaymentScope.OWN,
                        onOpenPayment = openCashPayment(CashPaymentScope.OWN),
                        onBack = backUnlessInBar(AppSection.MY_CASH_PAYMENTS),
                    )
                }
                composable<CashPaymentDetailDestination> { entry ->
                    val scope = CashPaymentScope.fromWire(entry.toRoute<CashPaymentDetailDestination>().scope) ?: CashPaymentScope.OWN
                    val source = if (scope == CashPaymentScope.DISBURSEMENTS) ViewedFileSource.CASH_RECEIPT else ViewedFileSource.MY_CASH_RECEIPT
                    CashPaymentDetailRoute(onBack = { navController.popBackStack() }, onOpenReceipt = openFile(source))
                }
                composable<DocumentViewerDestination> { entry ->
                    DocumentViewerRoute(title = entry.toRoute<DocumentViewerDestination>().title, onBack = { navController.popBackStack() })
                }
                composable<ProfileDestination> {
                    ProfileRoute(
                        links = layout.overflow.map { ProfileLink(it.name, it.linkLabel, it.icon) },
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
                    SignedDocumentsRoute(onBack = { navController.popBackStack() }, onOpenFile = openFile(ViewedFileSource.SIGNED))
                }
                composable<ChangePasswordDestination> {
                    ChangePasswordRoute(onBack = { navController.popBackStack() })
                }
                composable<TripDetailDestination> {
                    TripDetailRoute(
                        onBack = { navController.popBackStack() },
                        onOpenDocuments = { tripId, slot -> navController.navigate(TripDocumentsDestination(tripId, slot)) },
                        onOpenMessages = { navController.navigate(TripMessagesDestination(it)) },
                        onReject = { navController.navigate(RejectTripDestination(it)) },
                        onReportIncident = { navController.navigate(ReportIncidentDestination(it)) },
                        onStartShift = { navController.navigate(StartShiftDestination) { launchSingleTop = true } },
                        onConfirmTrailer = { navController.navigate(ConfirmTrailerDestination(it)) { launchSingleTop = true } },
                    )
                }
                composable<ConfirmTrailerDestination> {
                    ConfirmTrailerRoute(
                        onBack = { navController.popBackStack() },
                        // Back on the trip screen the confirmed trailer and the next step are shown.
                        onConfirmed = { navController.popBackStack() },
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
}

@Composable
private fun rememberUnreadMessages(badgeViewModel: MessageBadgeViewModel = hiltViewModel()): Int {
    val unread by badgeViewModel.unreadCount.collectAsStateWithLifecycle()
    return unread
}

@Composable
private fun rememberDocumentsToSign(viewModel: PendingSignaturesViewModel = hiltViewModel()): Int {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    return pending?.count ?: 0
}

/**
 * Notification taps of the account's modules: messages open their conversation and the wake-up alarm the shift start
 * (driver), a personnel document opens that document (docs/17). New messages and documents can only be announced once
 * notifications are allowed.
 */
@Composable
private fun NotificationEffects(
    navController: NavHostController,
    driver: Boolean,
    hrDocuments: Boolean,
    routingViewModel: NotificationRoutingViewModel = hiltViewModel(),
) {
    val openRequest by routingViewModel.openRequest.collectAsStateWithLifecycle()
    val startShiftRequest by routingViewModel.startShiftRequest.collectAsStateWithLifecycle()
    val documentRequest by routingViewModel.documentRequest.collectAsStateWithLifecycle()
    val tripRequest by routingViewModel.tripRequest.collectAsStateWithLifecycle()

    // A tapped TAR warning opens its trip (docs/26 §6).
    LaunchedEffect(tripRequest, driver) {
        val tripId = tripRequest ?: return@LaunchedEffect
        if (driver) navController.navigate(TripDetailDestination(tripId)) { launchSingleTop = true }
        routingViewModel.onTripRequestHandled()
    }

    // A tapped message notification opens its conversation (also when it was tapped before signing in).
    LaunchedEffect(openRequest, driver) {
        val request = openRequest ?: return@LaunchedEffect
        if (!driver) {
            routingViewModel.onOpenRequestHandled()
            return@LaunchedEffect
        }
        val tripId = request.tripId
        if (tripId == null) navController.navigate(GeneralMessagesDestination) else navController.navigate(TripMessagesDestination(tripId))
        routingViewModel.onOpenRequestHandled()
    }

    // The wake-up alarm opens the daily shift start.
    LaunchedEffect(startShiftRequest, driver) {
        if (!startShiftRequest) return@LaunchedEffect
        if (driver) navController.navigate(StartShiftDestination) { launchSingleTop = true }
        routingViewModel.onStartShiftRequestHandled()
    }

    // A tapped document notification opens that document.
    LaunchedEffect(documentRequest, hrDocuments) {
        val documentId = documentRequest ?: return@LaunchedEffect
        if (hrDocuments) navController.navigate(HrDocumentDetailDestination(documentId)) { launchSingleTop = true }
        routingViewModel.onDocumentRequestHandled()
    }

    // Android 13+: new messages and documents can only be announced once notifications are allowed.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (routingViewModel.shouldAskForNotificationPermission()) {
            routingViewModel.onNotificationPermissionAsked()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Navigation icon with a count (unread messages, documents to sign), read out with [description]. */
@Composable
private fun CountIcon(icon: ImageVector, count: Int, description: String, tag: String) {
    BadgedBox(
        badge = {
            Badge(modifier = Modifier.testTag(tag)) {
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
        hierarchy.any { it.hasRoute<HrDocumentsDestination>() } -> AppSection.HR_DOCUMENTS
        hierarchy.any { it.hasRoute<MyDocumentsDestination>() } -> AppSection.MY_DOCUMENTS
        hierarchy.any { it.hasRoute<VehicleDocumentsDestination>() } -> AppSection.VEHICLE_DOCUMENTS
        hierarchy.any { it.hasRoute<CashDisbursementsDestination>() } -> AppSection.CASH_DISBURSEMENTS
        hierarchy.any { it.hasRoute<MyCashPaymentsDestination>() } -> AppSection.MY_CASH_PAYMENTS
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

/**
 * One line that shrinks instead of being cut off when a long word (Russian, Polish) meets large system
 * text: "Документы" must never read "Документ". A label that does not fit even at the smallest size is
 * ellipsised in the middle of its slot instead of being cut off at the end, which reads as a shifted word.
 */
@Composable
private fun NavLabel(text: String) {
    val size = LocalTextStyle.current.fontSize
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = size, stepSize = 0.5.sp),
    )
}
