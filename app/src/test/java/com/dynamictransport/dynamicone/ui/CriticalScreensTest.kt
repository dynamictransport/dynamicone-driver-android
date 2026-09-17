package com.dynamictransport.dynamicone.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.domain.model.BreakState
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.statemachine.NextActionResolver
import com.dynamictransport.dynamicone.domain.statemachine.PrimaryAction
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.home.HomeScreen
import com.dynamictransport.dynamicone.feature.home.HomeUiState
import com.dynamictransport.dynamicone.feature.tripdetail.RejectTripScreen
import com.dynamictransport.dynamicone.feature.tripdetail.RejectTripUiState
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailScreen
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailUiState
import com.dynamictransport.dynamicone.feature.tripdetail.TripTimeline
import com.dynamictransport.dynamicone.testing.ACTIVE_SHIFT
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.testTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val primaryActions = mutableListOf<PrimaryAction>()
    private var incidentReports = 0

    private fun homeState(trip: Trip?, primary: PrimaryAction?, shift: Shift = ACTIVE_SHIFT) = HomeUiState(
        loading = false,
        driverName = "Mehmet Demir",
        shift = shift,
        lastSyncAt = TEST_NOW,
        focusTrip = trip,
        newTrip = trip?.takeIf { it.state == TripState.ASSIGNED },
        primaryAction = primary,
    )

    private fun setHome(state: HomeUiState) {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                HomeScreen(
                    state = state,
                    onPrimaryAction = { primaryActions += it },
                    onOpenTrip = {},
                    onOpenTrips = {},
                    onOpenDocuments = {},
                    onOpenEarnings = {},
                    onReportIncident = { incidentReports++ },
                    onOpenShift = {},
                    onRetrySync = {},
                    onMessageShown = {},
                    now = TEST_NOW,
                )
            }
        }
    }

    @Test
    fun newTripShowsOneDominantReviewAction() {
        val trip = testTrip(state = TripState.ASSIGNED)
        setHome(homeState(trip, PrimaryAction.ReviewNewTrip(trip.id), Shift.NotStarted))

        composeRule.onAllNodesWithTag(PRIMARY_ACTION_TAG).assertCountEquals(1)
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("VIEW AND ACCEPT TRIP")
        composeRule.onNodeWithTag("new_trip_alert").assertIsDisplayed()
    }

    @Test
    fun primaryActionLabelFollowsTheTripStateAndIsLarge() {
        val trip = testTrip(state = TripState.STARTED)
        setHome(homeState(trip, PrimaryAction.OnTrip(trip.id, TripActionType.ARRIVE_PICKUP)))

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG)
            .assertTextContains("ARRIVED AT PICKUP")
            .assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun tappingThePrimaryActionInvokesItOnce() {
        val trip = testTrip(state = TripState.STARTED)
        val action = PrimaryAction.OnTrip(trip.id, TripActionType.ARRIVE_PICKUP)
        setHome(homeState(trip, action))

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).performClick()

        assertEquals(listOf<PrimaryAction>(action), primaryActions)
    }

    @Test
    fun primaryActionIsDisabledWhileSubmitting() {
        val trip = testTrip(state = TripState.STARTED)
        setHome(homeState(trip, PrimaryAction.OnTrip(trip.id, TripActionType.ARRIVE_PICKUP)).copy(submitting = true))

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertIsNotEnabled()
    }

    @Test
    fun offlineAndPendingStatusAreVisibleWithText() {
        // Queued actions exist only with the offline queue of the demo backend.
        setHome(homeState(null, PrimaryAction.EndShift).copy(isDemoMode = true, isOnline = false, pendingCount = 3))

        composeRule.onNodeWithText("You are offline — actions are being saved").assertIsDisplayed()
        composeRule.onNodeWithText("3 actions waiting to be sent").assertIsDisplayed()
        composeRule.onNodeWithText("Offline").assertIsDisplayed()
    }

    @Test
    fun realApiOfflineStateDoesNotPromiseQueuedActions() {
        setHome(homeState(null, null).copy(isDemoMode = false, isOnline = false))

        composeRule.onNodeWithText("No connection. Actions cannot be sent until you are back online.").assertIsDisplayed()
        composeRule.onNodeWithText("You are offline — actions are being saved").assertDoesNotExist()
    }

    @Test
    fun reportProblemTileIsActive() {
        setHome(homeState(null, PrimaryAction.EndShift))

        composeRule.onNodeWithTag("quick_report_problem").performScrollTo().performClick()

        assertEquals(1, incidentReports)
    }

    @Test
    @Config(qualifiers = "tr-w411dp-h891dp")
    fun turkishLabelsAreUsed() {
        val trip = testTrip(state = TripState.AT_PICKUP)
        setHome(homeState(trip, PrimaryAction.OnTrip(trip.id, TripActionType.COMPLETE_PICKUP)))

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("YÜKLEME TAMAMLANDI")
        composeRule.onNodeWithText("Merhaba, Mehmet").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "pl-w320dp-h640dp")
    fun polishWithLargeFontKeepsThePrimaryActionUsable() {
        RuntimeEnvironment.setFontScale(2f)
        val trip = testTrip(state = TripState.PICKUP_COMPLETED)
        setHome(homeState(trip, PrimaryAction.OnTrip(trip.id, TripActionType.START_DELIVERY_LEG)))

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG)
            .assertIsDisplayed()
            .assertTextContains("RUSZAM DO ROZŁADUNKU")
            .assertHeightIsAtLeast(64.dp)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class TripDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val primaryActions = mutableListOf<PrimaryAction>()
    private val secondaryActions = mutableListOf<TripActionType>()

    private fun setDetail(trip: Trip, shift: Shift = ACTIVE_SHIFT) {
        val state = TripDetailUiState(
            loading = false,
            trip = trip,
            plan = NextActionResolver.forTrip(trip, shift),
            timeline = TripTimeline.build(trip, emptyList()),
        )
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                TripDetailScreen(
                    state = state,
                    onBack = {},
                    onPrimaryAction = { primaryActions += it },
                    onSecondaryAction = { secondaryActions += it },
                    onOpenDocuments = {},
                    onOpenNavigation = { _, _ -> },
                    onReportIncident = {},
                    onMessageShown = {},
                )
            }
        }
    }

    @Test
    fun breakShowsOnlyEndBreak() {
        setDetail(testTrip(state = TripState.EN_ROUTE_DELIVERY, breakState = BreakState.ON_BREAK))

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("END BREAK")
        composeRule.onNodeWithTag("secondary_START_BREAK").assertDoesNotExist()
        composeRule.onNodeWithTag("break_banner").assertExists()
    }

    @Test
    fun rejectIsHiddenWhenTheBackendDoesNotAllowIt() {
        setDetail(testTrip(state = TripState.ASSIGNED, serverAllowedActions = setOf(TripActionType.ACCEPT_TRIP)))

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("ACCEPT TRIP")
        composeRule.onNodeWithTag("secondary_REJECT_TRIP").assertDoesNotExist()
    }

    @Test
    fun rejectIsASecondaryActionWhenAllowed() {
        setDetail(
            testTrip(state = TripState.ASSIGNED, serverAllowedActions = setOf(TripActionType.ACCEPT_TRIP, TripActionType.REJECT_TRIP)),
        )

        composeRule.onNodeWithTag("secondary_REJECT_TRIP").performClick()

        assertEquals(listOf(TripActionType.REJECT_TRIP), secondaryActions)
        assertTrue(primaryActions.isEmpty())
    }

    @Test
    fun completingDeliveryRequiresAHoldNotATap() {
        setDetail(testTrip(state = TripState.AT_DELIVERY))
        val button = composeRule.onNodeWithText("DELIVERY COMPLETED")

        button.performClick()
        composeRule.mainClock.advanceTimeBy(2_000)
        assertTrue(primaryActions.isEmpty())

        composeRule.mainClock.autoAdvance = false
        button.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(1_500)
        button.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(300)

        assertEquals(listOf<PrimaryAction>(PrimaryAction.OnTrip("trip-1", TripActionType.COMPLETE_DELIVERY)), primaryActions)
    }

    @Test
    fun acceptedTripWithoutShiftOffersStartShift() {
        setDetail(testTrip(state = TripState.ACCEPTED), shift = Shift.NotStarted)

        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertTextContains("START SHIFT")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class RejectTripScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen() {
        composeRule.setContent {
            var state by remember { mutableStateOf(RejectTripUiState(tripNo = "TRP-261245")) }
            DynamicOneTheme(darkTheme = false) {
                RejectTripScreen(
                    state = state,
                    onBack = {},
                    onReasonSelected = { state = state.copy(reason = it) },
                    onNoteChanged = { state = state.copy(note = it) },
                    onSubmit = {},
                )
            }
        }
    }

    @Test
    fun rejectNeedsAPresetReason() {
        setScreen()

        composeRule.onNodeWithText("REJECT TRIP").assertIsNotEnabled()
        composeRule.onNodeWithTag("reject_reason_HEALTH").performScrollTo().performClick()
        composeRule.onNodeWithText("REJECT TRIP").assertIsEnabled()
    }

    @Test
    fun otherReasonNeedsAShortExplanation() {
        setScreen()

        composeRule.onNodeWithTag("reject_reason_OTHER").performScrollTo().performClick()
        composeRule.onNodeWithText("REJECT TRIP").assertIsNotEnabled()

        composeRule.onNodeWithTag("reject_note").performScrollTo().performTextInput("Truck in workshop")
        composeRule.onNodeWithText("REJECT TRIP").assertIsEnabled()
    }
}
