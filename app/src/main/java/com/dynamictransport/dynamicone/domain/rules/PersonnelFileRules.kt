package com.dynamictransport.dynamicone.domain.rules

import com.dynamictransport.dynamicone.domain.model.AssignedVehicleState
import com.dynamictransport.dynamicone.domain.model.DocumentRequirement
import com.dynamictransport.dynamicone.domain.model.PersonnelDocument
import com.dynamictransport.dynamicone.domain.model.RequirementStatus
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentRequirement
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentsSnapshot
import com.dynamictransport.dynamicone.domain.model.VehicleRequirementStatus
import java.time.LocalDate

/** Display rules of the personnel and vehicle files; the server decides every status, the app only shows it. */
object PersonnelFileRules {

    /** A missing document never offers a file, whatever else the server sends. */
    fun canOpen(requirement: DocumentRequirement): Boolean =
        requirement.documentId != null && requirement.status != RequirementStatus.MISSING

    fun canOpen(requirement: VehicleDocumentRequirement): Boolean =
        requirement.documentId != null && requirement.status == VehicleRequirementStatus.AVAILABLE

    /** The printed expiry date lies before [today] (the phone's date). Such a file can still be opened. */
    fun isPastExpiry(document: PersonnelDocument, today: LocalDate): Boolean = document.expiresOn?.isBefore(today) == true

    /**
     * Whether an answer that could not be refreshed may still be shown: never once `me` names another vehicle (or none),
     * so the files of a vehicle the driver no longer has disappear when the assignment changes. A fresh answer is the
     * server's current word and is always shown.
     */
    fun isShowable(snapshot: VehicleDocumentsSnapshot, assignment: AssignedVehicleState?): Boolean {
        if (!snapshot.stale) return true
        val shownVehicleId = snapshot.documents.vehicle?.vehicleId
        return when (assignment) {
            is AssignedVehicleState.Assigned -> shownVehicleId != null && shownVehicleId == assignment.vehicle.vehicleId
            AssignedVehicleState.NotAssigned -> snapshot.documents.vehicle == null
            AssignedVehicleState.Loading, AssignedVehicleState.Unavailable, null -> true
        }
    }
}
