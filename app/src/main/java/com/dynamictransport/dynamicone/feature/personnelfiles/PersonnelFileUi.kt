package com.dynamictransport.dynamicone.feature.personnelfiles

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.PersonnelDocument
import com.dynamictransport.dynamicone.domain.model.PersonnelDocumentType
import com.dynamictransport.dynamicone.domain.model.RequirementStatus
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentType
import com.dynamictransport.dynamicone.domain.model.VehicleRequirementStatus
import com.dynamictransport.dynamicone.feature.common.messageRes

@StringRes
fun PersonnelDocumentType.labelRes(): Int = when (this) {
    PersonnelDocumentType.PASSPORT -> R.string.personnel_type_passport
    PersonnelDocumentType.RESIDENCE_PERMIT -> R.string.personnel_type_residence_permit
    PersonnelDocumentType.VISA -> R.string.personnel_type_visa
    PersonnelDocumentType.WORK_PERMIT -> R.string.personnel_type_work_permit
    PersonnelDocumentType.EMPLOYMENT_CONTRACT -> R.string.personnel_type_employment_contract
    PersonnelDocumentType.A1_CERTIFICATE -> R.string.personnel_type_a1
    PersonnelDocumentType.CODE_95 -> R.string.personnel_type_code95
    PersonnelDocumentType.DRIVING_LICENCE -> R.string.personnel_type_driving_licence
    PersonnelDocumentType.RED_DOCUMENT -> R.string.personnel_type_red_document
    PersonnelDocumentType.TACHOGRAPH_CARD -> R.string.personnel_type_tachograph_card
    PersonnelDocumentType.ADR_CERTIFICATE -> R.string.personnel_type_adr
    PersonnelDocumentType.OCCUPATIONAL_SAFETY_CERTIFICATE -> R.string.personnel_type_occupational_safety
    PersonnelDocumentType.PSYCHOTECHNICAL_CERTIFICATE -> R.string.personnel_type_psychotechnical
    PersonnelDocumentType.MEDICAL_CERTIFICATE -> R.string.personnel_type_medical
    PersonnelDocumentType.EUROPEAN_HEALTH_INSURANCE_CARD -> R.string.personnel_type_ehic
    PersonnelDocumentType.OTHER -> R.string.personnel_type_other
}

@StringRes
fun VehicleDocumentType.labelRes(): Int = when (this) {
    VehicleDocumentType.INSURANCE_POLICY -> R.string.vehicle_type_insurance
    VehicleDocumentType.TRANSPORT_LICENSE -> R.string.vehicle_type_transport_license
    VehicleDocumentType.REGISTRATION_CERTIFICATE -> R.string.vehicle_type_registration
    VehicleDocumentType.OTHER -> R.string.personnel_type_other
}

/** A known type in the app language; an unknown one with the server's name, or a neutral default without one. */
@Composable
@ReadOnlyComposable
fun documentTitle(document: PersonnelDocument): String =
    if (document.type == PersonnelDocumentType.OTHER) document.displayName ?: stringResource(R.string.personnel_type_other)
    else stringResource(document.type.labelRes())

/** A status is icon, text and colour together (CLAUDE.md §7). */
data class StatusVisual(@param:StringRes val label: Int, val icon: ImageVector, val tone: StatusTone)

/** Missing and expired required documents keep a driver off the road: shown as danger; the rest by urgency. */
fun RequirementStatus.visual(): StatusVisual = when (this) {
    RequirementStatus.VALID -> StatusVisual(R.string.requirement_status_valid, Icons.Filled.CheckCircle, StatusTone.Positive)
    RequirementStatus.EXPIRING_SOON -> StatusVisual(R.string.requirement_status_expiring, Icons.Filled.Schedule, StatusTone.Warning)
    RequirementStatus.EXPIRED -> StatusVisual(R.string.requirement_status_expired, Icons.Filled.EventBusy, StatusTone.Danger)
    RequirementStatus.INCOMPLETE -> StatusVisual(R.string.requirement_status_incomplete, Icons.Filled.ErrorOutline, StatusTone.Warning)
    RequirementStatus.MISSING -> StatusVisual(R.string.requirement_status_missing, Icons.Filled.RemoveCircleOutline, StatusTone.Danger)
    RequirementStatus.UNKNOWN -> StatusVisual(R.string.requirement_status_unknown, Icons.AutoMirrored.Filled.HelpOutline, StatusTone.Neutral)
}

fun VehicleRequirementStatus.visual(): StatusVisual = when (this) {
    VehicleRequirementStatus.AVAILABLE -> StatusVisual(R.string.vehicle_document_available, Icons.Filled.CheckCircle, StatusTone.Positive)
    VehicleRequirementStatus.MISSING -> StatusVisual(R.string.requirement_status_missing, Icons.Filled.RemoveCircleOutline, StatusTone.Danger)
    VehicleRequirementStatus.UNKNOWN -> StatusVisual(R.string.requirement_status_unknown, Icons.AutoMirrored.Filled.HelpOutline, StatusTone.Neutral)
}

/** Why a file could not be opened: gone or no longer allowed (404), a passing storage problem (503), or the usual. */
@StringRes
fun ActionRejection.fileMessageRes(): Int = when (this) {
    ActionRejection.NOT_FOUND -> R.string.document_viewer_not_found
    ActionRejection.SERVICE_UNAVAILABLE -> R.string.document_viewer_storage_unavailable
    ActionRejection.FORBIDDEN -> R.string.feature_access_required
    ActionRejection.UNSUPPORTED_FILE -> R.string.document_viewer_unreadable
    else -> messageRes()
}
