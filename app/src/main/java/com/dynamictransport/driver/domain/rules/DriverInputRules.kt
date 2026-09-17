package com.dynamictransport.driver.domain.rules

import com.dynamictransport.driver.domain.model.ActionRejection
import com.dynamictransport.driver.domain.model.ExecutionActionCode
import com.dynamictransport.driver.domain.model.IncidentCategory
import com.dynamictransport.driver.domain.model.RejectReason

/** Validation for the few free-text inputs a driver may give. Presets first, text only when needed. */
object DriverInputRules {

    const val REJECT_NOTE_MAX_LENGTH = 200
    /** Driver API `ProblemReported` notes are limited to 1000 characters; room is kept for the category prefix. */
    const val INCIDENT_NOTE_MAX_LENGTH = 960

    fun validateReject(reason: RejectReason?, note: String?): ActionRejection? {
        if (reason == null) return ActionRejection.REASON_REQUIRED
        val normalized = normalizeNote(note)
        if (reason == RejectReason.OTHER && normalized == null) return ActionRejection.NOTE_REQUIRED
        if ((normalized?.length ?: 0) > REJECT_NOTE_MAX_LENGTH) return ActionRejection.NOTE_TOO_LONG
        return null
    }

    fun validateIncident(category: IncidentCategory?, note: String?): ActionRejection? {
        if (category == null) return ActionRejection.REASON_REQUIRED
        if ((normalizeNote(note)?.length ?: 0) > INCIDENT_NOTE_MAX_LENGTH) return ActionRejection.NOTE_TOO_LONG
        return null
    }

    fun normalizeNote(note: String?): String? = note?.trim()?.takeIf { it.isNotEmpty() }

    /** Trip action notes (Driver API): at most 1000 characters; a problem report needs one. */
    const val ACTION_NOTE_MAX_LENGTH = 1000

    /** Driver API messages: 1–2000 characters. */
    const val MESSAGE_MAX_LENGTH = 2000

    /** Driver API new password length; the server additionally applies its Identity rules. */
    val PASSWORD_LENGTH = 12..128

    fun validateActionNote(code: ExecutionActionCode, note: String?): ActionRejection? {
        val normalized = normalizeNote(note)
        if (code == ExecutionActionCode.PROBLEM && normalized == null) return ActionRejection.NOTE_REQUIRED
        if ((normalized?.length ?: 0) > ACTION_NOTE_MAX_LENGTH) return ActionRejection.NOTE_TOO_LONG
        return null
    }

    fun validateMessage(body: String?): ActionRejection? {
        val normalized = normalizeNote(body) ?: return ActionRejection.NOTE_REQUIRED
        return if (normalized.length > MESSAGE_MAX_LENGTH) ActionRejection.NOTE_TOO_LONG else null
    }
}
