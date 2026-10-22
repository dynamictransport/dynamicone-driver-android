package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.DeviceUptime
import com.dynamictransport.dynamicone.core.common.IdGenerator
import com.dynamictransport.dynamicone.core.database.HrDocumentCacheEntity
import com.dynamictransport.dynamicone.core.database.HrDocumentDao
import com.dynamictransport.dynamicone.core.database.HrSignatureEntity
import com.dynamictransport.dynamicone.core.network.DriverErrorCodes
import com.dynamictransport.dynamicone.core.network.HrDocumentDto
import com.dynamictransport.dynamicone.core.network.HrDocumentErrorCodes
import com.dynamictransport.dynamicone.core.network.HrDocumentWireNames
import com.dynamictransport.dynamicone.core.network.HrSignatureDrawingDto
import com.dynamictransport.dynamicone.core.network.HrSignatureRequestDto
import com.dynamictransport.dynamicone.core.network.MobileApi
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.core.sync.HrDocumentSyncScheduler
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DrawnSignature
import com.dynamictransport.dynamicone.domain.model.HrContentProblem
import com.dynamictransport.dynamicone.domain.model.HrContentResult
import com.dynamictransport.dynamicone.domain.model.HrDocument
import com.dynamictransport.dynamicone.domain.model.HrDocumentLists
import com.dynamictransport.dynamicone.domain.model.HrDocumentLookup
import com.dynamictransport.dynamicone.domain.model.HrDocumentStatus
import com.dynamictransport.dynamicone.domain.model.HrSignResult
import com.dynamictransport.dynamicone.domain.model.LocalSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SignatureFailure
import com.dynamictransport.dynamicone.domain.repository.HrDocumentRepository
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personnel documents to sign against the Mobile API (docs/17_HR_DOCUMENT_SIGNING.md). A signature is written to the
 * phone with a new command id before it is sent; when the server does not take it, it waits and is sent again
 * automatically with the same command id (the server is idempotent). Only 400 and 409 are final answers, and a
 * signature the server has not confirmed is never shown as signed. The pending list is kept on the phone so it can be
 * read without network; PDFs are kept while they still need a signature.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RealHrDocumentRepository @Inject constructor(
    private val api: MobileApi,
    private val tracker: ApiActivityTracker,
    private val dao: HrDocumentDao,
    private val files: HrDocumentFileStore,
    private val downloader: ProtectedFileDownloader,
    private val sessionPreferences: SessionPreferences,
    private val scheduler: HrDocumentSyncScheduler,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val uptime: DeviceUptime,
    private val json: Json,
) : HrDocumentRepository, HrDocumentSessionData {

    private val pending = MutableStateFlow<List<HrDocument>?>(null)
    private val pendingUpdatedAt = MutableStateFlow<Instant?>(null)
    private val signed = MutableStateFlow<List<HrDocument>?>(null)
    private val canLoadMore = MutableStateFlow(false)

    /** Documents read one at a time (a notification, a withdrawn one) that may be in neither list. */
    private val single = MutableStateFlow<Map<String, HrDocument>>(emptyMap())

    /** Documents the server says this account does not have (any more). */
    private val missing = MutableStateFlow<Set<String>>(emptySet())

    /** The pending list as the server sent it, stored on the phone. */
    private val pendingDtos = MutableStateFlow<List<HrDocumentDto>>(emptyList())
    private val readMutex = Mutex()
    private val sendMutex = Mutex()

    @Volatile
    private var nextSignedPage = 2

    @Volatile
    private var cacheLoadedFor: String? = null

    private val localSignatures: Flow<List<LocalSignature>> = sessionPreferences.userIdChanges.flatMapLatest { userId ->
        if (userId == null) flowOf(emptyList()) else dao.observeSignatures(userId).map { rows -> rows.map(::toLocal) }
    }

    override fun observeLists(): Flow<HrDocumentLists> =
        combine(pending, signed, canLoadMore, localSignatures, pendingUpdatedAt) { pendingList, signedList, more, local, updatedAt ->
            HrDocumentLists(pendingList, signedList, more, local, updatedAt)
        }.onStart { loadCache() }

    override fun observeDocument(documentId: String): Flow<HrDocumentLookup> =
        combine(observeLists(), single, missing) { lists, one, gone ->
            val document = lists.pending?.firstOrNull { it.id == documentId }
                ?: lists.signed?.firstOrNull { it.id == documentId }
                ?: one[documentId]
            when {
                document != null -> HrDocumentLookup.Found(document, lists.localFor(documentId))
                documentId in gone -> HrDocumentLookup.Missing
                else -> HrDocumentLookup.Loading
            }
        }.distinctUntilChanged()

    override suspend fun refresh(): RefreshResult = readMutex.withLock {
        // Safety net: a signature still waiting on the phone is handed to the background send whenever the list is read.
        sessionPreferences.userId()?.let { userId -> if (dao.getUnsent(userId).isNotEmpty()) scheduler.requestSend() }
        read {
            val pendingItems = readAllPending()
            val signedPage = tracker.call { api.hrDocuments(HrDocumentWireNames.STATE_SIGNED, 1, PAGE_SIZE) }
            applyPending(pendingItems)
            signed.value = signedPage.items.orEmpty().mapNotNull(HrDocumentMapping::document)
            nextSignedPage = 2
            canLoadMore.value = signedPage.hasMore
            tracker.markReadSucceeded()
        }
    }

    override suspend fun loadMoreSigned(): RefreshResult = readMutex.withLock {
        if (!canLoadMore.value) return@withLock RefreshResult.SUCCESS
        read {
            val page = nextSignedPage
            val response = tracker.call { api.hrDocuments(HrDocumentWireNames.STATE_SIGNED, page, PAGE_SIZE) }
            val more = response.items.orEmpty().mapNotNull(HrDocumentMapping::document)
            signed.update { (it.orEmpty() + more).distinctBy { document -> document.id } }
            nextSignedPage = page + 1
            canLoadMore.value = response.hasMore
            tracker.markReadSucceeded()
        }
    }

    override suspend fun refreshDocument(documentId: String): RefreshResult = read {
        val dto = try {
            tracker.call { api.hrDocument(documentId) }
        } catch (e: RemoteException.NotFound) {
            if (e.problem?.code != HrDocumentErrorCodes.NOT_FOUND) throw e
            forget(documentId)
            tracker.markReadSucceeded()
            return@read
        }
        val document = HrDocumentMapping.document(dto) ?: throw RemoteException.InvalidResponse()
        apply(document, dto)
        tracker.markReadSucceeded()
    }

    override suspend fun content(documentId: String, languageCode: String): HrContentResult {
        val language = find(documentId)?.language(languageCode) ?: return HrContentResult.Unavailable(HrContentProblem.NOT_FOUND)
        files.existing(language.contentSha256)?.let { return HrContentResult.Available(it.path, language.code, language.contentSha256) }
        if (!files.hasSpaceFor(language.sizeBytes)) return HrContentResult.Unavailable(HrContentProblem.LOW_STORAGE)
        return try {
            val body = tracker.call { api.hrDocumentContent(documentId, language.code) }
            val file = files.store(body, language.contentSha256)
                ?: return HrContentResult.Unavailable(HrContentProblem.INTEGRITY)
            tracker.markReadSucceeded()
            HrContentResult.Available(file.path, language.code, language.contentSha256)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            val problem = HrDocumentMapping.contentProblem(e)
            when (problem) {
                HrContentProblem.WITHDRAWN -> withdrawn(documentId)
                HrContentProblem.NOT_FOUND -> forget(documentId)
                else -> Unit
            }
            HrContentResult.Unavailable(problem)
        } catch (_: IOException) {
            // The connection broke during the download, or the phone ran out of room while writing.
            HrContentResult.Unavailable(if (files.hasSpaceFor(language.sizeBytes)) HrContentProblem.OFFLINE else HrContentProblem.LOW_STORAGE)
        }
    }

    /** Called only after the employee accepted the declaration: the request always confirms it. */
    override suspend fun sign(documentId: String, languageCode: String, contentSha256: String, signature: DrawnSignature): HrSignResult {
        val userId = sessionPreferences.userId() ?: return HrSignResult.NotLoggedIn
        if (!HrDocumentRules.isValid(signature)) return HrSignResult.Incomplete
        return sendMutex.withLock {
            // A second tap or a second screen: the signature already waiting on the phone is the one that is sent.
            val entity = dao.getWaitingSignature(userId, documentId) ?: newSignature(userId, documentId, languageCode, contentSha256, signature).also {
                // Stored before anything is sent: a lost response or a closed app cannot lose the signature.
                dao.deleteFailedSignatures(userId, documentId)
                dao.insertSignature(it)
            }
            send(entity, scheduleRetry = true)
        }
    }

    override suspend fun sendPending(): Boolean = sendMutex.withLock {
        // Without the account's session nothing can be sent; signing in again starts the next attempt.
        val userId = sessionPreferences.userId() ?: return@withLock true
        for (signature in dao.getUnsent(userId)) {
            // The server is not reachable: stop here, the worker tries again with backoff.
            if (send(signature, scheduleRetry = false) is HrSignResult.Saved) return@withLock false
        }
        true
    }

    override suspend fun openReceipt(documentId: String): DocumentContentResult =
        downloader.download("hr-receipt-$documentId", PDF, ProtectedFileDownloader.PRIVATE_VIEW_DIRECTORY) { api.hrDocumentReceipt(documentId) }

    /** Server data held in memory, the stored list and the PDFs. Signatures waiting on the phone are kept ([keepOnly]). */
    override suspend fun clear() {
        pending.value = null
        pendingUpdatedAt.value = null
        signed.value = null
        canLoadMore.value = false
        single.value = emptyMap()
        missing.value = emptySet()
        pendingDtos.value = emptyList()
        nextSignedPage = 2
        cacheLoadedFor = null
        dao.deleteAllCache()
        files.clear()
    }

    /** Another account signed in on this phone: earlier accounts' signatures must never be sent with this session. */
    override suspend fun keepOnly(userId: String) {
        dao.deleteSignaturesForOtherUsers(userId)
        dao.deleteCacheForOtherUsers(userId)
    }

    private suspend fun loadCache() {
        val userId = sessionPreferences.userId() ?: return
        if (cacheLoadedFor == userId) return
        cacheLoadedFor = userId
        val cache = dao.getCache(userId) ?: return
        // A server read that finished in the meantime is newer.
        if (pending.value != null) return
        val dtos = HrDocumentMapping.decodeItems(json, cache.pendingJson)
        pendingDtos.value = dtos
        pending.value = HrDocumentRules.sortPending(dtos.mapNotNull(HrDocumentMapping::document).filter { it.status == HrDocumentStatus.PENDING })
        pendingUpdatedAt.value = cache.updatedAt
    }

    private suspend fun readAllPending(): List<HrDocumentDto> {
        val items = mutableListOf<HrDocumentDto>()
        var page = 1
        while (true) {
            val response = tracker.call { api.hrDocuments(HrDocumentWireNames.STATE_PENDING, page, MAX_PAGE_SIZE) }
            items += response.items.orEmpty()
            if (!response.hasMore || page >= MAX_PENDING_PAGES) return items
            page++
        }
    }

    private suspend fun applyPending(dtos: List<HrDocumentDto>) {
        val documents = dtos.mapNotNull(HrDocumentMapping::document).filter { it.status == HrDocumentStatus.PENDING }
        val ids = documents.map { it.id }.toSet()
        pending.value = HrDocumentRules.sortPending(documents)
        missing.update { it - ids }
        storePending(dtos.filter { it.id in ids })
        files.keepOnly(documents.flatMap { document -> document.languages.map { it.contentSha256 } }.toSet())
    }

    private suspend fun storePending(dtos: List<HrDocumentDto>) {
        pendingDtos.value = dtos
        val now = clock.instant()
        pendingUpdatedAt.value = now
        val userId = sessionPreferences.userId() ?: return
        dao.upsertCache(HrDocumentCacheEntity(userId, HrDocumentMapping.encodeItems(json, dtos), now))
    }

    /** One document read from the server: it moves to the list its status belongs to. */
    private suspend fun apply(document: HrDocument, dto: HrDocumentDto?) {
        single.update { it + (document.id to document) }
        missing.update { it - document.id }
        when (document.status) {
            HrDocumentStatus.PENDING -> if (pending.value != null) {
                pending.update { list -> HrDocumentRules.sortPending(list.orEmpty().filterNot { it.id == document.id } + document) }
                if (dto != null) storePending(pendingDtos.value.filterNot { it.id == document.id } + dto)
            }
            HrDocumentStatus.SIGNED -> {
                removePending(document.id)
                signed.update { list -> list?.let { listOf(document) + it.filterNot { other -> other.id == document.id } } }
            }
            HrDocumentStatus.WITHDRAWN, HrDocumentStatus.UNKNOWN -> removePending(document.id)
        }
    }

    private suspend fun removePending(documentId: String) {
        if (pending.value?.any { it.id == documentId } != true) return
        pending.update { list -> list?.filterNot { it.id == documentId } }
        storePending(pendingDtos.value.filterNot { it.id == documentId })
    }

    /** Kept as withdrawn, so a screen showing it can say so instead of "not found". */
    private suspend fun withdrawn(documentId: String) {
        val known = find(documentId)
        removePending(documentId)
        if (known == null) {
            missing.update { it + documentId }
        } else {
            single.update { it + (documentId to known.copy(status = HrDocumentStatus.WITHDRAWN)) }
        }
    }

    private suspend fun forget(documentId: String) {
        removePending(documentId)
        single.update { it - documentId }
        missing.update { it + documentId }
    }

    private fun find(documentId: String): HrDocument? =
        pending.value?.firstOrNull { it.id == documentId } ?: signed.value?.firstOrNull { it.id == documentId } ?: single.value[documentId]

    private fun newSignature(
        userId: String,
        documentId: String,
        languageCode: String,
        contentSha256: String,
        signature: DrawnSignature,
    ): HrSignatureEntity {
        val now = clock.instant()
        return HrSignatureEntity(
            commandId = ids.newId(),
            userId = userId,
            documentId = documentId,
            languageCode = languageCode,
            contentSha256 = contentSha256,
            strokesJson = HrDocumentMapping.encodeStrokes(json, signature.strokes),
            aspectRatio = signature.aspectRatio,
            signedAt = now,
            signedElapsedMillis = uptime.elapsedRealtimeMillis(),
            bootCount = uptime.bootCount(),
            state = LocalSignatureState.PENDING,
            failure = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun send(signature: HrSignatureEntity, scheduleRetry: Boolean): HrSignResult {
        dao.updateSignatureState(signature.commandId, LocalSignatureState.SENDING, null, clock.instant())
        val request = HrSignatureRequestDto(
            commandId = signature.commandId,
            languageCode = signature.languageCode,
            contentSha256 = signature.contentSha256,
            declarationAccepted = true,
            signature = HrSignatureDrawingDto(signature.aspectRatio, HrDocumentMapping.decodeStrokes(json, signature.strokesJson)),
            signedAtUtc = signature.signedAt.toString(),
            elapsedSinceSignMs = elapsedSince(signature),
            installationId = sessionPreferences.installationId(),
        )
        val response = try {
            tracker.call { api.signHrDocument(signature.documentId, request) }
        } catch (e: CancellationException) {
            // The screen was left (or the worker stopped) while sending: the stored signature is sent in the background
            // with the same command id instead of waiting for the next app start.
            withContext(NonCancellable) {
                dao.updateSignatureState(signature.commandId, LocalSignatureState.PENDING, null, clock.instant())
                scheduler.requestSend()
            }
            throw e
        } catch (e: RemoteException) {
            return when (val outcome = HrDocumentMapping.signatureOutcome(e)) {
                is SignatureOutcome.Retry -> waitForRetry(signature, scheduleRetry, outcome.hold, outcome.delaySeconds)
                is SignatureOutcome.Rejected -> rejected(signature, outcome.failure)
                SignatureOutcome.AlreadySigned -> {
                    dao.deleteSignaturesOf(signature.userId, signature.documentId)
                    // Best effort: the signed document moves to its list; the next refresh does it otherwise.
                    refreshDocument(signature.documentId)
                    HrSignResult.AlreadySigned
                }
                SignatureOutcome.Withdrawn -> {
                    dao.deleteSignaturesOf(signature.userId, signature.documentId)
                    withdrawn(signature.documentId)
                    HrSignResult.Withdrawn
                }
            }
        }
        // A success without the contract body: resending returns the recorded signature (idempotent).
        val document = response.document?.let(HrDocumentMapping::document) ?: return waitForRetry(signature, scheduleRetry)
        // Confirmed: the drawn strokes are not kept on the phone any longer.
        dao.deleteSignaturesOf(signature.userId, signature.documentId)
        apply(document.copy(status = HrDocumentStatus.SIGNED), dto = null)
        return HrSignResult.Signed(document)
    }

    private suspend fun rejected(signature: HrSignatureEntity, failure: SignatureFailure): HrSignResult {
        if (failure == SignatureFailure.NOT_FOUND) {
            dao.deleteSignaturesOf(signature.userId, signature.documentId)
            forget(signature.documentId)
        } else {
            dao.updateSignatureState(signature.commandId, LocalSignatureState.FAILED, failure, clock.instant())
            // New content or languages: read the document again so the employee can sign what is current.
            if (failure == SignatureFailure.DOCUMENT_CHANGED || failure == SignatureFailure.LANGUAGE_UNAVAILABLE) {
                refreshDocument(signature.documentId)
            }
        }
        return HrSignResult.Failed(failure)
    }

    private suspend fun waitForRetry(
        signature: HrSignatureEntity,
        scheduleRetry: Boolean,
        hold: SignatureFailure? = null,
        delaySeconds: Long = 0L,
    ): HrSignResult {
        dao.updateSignatureState(signature.commandId, LocalSignatureState.PENDING, hold, clock.instant())
        if (scheduleRetry) scheduler.requestSend(delaySeconds)
        return HrSignResult.Saved(signature.commandId, hold)
    }

    /** Monotonic time since the signature; null after a restart, when the boot-relative clocks cannot be compared. */
    private fun elapsedSince(signature: HrSignatureEntity): Long? {
        val boot = signature.bootCount ?: return null
        if (uptime.bootCount() != boot) return null
        return (uptime.elapsedRealtimeMillis() - signature.signedElapsedMillis).takeIf { it >= 0 }
    }

    private inline fun read(block: () -> Unit): RefreshResult = try {
        block()
        RefreshResult.SUCCESS
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        when {
            // `403 hr_documents_access_required`: the account may not sign documents (any more).
            e is RemoteException.Forbidden && e.problem?.code != DriverErrorCodes.PASSWORD_CHANGE_REQUIRED -> RefreshResult.ACCESS_REQUIRED
            // The endpoints are not live on this server yet.
            e is RemoteException.NotFound && !e.isApiDisabled -> RefreshResult.NOT_SUPPORTED
            else -> e.toRefreshResult()
        }
    }

    private fun toLocal(entity: HrSignatureEntity) = LocalSignature(
        commandId = entity.commandId,
        documentId = entity.documentId,
        languageCode = entity.languageCode,
        signedAt = entity.signedAt,
        state = entity.state,
        failure = entity.failure,
    )

    private companion object {
        const val PAGE_SIZE = 25
        const val MAX_PAGE_SIZE = 100
        const val MAX_PENDING_PAGES = 5
        const val PDF = "application/pdf"
    }
}
