package com.dynamictransport.dynamicone.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.component.ActionTone
import com.dynamictransport.dynamicone.core.design.component.actionColors
import com.dynamictransport.dynamicone.core.design.theme.DriverColors
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionButtonColorsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun themes(): List<DriverColors> {
        val captured = mutableListOf<DriverColors>()
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) { captured += DriverTheme.colors }
            DynamicOneTheme(darkTheme = true) { captured += DriverTheme.colors }
        }
        composeRule.waitForIdle()
        return captured.distinct().also { assertEquals(2, it.size) }
    }

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun disabledActionLooksDifferentFromEveryEnabledToneButStaysReadable() {
        for (colors in themes()) {
            val disabled = actionColors(ActionTone.Positive, active = false, loading = false, colors = colors)
            for (tone in ActionTone.entries) {
                val enabled = actionColors(tone, active = true, loading = false, colors = colors)
                assertNotEquals(enabled.container, disabled.container)
                assertNotEquals(enabled.content, disabled.content)
            }
            assertTrue(contrast(disabled.container, disabled.content) >= 4.5)
        }
    }

    @Test
    fun loadingActionKeepsItsToneSoTheSpinnerStaysVisible() {
        for (colors in themes()) {
            for (tone in ActionTone.entries) {
                assertEquals(
                    actionColors(tone, active = true, loading = false, colors = colors),
                    actionColors(tone, active = false, loading = true, colors = colors),
                )
            }
        }
    }
}
