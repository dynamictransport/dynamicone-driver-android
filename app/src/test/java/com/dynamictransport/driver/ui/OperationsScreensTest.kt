package com.dynamictransport.driver.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.driver.core.design.theme.DynamicOneDriverTheme
import com.dynamictransport.driver.domain.model.Conversation
import com.dynamictransport.driver.domain.model.DocumentSlotState
import com.dynamictransport.driver.domain.model.DocumentType
import com.dynamictransport.driver.domain.model.DriverMessage
import com.dynamictransport.driver.domain.model.ExecutionAction
import com.dynamictransport.driver.domain.model.ExecutionActionCode
import com.dynamictransport.driver.domain.model.FinalizedMonthlyPay
import com.dynamictransport.driver.domain.model.GeoPoint
import com.dynamictransport.driver.domain.model.MessageDirection
import com.dynamictransport.driver.domain.model.MonthlyPayroll
import com.dynamictransport.driver.domain.model.PayrollCurrency
import com.dynamictransport.driver.domain.model.PayrollLine
import com.dynamictransport.driver.domain.model.PendingCommandState
import com.dynamictransport.driver.domain.model.PendingMessage
import com.dynamictransport.driver.domain.model.PendingTripAction
import com.dynamictransport.driver.domain.model.RefreshResult
import com.dynamictransport.driver.domain.model.StopActivity
import com.dynamictransport.driver.domain.model.TripContainer
import com.dynamictransport.driver.domain.model.TripExecution
import com.dynamictransport.driver.domain.model.TripLocation
import com.dynamictransport.driver.domain.model.TripState
import com.dynamictransport.driver.domain.model.TripWorkflow
import com.dynamictransport.driver.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.driver.feature.documents.DocumentsScreen
import com.dynamictransport.driver.feature.documents.DocumentsUiState
import com.dynamictransport.driver.feature.messages.MessagesScreen
import com.dynamictransport.driver.feature.messages.MessagesUiState
import com.dynamictransport.driver.feature.payroll.PayrollScreen
import com.dynamictransport.driver.feature.payroll.PayrollUiState
import com.dynamictransport.driver.feature.tripdetail.TripDetailScreen
import com.dynamictransport.driver.feature.tripdetail.TripDetailUiState
import com.dynamictransport.driver.testing.TEST_NOW
import com.dynamictransport.driver.testing.testAction
import com.dynamictransport.driver.testing.testExecution
import com.dynamictransport.driver.testing.testSlot
import com.dynamictransport.driver.testing.testStop
import com.dynamictransport.driver.testing.testTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class TripExecutionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val executed = mutableListOf<ExecutionAction>()
    private val navigations = mutableListOf<GeoPoint>()
    private var resent = 0

    private val stops = listOf(
        testStop("s1", 1, StopActivity.PICKUP, "Rotterdam ECT Delta"),
        testStop("s2", 2, StopActivity.UNLOAD, "Venlo", coordinates = null),
    )
    private val containers = listOf(TripContainer("c1", "MSKU 458921-7", "781392"), TripContainer("c2", "TGHU 902114-6", null))

    private fun state(
        actions: List<ExecutionAction>,
        pending: PendingTripAction? = null,
        execution: TripExecution? = testExecution(actions = actions),
        refresh: RefreshResult = RefreshResult.SUCCESS,
    ) = TripDetailUiState(
        loading = false,
        trip = testTrip(
            state = TripState.STARTED,
            workflow = TripWorkflow.TRIP_ACTIONS,
            serverAllowedActions = emptySet(),
            stops = stops,
            containers = containers,
            nextStop = TripLocation("s2", "Venlo", null),
        ),
        execution = execution,
        pendingAction = pending,
        refreshResult = refresh,
    )

    private fun setScreen(state: TripDetailUiState) {
        composeRule.setContent {
            DynamicOneDriverTheme(darkTheme = false) {
                TripDetailScreen(
                    state = state,
                    onBack = {},
                    onPrimaryAction = {},
                    onSecondaryAction = {},
                    onOpenDocuments = {},
                    onOpenNavigation = { point, _ -> navigations += point },
                    onReportIncident = {},
                    onMessageShown = {},
                    onExecutionAction = { executed += it },
                    onResendPending = { resent++ },
                )
            }
        }
    }

    @Test
    fun serverOptionsAreTheOnlyActionsAndEachContainerIsSeparate() {
        val first = testAction(ExecutionActionCode.COMPLETE_STOP, "I picked up the full container", "s1", "c1")
        val second = testAction(ExecutionActionCode.COMPLETE_STOP, "I picked up the full container", "s1", "c2")
        setScreen(state(listOf(first, second, testAction(ExecutionActionCode.REST, "Report a break"), testAction(ExecutionActionCode.PROBLEM, "Report a problem"))))

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("I PICKED UP THE FULL CONTAINER")
        composeRule.onNodeWithText("1. Pickup · Rotterdam ECT Delta · Container MSKU 458921-7").assertExists()
        composeRule.onNodeWithText("1. Pickup · Rotterdam ECT Delta · Container TGHU 902114-6").assertExists()
        composeRule.onNodeWithTag("execution_REST").assertTextContains("REPORT A BREAK")
        composeRule.onNodeWithTag("execution_PROBLEM").assertExists()
        composeRule.onNodeWithTag("secondary_START_BREAK").assertDoesNotExist()

        composeRule.onNodeWithTag("execution_action_1").performClick()

        assertEquals(listOf(second), executed)
    }

    @Test
    fun unknownServerLabelIsShownAsSent() {
        setScreen(state(listOf(testAction(ExecutionActionCode.COMPLETE_STOP, "I checked the reefer unit", "s1", null))))

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("I checked the reefer unit")
    }

    @Test
    @Config(qualifiers = "tr-w411dp-h891dp")
    fun knownServerLabelsAreTranslated() {
        setScreen(state(listOf(testAction(ExecutionActionCode.COMPLETE_STOP, "I delivered the empty container", "s1", "c1"))))

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("BOŞ KONTEYNERİ TESLİM ETTİM")
        composeRule.onNodeWithText("1. Alım · Rotterdam ECT Delta · Konteyner MSKU 458921-7", substring = true).assertExists()
    }

    @Test
    fun storedActionWithUnknownOutcomeMustBeResentFirst() {
        val start = testAction(ExecutionActionCode.START, "I started the trip")
        setScreen(state(listOf(start), pending = PendingTripAction("cmd-1", "trip-1", "v1", start, null, null, TEST_NOW, PendingCommandState.OUTCOME_UNKNOWN)))

        composeRule.onNodeWithTag("pending_action").assertExists()
        composeRule.onNodeWithText("Connection lost — it is not known whether the server recorded it.", substring = true).assertExists()
        composeRule.onNodeWithText("I STARTED THE TRIP").assertDoesNotExist()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("SEND THE SAME ACTION AGAIN").performClick()

        assertEquals(1, resent)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun missingCoordinatesAreExplainedAndNeverNavigated() {
        setScreen(state(emptyList()))

        composeRule.onNodeWithTag("execution_no_action").assertExists()
        composeRule.onNodeWithTag("next_stop_location_missing").assertExists()
        composeRule.onNodeWithTag("next_stop_navigate").assertDoesNotExist()
        composeRule.onNodeWithTag("stop_location_missing_s2").performScrollTo()
        composeRule.onNodeWithTag("stop_navigate_s2").assertDoesNotExist()
        composeRule.onNodeWithTag("stop_navigate_s1").performScrollTo().performClick()

        assertEquals(listOf(GeoPoint(51.9, 4.1)), navigations)
    }

    @Test
    fun planThatCouldNotBeLoadedOffersRetryNotActions() {
        setScreen(state(emptyList(), execution = null, refresh = RefreshResult.SERVICE_UNAVAILABLE))

        composeRule.onNodeWithTag("execution_error").assertExists()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertDoesNotExist()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class DocumentSlotsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val trip = testTrip(
        state = TripState.STARTED,
        stops = listOf(testStop("s1", 1, StopActivity.PICKUP, "Rotterdam ECT Delta")),
        containers = listOf(TripContainer("c1", "MSKU 458921-7", null)),
    )
    private val cmr = testSlot("Cmr:trip:all", DocumentType.CMR, DocumentSlotState.MISSING)
    private val customs = testSlot("Customs:trip:all", DocumentType.CUSTOMS, DocumentSlotState.NOT_REQUIRED, required = false)
    private val interchange = testSlot(
        "Interchange:s1:c1", DocumentType.INTERCHANGE, DocumentSlotState.REJECTED, stopId = "s1", containerId = "c1", documentId = "d1", reviewNote = "Unreadable",
    )
    private val seal = testSlot("Seal:s1:c1", DocumentType.SEAL_PHOTO, DocumentSlotState.PENDING_REVIEW, stopId = "s1", containerId = "c1", documentId = "d2")
    private val slots = listOf(cmr, customs, interchange, seal)

    @Test
    fun slotsShowStateRequirementAndRejectionReasonSeparately() {
        composeRule.setContent {
            DynamicOneDriverTheme(darkTheme = false) {
                DocumentsScreen(
                    state = DocumentsUiState(loading = false, trip = trip, slots = slots, selectedSlotKey = cmr.key),
                    onBack = null,
                    onSelectSlot = {},
                    onTakePhoto = {},
                    onPickFromGallery = {},
                    onMessageShown = {},
                )
            }
        }

        composeRule.onNodeWithText("Customs document (A1)").assertExists()
        composeRule.onNodeWithTag("slot_state_Cmr:trip:all", useUnmergedTree = true).assertTextEquals("Missing · Required")
        composeRule.onNodeWithTag("slot_state_Customs:trip:all", useUnmergedTree = true).assertTextEquals("Not required · Not mandatory")
        composeRule.onNodeWithTag("slot_state_Seal:s1:c1", useUnmergedTree = true).assertTextEquals("Waiting for check · Required")
        composeRule.onNodeWithTag("slot_review_note_Interchange:s1:c1", useUnmergedTree = true).assertTextEquals("Reason: Unreadable")
        composeRule.onNode(hasAnyAncestor(hasTestTag("documents_open_count")) and hasText("2 to upload")).assertExists()
    }

    @Test
    fun photoOnlySlotsOfferNoPdf() {
        var state by mutableStateOf(DocumentsUiState(loading = false, trip = trip, slots = slots, selectedSlotKey = seal.key))
        composeRule.setContent {
            DynamicOneDriverTheme(darkTheme = false) {
                DocumentsScreen(state = state, onBack = null, onSelectSlot = {}, onTakePhoto = {}, onPickFromGallery = {}, onMessageShown = {})
            }
        }

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("TAKE PHOTO · Seal photo")
        composeRule.onNodeWithTag("documents_pick_pdf").assertDoesNotExist()

        state = state.copy(selectedSlotKey = cmr.key)

        composeRule.onNodeWithTag("documents_pick_pdf").assertExists()
    }

    @Test
    fun newAssignmentCannotReceiveDocuments() {
        composeRule.setContent {
            DynamicOneDriverTheme(darkTheme = false) {
                DocumentsScreen(
                    state = DocumentsUiState(loading = false, trip = testTrip(state = TripState.ASSIGNED), slots = listOf(cmr), selectedSlotKey = cmr.key),
                    onBack = null,
                    onSelectSlot = {},
                    onTakePhoto = {},
                    onPickFromGallery = {},
                    onMessageShown = {},
                )
            }
        }

        composeRule.onNodeWithTag("documents_upload_not_possible").assertExists()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertDoesNotExist()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class PayrollScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val month = YearMonth.of(2026, 9)

    private fun setScreen(state: PayrollUiState) {
        composeRule.setContent {
            DynamicOneDriverTheme(darkTheme = false) {
                PayrollScreen(state = state, onBack = {}, onPreviousMonth = {}, onNextMonth = {}, onRefresh = {})
            }
        }
    }

    @Test
    fun unpublishedMonthShowsNoAmounts() {
        setScreen(PayrollUiState(month = month, canGoBack = true, canGoForward = false, payroll = MonthlyPayroll.Unpublished(month)))

        composeRule.onNodeWithTag("payroll_unpublished").assertExists()
        composeRule.onNodeWithText("Pay information for this month has not been published yet.").assertExists()
        composeRule.onNodeWithTag("payroll_currency_PLN").assertDoesNotExist()
        composeRule.onNodeWithTag("payroll_next").assertIsNotEnabled()
    }

    @Test
    fun publishedPayrollKeepsCurrenciesSeparateAndIsNotNetPay() {
        val payroll = MonthlyPayroll.Published(
            month = month,
            publishedAt = TEST_NOW,
            currencies = listOf(
                PayrollCurrency(
                    currencyCode = "PLN",
                    baseGross = BigDecimal("5000.10"),
                    additions = listOf(
                        PayrollLine("Bonus", BigDecimal("0.20"), LocalDate.of(2026, 9, 1)),
                        PayrollLine("FutureAllowance", BigDecimal("12.345"), null),
                    ),
                    deductions = listOf(PayrollLine("TrafficFine", BigDecimal("300.00"), null)),
                    grossEarnings = BigDecimal("5000.30"),
                    totalDeductions = BigDecimal("300.00"),
                    balanceBeforeStatutoryDeductions = BigDecimal("4700.30"),
                ),
                PayrollCurrency("EUR", null, emptyList(), emptyList(), null, null, null),
            ),
            finalized = FinalizedMonthlyPay(null, null, BigDecimal("7.5"), null),
        )
        setScreen(PayrollUiState(month = month, canGoBack = true, canGoForward = false, payroll = payroll))

        composeRule.onNodeWithTag("payroll_currency_PLN").assertExists()
        composeRule.onNodeWithText("Traffic fine").performScrollTo()
        composeRule.onNodeWithText("FutureAllowance").assertExists()
        composeRule.onNodeWithText("12.345", substring = true).assertExists()
        composeRule.onNodeWithTag("payroll_currency_EUR").performScrollTo()
        // A missing amount is unknown, never zero.
        composeRule.onNode(hasAnyAncestor(hasTestTag("payroll_balance_EUR")) and hasText("—")).assertExists()
        composeRule.onAllNodesWithText("This is not net pay and not the amount paid to your bank account.").assertCountEquals(2)
        composeRule.onNodeWithTag("payroll_finalized").performScrollTo()
    }

    @Test
    fun earningsCarryAPlusDeductionsAMinusAndTheFinalFigureNoSign() {
        val payroll = MonthlyPayroll.Published(
            month = month,
            publishedAt = TEST_NOW,
            currencies = listOf(
                PayrollCurrency(
                    currencyCode = "EUR",
                    baseGross = BigDecimal("3000.00"),
                    additions = listOf(PayrollLine("Bonus", BigDecimal("50.00"), null)),
                    deductions = listOf(PayrollLine("VisaTravelExpense", BigDecimal("100.00"), LocalDate.of(2026, 9, 14))),
                    grossEarnings = BigDecimal("3050.00"),
                    totalDeductions = BigDecimal("100.00"),
                    balanceBeforeStatutoryDeductions = BigDecimal("2950.00"),
                ),
            ),
            finalized = null,
        )
        setScreen(PayrollUiState(month = month, canGoBack = true, canGoForward = false, payroll = payroll))

        // The sign is text next to the colour, so the meaning never depends on colour alone.
        composeRule.onNode(hasAnyAncestor(hasTestTag("payroll_earnings_total_EUR")) and hasText("+", substring = true)).assertExists()
        composeRule.onNode(hasAnyAncestor(hasTestTag("payroll_deductions_total_EUR")) and hasText("−", substring = true)).assertExists()
        composeRule.onAllNodes(hasAnyAncestor(hasTestTag("payroll_balance_EUR")) and hasText("+", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasAnyAncestor(hasTestTag("payroll_balance_EUR")) and hasText("−", substring = true)).assertCountEquals(0)
        composeRule.onNode(hasAnyAncestor(hasTestTag("payroll_additions_EUR")) and hasText("+", substring = true)).performScrollTo().assertExists()
        composeRule.onNode(hasAnyAncestor(hasTestTag("payroll_deductions_EUR")) and hasText("−", substring = true)).performScrollTo().assertExists()
        composeRule.onNodeWithText("Earnings").assertExists()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class MessagesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val visible = mutableListOf<DriverMessage>()
    private val resent = mutableListOf<String>()

    private fun setScreen(state: MessagesUiState) {
        composeRule.setContent {
            DynamicOneDriverTheme(darkTheme = false) {
                MessagesScreen(
                    state = state,
                    onBack = null,
                    onDraftChanged = {},
                    onSend = {},
                    onResend = { resent += it },
                    onDiscard = {},
                    onLoadOlder = {},
                    onMessageVisible = { visible += it },
                    onRetry = {},
                    onMessageShown = {},
                )
            }
        }
    }

    @Test
    fun incomingMessagesNameTheirSenderAndOwnMessagesStayYou() {
        val named = DriverMessage("m1", null, "Gate 4", MessageDirection.TO_DRIVER, TEST_NOW, null, senderName = "Ayşe Yılmaz")
        val outgoing = DriverMessage("m2", null, "On my way", MessageDirection.FROM_DRIVER, TEST_NOW.plusSeconds(60), null)
        setScreen(MessagesUiState(conversation = Conversation(null, listOf(named, outgoing), emptyList(), canLoadOlder = false, loaded = true)))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Ayşe Yılmaz").assertExists()
        composeRule.onNodeWithText("Office").assertDoesNotExist()
        composeRule.onNodeWithText("You").assertExists()
    }

    @Test
    fun displayedMessagesAreReportedAndStoredMessagesOfferResend() {
        val incoming = DriverMessage("m1", null, "Gate 4", MessageDirection.TO_DRIVER, TEST_NOW, null)
        val outgoing = DriverMessage("m2", null, "On my way", MessageDirection.FROM_DRIVER, TEST_NOW.plusSeconds(60), TEST_NOW.plusSeconds(120))
        val stored = PendingMessage("msg-1", null, "At the gate", TEST_NOW.plusSeconds(180), PendingCommandState.OUTCOME_UNKNOWN)
        setScreen(MessagesUiState(conversation = Conversation(null, listOf(incoming, outgoing), listOf(stored), canLoadOlder = false, loaded = true)))
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        assertTrue(visible.any { it.id == "m1" })
        composeRule.onNodeWithText("Gate 4").assertExists()
        composeRule.onNodeWithText("Office").assertExists()
        composeRule.onNodeWithText("Connection lost — it is not known whether the server recorded it.").assertExists()
        composeRule.onNodeWithTag("pending_message_resend", useUnmergedTree = true).performClick()

        assertEquals(listOf("msg-1"), resent)
    }

    @Test
    fun onlyMessagesOnScreenAreReportedAsSeen() {
        val many = (0 until 30).map { DriverMessage("m$it", null, "Message $it", MessageDirection.TO_DRIVER, TEST_NOW.plusSeconds(it.toLong()), null) }
        setScreen(MessagesUiState(conversation = Conversation(null, many, emptyList(), canLoadOlder = false, loaded = true)))
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        // The list follows the newest message: older unread messages that were never on screen are not read.
        composeRule.onNodeWithText("Message 29").assertExists()
        composeRule.onNodeWithText("Message 0").assertDoesNotExist()
        assertTrue(visible.any { it.id == "m29" })
        assertTrue(visible.none { it.id == "m0" })
    }

    @Test
    fun blankDraftCannotBeSent() {
        setScreen(MessagesUiState(draft = "   ", conversation = Conversation(null, emptyList(), emptyList(), canLoadOlder = false, loaded = true)))

        composeRule.onNodeWithTag("message_send").assertIsNotEnabled()
        composeRule.onNodeWithText("No messages yet.").assertExists()
    }
}
