package com.dynamictransport.dynamicone.feature.common

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class NavigationLinksTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val point = GeoPoint(51.9225, 4.47917)

    /** The screen's context: starting another app from an Activity needs no task flags. */
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    @Before
    fun setUp() {
        // Robolectric resolves every intent unless told to check; the app relies on ActivityNotFoundException.
        shadowOf(application).checkActivities(true)
    }

    @Test
    fun `geo links use the planned coordinates with locale independent decimals`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pl-PL"))
            assertEquals(
                "geo:51.9225,4.47917?q=51.9225,4.47917(Rotterdam%20ECT%20Delta)",
                NavigationLinks.geoUri(GeoPoint(51.9225, 4.47917), "Rotterdam ECT Delta"),
            )
            assertEquals("google.navigation:q=51.9225,4.47917", NavigationLinks.navigationUri(GeoPoint(51.9225, 4.47917)))
        } finally {
            Locale.setDefault(previous)
        }
        assertEquals("geo:-33.9,0.00001?q=-33.9,0.00001", NavigationLinks.geoUri(GeoPoint(-33.9, 0.00001), null))
        assertEquals("geo:51,6?q=51,6(D%C3%BCsseldorf%20%26%20Co)", NavigationLinks.geoUri(GeoPoint(51.0, 6.0), "Düsseldorf & Co"))
    }

    @Test
    fun `the truck navigation app gets guidance started directly`() {
        installNavigationApp(TOMTOM_GO_FLEET, "google.navigation", "geo")
        installNavigationApp(GOOGLE_MAPS, "geo", "google.navigation")

        assertTrue(NavigationLinks.open(activity, point, "Rotterdam ECT Delta"))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(TOMTOM_GO_FLEET, started.`package`)
        assertEquals("google.navigation:q=51.9225,4.47917", started.dataString)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `without a truck navigation app the point opens as a geo link for any maps app`() {
        installNavigationApp(GOOGLE_MAPS, "geo", "google.navigation")

        assertTrue(NavigationLinks.open(activity, point, "Rotterdam ECT Delta"))

        val started = shadowOf(activity).nextStartedActivity
        assertNull(started.`package`)
        assertEquals("geo:51.9225,4.47917?q=51.9225,4.47917(Rotterdam%20ECT%20Delta)", started.dataString)
    }

    @Test
    fun `without any navigation app nothing is started and the caller is told`() {
        assertFalse(NavigationLinks.open(activity, point, null))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun installNavigationApp(packageName: String, vararg schemes: String) {
        val component = ComponentName(packageName, "$packageName.MapsActivity")
        val packageManager = shadowOf(application.packageManager)
        packageManager.addActivityIfNotPresent(component)
        schemes.forEach { scheme ->
            val filter = IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme(scheme)
            }
            packageManager.addIntentFilterForActivity(component, filter)
        }
    }

    private companion object {
        const val TOMTOM_GO_FLEET = "com.tomtom.gplay.navapp.gofleet"
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
    }
}
