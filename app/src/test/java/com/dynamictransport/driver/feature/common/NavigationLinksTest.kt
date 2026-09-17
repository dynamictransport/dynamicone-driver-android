package com.dynamictransport.driver.feature.common

import com.dynamictransport.driver.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class NavigationLinksTest {

    @Test
    fun `geo links use the planned coordinates with locale independent decimals`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pl-PL"))
            assertEquals(
                "geo:51.9225,4.47917?q=51.9225,4.47917(Rotterdam%20ECT%20Delta)",
                NavigationLinks.geoUri(GeoPoint(51.9225, 4.47917), "Rotterdam ECT Delta"),
            )
        } finally {
            Locale.setDefault(previous)
        }
        assertEquals("geo:-33.9,0.00001?q=-33.9,0.00001", NavigationLinks.geoUri(GeoPoint(-33.9, 0.00001), null))
        assertEquals("geo:51,6?q=51,6(D%C3%BCsseldorf%20%26%20Co)", NavigationLinks.geoUri(GeoPoint(51.0, 6.0), "Düsseldorf & Co"))
    }
}
