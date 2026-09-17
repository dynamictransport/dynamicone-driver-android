package com.dynamictransport.dynamicone.core.nfc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleResumeEffect
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

enum class NfcAvailability {
    AVAILABLE,

    /** The phone has NFC, but it is switched off in the system settings. */
    DISABLED,
    NOT_SUPPORTED,
}

fun interface NfcStatusProvider {
    fun availability(): NfcAvailability
}

class AndroidNfcStatusProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NfcStatusProvider {
    override fun availability(): NfcAvailability {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcAvailability.NOT_SUPPORTED
        return if (adapter.isEnabled) NfcAvailability.AVAILABLE else NfcAvailability.DISABLED
    }
}

/** Opens the system NFC settings; falls back to the wireless settings on phones without that screen. */
fun openNfcSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * Reads tags while [active] and the screen is resumed (NFC reader mode). The app then receives the tag instead of the
 * system dispatching it to another app. Only the tag's identifier is used; NDEF content is not read.
 * [onTag] runs on the main thread.
 */
@Composable
fun NfcTagReaderEffect(active: Boolean, onTag: (ByteArray) -> Unit) {
    val activity = LocalActivity.current ?: return
    val currentOnTag = rememberUpdatedState(onTag)
    LifecycleResumeEffect(active, activity) {
        val adapter = if (active) NfcAdapter.getDefaultAdapter(activity) else null
        if (adapter != null && adapter.isEnabled) {
            adapter.enableReaderMode(
                activity,
                { tag: Tag -> tag.id?.let { id -> activity.runOnUiThread { currentOnTag.value(id) } } },
                READER_FLAGS,
                null,
            )
        }
        onPauseOrDispose {
            if (adapter != null) runCatching { adapter.disableReaderMode(activity) }
        }
    }
}

private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
    NfcAdapter.FLAG_READER_NFC_B or
    NfcAdapter.FLAG_READER_NFC_F or
    NfcAdapter.FLAG_READER_NFC_V or
    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
