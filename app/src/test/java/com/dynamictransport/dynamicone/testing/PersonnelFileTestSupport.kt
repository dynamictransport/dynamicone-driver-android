package com.dynamictransport.dynamicone.testing

import androidx.compose.ui.graphics.ImageBitmap
import com.dynamictransport.dynamicone.core.media.DocumentImageDecoder
import com.dynamictransport.dynamicone.domain.model.AssignedVehicle
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate

fun personnelDocument(
    id: String,
    type: PersonnelDocumentType = PersonnelDocumentType.PASSPORT,
    expiresOn: LocalDate? = null,
    isCurrent: Boolean = true,
    displayName: String? = null,
    contentType: String? = "application/pdf",
) = PersonnelDocument(
    id = id,
    type = type,
    displayName = displayName,
    fileName = "$id.pdf",
    contentType = contentType,
    sizeBytes = 2048,
    issuedOn = null,
    validFrom = null,
    expiresOn = expiresOn,
    isCurrent = isCurrent,
    uploadedAt = Instant.parse("2026-09-01T08:30:00Z"),
)

fun requirements(vararg items: DocumentRequirement) = DocumentRequirements(
    items = items.toList(),
    validCount = items.count { it.status == RequirementStatus.VALID },
    expiringSoonCount = items.count { it.status == RequirementStatus.EXPIRING_SOON },
    expiredCount = items.count { it.status == RequirementStatus.EXPIRED },
    missingOrIncompleteCount = items.count { it.status == RequirementStatus.MISSING || it.status == RequirementStatus.INCOMPLETE },
)

fun vehicleDocuments(vehicleId: String? = "veh-1") = if (vehicleId == null) {
    VehicleDocuments(null, emptyList(), emptyList())
} else {
    VehicleDocuments(
        vehicle = AssignedVehicle(vehicleId, "Dyna042", "WX 12345"),
        documents = listOf(VehicleDocument("f-ins", VehicleDocumentType.INSURANCE_POLICY, "policy.pdf", "application/pdf", 10, Instant.parse("2026-08-01T10:00:00Z"))),
        requirements = listOf(
            VehicleDocumentRequirement(VehicleDocumentType.INSURANCE_POLICY, VehicleRequirementStatus.AVAILABLE, "f-ins"),
            VehicleDocumentRequirement(VehicleDocumentType.TRANSPORT_LICENSE, VehicleRequirementStatus.MISSING, null),
            VehicleDocumentRequirement(VehicleDocumentType.REGISTRATION_CERTIFICATE, VehicleRequirementStatus.MISSING, null),
        ),
    )
}

class FakeMyDocumentRepository : MyDocumentRepository {
    val history = MutableStateFlow<PersonnelDocumentHistory?>(null)
    val requirements = MutableStateFlow<DocumentRequirements?>(null)
    var historyResult = RefreshResult.SUCCESS
    var requirementsResult = RefreshResult.SUCCESS
    var loadMoreResult = RefreshResult.SUCCESS
    var openResult: DocumentContentResult = DocumentContentResult.Available("/cache/private_document_view/p.pdf", "application/pdf")
    var historyReads = 0
    var requirementReads = 0
    var loadMoreCalls = 0
    val openRequests = mutableListOf<Pair<String, String?>>()

    override fun observeHistory(): Flow<PersonnelDocumentHistory?> = history

    override fun observeRequirements(): Flow<DocumentRequirements?> = requirements

    override suspend fun refreshHistory(): RefreshResult {
        historyReads++
        return historyResult
    }

    override suspend fun loadMoreHistory(): RefreshResult {
        loadMoreCalls++
        return loadMoreResult
    }

    override suspend fun refreshRequirements(): RefreshResult {
        requirementReads++
        return requirementsResult
    }

    override suspend fun open(documentId: String, contentTypeHint: String?): DocumentContentResult {
        openRequests += documentId to contentTypeHint
        return openResult
    }
}

class FakeVehicleDocumentRepository : VehicleDocumentRepository {
    val snapshot = MutableStateFlow<VehicleDocumentsSnapshot?>(null)
    var refreshResult = RefreshResult.SUCCESS
    var openResult: DocumentContentResult = DocumentContentResult.Available("/cache/private_document_view/v.png", "image/png")
    var reads = 0
    val openRequests = mutableListOf<String>()

    override fun observe(): Flow<VehicleDocumentsSnapshot?> = snapshot

    override suspend fun refresh(): RefreshResult {
        reads++
        return refreshResult
    }

    override suspend fun open(documentId: String, contentTypeHint: String?): DocumentContentResult {
        openRequests += documentId
        return openResult
    }
}

class RecordingFileCleaner : ViewedFileCleaner {
    val discarded = mutableListOf<String>()

    override fun discard(filePath: String) {
        discarded += filePath
    }
}

class FakeImageDecoder(var image: ImageBitmap? = null) : DocumentImageDecoder {
    val decoded = mutableListOf<String>()

    override suspend fun decode(filePath: String, maxEdgePx: Int): ImageBitmap? {
        decoded += filePath
        return image
    }
}
