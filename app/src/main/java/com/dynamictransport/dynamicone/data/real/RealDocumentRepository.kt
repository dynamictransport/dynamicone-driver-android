package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.AppDirectories
import com.dynamictransport.dynamicone.core.common.IoDispatcher
import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.DriverErrorCodes
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DocumentSlot
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.TripDocument
import com.dynamictransport.dynamicone.domain.repository.DocumentRepository
import com.dynamictransport.dynamicone.domain.rules.DocumentRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trip evidence through the Driver API: the backend's slot checklist, uploads against a slot and the file list.
 * Requirements are never recalculated on the device. An upload is confirmed by the server before success is
 * shown and is then "pending review", never "approved". Uploads have no idempotency key, so an unknown
 * outcome reads the checklist again instead of uploading a second revision.
 */
@Singleton
class RealDocumentRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
    private val trips: RealTripRepository,
    private val downloader: ProtectedFileDownloader,
    private val directories: AppDirectories,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : DocumentRepository {

    private val slots = MutableStateFlow<Map<String, List<DocumentSlot>>>(emptyMap())
    private val files = MutableStateFlow<Map<String, List<TripDocument>>>(emptyMap())
    private val uploadMutex = Mutex()
    private val preparer by lazy { DocumentUploadPreparer(File(directories.cacheDir, UPLOAD_DIRECTORY)) }

    override fun observeSlots(tripId: String): Flow<List<DocumentSlot>?> = slots.map { it[tripId] }.distinctUntilChanged()

    override fun observeFiles(tripId: String): Flow<List<TripDocument>> = files.map { it[tripId].orEmpty() }.distinctUntilChanged()

    override suspend fun refreshDocuments(tripId: String): RefreshResult = try {
        val requirements = tracker.call { api.documentRequirements(tripId) }.mapNotNull(DriverApiMapping::slot)
        val loaded = mutableListOf<TripDocument>()
        var page = 1
        do {
            val response = tracker.call { api.documents(tripId, page, PAGE_SIZE) }
            response.items.orEmpty().mapTo(loaded) { DriverApiMapping.document(tripId, it) }
            page++
        } while (response.hasMore && page <= MAX_PAGES)
        slots.update { it + (tripId to requirements) }
        files.update { it + (tripId to loaded) }
        tracker.markReadSucceeded()
        RefreshResult.SUCCESS
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        e.toRefreshResult()
    }

    override suspend fun upload(tripId: String, slot: DocumentSlot, localFilePath: String): ActionResult {
        val source = File(localFilePath)
        return try {
            val trip = trips.currentTrips().firstOrNull { it.id == tripId }
                ?: return ActionResult.Rejected(ActionRejection.NOT_FOUND)
            if (!DocumentRules.canUpload(trip)) return ActionResult.Rejected(ActionRejection.TRIP_REQUIRED)
            uploadMutex.withLock {
                when (val prepared = withContext(io) { preparer.prepare(source) }) {
                    is PreparedUpload.Invalid -> ActionResult.Rejected(prepared.reason)
                    is PreparedUpload.Ready -> send(tripId, slot, prepared)
                }
            }
        } finally {
            // Real mode keeps no local queue, so the captured file is not needed after the attempt.
            withContext(NonCancellable + io) { deleteCapturedMedia(source) }
        }
    }

    override suspend fun openDocument(tripId: String, documentId: String, contentTypeHint: String?): DocumentContentResult =
        downloader.download("document-$documentId", contentTypeHint) { api.documentContent(tripId, documentId) }

    fun clear() {
        slots.value = emptyMap()
        files.value = emptyMap()
    }

    private suspend fun send(tripId: String, slot: DocumentSlot, prepared: PreparedUpload.Ready): ActionResult {
        try {
            if (slot.type?.photoOnly == true && prepared.contentType !in DocumentRules.PHOTO_CONTENT_TYPES) {
                return ActionResult.Rejected(ActionRejection.PHOTO_REQUIRED)
            }
            val document = MultipartBody.Part.createFormData(
                DOCUMENT_FIELD,
                prepared.fileName,
                prepared.file.asRequestBody(prepared.contentType.toMediaType()),
            )
            val response = try {
                tracker.call { api.uploadSlotDocument(tripId, slot.key.toRequestBody(TEXT_PLAIN), document) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteException) {
                // Unknown outcome or a changed checklist: show the server's current state, never upload again automatically.
                if (e.isOutcomeUnknown() || e.problem?.code == DriverErrorCodes.INVALID_SLOT) refreshDocuments(tripId)
                return ActionResult.Rejected(e.toActionRejection())
            }
            refreshDocuments(tripId)
            // The trip screen shows the checklist of the execution plan.
            trips.refreshTrip(tripId)
            return if (response.succeeded) ActionResult.Queued else ActionResult.Rejected(ActionRejection.FAILED)
        } finally {
            if (prepared.temporary) withContext(NonCancellable + io) { prepared.file.delete() }
        }
    }

    private fun deleteCapturedMedia(file: File) {
        val mediaDirectory = File(directories.filesDir, MEDIA_DIRECTORY)
        if (file.parentFile?.canonicalPath == mediaDirectory.canonicalPath) file.delete()
    }

    private companion object {
        const val PAGE_SIZE = 25
        const val MAX_PAGES = 8

        /** Multipart field names of `POST trips/{tripId}/document-slots`. */
        const val DOCUMENT_FIELD = "document"
        const val UPLOAD_DIRECTORY = "document_upload"

        /** Private photo directory of FileDocumentMediaStore. */
        const val MEDIA_DIRECTORY = "document_media"
        val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
    }
}
