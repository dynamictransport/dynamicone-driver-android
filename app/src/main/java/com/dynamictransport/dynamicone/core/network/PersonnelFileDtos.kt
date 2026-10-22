package com.dynamictransport.dynamicone.core.network

import kotlinx.serialization.Serializable

/**
 * Files uploaded to the employee's personnel file and to the assigned vehicle's file (Mobile API v1
 * `me/documents`, `me/assigned-vehicle/documents`). The app only reads them; there is no upload endpoint.
 * Type and status names are text; a name this app does not know must never break parsing.
 */
@Serializable
data class PersonnelDocumentDto(
    val id: String? = null,
    val type: String? = null,
    val displayName: String? = null,
    val fileName: String? = null,
    val contentType: String? = null,
    val fileSizeBytes: Long? = null,
    val sha256: String? = null,
    /** `yyyy-MM-dd`. */
    val issuedOn: String? = null,
    val validFrom: String? = null,
    val expiresOn: String? = null,
    val isCurrent: Boolean? = null,
    val verificationStatus: String? = null,
    val uploadedAtUtc: String? = null,
)

@Serializable
data class PersonnelDocumentPageDto(
    val items: List<PersonnelDocumentDto>? = null,
    val page: Int? = null,
    val pageSize: Int? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class DocumentRequirementDto(
    val type: String? = null,
    val status: String? = null,
    val documentId: String? = null,
    val expiresOn: String? = null,
)

@Serializable
data class DocumentRequirementsDto(
    val items: List<DocumentRequirementDto>? = null,
    val validCount: Int? = null,
    val expiringSoonCount: Int? = null,
    val expiredCount: Int? = null,
    val missingOrIncompleteCount: Int? = null,
)

@Serializable
data class VehicleDocumentDto(
    val id: String? = null,
    val type: String? = null,
    val fileName: String? = null,
    val contentType: String? = null,
    val fileSizeBytes: Long? = null,
    val sha256: String? = null,
    val uploadedAtUtc: String? = null,
)

@Serializable
data class VehicleDocumentsResponseDto(
    /** Null when no vehicle is assigned; the lists are empty then. */
    val assignedVehicle: AssignedVehicleDto? = null,
    val items: List<VehicleDocumentDto>? = null,
    val requirements: List<DocumentRequirementDto>? = null,
)

/** Wire names of the personnel and vehicle file contract (case-sensitive text). */
object PersonnelFileWireNames {
    const val STATUS_MISSING = "Missing"
    const val STATUS_INCOMPLETE = "Incomplete"
    const val STATUS_EXPIRED = "Expired"
    const val STATUS_EXPIRING_SOON = "ExpiringSoon"
    const val STATUS_VALID = "Valid"

    /** Vehicle files only: the file exists (vehicle files carry no dates). */
    const val STATUS_AVAILABLE = "Available"
}
