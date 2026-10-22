package com.dynamictransport.dynamicone.core.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Only a vehicle tablet registers as one (docs/24 §3): the same 600 dp threshold as the wide layout. */
@RunWith(AndroidJUnit4::class)
class DeviceProfileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "sw640dp-w1024dp-h640dp-land")
    fun aTabletIsAVehicleTablet() {
        val profile = AndroidDeviceProfile(context)

        assertEquals("vehicleTablet", profile.kind)
        val model = profile.model
        assertNotNull(model)
        assertTrue(model!!.length <= AndroidDeviceProfile.MAX_MODEL_LENGTH)
        assertTrue(model.none { it.isISOControl() })
    }

    @Test
    @Config(qualifiers = "sw411dp-w411dp-h891dp")
    fun aPhoneIsAPhone() {
        assertEquals("phone", AndroidDeviceProfile(context).kind)
    }
}
