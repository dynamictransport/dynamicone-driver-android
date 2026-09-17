package com.dynamictransport.dynamicone.domain.repository

import com.dynamictransport.dynamicone.domain.model.ActionReceipt
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.AttendanceMonth
import com.dynamictransport.dynamicone.domain.model.AttendanceStatus
import com.dynamictransport.dynamicone.domain.model.Conversation
import com.dynamictransport.dynamicone.domain.model.ConversationList
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DocumentSlot
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.IncidentCategory
import com.dynamictransport.dynamicone.domain.model.LocalPunch
import com.dynamictransport.dynamicone.domain.model.LoginResult
import com.dynamictransport.dynamicone.domain.model.MonthlyPayroll
import com.dynamictransport.dynamicone.domain.model.PasswordChangeResult
import com.dynamictransport.dynamicone.domain.model.PendingTripAction
import com.dynamictransport.dynamicone.domain.model.PunchResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.RejectReason
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.SignedDocument
import com.dynamictransport.dynamicone.domain.model.SyncOverview
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionRecord
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripDocument
import com.dynamictransport.dynamicone.domain.model.TripExecution
import com.dynamictransport.dynamicone.domain.model.UserProfile
import com.dynamictransport.dynamicone.domain.scan.ScanMode
import com.dynamictransport.dynamicone.domain.scan.ScanOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.YearMonth

interface AuthRepository {
    val sessionState: StateFlow<SessionState>

    /** True when the last session ended because it expired (pending data is kept). */
    val sessionExpired: StateFlow<Boolean>

    suspend fun restoreSession()

    fun observeProfile(): Flow<UserProfile?>

    suspend fun login(username: String, password: String, twoFactorCode: String? = null): LoginResult

    /** Revokes the server session (when supported) and always clears the local session. */
    suspend fun logout()

    suspend fun changePassword(currentPassword: String, newPassword: String): PasswordChangeResult

    suspend fun lastUsername(): String?
}

interface ShiftRepository {
    fun observeShift(): Flow<Shift>

    suspend fun currentShift(): Shift

    suspend fun startShift(): ActionResult

    suspend fun endShift(): ActionResult
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

    /** Stores the command with a new command id, then sends it. Only options of the current plan are accepted. */
    suspend fun submit(tripId: String, action: ExecutionAction, note: String?): ActionResult

    /** Sends the stored command again with the same command id and payload. */
    suspend fun resendPending(tripId: String): ActionResult

    /** Drops the stored command and reads the current plan. */
    suspend fun discardPending(tripId: String): RefreshResult
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

interface AppInfo {
    val isDemoMode: Boolean
    val versionName: String

    /** The Firebase project configuration was present at build time (push notifications). */
    val isPushConfigured: Boolean get() = false
}

/** Controls for the in-app fake backend. `isAvailable` is false against a real backend. */
interface DemoBackendControls {
    val isAvailable: Boolean
    val simulateOffline: StateFlow<Boolean>

    fun setSimulateOffline(enabled: Boolean)

    suspend fun resetDemoData()
}
