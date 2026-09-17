package com.dynamictransport.driver.feature.common

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.dynamictransport.driver.domain.model.DocumentContentResult
import java.io.File

/** Hands a downloaded private file to a viewer app with a temporary read-only grant; the file stays in cache. */
@Composable
fun OpenDocumentEffect(
    request: DocumentContentResult.Available?,
    onHandled: () -> Unit,
    onNoViewer: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        onHandled()
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(request.filePath))
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, request.contentType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            onNoViewer()
        } catch (_: IllegalArgumentException) {
            onNoViewer()
        }
    }
}
