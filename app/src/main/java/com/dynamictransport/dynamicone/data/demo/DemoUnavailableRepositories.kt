package com.dynamictransport.dynamicone.data.demo

import com.dynamictransport.dynamicone.domain.model.ActionReceipt
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.AttendanceMonth
import com.dynamictransport.dynamicone.domain.model.AttendanceStatus
import com.dynamictransport.dynamicone.domain.model.CheckTemplate
import com.dynamictransport.dynamicone.domain.model.Conversation
import com.dynamictransport.dynamicone.domain.model.ConversationList
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DriverTimes
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.LocalPunch
import com.dynamictransport.dynamicone.domain.model.LocalShiftCommand
import com.dynamictransport.dynamicone.domain.model.MonthlyPayroll
import com.dynamictransport.dynamicone.domain.model.PendingTripAction
import com.dynamictransport.dynamicone.domain.model.PunchResult
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.ShiftCommandResult
import com.dynamictransport.dynamicone.domain.model.ShiftStartDraft
import com.dynamictransport.dynamicone.domain.model.SignedDocument
import com.dynamictransport.dynamicone.domain.model.TripExecution
import com.dynamictransport.dynamicone.domain.model.VehicleInfo
import com.dynamictransport.dynamicone.domain.model.VehicleLookup
import com.dynamictransport.dynamicone.domain.repository.AttendanceRepository
import com.dynamictransport.dynamicone.domain.repository.DailyShiftRepository
import com.dynamictransport.dynamicone.domain.repository.DriverTimesRepository
import com.dynamictransport.dynamicone.domain.repository.MessageRepository
import com.dynamictransport.dynamicone.domain.repository.PayrollRepository
import com.dynamictransport.dynamicone.domain.repository.SignedDocumentRepository
import com.dynamictransport.dynamicone.domain.repository.TripExecutionRepository
import com.dynamictransport.dynamicone.domain.repository.VehicleCheckRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.YearMonth

// Driver API features the in-app demo backend does not simulate. The demo shows them as unavailable
// instead of inventing trip plans, pay, personnel documents or conversations.

object UnavailableTripExecutionRepository : TripExecutionRepository {
    override fun observeExecution(tripId: String): Flow<TripExecution?> = flowOf(null)
    override fun observePendingAction(tripId: String): Flow<PendingTripAction?> = flowOf(null)
    override fun observeReceipts(tripId: String): Flow<List<ActionReceipt>> = flowOf(emptyList())
    override suspend fun refreshReceipts(tripId: String): RefreshResult = RefreshResult.NOT_SUPPORTED
    override suspend fun submit(tripId: String, action: ExecutionAction, note: String?): ActionResult =
        ActionResult.Rejected(ActionRejection.NOT_SUPPORTED)
    override suspend fun resendPending(tripId: String): ActionResult = ActionResult.Rejected(ActionRejection.NOT_SUPPORTED)
    override suspend fun discardPending(tripId: String): RefreshResult = RefreshResult.NOT_SUPPORTED
}

object UnavailablePayrollRepository : PayrollRepository {
    override fun observePayroll(month: YearMonth): Flow<MonthlyPayroll?> = flowOf(null)
    override suspend fun refresh(month: YearMonth): RefreshResult = RefreshResult.NOT_SUPPORTED
}

object UnavailableSignedDocumentRepository : SignedDocumentRepository {
    override fun observeDocuments(): Flow<List<SignedDocument>?> = flowOf(null)
    override fun observeCanLoadMore(): Flow<Boolean> = flowOf(false)
    override suspend fun refresh(): RefreshResult = RefreshResult.NOT_SUPPORTED
    override suspend fun loadMore(): RefreshResult = RefreshResult.NOT_SUPPORTED
    override suspend fun open(documentId: String): DocumentContentResult = DocumentContentResult.Unavailable(ActionRejection.NOT_SUPPORTED)
}

object UnavailableMessageRepository : MessageRepository {
    override fun observeConversations(): Flow<ConversationList> = flowOf(ConversationList.EMPTY)
    override suspend fun refreshConversations(): RefreshResult = RefreshResult.NOT_SUPPORTED
    override fun observeConversation(tripId: String?): Flow<Conversation> = flowOf(Conversation.empty(tripId))
    override suspend fun refresh(tripId: String?): RefreshResult = RefreshResult.NOT_SUPPORTED
    override suspend fun loadOlder(tripId: String?): RefreshResult = RefreshResult.NOT_SUPPORTED
    override suspend fun send(tripId: String?, body: String): ActionResult = ActionResult.Rejected(ActionRejection.NOT_SUPPORTED)
    override suspend fun resend(commandId: String): ActionResult = ActionResult.Rejected(ActionRejection.NOT_SUPPORTED)
    override suspend fun discard(commandId: String) = Unit
    override suspend fun markRead(tripId: String?, messageId: String): Boolean = false
}

/**
 * Without the Driver API daily shift (`app.driverShift=false` or the demo backend) there is no tachograph summary,
 * vehicle check or shift command: nothing is invented (docs/16 §12).
 */
object UnavailableDailyShiftRepository : DailyShiftRepository {
    override fun observePlannedVehicle(): Flow<VehicleInfo?> = flowOf(null)
    override fun observeCommands(): Flow<List<LocalShiftCommand>> = flowOf(emptyList())
    override suspend fun refresh(): RefreshResult = RefreshResult.NOT_SUPPORTED
    override suspend fun startShift(draft: ShiftStartDraft): ShiftCommandResult = ShiftCommandResult.NotAllowed(ActionRejection.NOT_SUPPORTED)
    override suspend fun sendPending(): Boolean = true
    override suspend fun resend(commandId: String): ShiftCommandResult = ShiftCommandResult.NotAllowed(ActionRejection.NOT_SUPPORTED)
    override suspend fun discard(commandId: String) = Unit
}

object UnavailableVehicleCheckRepository : VehicleCheckRepository {
    override fun observeTemplate(): Flow<CheckTemplate> = flowOf(CheckTemplate.Default)
    override suspend fun refreshTemplate(): RefreshResult = RefreshResult.NOT_SUPPORTED
    override suspend fun lookupVehicle(tagUid: String): VehicleLookup = VehicleLookup.Unverified
}

object UnavailableDriverTimesRepository : DriverTimesRepository {
    override fun observeDriverTimes(): Flow<DriverTimes?> = flowOf(null)
    override suspend fun current(): DriverTimes? = null
    override suspend fun refresh(): RefreshResult = RefreshResult.NOT_SUPPORTED
}

/** The demo backend only knows a driver account; office attendance needs the Mobile API. */
object UnavailableAttendanceRepository : AttendanceRepository {
    override fun observeStatus(): Flow<AttendanceStatus?> = flowOf(null)
    override fun observeLocalPunches(): Flow<List<LocalPunch>> = flowOf(emptyList())
    override suspend fun refreshStatus(): RefreshResult = RefreshResult.NOT_SUPPORTED
    override suspend fun recordPunch(direction: AttendanceDirection, tagUid: String): PunchResult = PunchResult.NotLoggedIn
    override suspend fun sendPending(): Boolean = true
    override suspend fun resend(commandId: String): PunchResult = PunchResult.NotLoggedIn
    override suspend fun dismiss(commandId: String) = Unit
    override fun observeMonth(month: YearMonth): Flow<AttendanceMonth?> = flowOf(null)
    override suspend fun refreshMonth(month: YearMonth): RefreshResult = RefreshResult.NOT_SUPPORTED
}
