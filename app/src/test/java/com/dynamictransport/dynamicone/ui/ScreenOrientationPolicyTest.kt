package com.dynamictransport.dynamicone.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.layout.ScreenOrientationPolicy
import com.dynamictransport.dynamicone.core.design.layout.applyScreenOrientationPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Phones stay upright; tablets (smallest side 600 dp or more) turn freely. */
@RunWith(AndroidJUnit4::class)
class ScreenOrientationPolicyTest {

    @Test
    fun phonesArePortraitTabletsAreFree() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ScreenOrientationPolicy.forSmallestWidth(320))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ScreenOrientationPolicy.forSmallestWidth(411))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ScreenOrientationPolicy.forSmallestWidth(599))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ScreenOrientationPolicy.forSmallestWidth(600))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ScreenOrientationPolicy.forSmallestWidth(640))
    }

    @Test
    @Config(qualifiers = "sw411dp-w891dp-h411dp-land")
    fun aPhoneTurnedSidewaysIsAskedBackToPortrait() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        activity.applyScreenOrientationPolicy()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
    }

    @Test
    @Config(qualifiers = "sw640dp-w1024dp-h640dp-land")
    fun aTabletKeepsItsOrientation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        activity.applyScreenOrientationPolicy()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }
}
