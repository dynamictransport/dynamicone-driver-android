package com.dynamictransport.dynamicone.domain.rules

import com.dynamictransport.dynamicone.domain.model.DrawnSignature
import com.dynamictransport.dynamicone.domain.model.HrDocumentDisplayState
import com.dynamictransport.dynamicone.domain.model.HrDocumentStatus
import com.dynamictransport.dynamicone.domain.model.LocalSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.SignatureFailure
import com.dynamictransport.dynamicone.domain.model.SignaturePoint
import com.dynamictransport.dynamicone.testing.VALID_STROKE
import com.dynamictransport.dynamicone.testing.hrDocument
import com.dynamictransport.dynamicone.testing.hrLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HrDocumentRulesTest {

    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val istanbul = ZoneId.of("Europe/Istanbul")

    // 23:59 in Warsaw (CEST) on 20 September.
    private val due = Instant.parse("2026-09-20T21:59:00Z")

    @Test
    fun `the deadline is read in the phone's calendar`() {
        assertEquals(DeadlineKind.NONE, HrDocumentRules.deadline(null, due, warsaw))
        assertEquals(DeadlineKind.LATER, HrDocumentRules.deadline(due, Instant.parse("2026-09-18T10:00:00Z"), warsaw))
        assertEquals(DeadlineKind.TOMORROW, HrDocumentRules.deadline(due, Instant.parse("2026-09-19T08:00:00Z"), warsaw))
        assertEquals(DeadlineKind.TODAY, HrDocumentRules.deadline(due, Instant.parse("2026-09-20T06:00:00Z"), warsaw))
        assertEquals(DeadlineKind.OVERDUE, HrDocumentRules.deadline(due, due, warsaw))
        assertEquals(DeadlineKind.OVERDUE, HrDocumentRules.deadline(due, due.plusSeconds(60), warsaw))
    }

    @Test
    fun `a phone in another time zone counts the last day in its own calendar`() {
        // 21:59Z is 00:59 on 21 September in Istanbul: the last day there is the 21st.
        val evening = Instant.parse("2026-09-19T21:30:00Z")
        assertEquals(DeadlineKind.TOMORROW, HrDocumentRules.deadline(due, evening, warsaw))
        assertEquals(DeadlineKind.TOMORROW, HrDocumentRules.deadline(due, Instant.parse("2026-09-19T22:30:00Z"), istanbul))
        assertEquals(DeadlineKind.TODAY, HrDocumentRules.deadline(due, Instant.parse("2026-09-20T21:30:00Z"), istanbul))
    }

    @Test
    fun `a signature on the phone decides the state until the server answered`() {
        val now = Instant.parse("2026-09-21T08:00:00Z")
        val overdue = hrDocument(dueAt = due)
        fun local(state: LocalSignatureState, failure: SignatureFailure? = null) =
            LocalSignature("sig-1", overdue.id, "tr", now, state, failure)

        assertEquals(HrDocumentDisplayState.OVERDUE, HrDocumentRules.displayState(overdue, null, now, warsaw))
        assertEquals(HrDocumentDisplayState.WAITING_TO_SEND, HrDocumentRules.displayState(overdue, local(LocalSignatureState.PENDING), now, warsaw))
        assertEquals(HrDocumentDisplayState.WAITING_TO_SEND, HrDocumentRules.displayState(overdue, local(LocalSignatureState.SENDING), now, warsaw))
        assertEquals(
            HrDocumentDisplayState.SEND_FAILED,
            HrDocumentRules.displayState(overdue, local(LocalSignatureState.FAILED, SignatureFailure.DOCUMENT_CHANGED), now, warsaw),
        )
        // The server's answer wins over anything on the phone.
        assertEquals(
            HrDocumentDisplayState.SIGNED,
            HrDocumentRules.displayState(overdue.copy(status = HrDocumentStatus.SIGNED), local(LocalSignatureState.PENDING), now, warsaw),
        )
        assertEquals(HrDocumentDisplayState.WITHDRAWN, HrDocumentRules.displayState(overdue.copy(status = HrDocumentStatus.WITHDRAWN), null, now, warsaw))
        assertEquals(HrDocumentDisplayState.DUE_SOON, HrDocumentRules.displayState(overdue, null, Instant.parse("2026-09-19T08:00:00Z"), warsaw))
        assertEquals(HrDocumentDisplayState.TO_SIGN, HrDocumentRules.displayState(overdue.copy(dueAt = null), null, now, warsaw))
    }

    @Test
    fun `documents to sign come soonest deadline first, without deadline last`() {
        val none = hrDocument(id = "none", dueAt = null)
        val late = hrDocument(id = "late", dueAt = due.plusSeconds(86_400))
        val soon = hrDocument(id = "soon", dueAt = due)

        assertEquals(listOf("soon", "late", "none"), HrDocumentRules.sortPending(listOf(none, late, soon)).map { it.id })
    }

    @Test
    fun `the app language is shown first, then the reference language`() {
        val document = hrDocument()

        assertEquals("tr", HrDocumentRules.pickLanguage(document, listOf("tr"))?.code)
        assertEquals("en", HrDocumentRules.pickLanguage(document, listOf("EN"))?.code)
        // Azerbaijani is not in this document: the Polish reference version is shown.
        assertEquals("pl", HrDocumentRules.pickLanguage(document, listOf("az", null))?.code)
        val single = document.copy(referenceLanguage = null, languages = listOf(hrLanguage("mul", "RODO PL/TR/EN/AZ", "D")))
        assertEquals("mul", HrDocumentRules.pickLanguage(single, listOf("tr"))?.code)
    }

    @Test
    fun `a signature must meet the server's rules`() {
        assertTrue(HrDocumentRules.isValid(DrawnSignature(listOf(VALID_STROKE), HrDocumentRules.PAD_ASPECT_RATIO)))
        assertFalse(HrDocumentRules.isValid(emptyList()))
        // A dot is not a stroke.
        assertFalse(HrDocumentRules.isValid(listOf(listOf(SignaturePoint(0.5f, 0.5f)))))
        // Too small: narrower than 3 % or lower than 1.5 % of the box.
        assertFalse(HrDocumentRules.isValid(listOf(listOf(SignaturePoint(0.5f, 0.5f), SignaturePoint(0.52f, 0.6f)))))
        assertFalse(HrDocumentRules.isValid(listOf(listOf(SignaturePoint(0.1f, 0.5f), SignaturePoint(0.6f, 0.51f)))))
        assertTrue(HrDocumentRules.isValid(listOf(listOf(SignaturePoint(0.1f, 0.5f), SignaturePoint(0.14f, 0.52f)))))
        // Outside the box.
        assertFalse(HrDocumentRules.isValid(listOf(listOf(SignaturePoint(0.1f, 0.5f), SignaturePoint(1.2f, 0.9f)))))
        assertFalse(HrDocumentRules.isValid(List(101) { VALID_STROKE }))
        assertFalse(HrDocumentRules.isValid(listOf(List(5001) { SignaturePoint(it % 2 * 0.5f, it % 3 * 0.3f) })))
    }

    @Test
    fun `drawing keeps points inside the box and drops ones that add nothing`() {
        assertEquals(SignaturePoint(0f, 1f), HrDocumentRules.nextPoint(null, -0.2f, 1.4f))
        assertNull(HrDocumentRules.nextPoint(SignaturePoint(0.5f, 0.5f), 0.501f, 0.501f))
        assertEquals(SignaturePoint(0.51f, 0.5f), HrDocumentRules.nextPoint(SignaturePoint(0.5f, 0.5f), 0.51f, 0.5f))

        assertTrue(HrDocumentRules.canStartStroke(List(99) { VALID_STROKE }))
        assertFalse(HrDocumentRules.canStartStroke(List(100) { VALID_STROKE }))
        assertFalse(HrDocumentRules.canStartStroke(listOf(List(4999) { SignaturePoint(0.1f, 0.1f) })))
    }
}
