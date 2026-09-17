package com.dynamictransport.dynamicone.smoke

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.dynamictransport.dynamicone.app.DynamicOneApplication
import com.dynamictransport.dynamicone.app.MainActivity
import com.dynamictransport.dynamicone.core.database.AppDatabase
import com.dynamictransport.dynamicone.core.sync.SyncWorker
import com.dynamictransport.dynamicone.core.sync.WorkManagerSyncScheduler
import com.dynamictransport.dynamicone.feature.common.PRIMARY_ACTION_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.Collections

/**
 * Headless end-to-end smoke test (no emulator available): the real Application, Hilt graph,
 * MainActivity, Room database, WorkManager configuration and in-app demo backend.
 * It walks the driver's core day and fails on any crash on the main thread.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = DynamicOneApplication::class, qualifiers = "en-w411dp-h891dp")
class HeadlessAppSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        FakeAndroidKeyStore.install()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
        markNetworkValidated()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test
    fun driverDayWorksEndToEndWithoutCrashes() {
        // 1. App start: session check ends on the login screen.
        waitFor(hasText("Welcome"))
        composeRule.onNodeWithText("Demo mode: any username, password \"demo\".").assertExists()

        // 2. Login: wrong password gives a human readable error, then a successful login.
        composeRule.onNodeWithTag("login_username").performTextInput("driver.1027")
        composeRule.onNodeWithTag("login_password").performTextInput("wrong")
        tapPrimary("SIGN IN")
        waitFor(hasText("Username or password is incorrect."))
        composeRule.onNodeWithTag("login_password").performTextClearance()
        composeRule.onNodeWithTag("login_password").performTextInput("demo")
        tapPrimary("SIGN IN")
        waitFor(hasText("Hello, Mehmet"))

        // 3. Navigation: trips tab + manual refresh pulls the demo trips into Room.
        composeRule.onNodeWithTag("nav_TRIPS").performClick()
        composeRule.onNodeWithContentDescription("Refresh").performClick()
        waitFor(hasTestTag("trip_card_trp-261245"))
        composeRule.onNodeWithTag("nav_HOME").performClick()

        // 4. Home: new assignment is the single dominant action.
        tapPrimary("VIEW AND ACCEPT TRIP")

        // 5. Active trip screen: accept -> start shift -> start trip -> break on/off.
        waitFor(hasText("Trip TRP-261245"))
        composeRule.onNodeWithTag("secondary_REJECT_TRIP").assertExists()
        tapPrimary("ACCEPT TRIP")
        tapPrimary("START SHIFT")
        tapPrimary("START TRIP")
        waitForPrimary("ARRIVED AT PICKUP")
        composeRule.onNodeWithTag("secondary_START_BREAK").performClick()
        waitFor(hasTestTag("break_banner"))
        tapPrimary("END BREAK")
        waitForPrimary("ARRIVED AT PICKUP")
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForPrimary("ARRIVED AT PICKUP")
        composeRule.onNodeWithText("Online").assertExists()

        // 6. Offline work: simulate no internet, keep progressing, pending is visible.
        toggleSimulatedOffline()
        waitFor(hasText("You are offline — actions are being saved"))
        tapPrimary("ARRIVED AT PICKUP")
        waitForPrimary("LOADING COMPLETED")
        waitFor(hasText("waiting to be sent", substring = true))

        // 7. Room: the optimistic state and the queue are persisted.
        assertDatabaseState()

        // 8. Activity recreation keeps the state.
        composeRule.activityRule.scenario.recreate()
        waitForPrimary("LOADING COMPLETED")

        // 9. Secondary screens open without crashing.
        composeRule.onNodeWithTag("nav_DOCUMENTS").performClick()
        waitFor(hasTestTag("slot_demo:CMR"))
        composeRule.onNodeWithTag("slot_demo:CMR").performScrollTo().performClick()
        waitFor(hasText("TAKE PHOTO · CMR"))
        // Driver API features the demo backend does not simulate are shown as unavailable, never with invented data.
        composeRule.onNodeWithTag("nav_MESSAGES").performClick()
        waitFor(hasTestTag("messages_unavailable"))
        composeRule.onNodeWithTag("nav_HOME").performClick()
        waitForPrimary("LOADING COMPLETED")

        composeRule.onNodeWithText("My pay").performScrollTo().performClick()
        waitFor(hasTestTag("payroll_unavailable"))
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForPrimary("LOADING COMPLETED")

        composeRule.onNodeWithTag("quick_report_problem").performScrollTo().performClick()
        waitFor(hasText("Trip: TRP-261245"))
        composeRule.onNodeWithTag("incident_category_TERMINAL_QUEUE").performScrollTo().performClick()
        tapPrimary("SEND REPORT")
        waitFor(hasTestTag("incident_done"))
        tapPrimary("BACK TO HOME")
        waitForPrimary("LOADING COMPLETED")

        composeRule.onNode(hasText("Shift started", substring = true)).performClick()
        waitFor(hasText("You cannot end the shift while a trip is in progress."))
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForPrimary("LOADING COMPLETED")

        // 10. Back online.
        toggleSimulatedOffline()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText("You are offline — actions are being saved")).fetchSemanticsNodes().isEmpty()
        }
        println("SMOKE queue before sync: ${pendingActionStates()}")

        // 10b. Sync wiring. Work is enqueued in WorkManager; Robolectric does not execute JobScheduler
        //      jobs, so the SyncWorker created by the real Hilt worker factory is run explicitly.
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerSyncScheduler.UNIQUE_WORK_NAME).get()
        println("SMOKE WorkManager unique work states: ${workInfos.map { it.state }}")
        assertTrue(workInfos.isNotEmpty())
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory((context as DynamicOneApplication).workerFactory)
            .build()
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        println("SMOKE queue after SyncWorker: ${pendingActionStates()}")
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM pending_actions WHERE state != 'SYNCED'"))
        openDatabase().use { database ->
            database.rawQuery("SELECT localState, serverState, syncState FROM trips WHERE id = 'trp-261245'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("AT_PICKUP", it.getString(0))
                assertEquals("AT_PICKUP", it.getString(1))
                assertEquals("SYNCED", it.getString(2))
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText("waiting to be sent", substring = true)).fetchSemanticsNodes().isEmpty()
        }
        waitForPrimary("LOADING COMPLETED")

        // 11. Sign out never deletes unsent work; username is remembered.
        composeRule.onNodeWithTag("nav_PROFILE").performClick()
        composeRule.onNodeWithTag("logout").performScrollTo().performClick()
        composeRule.waitForIdle()
        val confirmInDialog = hasText("Sign out") and hasAnyAncestor(isDialog())
        if (composeRule.onAllNodes(confirmInDialog).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNode(confirmInDialog).performClick()
        }
        waitFor(hasText("Welcome"))
        composeRule.onNodeWithTag("login_username").assertTextContains("driver.1027")
        assertTrue(queryLong("SELECT COUNT(*) FROM pending_actions") >= 6)

        assertTrue("Uncaught exceptions: $uncaught", uncaught.isEmpty())
    }

    private fun toggleSimulatedOffline() {
        composeRule.onNodeWithTag("nav_PROFILE").performClick()
        composeRule.onNodeWithTag("demo_simulate_offline").performScrollTo().performClick()
        composeRule.onNodeWithTag("nav_HOME").performClick()
    }

    private fun assertDatabaseState() {
        openDatabase().use { database ->
            database.rawQuery("SELECT localState, serverState FROM trips WHERE id = 'trp-261245'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("AT_PICKUP", it.getString(0))
            }
            database.rawQuery("SELECT localStatus FROM shift", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("ACTIVE", it.getString(0))
            }
        }
        assertTrue(queryLong("SELECT COUNT(*) FROM pending_actions WHERE state != 'SYNCED'") >= 1)
    }

    private fun pendingActionStates(): String = openDatabase().use { database ->
        database.rawQuery("SELECT state, COUNT(*) FROM pending_actions GROUP BY state", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add("${cursor.getString(0)}=${cursor.getLong(1)}") }.joinToString()
        }
    }

    private fun queryLong(sql: String): Long = openDatabase().use { database ->
        database.rawQuery(sql, null).use {
            it.moveToFirst()
            it.getLong(0)
        }
    }

    private fun openDatabase(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(context.getDatabasePath(AppDatabase.NAME).path, null, SQLiteDatabase.OPEN_READONLY)

    private fun markNetworkValidated() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(manager).setNetworkCapabilities(network, capabilities)
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForPrimary(label: String) {
        val matcher = hasTestTag(PRIMARY_ACTION_TAG) and hasText(label)
        try {
            composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        } catch (timeout: ComposeTimeoutException) {
            val shown = composeRule.onAllNodes(hasTestTag(PRIMARY_ACTION_TAG)).fetchSemanticsNodes()
                .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
            println("SMOKE expected primary '$label' but saw $shown")
            println("SMOKE trips: ${describeRows("SELECT id, serverState, localState, serverBreakState, localBreakState, syncState FROM trips")}")
            println("SMOKE shift: ${describeRows("SELECT serverStatus, localStatus, syncState FROM shift")}")
            println("SMOKE queue: ${describeRows("SELECT id, actionType, tripAction, state, lastError FROM pending_actions")}")
            throw timeout
        }
    }

    private fun describeRows(sql: String): String = openDatabase().use { database ->
        database.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { cursor.getString(it) ?: "null" })
            }.joinToString(" ; ")
        }
    }

    private fun tapPrimary(label: String) {
        waitForPrimary(label)
        composeRule.onNode(hasTestTag(PRIMARY_ACTION_TAG) and hasText(label)).performClick()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
    }
}
