package com.dynamictransport.dynamicone.testing

import com.dynamictransport.dynamicone.domain.model.ActionReceipt
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.Conversation
import com.dynamictransport.dynamicone.domain.model.ConversationList
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DocumentSlot
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.IncidentCategory
import com.dynamictransport.dynamicone.domain.model.LoginResult
import com.dynamictransport.dynamicone.domain.model.MonthlyPayroll
import com.dynamictransport.dynamicone.domain.model.PasswordChangeResult
import com.dynamictransport.dynamicone.domain.model.PendingTripAction
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.domain.model.SignedDocument
import com.dynamictransport.dynamicone.domain.model.SyncOverview
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionRecord
import com.dynamictransport.dynamicone.domain.model.TripDocument
import com.dynamictransport.dynamicone.domain.model.TripExecution
import com.dynamictransport.dynamicone.domain.model.UserProfile
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.AuthRepository
import com.dynamictransport.dynamicone.domain.repository.DocumentMediaStore
import com.dynamictransport.dynamicone.domain.repository.DocumentRepository
import com.dynamictransport.dynamicone.domain.repository.DocumentScanner
import com.dynamictransport.dynamicone.domain.repository.IncidentRepository
import com.dynamictransport.dynamicone.domain.repository.MessageRepository
import com.dynamictransport.dynamicone.domain.repository.NetworkMonitor
import com.dynamictransport.dynamicone.domain.repository.PayrollRepository
import com.dynamictransport.dynamicone.domain.repository.ShiftRepository
import com.dynamictransport.dynamicone.domain.repository.SignedDocumentRepository
import com.dynamictransport.dynamicone.domain.repository.SyncRepository
import com.dynamictransport.dynamicone.domain.repository.TripActionCommand
import com.dynamictransport.dynamicone.domain.repository.TripExecutionRepository
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import com.dynamictransport.dynamicone.domain.scan.ScanIssue
import com.dynamictransport.dynamicone.domain.scan.ScanMode
import com.dynamictransport.dynamicone.domain.scan.ScanOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.YearMonth

// Test doubles of the domain repositories. Test-only: never part of the app.

class FakeAuthRepository : AuthRepository {
    override val sessionState = MutableStateFlow<SessionState>(SessionState.LoggedIn("drv-1"))
    override val sessionExpired = MutableStateFlow(false)
    val driver = MutableStateFlow<UserProfile?>(UserProfile("drv-1", "Mehmet Demir", "1027", "tr", setOf(AppModule.DRIVER), "drv-1"))
    var loginResult = LoginResult.SUCCESS
    var loginCalls = 0
    var lastTwoFactorCode: String? = null
    var loginGate: CompletableDeferred<Unit>? = null
    var lastUsernameValue: String? = null
    var logoutCalls = 0
    var passwordChangeResult = PasswordChangeResult.SUCCESS
    val passwordChanges = mutableListOf<Pair<String, String>>()

    override suspend fun restoreSession() = Unit

    override fun observeProfile(): Flow<UserProfile?> = driver

    override suspend fun login(username: String, password: String, twoFactorCode: String?): LoginResult {
        loginCalls++
        lastTwoFactorCode = twoFactorCode
        loginGate?.await()
        return loginResult
    }

    override suspend fun logout() {
        logoutCalls++
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String): PasswordChangeResult {
        passwordChanges += currentPassword to newPassword
        return passwordChangeResult
    }

    override suspend fun lastUsername(): String? = lastUsernameValue
}

class FakeShiftRepository(initial: Shift = Shift.NotStarted) : ShiftRepository {
    val shift = MutableStateFlow(initial)
    var startCalls = 0
    var endCalls = 0

    override fun observeShift(): Flow<Shift> = shift

    override suspend fun currentShift(): Shift = shift.value

    override suspend fun startShift(): ActionResult {
        startCalls++
        shift.value = shift.value.copy(status = ShiftStatus.ACTIVE, startedAt = TEST_NOW)
        return ActionResult.Queued
    }

    override suspend fun endShift(): ActionResult {
        endCalls++
        shift.value = shift.value.copy(status = ShiftStatus.FINISHED, endedAt = TEST_NOW)
        return ActionResult.Queued
    }
}

class FakeTripRepository(initial: List<Trip> = emptyList()) : TripRepository {
    val trips = MutableStateFlow(initial)
    val history = MutableStateFlow<Map<String, List<TripActionRecord>>>(emptyMap())
    val submitted = mutableListOf<TripActionCommand>()
    val refreshedTrips = mutableListOf<String>()
    val canLoadMore = MutableStateFlow(false)
    var gate: CompletableDeferred<Unit>? = null
    var nextResult: ActionResult = ActionResult.Queued
    var refreshResult = RefreshResult.SUCCESS
    var loadMoreCalls = 0

    override fun observeTrips(): Flow<List<Trip>> = trips

    override fun observeTrip(tripId: String): Flow<Trip?> = trips.map { list -> list.firstOrNull { it.id == tripId } }

    override fun observeActionHistory(tripId: String): Flow<List<TripActionRecord>> =
        history.map { it[tripId].orEmpty() }

    override fun observeFinishedTripCount(since: Instant): Flow<Int> = flowOf(0)

    override suspend fun currentTrips(): List<Trip> = trips.value

    override suspend fun submitAction(command: TripActionCommand): ActionResult {
        submitted += command
        gate?.await()
        return nextResult
    }

    override suspend fun refreshTrip(tripId: String): RefreshResult {
        refreshedTrips += tripId
        return refreshResult
    }

    override suspend fun loadMoreTrips(): RefreshResult {
        loadMoreCalls++
        return RefreshResult.SUCCESS
    }

    override fun observeCanLoadMore(): Flow<Boolean> = canLoadMore
}

class FakeTripExecutionRepository : TripExecutionRepository {
    data class Submitted(val tripId: String, val action: ExecutionAction, val note: String?)

    val execution = MutableStateFlow<TripExecution?>(null)
    val pending = MutableStateFlow<PendingTripAction?>(null)
    val receipts = MutableStateFlow<List<ActionReceipt>>(emptyList())
    val submitted = mutableListOf<Submitted>()
    var submitResult: ActionResult = ActionResult.Queued
    var resendResult: ActionResult = ActionResult.Queued
    var gate: CompletableDeferred<Unit>? = null
    var resendCalls = 0
    var discardCalls = 0
    var receiptRefreshes = 0

    override fun observeExecution(tripId: String): Flow<TripExecution?> = execution

    override fun observePendingAction(tripId: String): Flow<PendingTripAction?> = pending

    override fun observeReceipts(tripId: String): Flow<List<ActionReceipt>> = receipts

    override suspend fun refreshReceipts(tripId: String): RefreshResult {
        receiptRefreshes++
        return RefreshResult.SUCCESS
    }

    override suspend fun submit(tripId: String, action: ExecutionAction, note: String?): ActionResult {
        submitted += Submitted(tripId, action, note)
        gate?.await()
        return submitResult
    }

    override suspend fun resendPending(tripId: String): ActionResult {
        resendCalls++
        return resendResult
    }

    override suspend fun discardPending(tripId: String): RefreshResult {
        discardCalls++
        pending.value = null
        return RefreshResult.SUCCESS
    }
}

class FakeSyncRepository : SyncRepository {
    val overview = MutableStateFlow(SyncOverview.Empty)
    val lastRefresh = MutableStateFlow<RefreshResult?>(RefreshResult.SUCCESS)
    var retryCalls = 0
    var refreshCalls = 0
    var refreshResult = RefreshResult.SUCCESS
    val acknowledged = mutableListOf<String>()

    override fun observeOverview(): Flow<SyncOverview> = overview

    override fun requestSync() = Unit

    override suspend fun retryFailed() {
        retryCalls++
    }

    override suspend fun acknowledgeFailure(clientActionId: String) {
        acknowledged += clientActionId
    }

    override suspend fun refreshFromServer(): RefreshResult {
        refreshCalls++
        lastRefresh.value = refreshResult
        return refreshResult
    }

    override fun observeLastRefresh(): Flow<RefreshResult?> = lastRefresh
}

class FakeNetworkMonitor(initiallyOnline: Boolean = true) : NetworkMonitor {
    val online = MutableStateFlow(initiallyOnline)
    override val isOnline: Flow<Boolean> = online
}

class FakeAppInfo(
    override val isDemoMode: Boolean = false,
    override val versionName: String = "0.1.0-test",
) : AppInfo

class FakeIncidentRepository : IncidentRepository {
    data class Report(val tripId: String?, val category: IncidentCategory, val note: String?)

    val reports = mutableListOf<Report>()

    override suspend fun report(tripId: String?, category: IncidentCategory, note: String?): ActionResult {
        reports += Report(tripId, category, note)
        return ActionResult.Queued
    }
}

class FakeDocumentRepository : DocumentRepository {
    data class Uploaded(val tripId: String, val slotKey: String, val path: String)

    val slots = MutableStateFlow<List<DocumentSlot>?>(null)
    val files = MutableStateFlow<List<TripDocument>>(emptyList())
    val uploaded = mutableListOf<Uploaded>()
    val refreshed = mutableListOf<String>()
    val opened = mutableListOf<String>()
    var refreshResult = RefreshResult.SUCCESS
    var openResult: DocumentContentResult = DocumentContentResult.Unavailable(ActionRejection.NOT_SUPPORTED)
    var uploadResult: ActionResult = ActionResult.Queued

    override fun observeSlots(tripId: String): Flow<List<DocumentSlot>?> = slots

    override fun observeFiles(tripId: String): Flow<List<TripDocument>> = files

    override suspend fun refreshDocuments(tripId: String): RefreshResult {
        refreshed += tripId
        return refreshResult
    }

    override suspend fun upload(tripId: String, slot: DocumentSlot, localFilePath: String): ActionResult {
        uploaded += Uploaded(tripId, slot.key, localFilePath)
        return uploadResult
    }

    override suspend fun openDocument(tripId: String, documentId: String, contentTypeHint: String?): DocumentContentResult {
        opened += documentId
        return openResult
    }
}

class FakePayrollRepository : PayrollRepository {
    val payrolls = MutableStateFlow<Map<YearMonth, MonthlyPayroll>>(emptyMap())
    val refreshed = mutableListOf<YearMonth>()
    var refreshResult = RefreshResult.SUCCESS

    /** What the "server" returns for a month when a refresh succeeds. */
    var server: (YearMonth) -> MonthlyPayroll = { MonthlyPayroll.Unpublished(it) }

    override fun observePayroll(month: YearMonth): Flow<MonthlyPayroll?> = payrolls.map { it[month] }

    override suspend fun refresh(month: YearMonth): RefreshResult {
        refreshed += month
        if (refreshResult == RefreshResult.SUCCESS) payrolls.update { it + (month to server(month)) }
        return refreshResult
    }
}

class FakeSignedDocumentRepository : SignedDocumentRepository {
    val documents = MutableStateFlow<List<SignedDocument>?>(null)
    val canLoadMore = MutableStateFlow(false)
    var refreshResult = RefreshResult.SUCCESS
    var server: List<SignedDocument> = emptyList()
    var openResult: DocumentContentResult = DocumentContentResult.Unavailable(ActionRejection.NOT_SUPPORTED)
    val opened = mutableListOf<String>()
    var loadMoreCalls = 0

    override fun observeDocuments(): Flow<List<SignedDocument>?> = documents

    override fun observeCanLoadMore(): Flow<Boolean> = canLoadMore

    override suspend fun refresh(): RefreshResult {
        if (refreshResult == RefreshResult.SUCCESS) documents.value = server
        return refreshResult
    }

    override suspend fun loadMore(): RefreshResult {
        loadMoreCalls++
        return RefreshResult.SUCCESS
    }

    override suspend fun open(documentId: String): DocumentContentResult {
        opened += documentId
        return openResult
    }
}

class FakeMessageRepository : MessageRepository {
    val conversation = MutableStateFlow(Conversation.empty(null))
    val sent = mutableListOf<Pair<String?, String>>()
    val resent = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    val markedRead = mutableListOf<Pair<String?, String>>()
    val refreshedTrips = mutableListOf<String?>()
    var refreshResult = RefreshResult.SUCCESS
    var sendResult: ActionResult = ActionResult.Queued
    var olderCalls = 0

    override fun observeConversation(tripId: String?): Flow<Conversation> = conversation.map { it.copy(tripId = tripId) }

    val conversations = MutableStateFlow(ConversationList.EMPTY)
    var conversationRefreshes = 0

    override fun observeConversations(): Flow<ConversationList> = conversations

    override suspend fun refreshConversations(): RefreshResult {
        conversationRefreshes++
        if (refreshResult == RefreshResult.SUCCESS) conversations.update { it.copy(loaded = true) }
        return refreshResult
    }

    override suspend fun refresh(tripId: String?): RefreshResult {
        refreshedTrips += tripId
        if (refreshResult == RefreshResult.SUCCESS) conversation.update { it.copy(loaded = true) }
        return refreshResult
    }

    override suspend fun loadOlder(tripId: String?): RefreshResult {
        olderCalls++
        return RefreshResult.SUCCESS
    }

    override suspend fun send(tripId: String?, body: String): ActionResult {
        sent += tripId to body
        return sendResult
    }

    override suspend fun resend(commandId: String): ActionResult {
        resent += commandId
        return ActionResult.Queued
    }

    override suspend fun discard(commandId: String) {
        discarded += commandId
    }

    override suspend fun markRead(tripId: String?, messageId: String): Boolean {
        markedRead += tripId to messageId
        return true
    }
}

class FakeDocumentMediaStore : DocumentMediaStore {
    var enoughSpace = true
    var created = 0
    var importedPath: String? = "/files/document_media/imported.jpg"
    val discarded = mutableListOf<String>()

    override fun hasEnoughSpace(): Boolean = enoughSpace

    override suspend fun createCaptureFile(): String {
        created++
        return "/files/document_media/capture-$created.jpg"
    }

    override suspend fun importFromContentUri(uri: String): String? = importedPath

    override suspend fun discard(filePath: String) {
        discarded += filePath
    }
}

class FakeDocumentScanner : DocumentScanner {
    data class Call(val sourcePath: String, val outputPath: String, val mode: ScanMode)

    val calls = mutableListOf<Call>()
    var issues: List<ScanIssue> = emptyList()
    var unreadable = false
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun process(sourcePath: String, outputPath: String, mode: ScanMode): ScanOutcome {
        calls += Call(sourcePath, outputPath, mode)
        gate?.await()
        return if (unreadable) ScanOutcome.Unreadable else ScanOutcome.Processed(outputPath, issues)
    }
}
