package com.dynamictransport.dynamicone.core.network

import com.dynamictransport.dynamicone.domain.repository.ServerClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures how far the device clock is from the server's, from the `Date` header of every API response
 * (docs/26). The header has one-second resolution, so a difference inside [TOLERANCE] counts as none; a
 * response that took longer than [MAX_ROUND_TRIP] says too little about the moment the server stamped it.
 */
@Singleton
class ServerTimeSync @Inject constructor(private val clock: Clock) : ServerClock {

    private val current = MutableStateFlow(Duration.ZERO)

    override val offset: StateFlow<Duration> = current.asStateFlow()

    override fun now(): Instant = clock.instant().plus(current.value)

    /** [serverDate] is the response's `Date`; [sentAt]/[receivedAt] are the device times around the exchange. */
    fun record(serverDate: Instant, sentAt: Instant, receivedAt: Instant) {
        val roundTrip = Duration.between(sentAt, receivedAt)
        if (roundTrip.isNegative || roundTrip > MAX_ROUND_TRIP) return
        val midpoint = sentAt.plus(roundTrip.dividedBy(2))
        val measured = Duration.between(midpoint, serverDate)
        current.value = if (measured.abs() < TOLERANCE) Duration.ZERO else measured
    }

    companion object {
        val TOLERANCE: Duration = Duration.ofSeconds(2)
        val MAX_ROUND_TRIP: Duration = Duration.ofSeconds(30)
    }
}

/** Feeds the `Date` header of each response into [ServerTimeSync]. Reads nothing else from the response. */
class ServerDateInterceptor(private val sync: ServerTimeSync) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val date = response.headers.getDate("Date")
        if (date != null && response.sentRequestAtMillis > 0 && response.receivedResponseAtMillis > 0) {
            sync.record(
                serverDate = date.toInstant(),
                sentAt = Instant.ofEpochMilli(response.sentRequestAtMillis),
                receivedAt = Instant.ofEpochMilli(response.receivedResponseAtMillis),
            )
        }
        return response
    }
}
