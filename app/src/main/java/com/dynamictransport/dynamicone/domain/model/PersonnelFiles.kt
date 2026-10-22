package com.dynamictransport.dynamicone.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * Document types of the personnel file. The first fifteen are the driver's required documents; the history can hold
 * other personnel and onboarding types as well, which stay [OTHER] with the server's display name.
 */
enum class PersonnelDocumentType(val wireName: String?) {
    PASSPORT("Passport"),
    RESIDENCE_PERMIT("ResidencePermit"),
    VISA("Visa"),
    WORK_PERMIT("WorkPermit"),
    EMPLOYMENT_CONTRACT("EmploymentContract"),
    A1_CERTIFICATE("A1Certificate"),
    CODE_95("Code95"),
    DRIVING_LICENCE("DrivingLicence"),
    RED_DOCUMENT("RedDocument"),
    TACHOGRAPH_CARD("TachographCard"),
    ADR_CERTIFICATE("AdrCertificate"),
    OCCUPATIONAL_SAFETY_CERTIFICATE("OccupationalSafetyCertificate"),
    PSYCHOTECHNICAL_CERTIFICATE("PsychotechnicalCertificate"),
    MEDICAL_CERTIFICATE("MedicalCertificate"),
    EUROPEAN_HEALTH_INSURANCE_CARD("EuropeanHealthInsuranceCard"),

    /** A type this app version does not know. */
    OTHER(null),
    ;

    companion object {
        fun fromWire(name: String?): PersonnelDocumentType = entries.firstOrNull { it.wireName != null && it.wireName == name } ?: OTHER
    }
}

/** One uploaded file of the account's own personnel file. */
data class PersonnelDocument(
    val id: String,
    val type: PersonnelDocumentType,
    /** The server's name of the type; shown for [PersonnelDocumentType.OTHER] only. */
    val displayName: String?,
    val fileName: String?,
    val contentType: String?,
    val sizeBytes: Long?,
    val issuedOn: LocalDate?,
    val validFrom: LocalDate?,
    val expiresOn: LocalDate?,
    /** The file currently in force for its type (older uploads stay in the history). */
    val isCurrent: Boolean,
    val uploadedAt: Instant?,
)

/** The personnel file history as read so far. */
data class PersonnelDocumentHistory(
    val documents: List<PersonnelDocument>,
    val canLoadMore: Boolean,
)

enum class RequirementStatus {
    MISSING,
    INCOMPLETE,
    EXPIRED,
    EXPIRING_SOON,
    VALID,

    /** A status this app version does not know: shown as such, never as valid. */
    UNKNOWN,
}

/** One required document of the driver, as the server judged it. */
data class DocumentRequirement(
    val type: PersonnelDocumentType,
    val status: RequirementStatus,
    /** The file to open; null when there is none to open. */
    val documentId: String?,
    val expiresOn: LocalDate?,
)

data class DocumentRequirements(
    val items: List<DocumentRequirement>,
    val validCount: Int,
    val expiringSoonCount: Int,
    val expiredCount: Int,
    val missingOrIncompleteCount: Int,
)

enum class VehicleDocumentType(val wireName: String?) {
    INSURANCE_POLICY("InsurancePolicy"),
    TRANSPORT_LICENSE("TransportLicense"),
    REGISTRATION_CERTIFICATE("RegistrationCertificate"),

    /** A type this app version does not know. */
    OTHER(null),
    ;

    companion object {
        fun fromWire(name: String?): VehicleDocumentType = entries.firstOrNull { it.wireName != null && it.wireName == name } ?: OTHER
    }
}

/** A file of the assigned vehicle. The backend keeps no dates for vehicle files, so none are shown or derived. */
data class VehicleDocument(
    val id: String,
    val type: VehicleDocumentType,
    val fileName: String?,
    val contentType: String?,
    val sizeBytes: Long?,
    val uploadedAt: Instant?,
)

enum class VehicleRequirementStatus {
    AVAILABLE,
    MISSING,
    UNKNOWN,
}

data class VehicleDocumentRequirement(
    val type: VehicleDocumentType,
    val status: VehicleRequirementStatus,
    val documentId: String?,
)

/** The files of the vehicle the server assigned to the driver; [vehicle] null means no vehicle is assigned. */
data class VehicleDocuments(
    val vehicle: AssignedVehicle?,
    val documents: List<VehicleDocument>,
    val requirements: List<VehicleDocumentRequirement>,
) {
    /** Files that no required type points at (still openable). */
    val otherDocuments: List<VehicleDocument>
        get() {
            val required = requirements.mapNotNullTo(HashSet()) { it.documentId }
            return documents.filterNot { it.id in required }
        }
}

/** What the phone knows about the assigned vehicle's files. */
data class VehicleDocumentsSnapshot(
    val documents: VehicleDocuments,
    /** The last read failed; [documents] is the previous answer. */
    val stale: Boolean,
)
