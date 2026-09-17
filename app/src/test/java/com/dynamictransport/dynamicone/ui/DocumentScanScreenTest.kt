package com.dynamictransport.dynamicone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.scan.ScanIssue
import com.dynamictransport.dynamicone.domain.scan.ScanMode
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.documents.scan.CameraPermission
import com.dynamictransport.dynamicone.feature.documents.scan.DocumentScanScreen
import com.dynamictransport.dynamicone.feature.documents.scan.DocumentScanUiState
import com.dynamictransport.dynamicone.feature.documents.scan.ScanPhase
import com.dynamictransport.dynamicone.feature.documents.scan.ScanReview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class DocumentScanScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var captures = 0
    private var retakes = 0
    private var uses = 0

    private val cmr = DocumentScanUiState(DocumentType.CMR, ScanMode.DOCUMENT, cameraStarting = false)

    private fun setScreen(state: DocumentScanUiState, permission: CameraPermission = CameraPermission.GRANTED) {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                DocumentScanScreen(
                    state = state,
                    permission = permission,
                    onClose = {},
                    onCapture = { captures++ },
                    onToggleTorch = {},
                    onRetake = { retakes++ },
                    onUse = { uses++ },
                    onRequestPermission = {},
                    onOpenSettings = {},
                    onMessageShown = {},
                    cameraPreview = { Box(it) },
                )
            }
        }
    }

    @Test
    fun cameraExplainsWhyThePhotoCannotBeTakenYet() {
        setScreen(cmr.copy(liveIssue = ScanIssue.CUT_OFF))

        composeRule.onNodeWithText("all four corners must be visible", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("TAKE PHOTO").assertIsNotEnabled()
        assertEquals(0, captures)
    }

    @Test
    fun readyCameraAllowsALargePhotoButton() {
        setScreen(cmr.copy(canCapture = true))

        composeRule.onNodeWithText("the photo is taken automatically", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG)
            .assertIsEnabled()
            .assertHeightIsAtLeast(64.dp)
            .performClick()
        assertEquals(1, captures)
    }

    @Test
    fun rejectedPhotoShowsTheReasonsAndOnlyOffersTakingItAgain() {
        setScreen(cmr.copy(phase = ScanPhase.REVIEW, review = ScanReview("/missing.jpg", listOf(ScanIssue.BLURRY, ScanIssue.LOW_RESOLUTION))))

        composeRule.onNodeWithText("Photo not accepted").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_issue_BLURRY").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_issue_LOW_RESOLUTION").assertIsDisplayed()
        composeRule.onNodeWithText("USE THIS PHOTO").assertDoesNotExist()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("TAKE AGAIN").performClick()
        assertEquals(1, retakes)
    }

    @Test
    fun acceptedPhotoCanBeUsedOrTakenAgain() {
        setScreen(cmr.copy(phase = ScanPhase.REVIEW, review = ScanReview("/missing.jpg", emptyList())))

        composeRule.onNodeWithTag("scan_review_accepted").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("USE THIS PHOTO").performClick()
        composeRule.onNodeWithText("Take again").performClick()
        assertEquals(1, uses)
        assertEquals(1, retakes)
    }

    @Test
    fun blockedCameraPermissionLeadsToTheSettings() {
        setScreen(cmr, permission = CameraPermission.BLOCKED)

        composeRule.onNodeWithText("Camera access needed").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("OPEN SETTINGS")
    }
}
