package com.dynamictransport.dynamicone.feature.common

import com.dynamictransport.dynamicone.domain.model.TerminalAppointment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class TarFormatsTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    private fun tar(start: String, end: String, zone: ZoneId = amsterdam) =
        TerminalAppointment("001234", Instant.parse(start), Instant.parse(end), zone)

    @Test
    fun `the window is shown in the terminal zone whatever the device zone`() {
        // The docs/26 example: 08:00–10:00 UTC is 10:00–12:00 in Amsterdam (summer time on 22.10.2026).
        assertEquals("22.10.2026 10:00 – 12:00", DriverFormats.tarWindow(tar("2026-10-22T08:00:00Z", "2026-10-22T10:00:00Z")))
        assertEquals("22.10.2026 17:00 – 19:00", DriverFormats.tarWindow(tar("2026-10-22T08:00:00Z", "2026-10-22T10:00:00Z", ZoneId.of("Asia/Tokyo"))))
    }

    @Test
    fun `a window across midnight shows both dates`() {
        assertEquals(
            "22.10.2026 23:30 – 23.10.2026 01:00",
            DriverFormats.tarWindow(tar("2026-10-22T21:30:00Z", "2026-10-22T23:00:00Z")),
        )
    }

    @Test
    fun `winter time uses the zone rules, not a fixed offset`() {
        // 25.10.2026 is the switch from CEST (+2) to CET (+1) at 03:00 local: 01:30 UTC is 02:30 CET.
        assertEquals("25.10.2026 02:30 – 04:00", DriverFormats.tarWindow(tar("2026-10-25T01:30:00Z", "2026-10-25T03:00:00Z")))
        // In December the same UTC times are one hour later than in summer: 08:00 UTC is 09:00 CET.
        assertEquals("15.12.2026 09:00 – 11:00", DriverFormats.tarWindow(tar("2026-12-15T08:00:00Z", "2026-12-15T10:00:00Z")))
    }

    @Test
    fun `countdown is hours minutes seconds and never negative`() {
        assertEquals("1:05:09", DriverFormats.countdown(Duration.ofSeconds(3_909)))
        assertEquals("0:00:00", DriverFormats.countdown(Duration.ofSeconds(-5)))
        assertEquals("27:00:00", DriverFormats.countdown(Duration.ofHours(27)))
    }

    @Test
    fun `zone names are the city of the IANA id`() {
        assertEquals("Amsterdam", DriverFormats.zoneName(amsterdam))
        assertEquals("Buenos Aires", DriverFormats.zoneName(ZoneId.of("America/Argentina/Buenos_Aires")))
        assertEquals("UTC", DriverFormats.zoneName(ZoneId.of("UTC")))
    }
}
