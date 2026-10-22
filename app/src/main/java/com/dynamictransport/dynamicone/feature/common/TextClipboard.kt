package com.dynamictransport.dynamicone.feature.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Copies a short plain text (a TAR code, docs/26). Never used for anything secret. */
object TextClipboard {

    /** False when the clipboard is not available. */
    fun copy(context: Context, label: String, text: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        return true
    }
}
