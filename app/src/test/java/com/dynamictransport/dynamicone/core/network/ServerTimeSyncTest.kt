package com.dynamictransport.dynamicone.core.network

import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.TEST_NOW
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ServerTimeSyncTest {

    private val clock = MutableClock()
    private val sync = ServerTimeSync(clock)

    @Test
    fun `the offset is the server date against the middle of the exchange`() {
        val sent = TEST_NOW
        sync.record(serverDate = sent.plusSeconds(61), sentAt = sent, receivedAt = sent.plusSeconds(2))
        assertEquals(Duration.ofSeconds(60), sync.offset.value)
        assertEquals(TEST_NOW.plusSeconds(60), sync.now())
    }

    @Test
    fun `a difference inside the Date header resolution counts as none`() {
        sync.record(serverDate = TEST_NOW.plusSeconds(1), sentAt = TEST_NOW, receivedAt = TEST_NOW)
        assertEquals(Duration.ZERO, sync.offset.value)
        sync.record(serverDate = TEST_NOW.minusSeconds(90), sentAt = TEST_NOW, receivedAt = TEST_NOW)
        assertEquals(Duration.ofSeconds(-90), sync.offset.value)
    }

    @Test
    fun `a slow or inconsistent exchange says nothing about the clock`() {
        sync.record(serverDate = TEST_NOW.plusSeconds(600), sentAt = TEST_NOW, receivedAt = TEST_NOW.plusSeconds(45))
        sync.record(serverDate = TEST_NOW.plusSeconds(600), sentAt = TEST_NOW, receivedAt = TEST_NOW.minusSeconds(1))
        assertEquals(Duration.ZERO, sync.offset.value)
    }

    @Test
    fun `the Date header of API responses feeds the offset`() {
        val server = MockWebServer()
        val serverNow = Instant.now().plusSeconds(300)
        server.enqueue(
            MockResponse().setBody("{}").setHeader("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(serverNow, java.time.ZoneOffset.UTC))),
        )
        server.start()
        try {
            val client = DriverHttpClients.base(appVersion = "1", logRequests = false, serverTime = sync)
            client.newCall(Request.Builder().url(server.url("/api/driver/v1/me")).build()).execute().close()
        } finally {
            server.shutdown()
        }
        val offset = sync.offset.value
        assertTrue("offset $offset", offset >= Duration.ofSeconds(290) && offset <= Duration.ofSeconds(310))
    }
}
