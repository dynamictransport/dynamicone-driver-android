package com.dynamictransport.dynamicone.domain.repository

import com.dynamictransport.dynamicone.domain.model.ActionReceipt
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.AssignedVehicleState
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.AttendanceMonth
import com.dynamictransport.dynamicone.domain.model.AttendanceStatus
import com.dynamictransport.dynamicone.domain.model.CashPayment
import com.dynamictransport.dynamicone.domain.model.CashPaymentList
import com.dynamictransport.dynamicone.domain.model.CashPaymentLookup
import com.dynamictransport.dynamicone.domain.model.CashPaymentScope
import com.dynamictransport.dynamicone.domain.model.CashPaymentState
import com.dynamictransport.dynamicone.domain.model.CashSignResult
import com.dynamictransport.dynamicone.domain.model.LocalCashSignature
import com.dynamictransport.dynamicone.domain.model.CheckTemplate
import com.dynamictransport.dynamicone.domain.model.Conversation
import com.dynamictransport.dynamicone.domain.model.ConversationList
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DocumentRequirements
import com.dynamictransport.dynamicone.domain.model.DocumentSlot
import com.dynamictransport.dynamicone.domain.model.DrawnSignature
import com.dynamictransport.dynamicone.domain.model.DriverTimes
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.HrContentResult
import com.dynamictransport.dynamicone.domain.model.HrDocumentLists
import com.dynamictransport.dynamicone.domain.model.HrDocumentLookup
import com.dynamictransport.dynamicone.domain.model.HrSignResult
import com.dynamictransport.dynamicone.domain.model.IncidentCategory
import com.dynamictransport.dynamicone.domain.model.LocalPunch
import com.dynamictransport.dynamicone.domain.model.LocalShiftCommand
import com.dynamictransport.dynamicone.domain.model.LoginResult
import com.dynamictransport.dynamicone.domain.model.MonthlyPayroll
import com.dynamictransport.dynamicone.domain.model.PasswordChangeResult
import com.dynamictransport.dynamicone.domain.model.PendingTripAction
import com.dynamictransport.dynamicone.domain.model.PersonnelDocumentHistory
import com.dynamictransport.dynamicone.domain.model.PunchResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.RejectReason
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.ShiftCommandResult
import com.dynamictransport.dynamicone.domain.model.ShiftStartDraft
import com.dynamictransport.dynamicone.domain.model.SignedDocument
import com.dynamictransport.dynamicone.domain.model.SnapshotCamera
import com.dynamictransport.dynamicone.domain.model.SnapshotDecision
import com.dynamictransport.dynamicone.domain.model.SnapshotFailure
import com.dynamictransport.dynamicone.domain.model.SnapshotOutcome
import com.dynamictransport.dynamicone.domain.model.SnapshotRequest
import com.dynamictransport.dynamicone.domain.model.SyncOverview
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionRecord
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripDocument
import com.dynamictransport.dynamicone.domain.model.TripExecution
import com.dynamictransport.dynamicone.domain.model.TripTrailerState
import com.dynamictransport.dynamicone.domain.model.UserProfile
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentsSnapshot
import com.dynamictransport.dynamicone.domain.model.VehicleInfo
import com.dynamictransport.dynamicone.domain.model.VehicleLookup
import com.dynamictransport.dynamicone.domain.model.VehiclePositionState
import com.dynamictransport.dynamicone.domain.model.WakeUpCall
import com.dynamictransport.dynamicone.domain.model.WakeUpDecision
import com.dynamictransport.dynamicone.domain.model.WakeUpOutcome
import com.dynamictransport.dynamicone.domain.rules.RestAlarmSettings
import com.dynamictransport.dynamicone.domain.scan.ScanMode
import com.dynamictransport.dynamicone.domain.scan.ScanOutcome
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

interface AuthRepository {
    val sessionState: StateFlow<SessionState>

    /** True when the last session ended because it expired (pending data is kept). */
    val sessionExpired: StateFlow<Boolean>

    suspend fun restoreSession()

    fun observeProfile(): Flow<UserProfile?>

    /** Path of the signed-in person's HR photo on the phone (docs/21); null when there is none. */
    fun observePhoto(): Flow<String?> = flowOf(null)

    /** The signed-in account's current fleet vehicle from `me`; trip and shift vehicles never stand in for it. */
    fun observeAssignedVehicle(): Flow<AssignedVehicleState>

    /** Reads `me` again (name, modules, assigned vehicle). A read that is already running is not repeated. */
    suspend fun refreshProfile()

    suspend fun login(username: String, password: String, twoFactorCode: String? = null): LoginResult

    /** Revokes the server session (when supported) and always clears the local session. */
    suspend fun logout()

    suspend fun changePassword(currentPassword: String, newPassword: String): PasswordChangeResult

    suspend fun lastUsername(): String?
}

/** Last position of the signed-in driver's assigned vehicle (docs/19). Held in memory only. */
interface VehiclePositionRepository {
    fun observePosition(): Flow<VehiclePositionState>

    /** A read that is already running is not repeated. */
    suspend fun refresh(): RefreshResult
}

interface ShiftRepository {
    fun observeShift(): Flow<Shift>

    suspend fun currentShift(): Shift

    /** Starts at once (demo backend). A daily shift is started with [DailyShiftRepository.startShift] instead. */
    suspend fun startShift(): ActionResult

    suspend fun endShift(): ActionResult

    /** Driver API daily shift (docs/16): starting needs the vehicle tag and the daily check. */
    val requiresVehicleCheck: Boolean get() = false
}

/** The driver's daily shift of the Driver API (docs/16_DRIVER_SHIFT_REST_AND_VEHICLE_CHECK.md §4, §7, §9). */
interface DailyShiftRepository {
    /** The vehicle of the driver's current or next assignment; null when there is none or it is unknown. */
    fun observePlannedVehicle(): Flow<VehicleInfo?>

    /** Starts and ends of the signed-in driver stored on the phone (waiting, recently confirmed or rejected), newest first. */
    fun observeCommands(): Flow<List<LocalShiftCommand>>

    suspend fun refresh(): RefreshResult

    /** Stores the start with its check and photos first, then sends it. The same command id is never stored twice. */
    suspend fun startShift(draft: ShiftStartDraft): ShiftCommandResult

    /** Sends waiting starts, ends and defect photos. False: something must be tried again later. */
    suspend fun sendPending(): Boolean

    /** Sends a rejected command again, unchanged. */
    suspend fun resend(commandId: String): ShiftCommandResult

    /** Removes a rejected command and its photos from the phone. */
    suspend fun discard(commandId: String)
}

/** Daily vehicle check list and the vehicle behind a tag (docs/16 §5, §6). */
interface VehicleCheckRepository {
    /** The server's list once read (also offline), the built-in default before. */
    fun observeTemplate(): Flow<CheckTemplate>

    suspend fun refreshTemplate(): RefreshResult

    suspend fun lookupVehicle(tagUid: String): VehicleLookup
}

/** The driver's tachograph summary (docs/16 §3). */
interface DriverTimesRepository {
    /** Null until read or loaded from the phone. */
    fun observeDriverTimes(): Flow<DriverTimes?>

    /** The latest known summary, also from the phone's storage (alarm after a restart). */
    suspend fun current(): DriverTimes?

    suspend fun refresh(): RefreshResult
}

/** App-private files for defect photos; never in the gallery. */
interface CheckPhotoStore {
    fun hasEnoughSpace(): Boolean

    /** New empty file the camera writes to; returns its path. */
    suspend fun createPhotoFile(): String

    suspend fun discard(filePath: String)
}

/** The wake-up alarm's settings and memory (docs/16 §1). */
interface RestAlarmSettingsRepository {
    val settings: Flow<RestAlarmSettings>

    suspend fun current(): RestAlarmSettings

    suspend fun update(transform: (RestAlarmSettings) -> RestAlarmSettings)
}

data class TripActionCommand(
    val tripId: String,
    val action: TripActionType,
    val rejectReason: RejectReason? = null,
    val note: String? = null,
)

interface TripRepository {
    fun observeTrips(): Flow<List<Trip>>

    fun observeTrip(tripId: String): Flow<Trip?>

    fun observeActionHistory(tripId: String): Flow<List<TripActionRecord>>

    /** Trips finished on this device since [since] (shift summary). */
    fun observeFinishedTripCount(since: Instant): Flow<Int>

    suspend fun currentTrips(): List<Trip>

    /** Accept and the local / legacy status workflow. Trip action workflow steps go through [TripExecutionRepository]. */
    suspend fun submitAction(command: TripActionCommand): ActionResult

    /** Reloads one trip (detail, stops, execution plan or legacy status history). */
    suspend fun refreshTrip(tripId: String): RefreshResult

    /** Loads the next page of trips, when the source is paged. */
    suspend fun loadMoreTrips(): RefreshResult

    fun observeCanLoadMore(): Flow<Boolean>
}

/** Server-driven trip action workflow (`execution` / `actions`). */
interface TripExecutionRepository {
    /** Null until read, and always null for sources without server execution plans (demo). */
    fun observeExecution(tripId: String): Flow<TripExecution?>

    /** The action stored on the device that has not been confirmed yet; at most one per trip. */
    fun observePendingAction(tripId: String): Flow<PendingTripAction?>

    /** Newest first, as recorded by the server. */
    fun observeReceipts(tripId: String): Flow<List<ActionReceipt>>

    suspend fun refreshReceipts(tripId: String): RefreshResult

    /**
     * Stores the command with a new command id, then sends it. Only options of the current plan are accepted.
     * Never a `CompleteStop` of an export loading stop (the planner closes it) and never a seal entry (see
     * [recordSealNumber]).
     */
    suspend fun submit(tripId: String, action: ExecutionAction, note: String?): ActionResult

    /**
     * docs/27: records the seal number of one container with the server's `RecordSealNumber` option, as its
     * own command. The seal photo is a separate upload; neither record stands in for the other.
     */
    suspend fun recordSealNumber(tripId: String, action: ExecutionAction, sealNumber: String): ActionResult

    /** Sends the stored command again with the same command id and payload. */
    suspend fun resendPending(tripId: String): ActionResult

    /** Drops the stored command and reads the current plan. */
    suspend fun discardPending(tripId: String): RefreshResult

    /** Trailer options and the confirmation of this trip; null until read (docs/20). */
    fun observeTrailers(tripId: String): Flow<TripTrailerState?>

    suspend fun refreshTrailers(tripId: String): RefreshResult

    /**
     * Confirms the trailer the driver picked from the list that was read. Needs a connection: the
     * choice carries the version of that read and is never queued with an outdated version.
     */
    suspend fun confirmTrailer(tripId: String, trailerId: String): ActionResult
}

interface DocumentRepository {
    /** Evidence slots of the trip as calculated by the backend; null until read. */
    fun observeSlots(tripId: String): Flow<List<DocumentSlot>?>

    /** Every uploaded file of the trip, newest first. */
    fun observeFiles(tripId: String): Flow<List<TripDocument>>

    suspend fun refreshDocuments(tripId: String): RefreshResult

    suspend fun upload(tripId: String, slot: DocumentSlot, localFilePath: String): ActionResult

    /** Makes the document content available as a local private file for viewing. */
    suspend fun openDocument(tripId: String, documentId: String, contentTypeHint: String?): DocumentContentResult
}

/** App-private storage for document photos. Media never goes into the database. */
interface DocumentMediaStore {
    fun hasEnoughSpace(): Boolean

    /** New empty file for a camera photo or a processed scan; returns its path. */
    suspend fun createCaptureFile(): String

    /** Copies picked media into private storage; returns the local path or null. */
    suspend fun importFromContentUri(uri: String): String?

    suspend fun discard(filePath: String)
}

/** Turns a camera or gallery photo into a checked, upright document image written to [outputPath]. */
interface DocumentScanner {
    suspend fun process(sourcePath: String, outputPath: String, mode: ScanMode): ScanOutcome
}

interface IncidentRepository {
    suspend fun report(tripId: String?, category: IncidentCategory, note: String?): ActionResult
}

interface PayrollRepository {
    /** Null until the month was read. */
    fun observePayroll(month: YearMonth): Flow<MonthlyPayroll?>

    suspend fun refresh(month: YearMonth): RefreshResult
}

interface SignedDocumentRepository {
    /** Null until read. */
    fun observeDocuments(): Flow<List<SignedDocument>?>

    fun observeCanLoadMore(): Flow<Boolean>

    suspend fun refresh(): RefreshResult

    suspend fun loadMore(): RefreshResult

    suspend fun open(documentId: String): DocumentContentResult
}

interface MessageRepository {
    /** The general conversation first, then one entry per trip with messages, grouped from the driver's inbox. */
    fun observeConversations(): Flow<ConversationList>

    /** Reads the newest inbox page (general and trip messages together) for the conversation list. */
    suspend fun refreshConversations(): RefreshResult

    /** [tripId] null is the general conversation; a trip id is that trip's conversation only. */
    fun observeConversation(tripId: String?): Flow<Conversation>

    /** Reads the newest page and merges it without duplicates. */
    suspend fun refresh(tripId: String?): RefreshResult

    suspend fun loadOlder(tripId: String?): RefreshResult

    suspend fun send(tripId: String?, body: String): ActionResult

    /** Sends a stored message again with the same command id. */
    suspend fun resend(commandId: String): ActionResult

    suspend fun discard(commandId: String)

    /** Read receipt for an incoming message the driver has seen. Returns true when it was sent. */
    suspend fun markRead(tripId: String?, messageId: String): Boolean
}

/** Office attendance with NFC tags (module `officeAttendance`). */
interface AttendanceRepository {
    /** Null until read. */
    fun observeStatus(): Flow<AttendanceStatus?>

    /** Scans of the signed-in account stored on the phone (waiting, recently confirmed or rejected), newest first. */
    fun observeLocalPunches(): Flow<List<LocalPunch>>

    suspend fun refreshStatus(): RefreshResult

    /** Stores the scan on the phone with a new command id first, then sends it. */
    suspend fun recordPunch(direction: AttendanceDirection, tagUid: String): PunchResult

    /** Sends every waiting punch of the signed-in account with its own command id. False: some must be tried again later. */
    suspend fun sendPending(): Boolean

    /** Sends a rejected punch again, unchanged. */
    suspend fun resend(commandId: String): PunchResult

    /** Removes a rejected punch from the phone. */
    suspend fun dismiss(commandId: String)

    /** Null until the month was read. */
    fun observeMonth(month: YearMonth): Flow<AttendanceMonth?>

    suspend fun refreshMonth(month: YearMonth): RefreshResult
}

/** Personnel documents to sign (docs/17_HR_DOCUMENT_SIGNING.md). */
interface HrDocumentRepository {
    /** Documents of the signed-in employee; the pending ones survive an app restart without network. */
    fun observeLists(): Flow<HrDocumentLists>

    fun observeDocument(documentId: String): Flow<HrDocumentLookup>

    /** Reads the documents to sign and the first page of signed ones. */
    suspend fun refresh(): RefreshResult

    suspend fun loadMoreSigned(): RefreshResult

    /** Reads one document, e.g. after a notification. */
    suspend fun refreshDocument(documentId: String): RefreshResult

    /** The PDF of one language version on the phone; downloaded and checked against its checksum when missing. */
    suspend fun content(documentId: String, languageCode: String): HrContentResult

    /** Stores the signature on the phone with a new command id first, then sends it. */
    suspend fun sign(documentId: String, languageCode: String, contentSha256: String, signature: DrawnSignature): HrSignResult

    /** Sends every waiting signature with its own command id. False: some must be tried again later. */
    suspend fun sendPending(): Boolean

    /** The signed proof PDF, downloaded for the in-app viewer. */
    suspend fun openReceipt(documentId: String): DocumentContentResult
}

/**
 * Files of the account's own personnel file: the history (module `hrDocuments`) and the driver's required documents
 * (module `driver`). Read only and held in memory; a file is downloaded only to be viewed.
 */
interface MyDocumentRepository {
    /** Null until read. */
    fun observeHistory(): Flow<PersonnelDocumentHistory?>

    /** Null until read. */
    fun observeRequirements(): Flow<DocumentRequirements?>

    /** Reads the first history page again. */
    suspend fun refreshHistory(): RefreshResult

    suspend fun loadMoreHistory(): RefreshResult

    suspend fun refreshRequirements(): RefreshResult

    /** The original file in a private temporary file for the in-app viewer. */
    suspend fun open(documentId: String, contentTypeHint: String?): DocumentContentResult
}

/** Files of the driver's currently assigned vehicle; the server decides the vehicle on every read. */
interface VehicleDocumentRepository {
    /** Null until read; a stale answer of another vehicle than `me` names is never emitted. */
    fun observe(): Flow<VehicleDocumentsSnapshot?>

    suspend fun refresh(): RefreshResult

    suspend fun open(documentId: String, contentTypeHint: String?): DocumentContentResult
}

/**
 * Cash approved by finance and handed over against the recipient's finger signature (docs/28_EMPLOYEE_CASH_PAYMENTS.md).
 * Lists and details live in memory only; a signature is stored on the phone with its command id until the server
 * confirmed it. The app never creates a payment, changes an amount or computes a balance.
 */
interface CashPaymentRepository {
    /** The list of one scope and state; null until read from the server. */
    fun observeList(scope: CashPaymentScope, state: CashPaymentState): Flow<CashPaymentList?>

    fun observePayment(scope: CashPaymentScope, paymentId: String): Flow<CashPaymentLookup>

    /** Signatures of one scope waiting on the phone or rejected, for the list's status lines. */
    fun observeLocalSignatures(scope: CashPaymentScope): Flow<List<LocalCashSignature>>

    /** Reads the first page again. */
    suspend fun refresh(scope: CashPaymentScope, state: CashPaymentState): RefreshResult

    suspend fun loadMore(scope: CashPaymentScope, state: CashPaymentState): RefreshResult

    /** Reads one payment from the server; a hand-over may only be signed after this succeeded on the open screen. */
    suspend fun refreshPayment(scope: CashPaymentScope, paymentId: String): RefreshResult

    /**
     * Stores the recipient's signature on the phone with a new command id, then sends it. A signature already waiting
     * for the same payment is sent again unchanged ([signature] is ignored then): a second tap never pays twice.
     */
    suspend fun sign(scope: CashPaymentScope, paymentId: String, formHash: String, signature: DrawnSignature): CashSignResult

    /** Sends every waiting signature with its own command id. False: some must be tried again later. */
    suspend fun sendPending(): Boolean

    /** The signed receipt PDF, downloaded with the bearer token for the in-app viewer. */
    suspend fun openReceipt(scope: CashPaymentScope, paymentId: String): DocumentContentResult
}

/** Removes a file downloaded for viewing once the viewer closes: nothing is kept for offline use. */
fun interface ViewedFileCleaner {
    fun discard(filePath: String)
}

interface SyncRepository {
    fun observeOverview(): Flow<SyncOverview>

    fun requestSync()

    suspend fun retryFailed()

    /** Hides a permanent failure from the banner. The record itself is kept for audit. */
    suspend fun acknowledgeFailure(clientActionId: String)

    suspend fun refreshFromServer(): RefreshResult

    /** Outcome of the latest server read; null while the first read of a session is still running. */
    fun observeLastRefresh(): Flow<RefreshResult?>
}

interface NetworkMonitor {
    val isOnline: Flow<Boolean>
}

/**
 * The server's idea of "now" (docs/26): the device clock corrected by the offset measured from the HTTP `Date`
 * header of the latest API response. Zero until a response has been seen, so it never invents a correction.
 */
interface ServerClock {
    /** Server time minus device time. */
    val offset: StateFlow<Duration>

    fun now(): Instant
}

interface AppInfo {
    val isDemoMode: Boolean
    val versionName: String

    /** The Firebase project configuration was present at build time (push notifications). */
    val isPushConfigured: Boolean get() = false

    /** Driver daily shift with tachograph rest, wake-up alarm and vehicle check (`app.driverShift`, docs/16). */
    val isDriverShiftEnabled: Boolean get() = false

    /** Device management and remote screen sessions (`app.deviceManagement`, docs/29); never in the demo. */
    val isDeviceManagementEnabled: Boolean get() = false
}

/** Controls for the in-app fake backend. `isAvailable` is false against a real backend. */
interface DemoBackendControls {
    val isAvailable: Boolean
    val simulateOffline: StateFlow<Boolean>

    fun setSimulateOffline(enabled: Boolean)

    suspend fun resetDemoData()
}

/**
 * Remote road snapshots (docs/24): the planner's request read after a push, the photo or failure of this tablet,
 * and the queue that carries them to the server. Only the vehicle tablet handles requests.
 */
interface RoadSnapshotRepository {
    /** The latest request of the signed-in driver, for the banners on the screens; null without one. */
    fun observeLatest(): Flow<SnapshotOutcome?>

    /** A push arrived: the request is read from the server and stored. */
    suspend fun onRequestSignal(tripId: String, requestId: String): SnapshotDecision

    /** The request the driver opened from the notification; null when it is closed or unknown. */
    suspend fun pendingRequest(requestId: String): SnapshotRequest?

    /** The photo was taken: it is prepared, stored and queued for the upload. False when it cannot be used. */
    suspend fun storePhoto(requestId: String, source: File, capturedAt: Instant, camera: SnapshotCamera): Boolean

    /** The photo could not be taken; the reason is queued for the server. */
    suspend fun reportFailure(requestId: String, failure: SnapshotFailure)

    /** Sends photos and failure reports waiting on the tablet. False when something must be tried again later. */
    suspend fun sendPending(): Boolean
}

/**
 * Wake-up calls (docs/25): the planner's call read after a push, the driver's answer on this device, and the queue
 * that carries the answer to the server. Every device of the driver handles calls.
 */
interface WakeUpCallRepository {
    /** The latest call of the signed-in driver, for the banner on the home screen; null without one. */
    fun observeLatest(): Flow<WakeUpOutcome?>

    /**
     * A `ring` push arrived: the call is read from the server and stored. [reason] and [expiresAt] are the push's own
     * data, used when the server cannot be asked right now.
     */
    suspend fun onRingSignal(callId: String, reason: String?, expiresAt: Instant?): WakeUpDecision

    /** A `stop` push arrived (answered elsewhere, cancelled): the call is closed on this device without a report. */
    suspend fun onStopSignal(callId: String)

    /** Calls that were ringing when the process stopped (a restart); the caller decides whether they still ring. */
    suspend fun ringingCalls(): List<WakeUpCall>

    /** This device started to ring; best effort, never queued. */
    suspend fun reportRinging(callId: String)

    /** The driver answered: the answer is stored and queued for the server. */
    suspend fun acknowledge(callId: String)

    /** The call rang until its deadline without an answer: the report is stored and queued. */
    suspend fun expire(callId: String)

    /** Sends answers and ring-out reports waiting on the device. False when something must be tried again later. */
    suspend fun sendPending(): Boolean
}
