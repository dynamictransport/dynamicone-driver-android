package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.AppDirectories
import com.dynamictransport.dynamicone.core.common.IoDispatcher
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
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

    /**
     * [directory]: [VIEW_DIRECTORY] for files handed to a viewer app (trip documents), [PRIVATE_VIEW_DIRECTORY] for files
     * only the app's own viewer reads (personnel, vehicle and signed files are never shared with another app).
     */
    suspend fun download(
        name: String,
        fallbackContentType: String?,
        directory: String = VIEW_DIRECTORY,
        request: suspend () -> ResponseBody,
    ): DocumentContentResult = try {
        val body = tracker.call(request)
        val contentType = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: fallbackContentType
        if (contentType == null) {
            body.close()
            DocumentContentResult.Unavailable(ActionRejection.UNSUPPORTED_FILE)
        } else {
            val file = withContext(io) {
                val folder = File(directories.cacheDir, directory).apply { mkdirs() }
                folder.listFiles()?.forEach { it.delete() }
                val safeName = name.filter { it.isLetterOrDigit() || it == '-' }
                val target = File(folder, "$safeName.${extensionFor(contentType)}")
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
        withContext(io) {
            File(directories.cacheDir, VIEW_DIRECTORY).deleteRecursively()
            File(directories.cacheDir, PRIVATE_VIEW_DIRECTORY).deleteRecursively()
        }
    }

    /** Removes one viewed file of the app's own viewer; files elsewhere are left alone. */
    fun discard(filePath: String) {
        val file = File(filePath)
        if (file.parentFile?.canonicalPath == File(directories.cacheDir, PRIVATE_VIEW_DIRECTORY).canonicalPath) file.delete()
    }

    private fun extensionFor(contentType: String): String = when (contentType.substringBefore(';').trim().lowercase()) {
        "application/pdf" -> "pdf"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "bin"
    }

    companion object {
        /** Shared read-only with the viewer app through FileProvider (res/xml/file_paths.xml). */
        const val VIEW_DIRECTORY = "document_view"

        /** Not in res/xml/file_paths.xml: no other app can be given these files. */
        const val PRIVATE_VIEW_DIRECTORY = "private_document_view"
    }
}
