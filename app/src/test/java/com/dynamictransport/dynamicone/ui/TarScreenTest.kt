package com.dynamictransport.dynamicone.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.StopActivity
import com.dynamictransport.dynamicone.domain.model.StopStatus
import com.dynamictransport.dynamicone.domain.model.TerminalAppointment
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.TripStop
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailScreen
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailUiState
import com.dynamictransport.dynamicone.testing.testExecution
import com.dynamictransport.dynamicone.testing.testStop
import com.dynamictransport.dynamicone.testing.testTrip
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** TAR on terminal stops (docs/26): code, window, phase and countdown, read-only. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class TarScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val copied = mutableListOf<String>()
    private var retries = 0
    private val amsterdam = ZoneId.of("Europe/Amsterdam")
    private val now: Instant = Instant.now()

    private fun tar(code: String, start: Instant, end: Instant) = TerminalAppointment(code, start, end, amsterdam)

    private fun setScreen(stops: List<TripStop>, refresh: RefreshResult = RefreshResult.SUCCESS, lastUpdatedAt: Instant? = now) {
        val state = TripDetailUiState(
            loading = false,
            trip = testTrip(state = TripState.STARTED, workflow = TripWorkflow.TRIP_ACTIONS, serverAllowedActions = emptySet(), stops = stops),
            execution = testExecution(actions = emptyList()),
            refreshResult = refresh,
            lastUpdatedAt = lastUpdatedAt,
        )
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                TripDetailScreen(
                    state = state,
                    onBack = {},
                    onPrimaryAction = {},
                    onSecondaryAction = {},
                    onOpenDocuments = {},
                    onOpenNavigation = { _, _ -> },
                    onReportIncident = {},
                    onMessageShown = {},
                    onRetry = { retries++ },
                    onCopyTar = { copied += it },
                )
            }
        }
    }

    @Test
    fun codeKeepsLeadingZerosAndIsCopiedAsText() {
        val stop = testStop("s1", 1, StopActivity.PICKUP, "ECT Delta").copy(tar = tar("001234", now.plusSeconds(7_200), now.plusSeconds(14_400)))
        setScreen(listOf(stop))

        composeRule.onNodeWithTag("stop_tar_s1").performScrollTo()
        composeRule.onNodeWithTag("stop_tar_s1_code").assertTextEquals("001234")
        composeRule.onNodeWithTag("stop_tar_s1_phase").assertTextEquals("TAR not started yet")
        composeRule.onNodeWithTag("stop_tar_s1_countdown").assertExists()
        composeRule.onNodeWithTag("stop_tar_s1_copy").performClick()

        assertEquals(listOf("001234"), copied)
    }

    @Test
    fun theLastHalfHourWarnsAndAnOpenStopPastTheEndIsExpired() {
        val endingSoon = testStop("s1", 1, StopActivity.PICKUP, "ECT Delta", status = StopStatus.ARRIVED)
            .copy(tar = tar("7", now.minusSeconds(3_600), now.plusSeconds(600)))
        val expired = testStop("s2", 2, StopActivity.UNLOAD, "APM")
            .copy(tar = tar("8", now.minusSeconds(7_200), now.minusSeconds(60)))
        setScreen(listOf(endingSoon, expired))

        composeRule.onNodeWithTag("stop_tar_s1").performScrollTo()
        composeRule.onNodeWithTag("stop_tar_s1_phase").assertTextEquals("TAR ends soon")
        composeRule.onNodeWithTag("stop_tar_s1_countdown").assertExists()
        composeRule.onNodeWithTag("stop_tar_s2").performScrollTo()
        composeRule.onNodeWithTag("stop_tar_s2_phase").assertTextEquals("TAR expired")
        composeRule.onNodeWithTag("stop_tar_s2_countdown").assertDoesNotExist()
    }

    @Test
    fun completedStopShowsItsTarAsHistoryAndStopsWithoutTarShowNothing() {
        val done = testStop("s1", 1, StopActivity.PICKUP, "ECT Delta", status = StopStatus.COMPLETED)
            .copy(tar = tar("000042", now.minusSeconds(600), now.plusSeconds(3_000)))
        val plain = testStop("s2", 2, StopActivity.UNLOAD, "Venlo")
        setScreen(listOf(done, plain))

        composeRule.onNodeWithTag("stop_tar_s1").performScrollTo()
        composeRule.onNodeWithTag("stop_tar_s1_phase").assertTextEquals("Past TAR")
        composeRule.onNodeWithTag("stop_tar_s1_code").assertTextEquals("000042")
        composeRule.onNodeWithTag("stop_tar_s1_countdown").assertDoesNotExist()
        composeRule.onNodeWithTag("stop_tar_s2").assertDoesNotExist()
    }

    @Test
    fun windowIsShownInTheTerminalZoneWithBothDatesAcrossMidnight() {
        val night = testStop("s1", 1, StopActivity.PICKUP, "ECT Delta")
            .copy(tar = tar("5", Instant.parse("2030-10-22T21:30:00Z"), Instant.parse("2030-10-22T23:00:00Z")))
        setScreen(listOf(night))

        composeRule.onNodeWithTag("stop_tar_s1").performScrollTo()
        composeRule.onNodeWithTag("stop_tar_s1_window").assertTextEquals("22.10.2030 23:30 – 23.10.2030 01:00")
    }

    @Test
    fun failedReloadKeepsTheTripAndSaysHowOldItIs() {
        val stop = testStop("s1", 1, StopActivity.PICKUP, "ECT Delta").copy(tar = tar("1", now.plusSeconds(60), now.plusSeconds(120)))
        setScreen(listOf(stop), refresh = RefreshResult.OFFLINE, lastUpdatedAt = Instant.parse("2026-10-22T08:00:00Z"))

        composeRule.onNodeWithTag("trip_stale").assertExists()
        composeRule.onNodeWithTag("stop_tar_s1_code").assertExists()
        composeRule.onNodeWithTag("trip_stale").performScrollTo()
        composeRule.onNodeWithTag("trip_stale").assertExists()
    }

    @Test
    fun aSuccessfulReloadShowsNoStaleBanner() {
        setScreen(listOf(testStop("s1", 1, StopActivity.PICKUP, "ECT Delta")))
        composeRule.onNodeWithTag("trip_stale").assertDoesNotExist()
        assertEquals(Duration.ZERO, TripDetailUiState().clockOffset)
    }
}
