package com.dynamictransport.dynamicone.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.dynamictransport.dynamicone.domain.model.CashSignFailure
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * A recipient's signature for a cash hand-over, written before it is sent (docs/28); the command id is the idempotency
 * key, so a lost answer or a second tap never creates a second payment. A signature the server confirmed is deleted
 * at once: the drawn strokes stay on the phone no longer than needed.
 */
@Entity(
    tableName = "cash_signatures",
    indices = [Index(value = ["userId", "state"]), Index(value = ["userId", "paymentId"])],
)
data class CashSignatureEntity(
    @PrimaryKey val commandId: String,
    /** The signed-in account that sends it (the officer or the recipient). */
    val userId: String,
    /** [com.dynamictransport.dynamicone.domain.model.CashPaymentScope] name: decides the endpoint. */
    val scope: String,
    val paymentId: String,
    /** The approved form's hash as read before signing. */
    val formHash: String,
    /** The strokes in the contract's `strokes` JSON shape. */
    val strokesJson: String,
    val aspectRatio: Float,
    /** Phone clock at the signature. */
    val signedAt: Instant,
    val state: LocalSignatureState,
    val failure: CashSignFailure?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Dao
interface CashPaymentDao {
    @Query("SELECT * FROM cash_signatures WHERE userId = :userId ORDER BY signedAt, commandId")
    fun observeSignatures(userId: String): Flow<List<CashSignatureEntity>>

    @Query("SELECT * FROM cash_signatures WHERE commandId = :commandId")
    suspend fun getSignature(commandId: String): CashSignatureEntity?

    /** The waiting signature of a payment; `SENDING` only remains after an interrupted send. */
    @Query(
        "SELECT * FROM cash_signatures WHERE userId = :userId AND paymentId = :paymentId AND state IN ('PENDING', 'SENDING') " +
            "ORDER BY signedAt LIMIT 1",
    )
    suspend fun getWaitingSignature(userId: String, paymentId: String): CashSignatureEntity?

    /** Waiting signatures, oldest first. */
    @Query("SELECT * FROM cash_signatures WHERE userId = :userId AND state IN ('PENDING', 'SENDING') ORDER BY signedAt, commandId")
    suspend fun getUnsent(userId: String): List<CashSignatureEntity>

    @Insert
    suspend fun insertSignature(signature: CashSignatureEntity)

    @Query("UPDATE cash_signatures SET state = :state, failure = :failure, updatedAt = :updatedAt WHERE commandId = :commandId")
    suspend fun updateSignatureState(commandId: String, state: LocalSignatureState, failure: CashSignFailure?, updatedAt: Instant)

    /** Rejected signatures of a payment, replaced by a new one. */
    @Query("DELETE FROM cash_signatures WHERE userId = :userId AND paymentId = :paymentId AND state = 'FAILED'")
    suspend fun deleteFailedSignatures(userId: String, paymentId: String)

    /** Everything of a payment that no longer needs a signature (received, cancelled, gone). */
    @Query("DELETE FROM cash_signatures WHERE userId = :userId AND paymentId = :paymentId")
    suspend fun deleteSignaturesOf(userId: String, paymentId: String)

    @Query("DELETE FROM cash_signatures WHERE userId != :userId")
    suspend fun deleteSignaturesForOtherUsers(userId: String)

    /** Signing out: the session's sensitive data leaves the phone. */
    @Query("DELETE FROM cash_signatures")
    suspend fun deleteAllSignatures()
}
