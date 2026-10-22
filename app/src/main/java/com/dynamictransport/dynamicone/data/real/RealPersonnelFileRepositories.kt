package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.network.DocumentRequirementDto
import com.dynamictransport.dynamicone.core.network.DocumentRequirementsDto
import com.dynamictransport.dynamicone.core.network.MobileApi
import com.dynamictransport.dynamicone.core.network.PersonnelDocumentDto
import com.dynamictransport.dynamicone.core.network.PersonnelFileWireNames
import com.dynamictransport.dynamicone.core.network.VehicleDocumentDto
import com.dynamictransport.dynamicone.core.network.VehicleDocumentsResponseDto
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DocumentRequirement
import com.dynamictransport.dynamicone.domain.model.DocumentRequirements
import com.dynamictransport.dynamicone.domain.model.PersonnelDocument
import com.dynamictransport.dynamicone.domain.model.PersonnelDocumentHistory
import com.dynamictransport.dynamicone.domain.model.PersonnelDocumentType
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.RequirementStatus
import com.dynamictransport.dynamicone.domain.model.VehicleDocument
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentRequirement
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentType
import com.dynamictransport.dynamicone.domain.model.VehicleDocuments
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentsSnapshot
import com.dynamictransport.dynamicone.domain.model.VehicleRequirementStatus
import com.dynamictransport.dynamicone.domain.repository.MyDocumentRepository
import com.dynamictransport.dynamicone.domain.repository.VehicleDocumentRepository
import com.dynamictransport.dynamicone.domain.repository.ViewedFileCleaner
import com.dynamictransport.dynamicone.domain.rules.PersonnelFileRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account's own personnel file (Mobile API `me/documents`). Everything is kept in memory for the signed-in account
 * only: gone after a restart, on sign-out and when another account signs in. Files are never stored for offline use.
 */
@Singleton
class RealMyDocumentRepository @Inject constructor(
    private val api: MobileApi,
    private val tracker: ApiActivityTracker,
    private val downloader: ProtectedFileDownloader,
    private val sessionPreferences: SessionPreferences,
) : MyDocumentRepository {

    private data class History(val userId: String, val documents: List<PersonnelDocument>, val canLoadMore: Boolean, val nextPage: Int)

    private data class Requirements(val userId: String, val requirements: DocumentRequirements)

    private val history = MutableStateFlow<History?>(null)
    private val requirements = MutableStateFlow<Requirements?>(null)
    private val historyMutex = Mutex()
    private val requirementsMutex = Mutex()

    override fun observeHistory(): Flow<PersonnelDocumentHistory?> =
        combine(sessionPreferences.userIdChanges, history) { userId, current ->
            current?.takeIf { userId != null && it.userId == userId }?.let { PersonnelDocumentHistory(it.documents, it.canLoadMore) }
        }.distinctUntilChanged()

    override fun observeRequirements(): Flow<DocumentRequirements?> =
        combine(sessionPreferences.userIdChanges, requirements) { userId, current ->
            current?.takeIf { userId != null && it.userId == userId }?.requirements
        }.distinctUntilChanged()

    override suspend fun refreshHistory(): RefreshResult = historyMutex.withLock {
        val userId = sessionPreferences.userId() ?: return@withLock RefreshResult.SESSION_EXPIRED
        var page: History? = null
        val result = driverRead(tracker) {
            val response = tracker.call { api.myDocuments(1, PAGE_SIZE) }
            page = History(userId, response.items.orEmpty().mapNotNull(PersonnelFileMapping::document), response.hasMore, nextPage = 2)
        }
        if (sessionPreferences.userId() == userId) page?.let { history.value = it }
        result
    }

    override suspend fun loadMoreHistory(): RefreshResult = historyMutex.withLock {
        val userId = sessionPreferences.userId() ?: return@withLock RefreshResult.SESSION_EXPIRED
        val current = history.value?.takeIf { it.userId == userId && it.canLoadMore } ?: return@withLock RefreshResult.SUCCESS
        var next: History? = null
        val result = driverRead(tracker) {
            val response = tracker.call { api.myDocuments(current.nextPage, PAGE_SIZE) }
            val added = response.items.orEmpty().mapNotNull(PersonnelFileMapping::document)
            next = current.copy(
                documents = (current.documents + added).distinctBy { it.id },
                canLoadMore = response.hasMore,
                nextPage = current.nextPage + 1,
            )
        }
        if (sessionPreferences.userId() == userId) next?.let { history.value = it }
        result
    }

    override suspend fun refreshRequirements(): RefreshResult = requirementsMutex.withLock {
        val userId = sessionPreferences.userId() ?: return@withLock RefreshResult.SESSION_EXPIRED
        var answer: DocumentRequirements? = null
        val result = driverRead(tracker) {
            answer = PersonnelFileMapping.requirements(tracker.call { api.myDocumentRequirements() })
        }
        if (sessionPreferences.userId() == userId) answer?.let { requirements.value = Requirements(userId, it) }
        result
    }

    override suspend fun open(documentId: String, contentTypeHint: String?): DocumentContentResult =
        downloader.download("personnel-$documentId", contentTypeHint, ProtectedFileDownloader.PRIVATE_VIEW_DIRECTORY) {
            api.myDocumentContent(documentId)
        }

    fun clear() {
        history.value = null
        requirements.value = null
    }

    companion object {
        /** The contract allows 1–100. */
        const val PAGE_SIZE = 50
    }
}

/**
 * Files of the driver's assigned vehicle (Mobile API `me/assigned-vehicle/documents`). The server resolves the vehicle
 * on every read; the app never picks a vehicle or employee. Held in memory for the signed-in account only.
 */
@Singleton
class RealVehicleDocumentRepository @Inject constructor(
    private val api: MobileApi,
    private val tracker: ApiActivityTracker,
    private val downloader: ProtectedFileDownloader,
    private val sessionPreferences: SessionPreferences,
) : VehicleDocumentRepository {

    private data class Entry(val userId: String, val snapshot: VehicleDocumentsSnapshot)

    private val entry = MutableStateFlow<Entry?>(null)
    private val reading = Mutex()

    override fun observe(): Flow<VehicleDocumentsSnapshot?> =
        combine(sessionPreferences.userIdChanges, sessionPreferences.assignedVehicleChanges, entry) { userId, assignment, current ->
            current?.takeIf { userId != null && it.userId == userId }?.snapshot
                // After a failed read the previous vehicle's files disappear as soon as `me` names another vehicle.
                ?.takeIf { PersonnelFileRules.isShowable(it, assignment) }
        }.distinctUntilChanged()

    override suspend fun refresh(): RefreshResult {
        val userId = sessionPreferences.userId() ?: return RefreshResult.SESSION_EXPIRED
        if (!reading.tryLock()) return RefreshResult.SUCCESS
        try {
            var answer: VehicleDocuments? = null
            val result = driverRead(tracker) {
                answer = PersonnelFileMapping.vehicleDocuments(tracker.call { api.assignedVehicleDocuments() })
            }
            // An answer that arrives after another account signed in belongs to nobody on screen.
            if (sessionPreferences.userId() != userId) return result
            val fresh = answer
            if (fresh != null) {
                entry.value = Entry(userId, VehicleDocumentsSnapshot(fresh, stale = false))
            } else {
                val previous = entry.value?.takeIf { it.userId == userId }
                entry.value = previous?.copy(snapshot = previous.snapshot.copy(stale = true))
            }
            return result
        } finally {
            reading.unlock()
        }
    }

    override suspend fun open(documentId: String, contentTypeHint: String?): DocumentContentResult =
        downloader.download("vehicle-$documentId", contentTypeHint, ProtectedFileDownloader.PRIVATE_VIEW_DIRECTORY) {
            api.assignedVehicleDocumentContent(documentId)
        }

    fun clear() {
        entry.value = null
    }
}

/** Deletes a file of the in-app viewer as soon as it is closed. */
class DownloadedFileCleaner @Inject constructor(private val downloader: ProtectedFileDownloader) : ViewedFileCleaner {
    override fun discard(filePath: String) = downloader.discard(filePath)
}

object PersonnelFileMapping {

    /** Null without an id: such an item could never be opened. */
    fun document(dto: PersonnelDocumentDto): PersonnelDocument? {
        val id = dto.id?.takeIf(String::isNotBlank) ?: return null
        return PersonnelDocument(
            id = id,
            type = PersonnelDocumentType.fromWire(dto.type),
            displayName = dto.displayName?.trim()?.takeIf(String::isNotEmpty),
            fileName = dto.fileName?.takeIf(String::isNotBlank),
            contentType = dto.contentType?.takeIf(String::isNotBlank),
            sizeBytes = dto.fileSizeBytes?.takeIf { it >= 0 },
            issuedOn = date(dto.issuedOn),
            validFrom = date(dto.validFrom),
            expiresOn = date(dto.expiresOn),
            isCurrent = dto.isCurrent == true,
            uploadedAt = dto.uploadedAtUtc?.toInstantOrNull(),
        )
    }

    /** The counts are the server's; the app does not recount them. */
    fun requirements(dto: DocumentRequirementsDto) = DocumentRequirements(
        items = dto.items.orEmpty().map(::requirement),
        validCount = dto.validCount?.coerceAtLeast(0) ?: 0,
        expiringSoonCount = dto.expiringSoonCount?.coerceAtLeast(0) ?: 0,
        expiredCount = dto.expiredCount?.coerceAtLeast(0) ?: 0,
        missingOrIncompleteCount = dto.missingOrIncompleteCount?.coerceAtLeast(0) ?: 0,
    )

    fun requirement(dto: DocumentRequirementDto) = DocumentRequirement(
        type = PersonnelDocumentType.fromWire(dto.type),
        status = when (dto.status) {
            PersonnelFileWireNames.STATUS_MISSING -> RequirementStatus.MISSING
            PersonnelFileWireNames.STATUS_INCOMPLETE -> RequirementStatus.INCOMPLETE
            PersonnelFileWireNames.STATUS_EXPIRED -> RequirementStatus.EXPIRED
            PersonnelFileWireNames.STATUS_EXPIRING_SOON -> RequirementStatus.EXPIRING_SOON
            PersonnelFileWireNames.STATUS_VALID -> RequirementStatus.VALID
            else -> RequirementStatus.UNKNOWN
        },
        documentId = dto.documentId?.takeIf(String::isNotBlank),
        expiresOn = date(dto.expiresOn),
    )

    fun vehicleDocuments(dto: VehicleDocumentsResponseDto): VehicleDocuments {
        val vehicle = AccountMapping.assignedVehicle(dto.assignedVehicle)
        // Without a vehicle there is nothing to show, whatever the lists say.
        if (vehicle == null) return VehicleDocuments(null, emptyList(), emptyList())
        return VehicleDocuments(
            vehicle = vehicle,
            documents = dto.items.orEmpty().mapNotNull(::vehicleDocument),
            requirements = dto.requirements.orEmpty().map { requirement ->
                VehicleDocumentRequirement(
                    type = VehicleDocumentType.fromWire(requirement.type),
                    status = when (requirement.status) {
                        PersonnelFileWireNames.STATUS_AVAILABLE -> VehicleRequirementStatus.AVAILABLE
                        PersonnelFileWireNames.STATUS_MISSING -> VehicleRequirementStatus.MISSING
                        else -> VehicleRequirementStatus.UNKNOWN
                    },
                    documentId = requirement.documentId?.takeIf(String::isNotBlank),
                )
            },
        )
    }

    private fun vehicleDocument(dto: VehicleDocumentDto): VehicleDocument? {
        val id = dto.id?.takeIf(String::isNotBlank) ?: return null
        return VehicleDocument(
            id = id,
            type = VehicleDocumentType.fromWire(dto.type),
            fileName = dto.fileName?.takeIf(String::isNotBlank),
            contentType = dto.contentType?.takeIf(String::isNotBlank),
            sizeBytes = dto.fileSizeBytes?.takeIf { it >= 0 },
            uploadedAt = dto.uploadedAtUtc?.toInstantOrNull(),
        )
    }

    /** `yyyy-MM-dd` as sent; anything else is treated as no date rather than guessed. */
    fun date(value: String?): LocalDate? = value?.takeIf(String::isNotBlank)?.let {
        try {
            LocalDate.parse(it.trim())
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
