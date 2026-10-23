package com.dynamictransport.dynamicone.domain.rules

import com.dynamictransport.dynamicone.domain.model.ActionRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/27: the seal number the backend accepts (1–100 characters after trimming, no control characters). */
class SealNumberRulesTest {

    @Test
    fun `empty, blank, control characters and more than 100 characters are refused`() {
        listOf(null, "", "   ", "SEAL\nOTHER", "SEAL\tX", "A".repeat(101), " " + "A".repeat(101) + " ").forEach { value ->
            assertEquals(value.toString(), ActionRejection.SEAL_NUMBER_INVALID, DriverInputRules.validateSealNumber(value))
        }
    }

    @Test
    fun `trimmed values of 1 to 100 characters pass unchanged in case`() {
        assertNull(DriverInputRules.validateSealNumber("SEAL-12345"))
        assertNull(DriverInputRules.validateSealNumber(" seal-ab ÇĞ "))
        assertNull(DriverInputRules.validateSealNumber("A".repeat(100)))
        assertNull(DriverInputRules.validateSealNumber(" " + "A".repeat(100) + " "))
        assertEquals("seal-ab ÇĞ", DriverInputRules.normalizeSealNumber(" seal-ab ÇĞ "))
        assertEquals("", DriverInputRules.normalizeSealNumber(null))
    }
}
