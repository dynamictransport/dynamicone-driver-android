package com.dynamictransport.dynamicone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.domain.model.Shift
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.statemachine.NextActionResolver
import com.dynamictransport.dynamicone.domain.statemachine.PrimaryAction
import com.dynamictransport.dynamicone.feature.auth.LoginScreen
import com.dynamictransport.dynamicone.feature.auth.LoginUiState
import com.dynamictransport.dynamicone.feature.common.ACTION_BAR_TAG
import com.dynamictransport.dynamicone.feature.common.ACTION_PANE_TAG
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import com.dynamictransport.dynamicone.feature.home.HomeScreen
import com.dynamictransport.dynamicone.feature.home.HomeUiState
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailScreen
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailUiState
import com.dynamictransport.dynamicone.feature.tripdetail.TripTimeline
import com.dynamictransport.dynamicone.testing.ACTIVE_SHIFT
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.testTrip
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A tablet mounted in landscape in the truck (1024 x 640 dp): the dominant action moves into a pane on the right,
 * content keeps a readable width, and the login form sits beside the brand. Phones keep their layout.
 */
@RunWith(AndroidJUnit4::class)
class WideScreenLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w1024dp-h640dp-land")
    fun contentIsCappedAndCentredOnAWideScreen() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .readableWidth()
                        .height(10.dp)
                        .testTag("content"),
                )
            }
        }

        composeRule.onNodeWithTag("content")
            .assertWidthIsEqualTo(Dimens.ContentMaxWidth)
            .assertLeftPositionInRootIsEqualTo((1024.dp - Dimens.ContentMaxWidth) / 2)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun contentFillsAPhone() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .readableWidth()
                        .height(10.dp)
                        .testTag("content"),
                )
            }
        }

        composeRule.onNodeWithTag("content")
            .assertWidthIsEqualTo(411.dp)
            .assertLeftPositionInRootIsEqualTo(0.dp)
    }

    @Test
    @Config(qualifiers = "w1024dp-h640dp-land")
    fun tripActionsSitInAPaneBesideTheTripOnAWideScreen() {
        setTripDetail(TripState.ASSIGNED)

        composeRule.onNodeWithTag(ACTION_PANE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ACTION_BAR_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertIsDisplayed().assertTextContains("ACCEPT TRIP")
        composeRule.onNode(hasTestTag(PRIMARY_ACTION_TAG) and hasAnyAncestor(hasTestTag(ACTION_PANE_TAG))).assertExists()
        val pane = composeRule.onNodeWithTag(ACTION_PANE_TAG).getUnclippedBoundsInRoot()
        assertTrue("pane is ${pane.width} wide", pane.width == Dimens.ActionPaneWidth)
        assertTrue("pane ends at ${pane.right}", pane.right == 1024.dp)
    }

    @Test
    @Config(qualifiers = "w1024dp-h640dp-land")
    fun aFinishedTripHasNoActionPane() {
        setTripDetail(TripState.FINISHED)

        composeRule.onNodeWithTag(ACTION_PANE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(ACTION_BAR_TAG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun tripActionsStayAtTheBottomOnAPhone() {
        setTripDetail(TripState.ASSIGNED)

        composeRule.onNodeWithTag(ACTION_BAR_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ACTION_PANE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertIsDisplayed().assertTextContains("ACCEPT TRIP")
    }

    @Test
    @Config(qualifiers = "w1024dp-h640dp-land")
    fun homeActionSitsInAPaneOnAWideScreen() {
        val trip = testTrip(state = TripState.STARTED)
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                HomeScreen(
                    state = HomeUiState(
                        loading = false,
                        driverName = "Mehmet Demir",
                        shift = ACTIVE_SHIFT,
                        lastSyncAt = TEST_NOW,
                        focusTrip = trip,
                        primaryAction = PrimaryAction.OnTrip(trip.id, TripActionType.ARRIVE_PICKUP),
                    ),
                    onPrimaryAction = {},
                    onOpenTrip = {},
                    onOpenTrips = {},
                    onOpenDocuments = {},
                    onOpenEarnings = {},
                    onReportIncident = {},
                    onOpenShift = {},
                    onRetrySync = {},
                    onMessageShown = {},
                    now = TEST_NOW,
                )
            }
        }

        composeRule.onNodeWithTag(ACTION_PANE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ACTION_BAR_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertIsDisplayed().assertTextContains("ARRIVED AT PICKUP")
        composeRule.onNodeWithTag("trip_card_${trip.id}").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1024dp-h640dp-land")
    fun loginFormSitsBesideTheBrandOnAWideScreen() {
        setLogin()

        composeRule.onNodeWithTag("login_wide").assertExists()
        composeRule.onNodeWithTag("app_logo").assertIsDisplayed()
        composeRule.onNodeWithTag("login_username").assertIsDisplayed()
        composeRule.onNodeWithTag("login_password").assertIsDisplayed()
        composeRule.onNodeWithTag(PRIMARY_ACTION_TAG).assertIsDisplayed()
        val form = composeRule.onNodeWithTag("login_form").getUnclippedBoundsInRoot()
        val logo = composeRule.onNodeWithTag("app_logo").getUnclippedBoundsInRoot()
        assertTrue("form is ${form.width} wide", form.width == Dimens.LoginFormWidth)
        assertTrue("form starts at ${form.left}, logo ends at ${logo.right}", form.left >= logo.right)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun loginStaysOneColumnOnAPhone() {
        setLogin()

        composeRule.onNodeWithTag("login_wide").assertDoesNotExist()
        composeRule.onNodeWithTag("login_username").assertIsDisplayed()
        val form = composeRule.onNodeWithTag("login_form").getUnclippedBoundsInRoot()
        val logo = composeRule.onNodeWithTag("app_logo").getUnclippedBoundsInRoot()
        assertTrue("form starts at ${form.top}, logo ends at ${logo.bottom}", form.top >= logo.bottom)
    }

    private fun setTripDetail(state: TripState, shift: Shift = ACTIVE_SHIFT) {
        val trip = testTrip(state = state)
        val uiState = TripDetailUiState(
            loading = false,
            trip = trip,
            plan = NextActionResolver.forTrip(trip, shift),
            timeline = TripTimeline.build(trip, emptyList()),
        )
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                TripDetailScreen(
                    state = uiState,
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
    }

    private fun setLogin() {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                LoginScreen(
                    state = LoginUiState(),
                    onUsernameChanged = {},
                    onPasswordChanged = {},
                    onTogglePasswordVisibility = {},
                    onLogin = {},
                )
            }
        }
    }
}
