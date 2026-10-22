package com.dynamictransport.dynamicone.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.SignatureFailure
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Signatures of one account written before they are sent (docs/17 §5.4); the command id is the idempotency key. A
 * signature the server confirmed is deleted at once: the drawn strokes are not kept on the phone longer than needed.
 */
@Entity(
    tableName = "hr_signatures",
    indices = [Index(value = ["userId", "state"]), Index(value = ["userId", "documentId"])],
)
data class HrSignatureEntity(
    @PrimaryKey val commandId: String,
    val userId: String,
    /** Assignment id. */
    val documentId: String,
    val languageCode: String,
    /** SHA-256 of the PDF that was shown. */
    val contentSha256: String,
    /** The strokes in the contract's `signature.strokes` JSON shape. */
    val strokesJson: String,
    val aspectRatio: Float,
    /** Phone clock at the signature. */
    val signedAt: Instant,
    /** `SystemClock.elapsedRealtime()` at the signature. */
    val signedElapsedMillis: Long,
    /** Boot count at the signature; the elapsed time is only comparable within the same boot. */
    val bootCount: Int?,
    val state: LocalSignatureState,
    val failure: SignatureFailure?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** The last pending document list of an account, so it can be shown without network. */
@Entity(tableName = "hr_document_cache")
data class HrDocumentCacheEntity(
    @PrimaryKey val userId: String,
    /** The server's `HrDocument` items as JSON. */
    val pendingJson: String,
    val updatedAt: Instant,
)

@Dao
interface HrDocumentDao {
    @Query("SELECT * FROM hr_signatures WHERE userId = :userId ORDER BY signedAt, commandId")
    fun observeSignatures(userId: String): Flow<List<HrSignatureEntity>>

    @Query("SELECT * FROM hr_signatures WHERE commandId = :commandId")
    suspend fun getSignature(commandId: String): HrSignatureEntity?

    /** The waiting signature of a document; `SENDING` only remains after an interrupted send. */
    @Query(
        "SELECT * FROM hr_signatures WHERE userId = :userId AND documentId = :documentId AND state IN ('PENDING', 'SENDING') " +
            "ORDER BY signedAt LIMIT 1",
    )
    suspend fun getWaitingSignature(userId: String, documentId: String): HrSignatureEntity?

    /** Waiting signatures, oldest first. */
    @Query("SELECT * FROM hr_signatures WHERE userId = :userId AND state IN ('PENDING', 'SENDING') ORDER BY signedAt, commandId")
    suspend fun getUnsent(userId: String): List<HrSignatureEntity>

    @Insert
    suspend fun insertSignature(signature: HrSignatureEntity)

    @Query("UPDATE hr_signatures SET state = :state, failure = :failure, updatedAt = :updatedAt WHERE commandId = :commandId")
    suspend fun updateSignatureState(commandId: String, state: LocalSignatureState, failure: SignatureFailure?, updatedAt: Instant)

    @Query("DELETE FROM hr_signatures WHERE commandId = :commandId")
    suspend fun deleteSignature(commandId: String)

    /** Rejected signatures of a document, replaced by a new one. */
    @Query("DELETE FROM hr_signatures WHERE userId = :userId AND documentId = :documentId AND state = 'FAILED'")
    suspend fun deleteFailedSignatures(userId: String, documentId: String)

    /** Everything of a document that no longer needs a signature (signed elsewhere, withdrawn). */
    @Query("DELETE FROM hr_signatures WHERE userId = :userId AND documentId = :documentId")
    suspend fun deleteSignaturesOf(userId: String, documentId: String)

    @Query("DELETE FROM hr_signatures WHERE userId != :userId")
    suspend fun deleteSignaturesForOtherUsers(userId: String)

    @Query("SELECT * FROM hr_document_cache WHERE userId = :userId")
    suspend fun getCache(userId: String): HrDocumentCacheEntity?

    @Upsert
    suspend fun upsertCache(cache: HrDocumentCacheEntity)

    @Query("DELETE FROM hr_document_cache WHERE userId != :userId")
    suspend fun deleteCacheForOtherUsers(userId: String)

    @Query("DELETE FROM hr_document_cache")
    suspend fun deleteAllCache()
}
