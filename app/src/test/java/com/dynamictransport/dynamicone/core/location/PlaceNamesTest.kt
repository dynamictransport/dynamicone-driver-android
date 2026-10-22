package com.dynamictransport.dynamicone.core.location

import android.location.Address
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PlaceNamesTest {

    private fun address(locality: String? = null, subAdminArea: String? = null, adminArea: String? = null, countryCode: String? = null) =
        Address(Locale.ROOT).apply {
            this.locality = locality
            this.subAdminArea = subAdminArea
            this.adminArea = adminArea
            this.countryCode = countryCode
        }

    @Test
    fun `town and country code`() {
        assertEquals("Gliwice, PL", PlaceNames.format(address(locality = "Gliwice", adminArea = "Śląskie", countryCode = "PL")))
    }

    @Test
    fun `outside a town the next larger area is named`() {
        assertEquals("Powiat gliwicki, PL", PlaceNames.format(address(subAdminArea = "Powiat gliwicki", adminArea = "Śląskie", countryCode = "PL")))
        assertEquals("Śląskie, PL", PlaceNames.format(address(locality = " ", adminArea = "Śląskie", countryCode = "PL")))
    }

    @Test
    fun `without a place there is no name and without a country the place stands alone`() {
        assertNull(PlaceNames.format(address(countryCode = "PL")))
        assertEquals("Gliwice", PlaceNames.format(address(locality = "Gliwice")))
    }
}
