package com.dynamictransport.dynamicone.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.domain.model.DocumentRequirement
import com.dynamictransport.dynamicone.domain.model.PersonnelDocumentType
import com.dynamictransport.dynamicone.domain.model.RequirementStatus
import com.dynamictransport.dynamicone.feature.personnelfiles.DOCUMENT_VIEWER_ERROR_TAG
import com.dynamictransport.dynamicone.feature.personnelfiles.DocumentViewerScreen
import com.dynamictransport.dynamicone.feature.personnelfiles.DocumentViewerUiState
import com.dynamictransport.dynamicone.feature.personnelfiles.FileToView
import com.dynamictransport.dynamicone.feature.personnelfiles.LoadedPart
import com.dynamictransport.dynamicone.feature.personnelfiles.MY_DOCUMENTS_LIST_TAG
import com.dynamictransport.dynamicone.feature.personnelfiles.MyDocumentsScreen
import com.dynamictransport.dynamicone.feature.personnelfiles.MyDocumentsUiState
import com.dynamictransport.dynamicone.feature.personnelfiles.VEHICLE_DOCUMENTS_NONE_TAG
import com.dynamictransport.dynamicone.feature.personnelfiles.VehicleDocumentsScreen
import com.dynamictransport.dynamicone.feature.personnelfiles.VehicleDocumentsUiState
import com.dynamictransport.dynamicone.testing.personnelDocument
import com.dynamictransport.dynamicone.testing.requirements
import com.dynamictransport.dynamicone.testing.vehicleDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w411dp-h891dp")
class PersonnelFileScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<FileToView>()
    private var retries = 0
    private var loadMores = 0
    private val today = LocalDate.of(2026, 9, 30)

    /** Every text is shown somewhere inside the tagged container (cards merge their children for screen readers). */
    private fun assertShows(tag: String, vararg texts: String) {
        texts.forEach { text ->
            composeRule.onNode(hasTestTag(tag).and(hasAnyDescendant(hasText(text, substring = true))), useUnmergedTree = true).assertExists()
        }
    }

    private fun showMyDocuments(state: MyDocumentsUiState) {
        composeRule.setContent {
            DynamicOneTheme {
                MyDocumentsScreen(
                    state = state,
                    onBack = null,
                    onOpenFile = { opened += it },
                    onRetry = { retries++ },
                    onLoadMore = { loadMores++ },
                    onMessageShown = {},
                    today = today,
                )
            }
        }
    }

    private val driverRequirements = requirements(
        DocumentRequirement(PersonnelDocumentType.PASSPORT, RequirementStatus.VALID, "d-pass", LocalDate.of(2030, 5, 1)),
        DocumentRequirement(PersonnelDocumentType.CODE_95, RequirementStatus.EXPIRED, "d-95", LocalDate.of(2026, 1, 31)),
        DocumentRequirement(PersonnelDocumentType.VISA, RequirementStatus.MISSING, null, null),
        DocumentRequirement(PersonnelDocumentType.DRIVING_LICENCE, RequirementStatus.EXPIRING_SOON, "d-lic", LocalDate.of(2026, 10, 15)),
    )

    @Test
    fun theSummaryShowsTheServerCountsWithWords() {
        showMyDocuments(MyDocumentsUiState(showRequirements = true, requirements = LoadedPart(driverRequirements)))

        assertShows("my_documents_summary_VALID", "1", "Valid")
        assertShows("my_documents_summary_EXPIRED", "1", "Expired")
        assertShows("my_documents_summary_EXPIRING_SOON", "1", "Expiring soon")
        assertShows("my_documents_summary_MISSING", "1", "Missing or incomplete")
    }

    @Test
    fun anExpiredDocumentOpensAndSaysItExpired() {
        showMyDocuments(
            MyDocumentsUiState(showRequirements = true, showHistory = true, requirements = LoadedPart(driverRequirements), history = LoadedPart(emptyList())),
        )

        composeRule.onNodeWithTag(MY_DOCUMENTS_LIST_TAG).performScrollToKey("requirement_CODE_95_d-95")
        assertShows("my_documents_requirement_CODE_95", "Expired on")
        composeRule.onNodeWithTag("my_documents_requirement_CODE_95")
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(listOf(FileToView("d-95", "Code 95", null)), opened)
    }

    @Test
    fun withoutTheHrModuleAnExistingFileShowsItsStateButDoesNotOpen() {
        // The server gives file contents to accounts with `hrDocuments` only.
        showMyDocuments(MyDocumentsUiState(showRequirements = true, requirements = LoadedPart(driverRequirements)))

        composeRule.onNodeWithTag(MY_DOCUMENTS_LIST_TAG).performScrollToKey("requirement_CODE_95_d-95")
        assertShows("my_documents_requirement_CODE_95", "Expired on", "Expired")
        composeRule.onAllNodes(hasTestTag("my_documents_requirement_CODE_95").and(hasClickAction())).assertCountEquals(0)
        composeRule.onAllNodes(
            hasTestTag("my_documents_requirement_CODE_95").and(hasAnyDescendant(hasText("No file"))),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun aMissingDocumentOffersNoFile() {
        showMyDocuments(MyDocumentsUiState(showRequirements = true, requirements = LoadedPart(driverRequirements)))

        composeRule.onNodeWithTag(MY_DOCUMENTS_LIST_TAG).performScrollToKey("requirement_VISA_null")
        val missing = composeRule.onNodeWithTag("my_documents_requirement_VISA")
        assertShows("my_documents_requirement_VISA", "No file", "Missing")
        composeRule.onAllNodes(hasTestTag("my_documents_requirement_VISA").and(hasClickAction())).assertCountEquals(0)
        missing.performClick()
        assertTrue(opened.isEmpty())
    }

    @Test
    fun theHistoryMarksExpiredFilesAndStillOpensThem() {
        showMyDocuments(
            MyDocumentsUiState(
                showHistory = true,
                history = LoadedPart(
                    listOf(
                        personnelDocument("d-old", PersonnelDocumentType.MEDICAL_CERTIFICATE, expiresOn = LocalDate.of(2026, 9, 29), isCurrent = false),
                        personnelDocument("d-new", PersonnelDocumentType.OTHER, displayName = "Onboarding checklist", contentType = "image/jpeg"),
                    ),
                ),
                canLoadMore = true,
            ),
        )

        composeRule.onNodeWithTag("my_documents_history_expired_d-old", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("my_documents_history_d-old").performClick()
        composeRule.onNodeWithText("Onboarding checklist").assertIsDisplayed()
        composeRule.onNodeWithTag("my_documents_history_d-new").performClick()
        composeRule.onNodeWithTag(MY_DOCUMENTS_LIST_TAG).performScrollToKey("history_more")
        composeRule.onNodeWithTag("my_documents_load_more").performClick()

        assertEquals(
            listOf(FileToView("d-old", "Medical certificate", "application/pdf"), FileToView("d-new", "Onboarding checklist", "image/jpeg")),
            opened,
        )
        assertEquals(1, loadMores)
    }

    @Test
    fun anEmptyPersonnelFileSaysSo() {
        showMyDocuments(MyDocumentsUiState(showHistory = true, history = LoadedPart(emptyList())))

        composeRule.onNodeWithTag("my_documents_history_empty").assertIsDisplayed()
    }

    @Test
    fun aFailedReadOffersRetry() {
        showMyDocuments(MyDocumentsUiState(showHistory = true, history = LoadedPart(error = R.string.feature_access_required)))

        assertShows("my_documents_history_error", "may not see this")
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    // --- Vehicle ---

    private fun showVehicle(state: VehicleDocumentsUiState) {
        composeRule.setContent {
            DynamicOneTheme {
                VehicleDocumentsScreen(state = state, onBack = {}, onOpenFile = { opened += it }, onRetry = { retries++ })
            }
        }
    }

    @Test
    fun withoutAnAssignedVehicleAClearEmptyStateIsShown() {
        showVehicle(VehicleDocumentsUiState(documents = LoadedPart(vehicleDocuments(null))))

        assertShows(VEHICLE_DOCUMENTS_NONE_TAG, "No vehicle is assigned to you", "When the office assigns you a vehicle")
    }

    @Test
    fun theVehicleFilesShowTheVehicleAndOpenOnlyWhatExists() {
        showVehicle(VehicleDocumentsUiState(documents = LoadedPart(vehicleDocuments("veh-1"))))

        composeRule.onNodeWithText("Dyna042 · WX 12345", substring = true).assertIsDisplayed()
        assertShows("vehicle_documents_requirement_INSURANCE_POLICY", "Available")
        assertShows("vehicle_documents_requirement_TRANSPORT_LICENSE", "No file", "Missing")
        composeRule.onNodeWithTag("vehicle_documents_requirement_INSURANCE_POLICY").performClick()
        composeRule.onNodeWithTag("vehicle_documents_requirement_TRANSPORT_LICENSE").performClick()

        assertEquals(listOf(FileToView("f-ins", "Insurance", "application/pdf")), opened)
    }

    // --- Viewer ---

    @Test
    fun theViewerExplainsAGoneFileAndOffersRetry() {
        composeRule.setContent {
            DynamicOneTheme {
                DocumentViewerScreen(
                    title = "Passport",
                    state = DocumentViewerUiState(loading = false, error = R.string.document_viewer_not_found),
                    onBack = {},
                    onRetry = { retries++ },
                )
            }
        }

        assertShows(DOCUMENT_VIEWER_ERROR_TAG, "no longer have access")
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }
}
