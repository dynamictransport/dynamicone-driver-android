package com.dynamictransport.dynamicone.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.alarm.RestAlarmPermissionState
import com.dynamictransport.dynamicone.core.alarm.RestAlarmStatus
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.core.nfc.NfcAvailability
import com.dynamictransport.dynamicone.domain.model.CheckAnswer
import com.dynamictransport.dynamicone.domain.model.CheckPhoto
import com.dynamictransport.dynamicone.domain.model.CheckResult
import com.dynamictransport.dynamicone.domain.model.CheckTemplate
import com.dynamictransport.dynamicone.domain.model.LocalShiftCommand
import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.ShiftCommandFailure
import com.dynamictransport.dynamicone.domain.model.ShiftCommandState
import com.dynamictransport.dynamicone.domain.model.ShiftCommandType
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.domain.model.VehicleInfo
import com.dynamictransport.dynamicone.domain.rules.RestAlarmMode
import com.dynamictransport.dynamicone.domain.rules.RestAlarmPlan
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.restalarm.RestAlarmScreen
import com.dynamictransport.dynamicone.feature.shift.DriverDayCard
import com.dynamictransport.dynamicone.feature.shift.DriverDayState
import com.dynamictransport.dynamicone.feature.shift.ShiftScreen
import com.dynamictransport.dynamicone.feature.shift.ShiftUiState
import com.dynamictransport.dynamicone.feature.shiftstart.StartShiftScreen
import com.dynamictransport.dynamicone.feature.shiftstart.StartShiftStep
import com.dynamictransport.dynamicone.feature.shiftstart.StartShiftUiState
import com.dynamictransport.dynamicone.testing.ACTIVE_SHIFT
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.restingTimes
import com.dynamictransport.dynamicone.testing.workingTimes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.util.TimeZone

/** Driver daily shift screens (docs/16): large single actions, status with icon and text. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class DailyShiftScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()
    private lateinit var defaultZone: TimeZone
    private val items = CheckTemplate.Default.items

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() = TimeZone.setDefault(defaultZone)

    private fun setStartShift(state: StartShiftUiState) {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                StartShiftScreen(
                    state = state,
                    onBack = { calls += "back" },
                    onClose = { calls += "close" },
                    onRestWarningConfirmed = { calls += "rest_confirmed" },
                    onOpenNfcSettings = { calls += "nfc_settings" },
                    onUsePlannedVehicle = { calls += "planned" },
                    onRescan = { calls += "rescan" },
                    onStartCheck = { calls += "start_check" },
                    onAnswerOk = { calls += "ok" },
                    onAnswerNotApplicable = { calls += "na" },
                    onDefect = { calls += "defect" },
                    onNoteChanged = { calls += "note" },
                    onTakePhoto = { calls += "photo" },
                    onRemovePhoto = { calls += "remove_$it" },
                    onDefectDone = { calls += "defect_done" },
                    onEditItem = { calls += "edit_$it" },
                    onSubmit = { calls += "submit" },
                    onStartOver = { calls += "start_over" },
                    onMessageShown = {},
                    now = TEST_NOW,
                )
            }
        }
    }

    /** Tagged containers do not merge their children's text; the text is looked up below the tag. */
    private fun assertTagHasText(tag: String, text: String) {
        composeRule.onNode(hasTestTag(tag) and hasAnyDescendant(hasText(text, substring = true))).assertExists()
    }

    private fun checkState(index: Int, answers: Map<String, CheckAnswer> = emptyMap()) = StartShiftUiState(
        step = StartShiftStep.CHECK,
        vehicle = VehicleInfo("veh-1", "WX 12345"),
        vehicleVerified = true,
        template = CheckTemplate.Default,
        itemIndex = index,
        answers = answers,
    )

    @Test
    fun restNotOverAsksToHoldBeforeStarting() {
        setStartShift(StartShiftUiState(step = StartShiftStep.REST_WARNING, restEndsAt = TEST_NOW.plus(Duration.ofMinutes(25))))

        composeRule.onNodeWithTag("start_shift_rest_warning").assertIsDisplayed()
        composeRule.onNodeWithText("According to the tachograph your daily rest ends at 08:25 (0 h 25 min left).").assertIsDisplayed()
        // A tap does not start: the button must be held (accessibility click confirms).
        assertTagHasText(PRIMARY_ACTION_TAG, "START ANYWAY")
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()
        assertEquals(emptyList<String>(), calls)
        composeRule.onNodeWithText("START ANYWAY").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("rest_confirmed"), calls)
    }

    @Test
    fun scanWaitsForTheVehicleTagAndOffersThePlannedVehicle() {
        setStartShift(
            StartShiftUiState(
                step = StartShiftStep.SCAN,
                nfc = NfcAvailability.DISABLED,
                plannedVehicle = VehicleInfo("veh-1", "WX 12345"),
            ),
        )

        composeRule.onNodeWithTag("start_shift_scanning").assertIsDisplayed()
        composeRule.onNodeWithTag("start_shift_nfc_disabled").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("TURN ON NFC").assertHeightIsAtLeast(64.dp).performClick()
        composeRule.onNodeWithTag("start_shift_use_planned").assertTextContains("Tag does not read: use WX 12345").performClick()
        assertEquals(listOf("nfc_settings", "planned"), calls)
    }

    @Test
    fun vehicleShowsThePlateAndWarnsAboutAnotherVehicle() {
        setStartShift(
            StartShiftUiState(
                step = StartShiftStep.VEHICLE,
                vehicle = VehicleInfo("veh-1", "WX 12345", "DAF XF 480"),
                vehicleVerified = true,
                vehiclePlanned = false,
                plannedVehicle = VehicleInfo("veh-2", "WX 99999", trailerRegistrationNumber = "WGM 1234A"),
            ),
        )

        composeRule.onNodeWithTag("start_shift_plate").assertTextContains("WX 12345")
        assertTagHasText("start_shift_other_vehicle", "WX 99999")
        composeRule.onNodeWithText("Trailer: WGM 1234A").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("START CHECK").performClick()
        assertEquals(listOf("start_check"), calls)
    }

    @Test
    fun oneItemPerPageWithLargeOkAndProblemButtons() {
        setStartShift(checkState(index = 2))

        composeRule.onNodeWithTag("check_progress").assertTextContains("Item 3 of ${items.size}")
        composeRule.onNodeWithText("Lights and indicators work").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("OK").assertHeightIsAtLeast(64.dp).performClick()
        composeRule.onNodeWithTag("check_answer_defect").assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(listOf("ok", "defect"), calls)
    }

    @Test
    fun notApplicableIsOfferedOnlyWhereAllowed() {
        val containerIndex = items.indexOfFirst { it.key == "container_locks" }
        setStartShift(checkState(index = containerIndex))

        composeRule.onNodeWithText("Only when a container is loaded").assertIsDisplayed()
        composeRule.onNodeWithTag("check_answer_not_applicable").performClick()
        assertEquals(listOf("na"), calls)
    }

    @Test
    fun aDefectWithoutPhotoCannotContinue() {
        setStartShift(checkState(index = 0, answers = mapOf("no_new_damage" to CheckAnswer("no_new_damage", CheckResult.DEFECT))))

        composeRule.onNodeWithTag("check_defect_panel").assertIsDisplayed()
        composeRule.onNodeWithText("Take a photo of the problem").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("CONTINUE").assertIsNotEnabled()
        composeRule.onNodeWithTag("check_take_photo").performScrollTo().performClick()
        composeRule.onNodeWithTag("check_answer_ok_after_all").performClick()
        assertEquals(listOf("photo", "ok"), calls)
    }

    @Test
    fun summaryListsDefectsAndIsConfirmedByHolding() {
        val answers = items.associate { it.key to CheckAnswer(it.key, CheckResult.OK) } +
            ("lights" to CheckAnswer("lights", CheckResult.DEFECT, "Left brake light", listOf(CheckPhoto("p1", "/missing.jpg"))))
        setStartShift(
            StartShiftUiState(
                step = StartShiftStep.SUMMARY,
                vehicle = VehicleInfo("veh-1", "WX 12345"),
                template = CheckTemplate.Default,
                answers = answers,
                restWarningAcknowledged = true,
            ),
        )

        composeRule.onNodeWithText("${items.size} items checked").assertIsDisplayed()
        composeRule.onNodeWithText("1 problem reported").assertIsDisplayed()
        composeRule.onNodeWithText("Started before the end of the rest").assertIsDisplayed()
        composeRule.onNodeWithTag("summary_defect_lights").performScrollTo().assertTextContains("Left brake light", substring = true).performClick()
        assertTagHasText(PRIMARY_ACTION_TAG, "SEND CHECK AND START SHIFT")
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()
        assertEquals(listOf("edit_lights"), calls)
        composeRule.onNodeWithText("SEND CHECK AND START SHIFT").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("edit_lights", "submit"), calls)
    }

    @Test
    fun aRejectedStartExplainsWhyAndOffersANewStart() {
        setStartShift(
            StartShiftUiState(
                step = StartShiftStep.SUMMARY,
                template = CheckTemplate.Default,
                answers = items.associate { it.key to CheckAnswer(it.key, CheckResult.OK) },
                failure = ShiftCommandFailure.TAG_UNKNOWN,
            ),
        )

        assertTagHasText("start_shift_failed", "the scanned tag is not registered")
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("START AGAIN").performClick()
        assertEquals(listOf("start_over"), calls)
    }

    @Test
    fun savedOnThePhoneIsNotShownAsStarted() {
        setStartShift(StartShiftUiState(step = StartShiftStep.DONE, recorded = false, vehicle = VehicleInfo("veh-1", "WX 12345")))

        composeRule.onNodeWithTag("start_shift_saved").assertIsDisplayed()
        composeRule.onNodeWithText("Saved on this phone").assertIsDisplayed()
        composeRule.onNodeWithText("The shift and the check are sent automatically when internet is back.").assertIsDisplayed()
    }

    private val restEnd = Instant.parse("2026-09-12T11:40:00Z")
    private val rest = RestWindow(restEnd.minus(Duration.ofHours(9)), restEnd)

    private fun setCard(state: DriverDayState, shift: Shift = Shift.NotStarted.copy(requiresVehicleCheck = true)) {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                DriverDayCard(
                    state = state,
                    shift = shift,
                    now = TEST_NOW,
                    onDismissAlarm = { calls += "dismiss" },
                    onRestoreAlarm = { calls += "restore" },
                    onOpenAlarmSettings = { calls += "settings" },
                )
            }
        }
    }

    @Test
    fun restCardShowsTheEndAndTheAlarm() {
        setCard(
            DriverDayState(
                times = restingTimes(end = restEnd, start = rest.start),
                alarm = RestAlarmStatus(RestAlarmPlan.Scheduled(rest, restEnd), RestAlarmMode.ALARM, Duration.ZERO, RestAlarmPermissionState(exactAlarms = false)),
            ),
        )

        composeRule.onNodeWithTag("driver_day_rest_end").assertTextContains("Ends at 11:40")
        composeRule.onNodeWithText("3 h 40 min left").assertIsDisplayed()
        assertTagHasText("driver_day_alarm_scheduled", "Alarm at 11:40")
        composeRule.onNodeWithText("Switch off").performClick()
        composeRule.onNodeWithTag("driver_day_alarm_permission").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").performClick()
        assertEquals(listOf("dismiss", "settings"), calls)
    }

    @Test
    fun workingCardShowsRemainingDrivingAndNextBreak() {
        setCard(DriverDayState(times = workingTimes(), alarm = null), shift = ACTIVE_SHIFT.copy(requiresVehicleCheck = true))

        assertTagHasText("driver_day_driving_left", "Driving left today: 2 h 5 min")
        composeRule.onNodeWithText("Next break by 09:30 (0 h 45 min)").assertIsDisplayed()
        composeRule.onNodeWithText("Tachograph data 08:00 · indicative").assertIsDisplayed()
    }

    @Test
    fun workingCardHidesClockTimesFromASnapshotOlderThanADay() {
        // The server returns the newest stored snapshot however old it is; 25 h old break/shift times would be past dates.
        val reference = TEST_NOW.minus(Duration.ofHours(25))
        val times = workingTimes(reference = reference).copy(latestShiftEnd = reference.plus(Duration.ofHours(6)))
        setCard(DriverDayState(times = times, alarm = null), shift = ACTIVE_SHIFT.copy(requiresVehicleCheck = true))

        assertTagHasText("driver_day_driving_left", "Driving left today: 2 h 5 min")
        assertTagHasText("driver_day_outdated", "Tachograph data is out of date: last known")
        composeRule.onNodeWithTag("driver_day_break_due").assertDoesNotExist()
        composeRule.onNodeWithTag("driver_day_shift_end").assertDoesNotExist()
        composeRule.onNodeWithText("Indicative values; the tachograph is authoritative.").assertIsDisplayed()
    }

    @Test
    fun workingCardKeepsClockTimesFromASnapshotYoungerThanADay() {
        val reference = TEST_NOW.minus(Duration.ofHours(23))
        setCard(DriverDayState(times = workingTimes(reference = reference), alarm = null), shift = ACTIVE_SHIFT.copy(requiresVehicleCheck = true))

        composeRule.onNodeWithTag("driver_day_break_due").assertIsDisplayed()
        composeRule.onNodeWithTag("driver_day_outdated").assertDoesNotExist()
        composeRule.onNodeWithText("Last known tachograph data: ", substring = true).assertIsDisplayed()
    }

    @Test
    fun wakeUpAlarmOffersStartingTheShift() {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                RestAlarmScreen(
                    restEnd = TEST_NOW,
                    test = false,
                    onStartShift = { calls += "start" },
                    onSnooze = { calls += "snooze" },
                    onStop = { calls += "stop" },
                    now = TEST_NOW.plusSeconds(60),
                )
            }
        }

        composeRule.onNodeWithTag("rest_alarm_title").assertTextContains("Your rest is over")
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("START SHIFT").assertHeightIsAtLeast(64.dp).performClick()
        composeRule.onNodeWithTag("rest_alarm_snooze").performClick()
        composeRule.onNodeWithTag("rest_alarm_stop").performClick()
        assertEquals(listOf("start", "snooze", "stop"), calls)
    }

    @Test
    fun dailyShiftCanEndDuringATripAndShowsARejectedStart() {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                ShiftScreen(
                    state = ShiftUiState(
                        loading = false,
                        shift = ACTIVE_SHIFT.copy(requiresVehicleCheck = true, status = ShiftStatus.ACTIVE),
                        canEndShift = true,
                        tripInProgress = true,
                        commands = listOf(
                            LocalShiftCommand("c9", ShiftCommandType.START, TEST_NOW, ShiftCommandState.FAILED, ShiftCommandFailure.TAG_UNKNOWN, "WX 1", null, 0),
                        ),
                    ),
                    onBack = {},
                    onStartShift = {},
                    onEndShift = { calls += "end" },
                    onMessageShown = {},
                    onResend = { calls += "resend_$it" },
                    onDiscard = { calls += "discard_$it" },
                    now = TEST_NOW,
                )
            }
        }

        composeRule.onNodeWithTag("shift_end_during_trip").assertIsDisplayed()
        composeRule.onNodeWithTag("shift_command_c9").performScrollTo()
        assertTagHasText("shift_command_c9", "the scanned tag is not registered")
        composeRule.onNodeWithText("Send again").performClick()
        composeRule.onNodeWithText("END SHIFT").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("resend_c9", "end"), calls)
    }
}
