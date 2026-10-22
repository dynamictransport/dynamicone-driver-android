package com.dynamictransport.dynamicone.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.AssignedVehicle
import com.dynamictransport.dynamicone.domain.model.AssignedVehicleState
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.UserProfile
import com.dynamictransport.dynamicone.domain.model.VehiclePosition
import com.dynamictransport.dynamicone.domain.model.VehiclePositionState
import com.dynamictransport.dynamicone.domain.statemachine.NextActionResolver
import com.dynamictransport.dynamicone.feature.common.ASSIGNED_VEHICLE_RETRY_TAG
import com.dynamictransport.dynamicone.feature.common.ASSIGNED_VEHICLE_TAG
import com.dynamictransport.dynamicone.feature.common.ASSIGNED_VEHICLE_VALUE_TAG
import com.dynamictransport.dynamicone.feature.common.VEHICLE_POSITION_MAP_TAG
import com.dynamictransport.dynamicone.feature.common.VEHICLE_POSITION_RETRY_TAG
import com.dynamictransport.dynamicone.feature.common.VEHICLE_POSITION_TAG
import com.dynamictransport.dynamicone.feature.common.VEHICLE_POSITION_VALUE_TAG
import com.dynamictransport.dynamicone.feature.home.HomeScreen
import com.dynamictransport.dynamicone.feature.home.HomeUiState
import com.dynamictransport.dynamicone.feature.profile.ProfileScreen
import com.dynamictransport.dynamicone.feature.profile.ProfileUiState
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailScreen
import com.dynamictransport.dynamicone.feature.tripdetail.TripDetailUiState
import com.dynamictransport.dynamicone.feature.tripdetail.TripTimeline
import com.dynamictransport.dynamicone.testing.ACTIVE_SHIFT
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.testTrip
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration

/** The assigned vehicle on home and profile, and the trip's own vehicle on the trip card and detail. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class AssignedVehicleScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var vehicleRetries = 0
    private var positionRetries = 0
    private val mapRequests = mutableListOf<Pair<GeoPoint, String?>>()

    private val dyna042 = AssignedVehicleState.Assigned(AssignedVehicle("veh-42", "Dyna042", "WX 12345"))
    private val gliwice = GeoPoint(50.294512, 18.671433)

    private fun positionAt(minutesAgo: Long) =
        VehiclePositionState.Known("veh-42", VehiclePosition(gliwice, TEST_NOW.minus(Duration.ofMinutes(minutesAgo)), TEST_NOW))

    private fun homeState(
        vehicle: AssignedVehicleState,
        trip: Trip? = null,
        position: VehiclePositionState = VehiclePositionState.NotAssigned,
        place: String? = null,
    ) = HomeUiState(
        loading = false,
        driverName = "Mehmet Demir",
        assignedVehicle = vehicle,
        shift = ACTIVE_SHIFT,
        lastSyncAt = TEST_NOW,
        focusTrip = trip,
        vehiclePosition = position,
        vehiclePlace = place,
    )

    private fun setHome(state: () -> HomeUiState) {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                HomeScreen(
                    state = state(),
                    onPrimaryAction = {},
                    onOpenTrip = {},
                    onOpenTrips = {},
                    onOpenDocuments = {},
                    onOpenEarnings = {},
                    onReportIncident = {},
                    onOpenShift = {},
                    onRetrySync = {},
                    onMessageShown = {},
                    onRetryAssignedVehicle = { vehicleRetries++ },
                    onRetryVehiclePosition = { positionRetries++ },
                    onShowVehicleOnMap = { point, label -> mapRequests += point to label },
                    now = TEST_NOW,
                )
            }
        }
    }

    private fun assertVehicleText(text: String) {
        composeRule.onNodeWithTag(ASSIGNED_VEHICLE_VALUE_TAG, useUnmergedTree = true).assertTextEquals(text)
    }

    private fun assertPositionText(text: String) {
        composeRule.onNodeWithTag(VEHICLE_POSITION_VALUE_TAG, useUnmergedTree = true).assertTextEquals(text)
    }

    // --- Vehicle position (docs/19) ---

    @Test
    fun homeShowsWhereTheVehicleIsAndOpensTheMapWithIt() {
        setHome { homeState(dyna042, position = positionAt(12), place = "Gliwice, PL") }

        composeRule.onNodeWithText("Vehicle location", useUnmergedTree = true).assertIsDisplayed()
        assertPositionText("Gliwice, PL · 12 min ago")
        composeRule.onNodeWithTag(VEHICLE_POSITION_MAP_TAG).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(listOf(gliwice to "Dyna042 · WX 12345"), mapRequests)
        composeRule.onAllNodesWithTag(VEHICLE_POSITION_RETRY_TAG).assertCountEquals(0)
    }

    @Test
    fun aPlaceThePhoneCannotNameShowsItsCoordinatesAndTheAgeGrows() {
        var position by mutableStateOf(positionAt(0))
        setHome { homeState(dyna042, position = position) }

        assertPositionText("50.2945, 18.6714 · just now")

        position = positionAt(185)
        composeRule.waitForIdle()
        assertPositionText("50.2945, 18.6714 · 3 h 5 min ago")

        // A day or older: the date and time instead of an age.
        position = positionAt(26 * 60)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(VEHICLE_POSITION_VALUE_TAG, useUnmergedTree = true).assertTextContains("50.2945, 18.6714 · ", substring = true)
        composeRule.onAllNodesWithText("ago", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun positionStatesAreDistinctAndOnlyAFailureOffersARetry() {
        var position by mutableStateOf<VehiclePositionState>(VehiclePositionState.Loading)
        setHome { homeState(dyna042, position = position) }

        assertPositionText("Loading location…")

        position = VehiclePositionState.NoData
        composeRule.waitForIdle()
        assertPositionText("No location reported yet")

        position = VehiclePositionState.NotLinked
        composeRule.waitForIdle()
        assertPositionText("Not connected to fleet tracking")
        composeRule.onAllNodesWithTag(VEHICLE_POSITION_MAP_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(VEHICLE_POSITION_RETRY_TAG).assertCountEquals(0)

        position = VehiclePositionState.Unavailable
        composeRule.waitForIdle()
        assertPositionText("Location could not be loaded")
        composeRule.onNodeWithTag(VEHICLE_POSITION_RETRY_TAG).performClick()
        assertEquals(1, positionRetries)
    }

    @Test
    fun noPositionRowWithoutTheEndpointOrWithoutAVehicle() {
        var position by mutableStateOf<VehiclePositionState>(VehiclePositionState.NotSupported)
        setHome { homeState(dyna042, position = position) }
        composeRule.onAllNodesWithTag(VEHICLE_POSITION_TAG).assertCountEquals(0)

        position = VehiclePositionState.NotAssigned
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(VEHICLE_POSITION_TAG).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "tr-w411dp-h891dp")
    fun turkishPositionTexts() {
        setHome { homeState(dyna042, position = positionAt(12), place = "Gliwice, PL") }

        composeRule.onNodeWithText("Araç konumu", useUnmergedTree = true).assertIsDisplayed()
        assertPositionText("Gliwice, PL · 12 dk önce")
        composeRule.onNodeWithText("Haritada göster", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun homeShowsTheDynaCodeAndThePlate() {
        setHome { homeState(dyna042) }

        composeRule.onNodeWithTag(ASSIGNED_VEHICLE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Assigned vehicle", useUnmergedTree = true).assertIsDisplayed()
        assertVehicleText("Dyna042 · WX 12345")
        composeRule.onAllNodesWithTag(ASSIGNED_VEHICLE_RETRY_TAG).assertCountEquals(0)
    }

    @Test
    fun homeShowsThePlateAloneWithoutDynaCode() {
        setHome { homeState(AssignedVehicleState.Assigned(AssignedVehicle("veh-42", null, "WX 12345"))) }

        assertVehicleText("WX 12345")
    }

    @Test
    fun homeSaysNoVehicleIsAssignedEvenWhenTheTripHasOne() {
        val trip = testTrip(state = TripState.STARTED).copy(vehicleCompanyCode = "Dyna007")
        setHome { homeState(AssignedVehicleState.NotAssigned, trip) }

        assertVehicleText("No vehicle assigned")
        // The trip card shows the trip's own vehicle, which may differ from the assignment.
        composeRule.onNodeWithTag("trip_card_vehicle_${trip.id}", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Dyna007 · WX 9234K", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Dyna007 · WX 12345", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun loadingFailureAndChangesAreDistinctAndTheOldVehicleIsNotKept() {
        var vehicle by mutableStateOf<AssignedVehicleState>(AssignedVehicleState.Loading)
        setHome { homeState(vehicle) }

        assertVehicleText("Loading vehicle…")
        composeRule.onAllNodesWithTag(ASSIGNED_VEHICLE_RETRY_TAG).assertCountEquals(0)

        vehicle = AssignedVehicleState.Unavailable
        composeRule.waitForIdle()
        assertVehicleText("Vehicle could not be loaded")
        composeRule.onNodeWithTag(ASSIGNED_VEHICLE_RETRY_TAG).performClick()
        assertEquals(1, vehicleRetries)

        vehicle = dyna042
        composeRule.waitForIdle()
        assertVehicleText("Dyna042 · WX 12345")

        vehicle = AssignedVehicleState.Assigned(AssignedVehicle("veh-7", "Dyna007", "WZ 777AA"))
        composeRule.waitForIdle()
        assertVehicleText("Dyna007 · WZ 777AA")

        vehicle = AssignedVehicleState.NotAssigned
        composeRule.waitForIdle()
        assertVehicleText("No vehicle assigned")
        composeRule.onAllNodesWithText("Dyna007 · WZ 777AA", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "tr-w411dp-h891dp")
    fun turkishTexts() {
        var vehicle by mutableStateOf<AssignedVehicleState>(AssignedVehicleState.NotAssigned)
        setHome { homeState(vehicle) }

        composeRule.onNodeWithText("Atanmış araç", useUnmergedTree = true).assertIsDisplayed()
        assertVehicleText("Atanmış araç yok")

        vehicle = AssignedVehicleState.Unavailable
        composeRule.waitForIdle()
        assertVehicleText("Araç bilgisi alınamadı")
    }

    private fun setProfile(modules: Set<AppModule>, vehicle: AssignedVehicleState) {
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                ProfileScreen(
                    state = ProfileUiState(
                        profile = UserProfile("user-1", "Anna Nowak", null, null, modules, null),
                        modules = modules,
                        assignedVehicle = vehicle,
                        versionName = "1.0",
                    ),
                    onRetryAll = {},
                    onAcknowledgeFailure = {},
                    onLogoutRequested = {},
                    onLogoutConfirmed = {},
                    onLogoutDismissed = {},
                    onSimulateOfflineChanged = {},
                    onResetDemoData = {},
                    onRetryAssignedVehicle = { vehicleRetries++ },
                )
            }
        }
    }

    @Test
    fun driverProfileShowsTheAssignedVehicle() {
        setProfile(setOf(AppModule.DRIVER), dyna042)

        composeRule.onNodeWithTag(ASSIGNED_VEHICLE_TAG).assertIsDisplayed()
        assertVehicleText("Dyna042 · WX 12345")
    }

    @Test
    fun driverProfileRetriesAFailedRead() {
        setProfile(setOf(AppModule.DRIVER), AssignedVehicleState.Unavailable)

        composeRule.onNodeWithTag(ASSIGNED_VEHICLE_RETRY_TAG).performClick()

        assertEquals(1, vehicleRetries)
    }

    @Test
    fun officeProfileHasNoVehicle() {
        setProfile(setOf(AppModule.OFFICE_ATTENDANCE), AssignedVehicleState.NotAssigned)

        composeRule.onAllNodesWithTag(ASSIGNED_VEHICLE_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithText("No vehicle assigned", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun tripDetailShowsTheTripVehicleWithItsDynaCode() {
        val trip = testTrip(state = TripState.STARTED).copy(vehicleCompanyCode = "Dyna007")
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                TripDetailScreen(
                    state = TripDetailUiState(
                        loading = false,
                        trip = trip,
                        plan = NextActionResolver.forTrip(trip, ACTIVE_SHIFT),
                        timeline = TripTimeline.build(trip, emptyList()),
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

        composeRule.onNodeWithTag("trip_vehicle", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Dyna007 · WX 9234K / WGM 45T2", useUnmergedTree = true).assertExists()
    }

    @Test
    fun tripWithoutDynaCodeShowsThePlateOnly() {
        val trip = testTrip(state = TripState.STARTED)
        setHome { homeState(dyna042, trip) }

        composeRule.onNodeWithText("WX 9234K", useUnmergedTree = true).assertIsDisplayed()
    }
}
