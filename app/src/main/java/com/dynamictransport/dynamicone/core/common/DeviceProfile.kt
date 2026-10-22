package com.dynamictransport.dynamicone.core.common

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** What the server needs to know about this device to target road snapshot requests (docs/24 §3). */
interface DeviceProfile {
    /** `vehicleTablet` (smallest side 600 dp or more, mounted in the truck) or `phone`. */
    val kind: String

    /** Manufacturer and model for the cockpit; null when unknown. */
    val model: String?

    val hasCamera: Boolean
}

class AndroidDeviceProfile @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DeviceProfile {

    override val kind: String
        get() = if (context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP) KIND_VEHICLE_TABLET else KIND_PHONE

    override val model: String?
        get() = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}"
            .filter { !it.isISOControl() }
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_MODEL_LENGTH)
            .ifBlank { null }

    override val hasCamera: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    companion object {
        /** The same threshold as the wide screen layout (ASSUMPTIONS W1/W6). */
        const val TABLET_MIN_SMALLEST_WIDTH_DP = 600
        const val KIND_VEHICLE_TABLET = "vehicleTablet"
        const val KIND_PHONE = "phone"
        const val MAX_MODEL_LENGTH = 200
    }
}
