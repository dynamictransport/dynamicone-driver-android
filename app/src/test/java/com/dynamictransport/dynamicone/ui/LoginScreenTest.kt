package com.dynamictransport.dynamicone.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.feature.auth.LoginScreen
import com.dynamictransport.dynamicone.feature.auth.LoginUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * On a 360 dp phone the language and support links do not fit side by side; the support label used to be squeezed
     * into one letter per line. Robolectric measures text much narrower than a device, so the screen here is narrow
     * enough for the same situation.
     */
    @Test
    @Config(qualifiers = "w150dp-h800dp")
    fun linksThatDoNotFitSideBySideMoveToTheNextLineInsteadOfSqueezing() {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                LoginScreen(
                    state = LoginUiState(),
                    onUsernameChanged = {},
                    onPasswordChanged = {},
                    onTogglePasswordVisibility = {},
                    onLogin = {},
                )
            }
        }

        val language = composeRule.onNodeWithTag("login_language").assertIsDisplayed().getUnclippedBoundsInRoot()
        val support = composeRule.onNodeWithTag("login_support").assertIsDisplayed().getUnclippedBoundsInRoot()

        assertTrue("support is ${support.height} high", support.height <= 64.dp)
        assertTrue("language is ${language.height} high", language.height <= 64.dp)
        assertTrue("support starts at ${support.top}, language ends at ${language.bottom}", support.top >= language.bottom)
    }
}
