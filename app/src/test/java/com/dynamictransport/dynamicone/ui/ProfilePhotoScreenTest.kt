package com.dynamictransport.dynamicone.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.UserProfile
import com.dynamictransport.dynamicone.feature.profile.PROFILE_JOB_TITLE_TAG
import com.dynamictransport.dynamicone.feature.profile.PROFILE_PHOTO_TAG
import com.dynamictransport.dynamicone.feature.profile.ProfileScreen
import com.dynamictransport.dynamicone.feature.profile.ProfileUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The HR photo (docs/21) replaces the placeholder in the profile header; without one nothing changes. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ProfilePhotoScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val profile = UserProfile("user-9", "Ayşe Yılmaz", null, null, setOf(AppModule.OFFICE_ATTENDANCE), null)

    private fun show(photoPath: String?, profile: UserProfile = this.profile) {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                ProfileScreen(
                    state = ProfileUiState(profile = profile, photoPath = photoPath, modules = profile.modules, versionName = "1.0"),
                    onRetryAll = {},
                    onAcknowledgeFailure = {},
                    onLogoutRequested = {},
                    onLogoutConfirmed = {},
                    onLogoutDismissed = {},
                    onSimulateOfflineChanged = {},
                    onResetDemoData = {},
                )
            }
        }
    }

    @Test
    fun photoIsShownNextToTheName() {
        show(photoPath = "/data/user/0/app/files/employee_photo/A.jpg")

        composeRule.onNodeWithTag(PROFILE_PHOTO_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Ayşe Yılmaz").assertIsDisplayed()
    }

    @Test
    fun withoutPhotoThePlaceholderStays() {
        show(photoPath = null)

        composeRule.onAllNodes(hasTestTag(PROFILE_PHOTO_TAG)).assertCountEquals(0)
        composeRule.onNodeWithText("Ayşe Yılmaz").assertIsDisplayed()
    }

    @Test
    fun jobTitleIsShownUnderTheName() {
        show(photoPath = null, profile = profile.copy(jobTitle = "Operations Manager"))

        composeRule.onNodeWithTag(PROFILE_JOB_TITLE_TAG).assertIsDisplayed().assertTextEquals("Operations Manager")
    }

    @Test
    fun withoutJobTitleNoEmptyLineIsShown() {
        show(photoPath = null)

        composeRule.onAllNodes(hasTestTag(PROFILE_JOB_TITLE_TAG)).assertCountEquals(0)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun aLongJobTitleIsNeitherTranslatedNorShortenedButCutAfterTwoLines() {
        val title = "Senior International Road Transport Operations and Customs Documentation Coordinator for Eastern Europe and Central Asia"
        show(photoPath = null, profile = profile.copy(jobTitle = title))

        val node = composeRule.onNodeWithTag(PROFILE_JOB_TITLE_TAG).assertIsDisplayed().assertTextEquals(title)
        val layout = node.textLayout()
        org.junit.Assert.assertEquals(2, layout.lineCount)
        org.junit.Assert.assertTrue(layout.hasVisualOverflow)
    }
}

/** The laid-out lines of a text node: a long job title stays within two lines and is cut with an ellipsis. */
internal fun androidx.compose.ui.test.SemanticsNodeInteraction.textLayout(): androidx.compose.ui.text.TextLayoutResult {
    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
    fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
    return results.single()
}
