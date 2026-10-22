package com.dynamictransport.dynamicone.data.real

import android.content.Context
import android.os.storage.StorageManager
import com.dynamictransport.dynamicone.core.common.AppDirectories
import com.dynamictransport.dynamicone.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Bytes the app may still write to its private storage. */
fun interface FreeSpace {
    fun bytes(): Long
}

class AndroidFreeSpace @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FreeSpace {
    override fun bytes(): Long = try {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir))
    } catch (_: IOException) {
        context.filesDir.usableSpace
    }
}

/**
 * PDFs of documents to sign, in app-private storage (never in the gallery or shared), named by their SHA-256 so a file
 * is only ever used when its bytes are exactly the ones the office sent. Kept while the document waits for the
 * signature, so it can be read again without network; removed when it no longer does and when the session ends.
 */
@Singleton
class HrDocumentFileStore @Inject constructor(
    private val directories: AppDirectories,
    private val freeSpace: FreeSpace,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val directory: File get() = File(directories.filesDir, DIRECTORY)

    suspend fun existing(sha256: String): File? = withContext(io) { file(sha256).takeIf { it.isFile && it.length() > 0 } }

    /** Room for the file plus a reserve, so the phone is never filled up by a document. */
    fun hasSpaceFor(sizeBytes: Long?): Boolean = freeSpace.bytes() >= (sizeBytes ?: UNKNOWN_SIZE_BYTES) + RESERVE_BYTES

    /** Writes the body, checks its SHA-256 and keeps it; null when the bytes are not the expected ones. */
    suspend fun store(body: ResponseBody, expectedSha256: String): File? = withContext(io) {
        val folder = directory.apply { mkdirs() }
        val temporary = File(folder, "$expectedSha256$PART")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            body.use { response ->
                response.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (digest.digest().toHex() != expectedSha256) {
                temporary.delete()
                return@withContext null
            }
            val target = file(expectedSha256)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target
        } catch (e: IOException) {
            temporary.delete()
            throw e
        }
    }

    /** Removes every file that is not one of [sha256s]. */
    suspend fun keepOnly(sha256s: Set<String>) = withContext(io) {
        directory.listFiles()?.forEach { file -> if (file.name.removeSuffix(PDF) !in sha256s) file.delete() }
        Unit
    }

    suspend fun clear() = withContext(io) {
        directory.deleteRecursively()
        Unit
    }

    private fun file(sha256: String) = File(directory, "$sha256$PDF")

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private companion object {
        const val DIRECTORY = "hr_documents"
        const val PDF = ".pdf"
        const val PART = ".part"
        const val BUFFER_BYTES = 64 * 1024
        const val UNKNOWN_SIZE_BYTES = 20L * 1024 * 1024
        const val RESERVE_BYTES = 50L * 1024 * 1024
    }
}
