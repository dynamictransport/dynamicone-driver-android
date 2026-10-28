package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.IdGenerator
import com.dynamictransport.dynamicone.core.database.CashPaymentDao
import com.dynamictransport.dynamicone.core.database.CashSignatureEntity
import com.dynamictransport.dynamicone.core.network.CashPaymentDto
import com.dynamictransport.dynamicone.core.network.CashPaymentErrorCodes
import com.dynamictransport.dynamicone.core.network.CashSignatureRequestDto
import com.dynamictransport.dynamicone.core.network.DriverErrorCodes
import com.dynamictransport.dynamicone.core.network.MobileApi
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.core.sync.CashPaymentSyncScheduler
import com.dynamictransport.dynamicone.domain.model.CashPayment
import com.dynamictransport.dynamicone.domain.model.CashPaymentList
import com.dynamictransport.dynamicone.domain.model.CashPaymentLookup
import com.dynamictransport.dynamicone.domain.model.CashPaymentScope
import com.dynamictransport.dynamicone.domain.model.CashPaymentState
import com.dynamictransport.dynamicone.domain.model.CashSignFailure
import com.dynamictransport.dynamicone.domain.model.CashSignResult
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DrawnSignature
import com.dynamictransport.dynamicone.domain.model.LocalCashSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.repository.CashPaymentRepository
import com.dynamictransport.dynamicone.domain.rules.HrDocumentRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cash payments against the Mobile API (docs/28_EMPLOYEE_CASH_PAYMENTS.md). Lists and details are held in memory
 * only and go with the session. A signature is written to the phone with a new command id before it is sent; when the
 * server cannot be reached it is sent again automatically with the same command id (the server is idempotent: 201 the
 * first time, 200 for the same command again). Only the server's answer moves a payment to "received"; the drawn
 * strokes are deleted as soon as it confirmed them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RealCashPaymentRepository @Inject constructor(
    private val api: MobileApi,
    private val tracker: ApiActivityTracker,
    private val dao: CashPaymentDao,
    private val downloader: ProtectedFileDownloader,
    private val sessionPreferences: SessionPreferences,
    private val scheduler: CashPaymentSyncScheduler,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val json: Json,
) : CashPaymentRepository, CashPaymentSessionData {

    private data class ListKey(val scope: CashPaymentScope, val state: CashPaymentState)

    private data class PaymentKey(val scope: CashPaymentScope, val paymentId: String)

    private val lists = MutableStateFlow<Map<ListKey, CashPaymentList>>(emptyMap())
    private val nextPages = mutableMapOf<ListKey, Int>()

    /** Payments read one at a time, which may be in no loaded list. */
    private val single = MutableStateFlow<Map<PaymentKey, CashPayment>>(emptyMap())

    /** Payments the server says this account does not have (any more). */
    private val missing = MutableStateFlow<Set<PaymentKey>>(emptySet())
    private val readMutex = Mutex()
    private val sendMutex = Mutex()

    private val localSignatures: Flow<List<LocalCashSignature>> = sessionPreferences.userIdChanges.flatMapLatest { userId ->
        if (userId == null) flowOf(emptyList()) else dao.observeSignatures(userId).map { rows -> rows.map(::toLocal) }
    }

    override fun observeList(scope: CashPaymentScope, state: CashPaymentState): Flow<CashPaymentList?> =
        lists.map { it[ListKey(scope, state)] }.distinctUntilChanged()

    override fun observePayment(scope: CashPaymentScope, paymentId: String): Flow<CashPaymentLookup> {
        val key = PaymentKey(scope, paymentId)
        return combine(lists, single, missing, localSignatures) { loaded, one, gone, local ->
            val payment = one[key] ?: loaded.filterKeys { it.scope == scope }.values.flatMap { it.items }.firstOrNull { it.id == paymentId }
            when {
                payment != null -> CashPaymentLookup.Found(payment, local.lastOrNull { it.scope == scope && it.paymentId == paymentId })
                key in gone -> CashPaymentLookup.Missing
                else -> CashPaymentLookup.Loading
            }
        }.distinctUntilChanged()
    }

    override fun observeLocalSignatures(scope: CashPaymentScope): Flow<List<LocalCashSignature>> =
        localSignatures.map { list -> list.filter { it.scope == scope } }.distinctUntilChanged()

    override suspend fun refresh(scope: CashPaymentScope, state: CashPaymentState): RefreshResult = readMutex.withLock {
        // Safety net: a signature still waiting on the phone is handed to the background send whenever a list is read.
        sessionPreferences.userId()?.let { userId -> if (dao.getUnsent(userId).isNotEmpty()) scheduler.requestSend() }
        read {
            val key = ListKey(scope, state)
            val page = tracker.call { list(scope, state, 1) }
            val items = page.items.orEmpty().mapNotNull(CashPaymentMapping::payment)
            lists.update { it + (key to CashPaymentList(items, page.hasMore, clock.instant())) }
            nextPages[key] = 2
            missing.update { gone -> gone - items.map { PaymentKey(scope, it.id) }.toSet() }
            tracker.markReadSucceeded()
        }
    }

    override suspend fun loadMore(scope: CashPaymentScope, state: CashPaymentState): RefreshResult = readMutex.withLock {
        val key = ListKey(scope, state)
        val current = lists.value[key] ?: return@withLock RefreshResult.SUCCESS
        if (!current.hasMore) return@withLock RefreshResult.SUCCESS
        read {
            val pageNumber = nextPages[key] ?: 2
            val page = tracker.call { list(scope, state, pageNumber) }
            val more = page.items.orEmpty().mapNotNull(CashPaymentMapping::payment)
            lists.update { loaded ->
                val known = loaded[key] ?: current
                loaded + (key to known.copy(items = (known.items + more).distinctBy { it.id }, hasMore = page.hasMore))
            }
            nextPages[key] = pageNumber + 1
            tracker.markReadSucceeded()
        }
    }

    override suspend fun refreshPayment(scope: CashPaymentScope, paymentId: String): RefreshResult = read {
        val dto = try {
            tracker.call { detail(scope, paymentId) }
        } catch (e: RemoteException.NotFound) {
            if (e.problem?.code != CashPaymentErrorCodes.PAYMENT_NOT_FOUND) throw e
            forget(scope, paymentId)
            tracker.markReadSucceeded()
            return@read
        }
        val payment = CashPaymentMapping.payment(dto) ?: throw RemoteException.InvalidResponse()
        apply(scope, payment)
        tracker.markReadSucceeded()
    }

    /** Called only after the recipient confirmed the declaration: the request always sends `confirmed = true`. */
    override suspend fun sign(scope: CashPaymentScope, paymentId: String, formHash: String, signature: DrawnSignature): CashSignResult {
        val userId = sessionPreferences.userId() ?: return CashSignResult.NotLoggedIn
        return sendMutex.withLock {
            // A second tap, a retry or a second screen: the signature already waiting on the phone is the one that is sent.
            val waiting = dao.getWaitingSignature(userId, paymentId)
            val entity = when {
                waiting != null -> waiting
                !HrDocumentRules.isValid(signature) -> return@withLock CashSignResult.Incomplete
                else -> newSignature(userId, scope, paymentId, formHash, signature).also {
                    // Stored before anything is sent: a lost answer or a closed app cannot lose the signature.
                    dao.deleteFailedSignatures(userId, paymentId)
                    dao.insertSignature(it)
                }
            }
            send(entity, scheduleRetry = true)
        }
    }

    override suspend fun sendPending(): Boolean = sendMutex.withLock {
        // Without the account's session nothing can be sent; signing in again starts the next attempt.
        val userId = sessionPreferences.userId() ?: return@withLock true
        for (signature in dao.getUnsent(userId)) {
            // The server is not reachable: stop here, the worker tries again with backoff.
            if (send(signature, scheduleRetry = false) is CashSignResult.Saved) return@withLock false
        }
        true
    }

    override suspend fun openReceipt(scope: CashPaymentScope, paymentId: String): DocumentContentResult =
        downloader.download("cash-receipt-${scope.wireName}-$paymentId", PDF, ProtectedFileDownloader.PRIVATE_VIEW_DIRECTORY) {
            when (scope) {
                CashPaymentScope.DISBURSEMENTS -> api.cashPaymentReceipt(paymentId)
                CashPaymentScope.OWN -> api.myCashPaymentReceipt(paymentId)
            }
        }

    /** Server data held in memory; signatures waiting on the phone are kept ([clearStoredSignatures], [keepOnly]). */
    override suspend fun clear() {
        lists.value = emptyMap()
        nextPages.clear()
        single.value = emptyMap()
        missing.value = emptySet()
    }

    override suspend fun clearStoredSignatures() = dao.deleteAllSignatures()

    /** Another account signed in on this phone: earlier accounts' signatures must never be sent with this session. */
    override suspend fun keepOnly(userId: String) = dao.deleteSignaturesForOtherUsers(userId)

    private suspend fun list(scope: CashPaymentScope, state: CashPaymentState, page: Int) = when (scope) {
        CashPaymentScope.DISBURSEMENTS -> api.cashPayments(state.wireName, page, PAGE_SIZE)
        CashPaymentScope.OWN -> api.myCashPayments(state.wireName, page, PAGE_SIZE)
    }

    private suspend fun detail(scope: CashPaymentScope, paymentId: String): CashPaymentDto = when (scope) {
        CashPaymentScope.DISBURSEMENTS -> api.cashPayment(paymentId)
        CashPaymentScope.OWN -> api.myCashPayment(paymentId)
    }

    /** One payment read from the server: it moves to the list of its state in every loaded list of the scope. */
    private fun apply(scope: CashPaymentScope, payment: CashPayment) {
        val key = PaymentKey(scope, payment.id)
        single.update { it + (key to payment) }
        missing.update { it - key }
        lists.update { loaded ->
            loaded.mapValues { (listKey, list) ->
                if (listKey.scope != scope) return@mapValues list
                val without = list.items.filterNot { it.id == payment.id }
                when {
                    listKey.state != payment.state -> list.copy(items = without)
                    list.items.any { it.id == payment.id } -> list.copy(items = list.items.map { if (it.id == payment.id) payment else it })
                    // Newly read in this state: newest first, like the server's order.
                    else -> list.copy(items = listOf(payment) + without)
                }
            }
        }
    }

    private fun forget(scope: CashPaymentScope, paymentId: String) {
        val key = PaymentKey(scope, paymentId)
        single.update { it - key }
        missing.update { it + key }
        lists.update { loaded ->
            loaded.mapValues { (listKey, list) -> if (listKey.scope == scope) list.copy(items = list.items.filterNot { it.id == paymentId }) else list }
        }
    }

    private fun newSignature(
        userId: String,
        scope: CashPaymentScope,
        paymentId: String,
        formHash: String,
        signature: DrawnSignature,
    ): CashSignatureEntity {
        val now = clock.instant()
        return CashSignatureEntity(
            commandId = ids.newId(),
            userId = userId,
            scope = scope.name,
            paymentId = paymentId,
            formHash = formHash,
            strokesJson = CashPaymentMapping.encodeStrokes(json, signature.strokes),
            aspectRatio = signature.aspectRatio,
            signedAt = now,
            state = LocalSignatureState.PENDING,
            failure = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun send(signature: CashSignatureEntity, scheduleRetry: Boolean): CashSignResult {
        val scope = CashPaymentScope.entries.firstOrNull { it.name == signature.scope } ?: run {
            // A row of an unknown scope (never written by this version) cannot be sent anywhere.
            dao.deleteSignaturesOf(signature.userId, signature.paymentId)
            return CashSignResult.Failed(CashSignFailure.REJECTED)
        }
        dao.updateSignatureState(signature.commandId, LocalSignatureState.SENDING, null, clock.instant())
        val request = CashSignatureRequestDto(
            commandId = signature.commandId,
            formHash = signature.formHash,
            confirmed = true,
            aspectRatio = signature.aspectRatio,
            strokes = CashPaymentMapping.decodeStrokes(json, signature.strokesJson),
        )
        val response = try {
            tracker.call {
                when (scope) {
                    CashPaymentScope.DISBURSEMENTS -> api.signCashPayment(signature.paymentId, request)
                    CashPaymentScope.OWN -> api.signMyCashPayment(signature.paymentId, request)
                }
            }
        } catch (e: CancellationException) {
            // The screen was left (or the worker stopped) while sending: the stored signature is sent in the background
            // with the same command id instead of waiting for the next app start.
            withContext(NonCancellable) {
                dao.updateSignatureState(signature.commandId, LocalSignatureState.PENDING, null, clock.instant())
                scheduler.requestSend()
            }
            throw e
        } catch (e: RemoteException) {
            return when (val outcome = CashPaymentMapping.signatureOutcome(e)) {
                is CashSignatureOutcome.Retry -> waitForRetry(signature, scheduleRetry, outcome.delaySeconds)
                is CashSignatureOutcome.Rejected -> {
                    dao.updateSignatureState(signature.commandId, LocalSignatureState.FAILED, outcome.failure, clock.instant())
                    // A changed form or a conflicting change: the real state is read again so the current form is shown.
                    if (outcome.reread) refreshPayment(scope, signature.paymentId)
                    CashSignResult.Failed(outcome.failure)
                }
                CashSignatureOutcome.AlreadyReceived -> {
                    dao.deleteSignaturesOf(signature.userId, signature.paymentId)
                    refreshPayment(scope, signature.paymentId)
                    CashSignResult.AlreadyReceived
                }
                CashSignatureOutcome.Cancelled -> {
                    dao.deleteSignaturesOf(signature.userId, signature.paymentId)
                    refreshPayment(scope, signature.paymentId)
                    CashSignResult.Cancelled
                }
                CashSignatureOutcome.AccessRequired -> {
                    // Final for this account: nothing is sent again in the background (P7).
                    dao.updateSignatureState(signature.commandId, LocalSignatureState.FAILED, CashSignFailure.ACCESS_REQUIRED, clock.instant())
                    CashSignResult.AccessRequired
                }
                CashSignatureOutcome.Missing -> {
                    dao.deleteSignaturesOf(signature.userId, signature.paymentId)
                    forget(scope, signature.paymentId)
                    CashSignResult.Missing
                }
            }
        }
        // A success without the contract body: resending returns the recorded hand-over (idempotent).
        val payment = CashPaymentMapping.payment(response) ?: return waitForRetry(signature, scheduleRetry)
        // Confirmed by the server: the drawn strokes are not kept on the phone any longer.
        dao.deleteSignaturesOf(signature.userId, signature.paymentId)
        apply(scope, payment)
        return CashSignResult.Signed(payment)
    }

    private suspend fun waitForRetry(signature: CashSignatureEntity, scheduleRetry: Boolean, delaySeconds: Long = 0L): CashSignResult {
        dao.updateSignatureState(signature.commandId, LocalSignatureState.PENDING, null, clock.instant())
        if (scheduleRetry) scheduler.requestSend(delaySeconds)
        return CashSignResult.Saved(signature.commandId)
    }

    private inline fun read(block: () -> Unit): RefreshResult = try {
        block()
        RefreshResult.SUCCESS
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        when {
            // `403 cash_delivery_required` / `cash_access_required`: the account may not see this (any more).
            e is RemoteException.Forbidden && e.problem?.code != DriverErrorCodes.PASSWORD_CHANGE_REQUIRED -> RefreshResult.ACCESS_REQUIRED
            // The endpoints are not live on this server.
            e is RemoteException.NotFound && !e.isApiDisabled -> RefreshResult.NOT_SUPPORTED
            else -> e.toRefreshResult()
        }
    }

    private fun toLocal(entity: CashSignatureEntity) = LocalCashSignature(
        commandId = entity.commandId,
        scope = CashPaymentScope.entries.firstOrNull { it.name == entity.scope } ?: CashPaymentScope.DISBURSEMENTS,
        paymentId = entity.paymentId,
        signedAt = entity.signedAt,
        state = entity.state,
        failure = entity.failure,
    )

    private companion object {
        const val PAGE_SIZE = 50
        const val PDF = "application/pdf"
    }
}
