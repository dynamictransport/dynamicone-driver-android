package com.dynamictransport.dynamicone.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.domain.model.DocumentSlotState
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.ExecutionBlock
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import com.dynamictransport.dynamicone.domain.model.PendingTripAction
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.StopActivity
import com.dynamictransport.dynamicone.domain.model.TripContainer
import com.dynamictransport.dynamicone.domain.model.TripExecution
import com.dynamictransport.dynamicone.domain.model.TripLocation
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailScreen
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailUiState
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.testAction
import com.dynamictransport.dynamicone.testing.testExecution
import com.dynamictransport.dynamicone.testing.testSlot
import com.dynamictransport.dynamicone.testing.testStop
import com.dynamictransport.dynamicone.testing.testTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** docs/27: the trip screen of an export loading stop with two containers. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ExportSealScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val executed = mutableListOf<ExecutionAction>()
    private val confirmedLoading = mutableListOf<ExecutionAction>()
    private val recordedSeals = mutableListOf<Pair<String?, String>>()
    private val openedDocuments = mutableListOf<String?>()

    private val confirmLoading = testAction(ExecutionActionCode.CONFIRM_LOADING, "Confirm loading completed", "s1", null, stopName = "Antwerp PSA")
    private val sealFirst = testAction(ExecutionActionCode.RECORD_SEAL_NUMBER, "Save seal number", "s1", "c1", containerNumber = "MSKU 458921-7", stopName = "Antwerp PSA")
    private val sealSecond = testAction(ExecutionActionCode.RECORD_SEAL_NUMBER, "Save seal number", "s1", "c2", containerNumber = "TGHU 902114-6", stopName = "Antwerp PSA", sealNumber = "SEAL-002")
    private fun completion(containerId: String, reason: ExecutionBlock?) =
        testAction(ExecutionActionCode.COMPLETE_STOP, "Container loading completed", "s1", containerId)
            .copy(canExecute = reason == null, blockingReason = reason)

    private val photoFirst = testSlot("Seal:s1:c1", DocumentType.SEAL_PHOTO, DocumentSlotState.MISSING, stopId = "s1", containerId = "c1")
    private val photoSecond = testSlot("Seal:s1:c2", DocumentType.SEAL_PHOTO, DocumentSlotState.PENDING_REVIEW, stopId = "s1", containerId = "c2", documentId = "d2")

    private fun state(execution: TripExecution, pending: PendingTripAction? = null, submitting: Boolean = false) = TripDetailUiState(
        loading = false,
        trip = testTrip(
            state = TripState.STARTED,
            workflow = TripWorkflow.TRIP_ACTIONS,
            serverAllowedActions = emptySet(),
            stops = listOf(testStop("s1", 1, StopActivity.LOAD, "Antwerp PSA")),
            containers = listOf(TripContainer("c1", "MSKU 458921-7", null), TripContainer("c2", "TGHU 902114-6", "SEAL-002")),
            nextStop = TripLocation("s1", "Antwerp PSA", null),
        ),
        execution = execution,
        pendingAction = pending,
        submitting = submitting,
        refreshResult = RefreshResult.SUCCESS,
    )

    private fun setScreen(state: TripDetailUiState) {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                TripDetailScreen(
                    state = state,
                    onBack = {},
                    onPrimaryAction = {},
                    onSecondaryAction = {},
                    onOpenDocuments = { openedDocuments += it },
                    onOpenNavigation = { _, _ -> },
                    onReportIncident = {},
                    onMessageShown = {},
                    onExecutionAction = { executed += it },
                    onConfirmLoading = { confirmedLoading += it },
                    onRecordSealNumber = { action, seal -> recordedSeals += action.containerId to seal },
                )
            }
        }
    }

    @Test
    fun beforeTheConfirmationOnlyTheLoadingConfirmationIsOfferedAndTheStopIsNeverCompleted() {
        setScreen(
            state(
                testExecution(
                    actions = listOf(
                        confirmLoading,
                        completion("c1", ExecutionBlock.LOADING_CONFIRMATION_REQUIRED).copy(blockingDocumentSlots = listOf("Seal:s1:c1")),
                        completion("c2", ExecutionBlock.LOADING_CONFIRMATION_REQUIRED).copy(blockingDocumentSlots = listOf("Seal:s1:c2")),
                        testAction(ExecutionActionCode.REST, "Report a break"),
                    ),
                    documents = listOf(photoFirst, photoSecond),
                ),
            ),
        )

        composeRule.onNodeWithTag("seal_loading_hint").assertExists()
        // A hold-to-confirm button: the loading confirmation cannot be undone and starts the seal reminders.
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertExists()
        composeRule.onNode(hasAnyAncestor(hasTestTag(PRIMARY_ACTION_TAG)) and hasText("LOADING COMPLETED"), useUnmergedTree = true).assertExists()
        val button = composeRule.onNodeWithTag(PRIMARY_ACTION_TAG)
        button.performClick()
        composeRule.mainClock.advanceTimeBy(2_000)
        assertTrue(confirmedLoading.isEmpty())
        composeRule.mainClock.autoAdvance = false
        button.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(1_500)
        button.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(300)
        assertEquals(listOf(confirmLoading), confirmedLoading)
        composeRule.onNodeWithText("CONTAINER LOADING COMPLETED").assertDoesNotExist()
        composeRule.onNodeWithTag("execution_action_1").assertDoesNotExist()
        composeRule.onNodeWithTag("execution_no_action").assertDoesNotExist()
        composeRule.onNodeWithTag("required_documents").assertDoesNotExist()
        composeRule.onNodeWithTag("seal_card_c1").assertDoesNotExist()
        composeRule.onNodeWithTag("execution_REST").assertExists()
        composeRule.onNodeWithTag("execution_CONFIRM_LOADING").assertDoesNotExist()
        assertTrue(executed.isEmpty())
    }

    @Test
    fun eachContainerHasItsOwnSealCardWithSeparateNumberAndPhotoState() {
        setScreen(
            state(
                testExecution(
                    actions = listOf(sealFirst, sealSecond, completion("c1", ExecutionBlock.SEAL_NUMBER_REQUIRED), completion("c2", null)),
                    documents = listOf(photoFirst, photoSecond),
                ),
            ),
        )

        composeRule.onNodeWithTag("seal_hint").assertExists()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("CONTAINER LOADING COMPLETED").assertDoesNotExist()
        composeRule.onNodeWithTag("seal_waiting_planner").assertDoesNotExist()

        // First container: nothing recorded yet.
        composeRule.onNodeWithTag("seal_card_c1").assertExists()
        composeRule.onNodeWithText("Seal · Container MSKU 458921-7").assertExists()
        composeRule.onNode(hasAnyAncestor(hasTestTag("seal_number_state_c1")) and hasText("Seal number missing"), useUnmergedTree = true).assertExists()
        composeRule.onNode(hasAnyAncestor(hasTestTag("seal_photo_state_c1")) and hasText("Photo missing"), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("seal_save_c1").assertIsNotEnabled()
        composeRule.onNodeWithTag("seal_complete_c1").assertDoesNotExist()

        // Second container: the seal number is on the server, the photo waits for review; never "approved".
        composeRule.onNodeWithTag("seal_card_c2").performScrollTo()
        composeRule.onNode(hasAnyAncestor(hasTestTag("seal_number_state_c2")) and hasText("Saved: SEAL-002"), useUnmergedTree = true).assertExists()
        composeRule.onNode(hasAnyAncestor(hasTestTag("seal_photo_state_c2")) and hasText("Uploaded, waiting for review"), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Approved").assertDoesNotExist()
        composeRule.onNodeWithTag("seal_complete_c2").assertExists()
        // The recorded value is not re-saved unchanged.
        composeRule.onNodeWithTag("seal_save_c2").assertIsNotEnabled()

        // Typing a seal number for the first container enables its own save button only.
        composeRule.onNodeWithTag("seal_input_c1").performScrollTo().performTextInput(" SEAL-001 ")
        composeRule.onNodeWithTag("seal_save_c1").performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithTag("seal_save_c2").assertIsNotEnabled()
        assertEquals(listOf("c1" to " SEAL-001 "), recordedSeals)

        // The photo of each container opens its own slot.
        composeRule.onNodeWithTag("seal_photo_upload_c1").performScrollTo().assertTextContains("TAKE THE SEAL PHOTO").performClick()
        composeRule.onNodeWithTag("seal_photo_upload_c2").performScrollTo().assertTextContains("Replace the photo").performClick()
        assertEquals(listOf("Seal:s1:c1", "Seal:s1:c2"), openedDocuments)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun invalidSealNumbersCannotBeSavedAndRejectedPhotosAskForAnotherUpload() {
        setScreen(
            state(
                testExecution(
                    actions = listOf(sealSecond, completion("c2", null)),
                    documents = listOf(photoSecond.copy(state = DocumentSlotState.REJECTED, reviewNote = "Blurry")),
                ),
            ),
        )

        composeRule.onNodeWithTag("seal_complete_c2").assertDoesNotExist()
        composeRule.onNodeWithTag("seal_waiting_planner").assertDoesNotExist()
        composeRule.onNodeWithText("Reason: Blurry").assertExists()
        composeRule.onNodeWithTag("seal_photo_upload_c2").assertTextContains("UPLOAD THE PHOTO AGAIN")

        composeRule.onNodeWithTag("seal_input_c2").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("seal_save_c2").assertIsNotEnabled()
        composeRule.onNodeWithTag("seal_input_c2").performTextInput("   ")
        composeRule.onNodeWithTag("seal_save_c2").assertIsNotEnabled()
        composeRule.onNodeWithTag("seal_input_c2").performTextClearance()
        composeRule.onNodeWithTag("seal_input_c2").performTextInput("A".repeat(120))
        composeRule.onNodeWithText("100 / 100").assertExists()
        composeRule.onNodeWithTag("seal_save_c2").assertIsEnabled()
        assertTrue(recordedSeals.isEmpty())
    }

    @Test
    fun withEverySealRecordedTheScreenWaitsForThePlanner() {
        setScreen(
            state(
                testExecution(
                    actions = listOf(sealFirst.copy(sealNumber = "SEAL-001"), sealSecond, completion("c1", null), completion("c2", null)),
                    documents = listOf(photoFirst.copy(state = DocumentSlotState.APPROVED), photoSecond),
                ),
            ),
        )

        composeRule.onNodeWithTag("seal_waiting_planner").assertExists()
        composeRule.onNodeWithText("Seal details saved. Waiting for the planner's confirmation.").assertExists()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("CONTAINER LOADING COMPLETED").assertDoesNotExist()
        composeRule.onNodeWithTag("seal_complete_c1").assertExists()
        composeRule.onNodeWithTag("seal_complete_c2").assertExists()
    }

    @Test
    fun aSealCommandWithUnknownOutcomeIsShownOnItsCardAndMustBeResentFirst() {
        val pending = PendingTripAction("cmd-1", "trip-1", "v1", sealFirst, null, null, TEST_NOW, PendingCommandState.OUTCOME_UNKNOWN, sealNumber = "SEAL-001")
        setScreen(state(testExecution(actions = listOf(sealFirst, sealSecond), documents = listOf(photoFirst, photoSecond)), pending = pending))

        composeRule.onNodeWithTag("pending_action").assertExists()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("SEND THE SAME ACTION AGAIN")
        composeRule.onNodeWithTag("seal_pending_c1").performScrollTo().assertExists()
        composeRule.onNodeWithTag("seal_save_c1").assertIsNotEnabled()
        composeRule.onNodeWithTag("seal_pending_c2").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "tr-w411dp-h891dp")
    fun turkishTextsOfTheSealFlow() {
        setScreen(
            state(
                testExecution(
                    actions = listOf(sealFirst.copy(sealNumber = "SEAL-001"), completion("c1", null)),
                    documents = listOf(photoFirst.copy(state = DocumentSlotState.PENDING_REVIEW, documentId = "d1")),
                ),
            ),
        )

        composeRule.onNodeWithText("Mühür bilgileri kaydedildi. Planlamacı onayı bekleniyor.").assertExists()
        composeRule.onNodeWithText("Mühür · Konteyner MSKU 458921-7").assertExists()
        composeRule.onNode(hasAnyAncestor(hasTestTag("seal_photo_state_c1")) and hasText("Yüklendi, inceleme bekliyor"), useUnmergedTree = true).assertExists()
    }
}
