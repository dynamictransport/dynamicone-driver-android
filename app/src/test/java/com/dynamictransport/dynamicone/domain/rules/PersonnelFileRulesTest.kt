package com.dynamictransport.dynamicone.domain.rules

import com.dynamictransport.dynamicone.domain.model.AssignedVehicle
import com.dynamictransport.dynamicone.domain.model.AssignedVehicleState
import com.dynamictransport.dynamicone.domain.model.DocumentRequirement
import com.dynamictransport.dynamicone.domain.model.PersonnelDocumentType
import com.dynamictransport.dynamicone.domain.model.RequirementStatus
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentRequirement
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentType
import com.dynamictransport.dynamicone.domain.model.VehicleDocumentsSnapshot
import com.dynamictransport.dynamicone.domain.model.VehicleRequirementStatus
import com.dynamictransport.dynamicone.testing.personnelDocument
import com.dynamictransport.dynamicone.testing.vehicleDocuments
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PersonnelFileRulesTest {

    private fun requirement(status: RequirementStatus, documentId: String?) =
        DocumentRequirement(PersonnelDocumentType.PASSPORT, status, documentId, null)

    @Test
    fun `a missing document never offers a file, every other status with a file does`() {
        assertFalse(PersonnelFileRules.canOpen(requirement(RequirementStatus.MISSING, null)))
        // Even if the server sent an id with it.
        assertFalse(PersonnelFileRules.canOpen(requirement(RequirementStatus.MISSING, "d-1")))
        assertFalse(PersonnelFileRules.canOpen(requirement(RequirementStatus.INCOMPLETE, null)))
        // Expired files can still be downloaded.
        assertTrue(PersonnelFileRules.canOpen(requirement(RequirementStatus.EXPIRED, "d-1")))
        assertTrue(PersonnelFileRules.canOpen(requirement(RequirementStatus.INCOMPLETE, "d-1")))
        assertTrue(PersonnelFileRules.canOpen(requirement(RequirementStatus.UNKNOWN, "d-1")))

        assertTrue(PersonnelFileRules.canOpen(VehicleDocumentRequirement(VehicleDocumentType.INSURANCE_POLICY, VehicleRequirementStatus.AVAILABLE, "f")))
        assertFalse(PersonnelFileRules.canOpen(VehicleDocumentRequirement(VehicleDocumentType.INSURANCE_POLICY, VehicleRequirementStatus.MISSING, "f")))
        assertFalse(PersonnelFileRules.canOpen(VehicleDocumentRequirement(VehicleDocumentType.INSURANCE_POLICY, VehicleRequirementStatus.AVAILABLE, null)))
    }

    @Test
    fun `the expiry date counts on the phone's own date, the last valid day is not expired`() {
        val today = LocalDate.of(2026, 9, 30)
        assertFalse(PersonnelFileRules.isPastExpiry(personnelDocument("d", expiresOn = today), today))
        assertTrue(PersonnelFileRules.isPastExpiry(personnelDocument("d", expiresOn = today.minusDays(1)), today))
        assertFalse(PersonnelFileRules.isPastExpiry(personnelDocument("d", expiresOn = null), today))
    }

    @Test
    fun `a fresh answer is always shown, a stale one only while me names the same vehicle`() {
        val fresh = VehicleDocumentsSnapshot(vehicleDocuments("veh-1"), stale = false)
        val stale = fresh.copy(stale = true)
        val same = AssignedVehicleState.Assigned(AssignedVehicle("veh-1", "Dyna042", "WX 12345"))
        val other = AssignedVehicleState.Assigned(AssignedVehicle("veh-2", "Dyna043", "WX 2"))

        assertTrue(PersonnelFileRules.isShowable(fresh, other))
        assertTrue(PersonnelFileRules.isShowable(stale, same))
        assertFalse(PersonnelFileRules.isShowable(stale, other))
        assertFalse(PersonnelFileRules.isShowable(stale, AssignedVehicleState.NotAssigned))
        // Without a newer word on the assignment the last answer stays.
        assertTrue(PersonnelFileRules.isShowable(stale, AssignedVehicleState.Unavailable))
        assertTrue(PersonnelFileRules.isShowable(stale, null))
        assertTrue(PersonnelFileRules.isShowable(VehicleDocumentsSnapshot(vehicleDocuments(null), stale = true), AssignedVehicleState.NotAssigned))
    }
}
