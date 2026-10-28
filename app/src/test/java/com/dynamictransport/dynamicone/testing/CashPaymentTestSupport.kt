package com.dynamictransport.dynamicone.testing

import com.dynamictransport.dynamicone.core.database.CashPaymentDao
import com.dynamictransport.dynamicone.core.database.CashSignatureEntity
import com.dynamictransport.dynamicone.core.sync.CashPaymentSyncScheduler
import com.dynamictransport.dynamicone.domain.model.CashPayment
import com.dynamictransport.dynamicone.domain.model.CashPaymentKind
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Same queries as the Room DAO, for JVM tests without a database. CashPaymentDaoTest covers the SQL. */
class InMemoryCashPaymentDao : CashPaymentDao {
    private val rows = MutableStateFlow<List<CashSignatureEntity>>(emptyList())

    val signatures: List<CashSignatureEntity> get() = rows.value

    private fun List<CashSignatureEntity>.ordered() = sortedWith(compareBy<CashSignatureEntity> { it.signedAt }.thenBy { it.commandId })

    private val CashSignatureEntity.waiting get() = state == LocalSignatureState.PENDING || state == LocalSignatureState.SENDING

    override fun observeSignatures(userId: String): Flow<List<CashSignatureEntity>> = rows.map { list -> list.filter { it.userId == userId }.ordered() }

    override suspend fun getSignature(commandId: String): CashSignatureEntity? = rows.value.firstOrNull { it.commandId == commandId }

    override suspend fun getWaitingSignature(userId: String, paymentId: String): CashSignatureEntity? =
        rows.value.filter { it.userId == userId && it.paymentId == paymentId && it.waiting }.ordered().firstOrNull()

    override suspend fun getUnsent(userId: String): List<CashSignatureEntity> = rows.value.filter { it.userId == userId && it.waiting }.ordered()

    override suspend fun insertSignature(signature: CashSignatureEntity) {
        check(rows.value.none { it.commandId == signature.commandId }) { "duplicate command id" }
        rows.update { it + signature }
    }

    override suspend fun updateSignatureState(commandId: String, state: LocalSignatureState, failure: CashSignFailure?, updatedAt: Instant) =
        rows.update { list -> list.map { if (it.commandId == commandId) it.copy(state = state, failure = failure, updatedAt = updatedAt) else it } }

    override suspend fun deleteFailedSignatures(userId: String, paymentId: String) =
        rows.update { list -> list.filterNot { it.userId == userId && it.paymentId == paymentId && it.state == LocalSignatureState.FAILED } }

    override suspend fun deleteSignaturesOf(userId: String, paymentId: String) =
        rows.update { list -> list.filterNot { it.userId == userId && it.paymentId == paymentId } }

    override suspend fun deleteSignaturesForOtherUsers(userId: String) = rows.update { list -> list.filter { it.userId == userId } }

    override suspend fun deleteAllSignatures() {
        rows.value = emptyList()
    }
}

class RecordingCashPaymentSyncScheduler : CashPaymentSyncScheduler {
    val delaysSeconds = mutableListOf<Long>()
    val requests: Int get() = delaysSeconds.size

    override fun requestSend(delaySeconds: Long) {
        delaysSeconds += delaySeconds
    }
}

class FakeCashPaymentRepository : CashPaymentRepository {
    val lists = MutableStateFlow<Map<Pair<CashPaymentScope, CashPaymentState>, CashPaymentList>>(emptyMap())
    val single = MutableStateFlow<Map<Pair<CashPaymentScope, String>, CashPayment>>(emptyMap())
    val missing = MutableStateFlow<Set<Pair<CashPaymentScope, String>>>(emptySet())
    val local = MutableStateFlow<List<LocalCashSignature>>(emptyList())
    var refreshResult = RefreshResult.SUCCESS
    var paymentRefreshResult = RefreshResult.SUCCESS
    var loadMoreResult = RefreshResult.SUCCESS
    var signResult: (String) -> CashSignResult = { CashSignResult.Saved("sig-1") }
    var signGate: CompletableDeferred<Unit>? = null
    var receiptResult: DocumentContentResult = DocumentContentResult.Available("/cache/receipt.pdf", "application/pdf")
    val signed = mutableListOf<Signed>()
    val refreshed = mutableListOf<Pair<CashPaymentScope, CashPaymentState>>()
    val refreshedPayments = mutableListOf<Pair<CashPaymentScope, String>>()
    val openedReceipts = mutableListOf<Pair<CashPaymentScope, String>>()

    data class Signed(val scope: CashPaymentScope, val paymentId: String, val formHash: String, val signature: DrawnSignature)

    override fun observeList(scope: CashPaymentScope, state: CashPaymentState): Flow<CashPaymentList?> = lists.map { it[scope to state] }

    override fun observePayment(scope: CashPaymentScope, paymentId: String): Flow<CashPaymentLookup> =
        combine(lists, single, missing, local) { loaded, one, gone, signatures ->
            val payment = one[scope to paymentId]
                ?: loaded.filterKeys { it.first == scope }.values.flatMap { it.items }.firstOrNull { it.id == paymentId }
            when {
                payment != null -> CashPaymentLookup.Found(payment, signatures.lastOrNull { it.scope == scope && it.paymentId == paymentId })
                (scope to paymentId) in gone -> CashPaymentLookup.Missing
                else -> CashPaymentLookup.Loading
            }
        }

    override fun observeLocalSignatures(scope: CashPaymentScope): Flow<List<LocalCashSignature>> = local.map { list -> list.filter { it.scope == scope } }

    override suspend fun refresh(scope: CashPaymentScope, state: CashPaymentState): RefreshResult {
        refreshed += scope to state
        return refreshResult
    }

    override suspend fun loadMore(scope: CashPaymentScope, state: CashPaymentState): RefreshResult = loadMoreResult

    override suspend fun refreshPayment(scope: CashPaymentScope, paymentId: String): RefreshResult {
        refreshedPayments += scope to paymentId
        return paymentRefreshResult
    }

    override suspend fun sign(scope: CashPaymentScope, paymentId: String, formHash: String, signature: DrawnSignature): CashSignResult {
        signed += Signed(scope, paymentId, formHash, signature)
        signGate?.await()
        return signResult(paymentId)
    }

    override suspend fun sendPending(): Boolean = true

    override suspend fun openReceipt(scope: CashPaymentScope, paymentId: String): DocumentContentResult {
        openedReceipts += scope to paymentId
        return receiptResult
    }

    fun list(scope: CashPaymentScope, state: CashPaymentState, vararg payments: CashPayment, hasMore: Boolean = false) =
        lists.update { it + ((scope to state) to CashPaymentList(payments.toList(), hasMore, TEST_NOW)) }

    fun payment(scope: CashPaymentScope, payment: CashPayment) = single.update { it + ((scope to payment.id) to payment) }
}

/** A domain payment for view model and screen tests. */
fun cashPayment(
    id: String = "pay-1",
    state: CashPaymentState = CashPaymentState.APPROVED,
    kind: CashPaymentKind = CashPaymentKind.ADVANCE,
    amount: BigDecimal = BigDecimal("100.00"),
    currency: String = "EUR",
    canSign: Boolean = state == CashPaymentState.APPROVED,
    receivedAt: Instant? = null,
    receiptAvailable: Boolean = state == CashPaymentState.RECEIVED,
    formHash: String? = "F".repeat(64),
    lines: List<String> = listOf("Avans / Zaliczka", "Tutar: 100,00 EUR"),
) = CashPayment(
    id = id,
    employeeId = "emp-1",
    employeeName = "Mehmet Yıldız",
    registrationNumber = "1042",
    kind = kind,
    kindName = kind.name.lowercase(),
    amount = amount,
    currency = currency,
    plannedPaymentDate = LocalDate.of(2026, 10, 30),
    state = state,
    approvedAt = Instant.parse("2026-10-27T09:15:00Z"),
    receivedAt = receivedAt,
    title = "Avans ödeme formu",
    lines = lines,
    declaration = "Yukarıda yazılı tutarı nakit olarak eksiksiz teslim aldım.",
    approvedByName = "Finans Müdürü",
    formHash = formHash,
    canSign = canSign,
    receiptAvailable = receiptAvailable,
)

/**
 * Mobile API cash payment bodies in the docs/28 contract shape. Names, numbers and amounts are invented samples for
 * tests, not production data.
 */
object CashPaymentJson {
    const val FORM_HASH = "9F1C4A0B2E6D7C8F0A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F6071"

    fun payment(
        id: String,
        state: String = "Approved",
        kind: String = "advance",
        amount: String = "100",
        currency: String = "EUR",
        plannedPaymentDate: String = "2026-10-30",
        receivedAtUtc: String? = null,
        canSign: Boolean = state == "Approved",
        receiptUrl: String? = if (state == "Received") "/api/mobile/v1/cash-payments/$id/receipt" else null,
        formHash: String = FORM_HASH,
        lines: String = """["Avans / Zaliczka","Tutar: 100,00 EUR"]""",
        employeeName: String = "Mehmet Yıldız",
    ) = """{"id":"$id","employeeId":"emp-1","employeeName":"$employeeName","registrationNumber":"1042","kind":"$kind",""" +
        """"amount":$amount,"currency":"$currency","plannedPaymentDate":"$plannedPaymentDate","state":"$state",""" +
        """"approvedAtUtc":"2026-10-27T09:15:00Z","receivedAtUtc":${q(receivedAtUtc)},"title":"Avans ödeme formu","lines":$lines,""" +
        """"declaration":"Yukarıda yazılı tutarı nakit olarak eksiksiz teslim aldım.","approvedByName":"Finans Müdürü",""" +
        """"formHash":"$formHash","canSign":$canSign,"receiptUrl":${q(receiptUrl)}}"""

    fun received(id: String) = payment(
        id,
        state = "Received",
        receivedAtUtc = "2026-10-28T10:05:00Z",
        canSign = false,
    )

    fun page(items: List<String>, page: Int = 1, pageSize: Int = 50, hasMore: Boolean = false) =
        """{"items":[${items.joinToString(",")}],"page":$page,"pageSize":$pageSize,"hasMore":$hasMore}"""

    private fun q(value: String?) = value?.let { "\"$it\"" } ?: "null"
}
