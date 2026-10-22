package com.dynamictransport.dynamicone.feature.common

import com.dynamictransport.dynamicone.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CoordinateFormatTest {

    @Test
    fun `pairs have six decimals with a dot, latitude first, in every phone language`() {
        val previous = Locale.getDefault()
        try {
            listOf("pl-PL", "tr-TR", "de-DE", "en-US").forEach { tag ->
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("51.250000, 4.500000", CoordinateFormat.pair(GeoPoint(51.25, 4.5)))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `minus signs are kept and a real zero stays a valid coordinate`() {
        assertEquals("-33.918861, -18.423300", CoordinateFormat.pair(GeoPoint(-33.918861, -18.4233)))
        assertEquals("0.000000, 0.000000", CoordinateFormat.pair(GeoPoint(0.0, 0.0)))
        assertEquals("-0.500000, 179.999999", CoordinateFormat.pair(GeoPoint(-0.5, 179.999999)))
    }

    @Test
    fun `values are rounded half up to six decimals without binary noise`() {
        assertEquals("51.922500", CoordinateFormat.decimal(51.9225))
        assertEquals("4.479171", CoordinateFormat.decimal(4.4791705))
        assertEquals("-4.479171", CoordinateFormat.decimal(-4.4791705))
        assertEquals("0.000001", CoordinateFormat.decimal(0.00000051))
        // A value that rounds to zero is plain zero, never "-0.000000".
        assertEquals("0.000000", CoordinateFormat.decimal(-0.0000001))
        assertEquals("0.000000", CoordinateFormat.decimal(-0.0))
        assertEquals("90.000000", CoordinateFormat.decimal(90.0))
    }
}
