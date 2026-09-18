package com.dynamictransport.dynamicone.data.media

import android.content.Context
import android.os.storage.StorageManager
import com.dynamictransport.dynamicone.core.common.IdGenerator
import com.dynamictransport.dynamicone.core.common.IoDispatcher
import com.dynamictransport.dynamicone.domain.repository.CheckPhotoStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Defect photos of the daily vehicle check in app-private storage (never the gallery). The camera app writes one file
 * through the FileProvider (`check_photos`); the file is deleted once the server has it.
 */
class FileCheckPhotoStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val ids: IdGenerator,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : CheckPhotoStore {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /** Counts clearable cache as available, like the system does when the photo is written. */
    override fun hasEnoughSpace(): Boolean = try {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir)) >= MIN_FREE_BYTES
    } catch (_: IOException) {
        context.filesDir.usableSpace >= MIN_FREE_BYTES
    }

    override suspend fun createPhotoFile(): String = withContext(io) {
        File(directory, "check_${ids.newId()}.jpg").apply { createNewFile() }.absolutePath
    }

    override suspend fun discard(filePath: String) {
        withContext(io) {
            val file = File(filePath)
            if (file.parentFile?.canonicalPath == directory.canonicalPath) file.delete()
        }
    }

    companion object {
        /** Also the FileProvider path `check_photos` (res/xml/file_paths.xml). */
        const val DIRECTORY = "vehicle_check_media"
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
    }
}
