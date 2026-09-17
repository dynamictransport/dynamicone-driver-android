package com.dynamictransport.driver.data.demo

import com.dynamictransport.driver.domain.model.ActionReceipt
import com.dynamictransport.driver.domain.model.ActionRejection
import com.dynamictransport.driver.domain.model.ActionResult
import com.dynamictransport.driver.domain.model.Conversation
import com.dynamictransport.driver.domain.model.ConversationList
import com.dynamictransport.driver.domain.model.DocumentContentResult
import com.dynamictransport.driver.domain.model.ExecutionAction
import com.dynamictransport.driver.domain.model.MonthlyPayroll
import com.dynamictransport.driver.domain.model.PendingTripAction
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.model.SignedDocument
import com.dynamictransport.driver.domain.model.TripExecution
import com.dynamictransport.driver.domain.repository.MessageRepository
import com.dynamictransport.driver.domain.repository.PayrollRepository
import com.dynamictransport.driver.domain.repository.SignedDocumentRepository
import com.dynamictransport.driver.domain.repository.TripExecutionRepository
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
