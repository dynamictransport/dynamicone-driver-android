package com.dynamictransport.dynamicone.feature.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import java.math.BigDecimal
import java.net.URLEncoder

/**
 * Navigation to a planned location. Only real coordinates are used: without them there is no navigation,
 * never a `0,0` point or a guessed address.
 *
 * The truck navigation app (TomTom GO Fleet on the Webfleet PRO tablets) gets a `google.navigation:` link
 * addressed to it, which starts guidance at once. Without such an app the generic `geo:` link is opened, which
 * lets the phone choose its maps app and shows the point on the map.
 */
object NavigationLinks {

    /** Navigation apps that start guidance straight from a `google.navigation:` link, in order of preference. */
    val TRUCK_NAVIGATION_PACKAGES = listOf(
        // TomTom GO Fleet (Webfleet PRO X tablets).
        "com.tomtom.gplay.navapp.gofleet",
        // TomTom GO Navigation.
        "com.tomtom.gplay.navapp",
    )

    /** `geo:lat,lng?q=lat,lng(label)` with locale-independent decimals. */
    fun geoUri(point: GeoPoint, label: String?): String {
        val coordinates = "${decimal(point.latitude)},${decimal(point.longitude)}"
        val query = label?.takeIf { it.isNotBlank() }
            ?.let { "$coordinates(${URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")})" }
            ?: coordinates
        return "geo:$coordinates?q=$query"
    }

    /** `google.navigation:q=lat,lng`: guidance to the point starts without a further tap. */
    fun navigationUri(point: GeoPoint): String = "google.navigation:q=${decimal(point.latitude)},${decimal(point.longitude)}"

    /** Starts guidance in the truck navigation app, else opens the point in any maps app; false when none is installed. */
    fun open(context: Context, point: GeoPoint, label: String?): Boolean {
        val guidance = navigationUri(point).toUri()
        for (packageName in TRUCK_NAVIGATION_PACKAGES) {
            if (start(context, Intent(Intent.ACTION_VIEW, guidance).setPackage(packageName))) return true
        }
        return start(context, Intent(Intent.ACTION_VIEW, geoUri(point, label).toUri()))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun decimal(value: Double): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
}
