package com.dynamictransport.dynamicone.data.media

import android.content.Context
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.dynamictransport.dynamicone.core.common.IdGenerator
import com.dynamictransport.dynamicone.core.common.IoDispatcher
import com.dynamictransport.dynamicone.domain.repository.DocumentMediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Stores document photos in app-private storage (never gallery/public storage).
 * Compression for upload is part of the Sprint 2 upload pipeline; originals stay readable.
 */
class FileDocumentMediaStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val ids: IdGenerator,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : DocumentMediaStore {

    private val directory: File
        get() = File(context.filesDir, MEDIA_DIRECTORY).apply { mkdirs() }

    /** Counts clearable cache as available, like the system does when the photo is written. */
    override fun hasEnoughSpace(): Boolean = try {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir)) >= MIN_FREE_BYTES
    } catch (_: IOException) {
        context.filesDir.usableSpace >= MIN_FREE_BYTES
    }

    override suspend fun createCaptureFile(): String = withContext(io) {
        File(directory, "doc_${ids.newId()}.jpg").apply { createNewFile() }.absolutePath
    }

    override suspend fun importFromContentUri(uri: String): String? = withContext(io) {
        try {
            val resolver = context.contentResolver
            val source = uri.toUri()
            val extension = resolver.getType(source)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: "jpg"
            val target = File(directory, "doc_${ids.newId()}.$extension")
            val copied = resolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (copied == null || copied == 0L) {
                target.delete()
                null
            } else {
                target.absolutePath
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    override suspend fun discard(filePath: String) {
        withContext(io) {
            val file = File(filePath)
            if (file.parentFile?.canonicalPath == directory.canonicalPath) file.delete()
        }
    }

    private companion object {
        const val MEDIA_DIRECTORY = "document_media"
        const val MIN_FREE_BYTES = 50L * 1024 * 1024
    }
}
