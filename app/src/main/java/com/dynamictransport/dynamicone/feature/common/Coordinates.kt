package com.dynamictransport.dynamicone.feature.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Planned stop coordinates as a driver types them into a truck navigation device (TomTom): decimal degrees
 * with a dot, six decimals, latitude first, the minus sign kept, independent of the phone language.
 * These are planned location coordinates, not the driver's live GPS position (docs/18).
 */
object CoordinateFormat {

    const val DECIMALS = 6

    /** `51.250000`, `-4.500000`, `0.000000`. */
    fun decimal(value: Double): String =
        BigDecimal(value.toString()).setScale(DECIMALS, RoundingMode.HALF_UP).toPlainString()

    /** `51.250000, 4.500000`. */
    fun pair(point: GeoPoint): String = "${decimal(point.latitude)}, ${decimal(point.longitude)}"
}

object CoordinateClipboard {

    /** Copies [point] as a [CoordinateFormat.pair]; false when the clipboard is not available. */
    fun copy(context: Context, point: GeoPoint, label: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, CoordinateFormat.pair(point)))
        return true
    }

    /** Android 13 and later confirm a copy themselves; the app confirms it on older versions only. */
    val systemConfirmsCopy: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
