package com.dynamictransport.dynamicone.core.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** A readable place for a vehicle position, resolved on the phone (docs/19 §3). */
interface PlaceNameResolver {
    /** "Gliwice, PL"; null when the phone cannot tell (no geocoder, no network, nothing near the point). */
    suspend fun placeName(point: GeoPoint, locale: Locale): String?
}

object PlaceNames {
    /** Town (or the next larger area) and country code; null without a place. */
    fun format(address: Address): String? {
        val place = listOf(address.locality, address.subAdminArea, address.adminArea).firstOrNull { !it.isNullOrBlank() }
            ?: return null
        val country = address.countryCode?.takeIf { it.isNotBlank() }
        return if (country == null) place else "$place, $country"
    }
}

/**
 * Android's geocoder, which asks the platform's location service (on most phones a Google service) over the network.
 * The last answer is kept for points about 100 m apart, so a parked vehicle is not looked up every minute.
 */
class GeocoderPlaceNameResolver(private val context: Context) : PlaceNameResolver {

    @Volatile
    private var last: Pair<String, String>? = null

    override suspend fun placeName(point: GeoPoint, locale: Locale): String? {
        if (!Geocoder.isPresent()) return null
        val key = String.format(Locale.ROOT, "%.3f,%.3f,%s", point.latitude, point.longitude, locale.toLanguageTag())
        last?.takeIf { it.first == key }?.let { return it.second }
        val address = withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) { lookup(Geocoder(context, locale), point) } ?: return null
        return PlaceNames.format(address)?.also { last = key to it }
    }

    private suspend fun lookup(geocoder: Geocoder, point: GeoPoint): Address? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(
                    point.latitude,
                    point.longitude,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(point.latitude, point.longitude, 1)?.firstOrNull() }.getOrNull()
            }
        }

    private companion object {
        const val LOOKUP_TIMEOUT_MILLIS = 5_000L
    }
}
