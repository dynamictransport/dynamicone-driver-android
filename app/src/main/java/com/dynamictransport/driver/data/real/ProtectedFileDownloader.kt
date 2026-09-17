package com.dynamictransport.driver.data.real

import com.dynamictransport.driver.core.common.AppDirectories
import com.dynamictransport.driver.core.common.IoDispatcher
import com.dynamictransport.driver.core.network.RemoteException
import com.dynamictransport.driver.domain.model.ActionRejection
import com.dynamictransport.driver.domain.model.DocumentContentResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a protected file with the bearer token into a private temporary cache file for viewing.
 * No storage URL is used and no token ever goes into a URL. Only the file currently opened is kept.
 */
@Singleton
class ProtectedFileDownloader @Inject constructor(
    private val tracker: ApiActivityTracker,
    private val directories: AppDirectories,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun download(name: String, fallbackContentType: String?, request: suspend () -> ResponseBody): DocumentContentResult = try {
        val body = tracker.call(request)
        val contentType = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: fallbackContentType
        if (contentType == null) {
            body.close()
            DocumentContentResult.Unavailable(ActionRejection.UNSUPPORTED_FILE)
        } else {
            val file = withContext(io) {
                val directory = File(directories.cacheDir, VIEW_DIRECTORY).apply { mkdirs() }
                directory.listFiles()?.forEach { it.delete() }
                val safeName = name.filter { it.isLetterOrDigit() || it == '-' }
                val target = File(directory, "$safeName.${extensionFor(contentType)}")
                body.use { response -> response.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } } }
                target
            }
            tracker.markReadSucceeded()
            DocumentContentResult.Available(file.path, contentType)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        DocumentContentResult.Unavailable(e.toActionRejection())
    } catch (_: IOException) {
        DocumentContentResult.Unavailable(ActionRejection.OFFLINE)
    }

    /** Removes every temporary viewing file (session end). */
    suspend fun clear() {
        withContext(io) { File(directories.cacheDir, VIEW_DIRECTORY).deleteRecursively() }
    }

    private fun extensionFor(contentType: String): String = when (contentType.substringBefore(';').trim().lowercase()) {
        "application/pdf" -> "pdf"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        else -> "bin"
    }

    companion object {
        /** Shared read-only with the viewer app through FileProvider (res/xml/file_paths.xml). */
        const val VIEW_DIRECTORY = "document_view"
    }
}
