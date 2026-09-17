package com.dynamictransport.driver.feature.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.dynamictransport.driver.domain.model.GeoPoint
import java.math.BigDecimal
import java.net.URLEncoder

/**
 * Navigation to a planned location. Only real coordinates are used: without them there is no navigation,
 * never a `0,0` point or a guessed address.
 */
object NavigationLinks {

    /** `geo:lat,lng?q=lat,lng(label)` with locale-independent decimals. */
    fun geoUri(point: GeoPoint, label: String?): String {
        val coordinates = "${decimal(point.latitude)},${decimal(point.longitude)}"
        val query = label?.takeIf { it.isNotBlank() }
            ?.let { "$coordinates(${URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")})" }
            ?: coordinates
        return "geo:$coordinates?q=$query"
    }

    /** Opens a navigation app; false when none is installed. */
    fun open(context: Context, point: GeoPoint, label: String?): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, geoUri(point, label).toUri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun decimal(value: Double): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
}
