package com.dynamictransport.dynamicone.feature

import com.dynamictransport.dynamicone.feature.messages.isMostlyVisible
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A message counts as seen only when most of it is outside the bars (read receipts, docs/13). */
class MessageVisibilityTest {

    @Test
    fun `a message at least half inside the visible area is seen`() {
        assertTrue(isMostlyVisible(offset = 0, size = 100, visibleEnd = 800))
        assertTrue(isMostlyVisible(offset = -50, size = 100, visibleEnd = 800))
        assertTrue(isMostlyVisible(offset = 750, size = 100, visibleEnd = 800))
    }

    @Test
    fun `a message mostly under the bars or outside the list is not seen`() {
        assertFalse(isMostlyVisible(offset = -60, size = 100, visibleEnd = 800))
        assertFalse(isMostlyVisible(offset = 760, size = 100, visibleEnd = 800))
        assertFalse(isMostlyVisible(offset = 900, size = 100, visibleEnd = 800))
    }

    @Test
    fun `a message taller than the screen is seen when it fills half of it`() {
        assertTrue(isMostlyVisible(offset = -1000, size = 3000, visibleEnd = 800))
        assertFalse(isMostlyVisible(offset = 500, size = 3000, visibleEnd = 800))
    }
}
