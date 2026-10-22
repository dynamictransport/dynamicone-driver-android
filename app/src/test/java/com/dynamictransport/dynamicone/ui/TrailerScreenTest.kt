package com.dynamictransport.dynamicone.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.trailer.ConfirmTrailerScreen
import com.dynamictransport.dynamicone.feature.trailer.ConfirmTrailerUiState
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailScreen
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailUiState
import com.dynamictransport.dynamicone.testing.testAction
import com.dynamictransport.dynamicone.testing.testExecution
import com.dynamictransport.dynamicone.testing.testTrailer
import com.dynamictransport.dynamicone.testing.testTrailerState
import com.dynamictransport.dynamicone.testing.testTrip
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class TrailerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val selected = mutableListOf<String>()
    private var confirmed = 0
    private var trailerRequested = 0

    private val options = listOf(
        testTrailer("tr1", "WGM 45T2", "DYNA-120"),
        testTrailer("tr2", "WGM 88X1"),
    )

    private fun setTrailerScreen(state: ConfirmTrailerUiState) {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                ConfirmTrailerScreen(
                    state = state,
                    onBack = {},
                    onQueryChanged = {},
                    onSelect = { selected += it },
                    onConfirm = { confirmed++ },
                    onRetry = {},
                    onMessageShown = {},
                )
            }
        }
    }

    @Test
    fun theDriverPicksFromTheListAndOnlyThenCanConfirm() {
        setTrailerScreen(
            ConfirmTrailerUiState(
                loading = false,
                tripNo = "TRP-1",
                options = options,
                plannedId = "tr2",
                canConfirm = true,
            ),
        )

        composeRule.onNodeWithText("Which trailer is under your truck?").assertIsDisplayed()
        composeRule.onNodeWithText("WGM 45T2").assertIsDisplayed()
        // The planned trailer is marked, not preselected.
        composeRule.onNodeWithText("Planned").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("CONFIRM THE TRAILER")
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertIsNotEnabled()

        composeRule.onNodeWithTag("trailer_option_tr1").performClick()
        assertEquals(listOf("tr1"), selected)
    }

    @Test
    fun anEmptyListSendsTheDriverToTheOfficeInsteadOfConfirming() {
        setTrailerScreen(ConfirmTrailerUiState(loading = false, tripNo = "TRP-1", canConfirm = true))

        composeRule.onNodeWithTag("trailer_none").assertIsDisplayed()
        composeRule.onNodeWithText("Call the office. The app cannot add a trailer.").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertDoesNotExist()
    }

    @Test
    fun aStartedTripShowsItsTrailerButCannotChangeIt() {
        setTrailerScreen(
            ConfirmTrailerUiState(
                loading = false,
                tripNo = "TRP-1",
                options = options,
                confirmedId = "tr1",
                selectedId = "tr1",
                canConfirm = false,
            ),
        )

        composeRule.onNodeWithTag("trailer_confirmed").assertIsDisplayed()
        composeRule.onNodeWithTag("trailer_locked").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertDoesNotExist()
        assertEquals(0, confirmed)
    }

    @Test
    fun theTripCannotStartBeforeTheTrailerIsConfirmed() {
        val blockedStart = testAction(ExecutionActionCode.START, "I started the trip", blockedByTrailer = true)
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                TripDetailScreen(
                    state = TripDetailUiState(
                        loading = false,
                        trip = testTrip(state = TripState.ACCEPTED, workflow = TripWorkflow.TRIP_ACTIONS, serverAllowedActions = emptySet()),
                        execution = testExecution(actions = listOf(blockedStart), trailerConfirmationRequired = true),
                    ),
                    onBack = {},
                    onPrimaryAction = {},
                    onSecondaryAction = {},
                    onOpenDocuments = {},
                    onOpenNavigation = { _, _ -> },
                    onReportIncident = {},
                    onMessageShown = {},
                    onConfirmTrailer = { trailerRequested++ },
                )
            }
        }

        composeRule.onNodeWithTag("trailer_required").assertIsDisplayed()
        composeRule.onAllNodesWithText("I STARTED THE TRIP").assertCountEquals(0)
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("SELECT THE TRAILER")

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()
        assertEquals(1, trailerRequested)
    }

    @Test
    fun theConfirmedTrailerIsShownOnTheTripScreen() {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                TripDetailScreen(
                    state = TripDetailUiState(
                        loading = false,
                        trip = testTrip(state = TripState.ACCEPTED, workflow = TripWorkflow.TRIP_ACTIONS, serverAllowedActions = emptySet()),
                        execution = testExecution(actions = listOf(testAction(ExecutionActionCode.START, "I started the trip"))),
                        trailers = testTrailerState(confirmedTrailerId = "tr1", trailers = options),
                    ),
                    onBack = {},
                    onPrimaryAction = {},
                    onSecondaryAction = {},
                    onOpenDocuments = {},
                    onOpenNavigation = { _, _ -> },
                    onReportIncident = {},
                    onMessageShown = {},
                )
            }
        }

        composeRule.onNodeWithText("WGM 45T2 · DYNA-120").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("I STARTED THE TRIP")
    }
}
