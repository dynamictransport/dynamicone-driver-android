package com.dynamictransport.driver.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.driver.core.design.component.HoldToConfirmButton
import com.dynamictransport.driver.core.design.theme.DynamicOneDriverTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class HoldToConfirmButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var confirmations = 0

    private fun setButton(enabled: Boolean = true) {
        composeRule.setContent {
            DynamicOneDriverTheme(darkTheme = false) {
                HoldToConfirmButton(
                    text = "FINISH TRIP",
                    hint = "Press and hold to confirm",
                    onConfirmed = { confirmations++ },
                    enabled = enabled,
                )
            }
        }
    }

    private fun holdFor(millis: Long) {
        composeRule.mainClock.autoAdvance = false
        val node = composeRule.onNodeWithText("FINISH TRIP")
        node.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(millis)
        node.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(500)
    }

    @Test
    fun quickTapNeverConfirms() {
        setButton()

        composeRule.onNodeWithText("FINISH TRIP").performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(2_000)

        assertEquals(0, confirmations)
    }

    @Test
    fun shortPressIsCancelled() {
        setButton()

        holdFor(300)

        assertEquals(0, confirmations)
    }

    @Test
    fun holdingConfirmsExactlyOnce() {
        setButton()

        holdFor(1_500)

        assertEquals(1, confirmations)
    }

    @Test
    fun disabledButtonIgnoresHolds() {
        setButton(enabled = false)
        composeRule.onNodeWithText("FINISH TRIP").assertIsNotEnabled()

        holdFor(1_500)

        assertEquals(0, confirmations)
    }

    @Test
    fun accessibilityClickConfirms() {
        setButton()

        composeRule.onNodeWithText("FINISH TRIP").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, confirmations)
    }
}
