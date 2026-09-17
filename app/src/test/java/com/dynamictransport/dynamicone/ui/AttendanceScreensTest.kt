package com.dynamictransport.dynamicone.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.app.navigation.AppShell
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.core.nfc.NfcAvailability
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.AttendanceDayState
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.LocalPunch
import com.dynamictransport.dynamicone.domain.model.LocalPunchState
import com.dynamictransport.dynamicone.domain.model.PunchFailure
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.rules.AttendanceSituation
import com.dynamictransport.dynamicone.feature.attendance.AttendanceRecordsScreen
import com.dynamictransport.dynamicone.feature.attendance.AttendanceRecordsUiState
import com.dynamictransport.dynamicone.feature.attendance.AttendanceScreen
import com.dynamictransport.dynamicone.feature.attendance.AttendanceUiState
import com.dynamictransport.dynamicone.feature.attendance.PunchOutcome
import com.dynamictransport.dynamicone.feature.attendance.RecordDayUi
import com.dynamictransport.dynamicone.feature.attendance.RecordPunchUi
import com.dynamictransport.dynamicone.feature.attendance.TodayPunch
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.profile.ProfileScreen
import com.dynamictransport.dynamicone.feature.profile.ProfileUiState
import com.dynamictransport.dynamicone.testing.TEST_NOW
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class AttendanceScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var primaryActions = 0
    private var cancels = 0
    private var nfcSettings = 0
    private val resent = mutableListOf<String>()
    private lateinit var defaultZone: TimeZone

    // Times are shown in the device time zone.
    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() = TimeZone.setDefault(defaultZone)

    private fun situation(checkedIn: Boolean) = AttendanceSituation(
        checkedIn = checkedIn,
        checkedInSince = if (checkedIn) TEST_NOW.minusSeconds(7_800) else null,
        nextDirection = if (checkedIn) AttendanceDirection.OUT else AttendanceDirection.IN,
        today = LocalDate.of(2026, 9, 12),
        zone = ZoneOffset.UTC,
    )

    private fun setScreen(state: AttendanceUiState) {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                AttendanceScreen(
                    state = state,
                    onPrimaryAction = { primaryActions++ },
                    onCancelScan = { cancels++ },
                    onOpenNfcSettings = { nfcSettings++ },
                    onRefresh = {},
                    onResend = { resent += it.commandId },
                    onDismiss = {},
                    onOutcomeDismissed = {},
                    onOpenRecords = {},
                    now = TEST_NOW,
                )
            }
        }
    }

    @Test
    fun notAtWorkOffersOneLargeStartButton() {
        setScreen(AttendanceUiState(loading = false, userName = "Ayşe Yılmaz", situation = situation(checkedIn = false)))

        composeRule.onNodeWithTag("attendance_checked_out").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG)
            .assertTextContains("START WORK")
            .assertHeightIsAtLeast(64.dp)
            .performClick()

        assertEquals(1, primaryActions)
    }

    @Test
    fun atWorkShowsSinceWhenAndOffersEndingWork() {
        setScreen(
            AttendanceUiState(
                loading = false,
                situation = situation(checkedIn = true),
                today = listOf(TodayPunch("p-1", AttendanceDirection.IN, TEST_NOW.minusSeconds(7_800), SyncState.SYNCED, "Entrance")),
            ),
        )

        composeRule.onNodeWithTag("attendance_checked_in").assertIsDisplayed()
        composeRule.onNodeWithText("Checked in 05:50 · 2 h 10 min").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("END WORK")
        composeRule.onNodeWithTag("attendance_today").performScrollTo()
        composeRule.onNodeWithText("Check-in 05:50").assertIsDisplayed()
    }

    @Test
    fun waitingForTheTagExplainsWhichTagAndCanBeCancelled() {
        setScreen(AttendanceUiState(loading = false, situation = situation(checkedIn = true), scanning = AttendanceDirection.OUT))

        composeRule.onNodeWithTag("attendance_scanning").assertIsDisplayed()
        composeRule.onNodeWithText("Scan the check-out tag").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("STOP SCANNING").performClick()

        assertEquals(1, cancels)
        assertEquals(0, primaryActions)
    }

    @Test
    fun phoneWithoutNfcCannotStartAndSaysWhy() {
        setScreen(AttendanceUiState(loading = false, situation = situation(checkedIn = false), nfc = NfcAvailability.NOT_SUPPORTED))

        composeRule.onNodeWithTag("attendance_nfc_not_supported").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertIsNotEnabled()
    }

    @Test
    fun switchedOffNfcOpensTheSettings() {
        setScreen(AttendanceUiState(loading = false, situation = situation(checkedIn = false), nfc = NfcAvailability.DISABLED))

        composeRule.onNodeWithTag("attendance_nfc_disabled").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("TURN ON NFC").performClick()

        assertEquals(1, nfcSettings)
        assertEquals(0, primaryActions)
    }

    @Test
    fun rejectedPunchesShowTheReasonAndCanBeResent() {
        val failed = LocalPunch("cmd-1", AttendanceDirection.IN, TEST_NOW, LocalPunchState.FAILED, PunchFailure.WRONG_TAG_DIRECTION, null, null)
        setScreen(
            AttendanceUiState(
                loading = false,
                situation = situation(checkedIn = false),
                outcome = PunchOutcome.Failed(AttendanceDirection.IN, PunchFailure.WRONG_TAG_DIRECTION),
                failed = listOf(failed),
                waitingCount = 2,
            ),
        )

        composeRule.onNodeWithTag("attendance_outcome_failed").assertIsDisplayed()
        composeRule.onNodeWithTag("attendance_waiting").assertIsDisplayed()
        composeRule.onNodeWithTag("attendance_failed_cmd-1").performScrollTo()
        composeRule.onNodeWithText("Send again").performClick()

        assertEquals(listOf("cmd-1"), resent)
    }

    @Test
    @Config(qualifiers = "tr-w411dp-h891dp")
    fun turkishLabelsAreUsed() {
        setScreen(AttendanceUiState(loading = false, situation = situation(checkedIn = false)))

        composeRule.onNodeWithText("Mesaide değilsiniz").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("MESAİ BAŞLAT")
    }

    @Test
    fun recordsShowDaysWithTimesStateAndWorkedTime() {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                AttendanceRecordsScreen(
                    state = AttendanceRecordsUiState(
                        month = YearMonth.of(2026, 9),
                        canGoBack = true,
                        canGoForward = false,
                        zone = ZoneOffset.UTC,
                        days = listOf(
                            RecordDayUi(
                                date = LocalDate.of(2026, 9, 10),
                                state = AttendanceDayState.COMPLETE,
                                workedMinutes = 492,
                                punches = listOf(
                                    RecordPunchUi("p-1", AttendanceDirection.IN, TEST_NOW.minusSeconds(86_400 * 2), "Entrance", null),
                                    RecordPunchUi("cmd-2", AttendanceDirection.OUT, TEST_NOW.minusSeconds(86_400 * 2 - 29_520), null, SyncState.PENDING),
                                ),
                            ),
                        ),
                    ),
                    onBack = null,
                    onPreviousMonth = {},
                    onNextMonth = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag("records_month").assertTextContains("September 2026")
        composeRule.onNodeWithTag("records_next").assertIsNotEnabled()
        composeRule.onNodeWithText("Complete").assertIsDisplayed()
        composeRule.onNodeWithText("Check-in 08:00").assertIsDisplayed()
        composeRule.onNodeWithText("Check-out 16:12").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting to send").assertIsDisplayed()
        composeRule.onNodeWithText("8 h 12 min").assertIsDisplayed()
    }

    @Test
    fun officeProfileHasNoDriverItems() {
        // An office account has all its areas in the bottom bar: nothing to list under "More".
        assertTrue(AppShell.layout(setOf(AppModule.OFFICE_ATTENDANCE)).overflow.isEmpty())
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                ProfileScreen(
                    state = ProfileUiState(modules = setOf(AppModule.OFFICE_ATTENDANCE), versionName = "1.0"),
                    onRetryAll = {},
                    onAcknowledgeFailure = {},
                    onLogoutRequested = {},
                    onLogoutConfirmed = {},
                    onLogoutDismissed = {},
                    onSimulateOfflineChanged = {},
                    onResetDemoData = {},
                    links = emptyList(),
                )
            }
        }

        composeRule.onNodeWithTag("profile_change_password").assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag("profile_payroll")).assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag("profile_signed_documents")).assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag("profile_more")).assertCountEquals(0)
    }
}

