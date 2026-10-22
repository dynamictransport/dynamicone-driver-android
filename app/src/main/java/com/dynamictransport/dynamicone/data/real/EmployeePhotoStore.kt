package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.AppDirectories
import com.dynamictransport.dynamicone.core.common.IoDispatcher
import com.dynamictransport.dynamicone.core.network.MobileErrorCodes
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.data.media.ProfilePhotoImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in person's HR photo (docs/21_EMPLOYEE_PHOTO.md) in app-private storage, so it also shows offline.
 * It is downloaded only when `me` names another SHA-256, kept only when the bytes hash to exactly that value, and stored
 * as a small upright JPEG without the original's EXIF data. The session's end removes it.
 */
@Singleton
class EmployeePhotoStore @Inject constructor(
    private val account: AccountRemote,
    private val tracker: ApiActivityTracker,
    private val directories: AppDirectories,
    private val images: ProfilePhotoImages,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val photo = MutableStateFlow<String?>(null)

    /** Guards the stored file and [generation]; never held during a download, so signing out is never delayed. */
    private val files = Mutex()

    /** One download at a time. */
    private val downloads = Mutex()

    /** Raised by [clear]: a download that began in the ended session is dropped. */
    private var generation = 0L

    private val directory: File get() = File(directories.filesDir, DIRECTORY)

    /** Path of the photo to show; null when there is none. */
    fun observe(): StateFlow<String?> = photo.asStateFlow()

    /** Shows the photo kept for a restored session before `me` answers. */
    suspend fun load() = files.withLock {
        photo.value = withContext(io) { stored().firstOrNull()?.path }
    }

    /** Brings the photo on the phone in line with `me.photoSha256`. A failed download keeps the photo that is there. */
    suspend fun sync(sha256: String?) {
        if (sha256 == null) files.withLock { removeAll() } else downloads.withLock { download(sha256) }
    }

    private suspend fun download(sha256: String) {
        val started = files.withLock {
            val existing = withContext(io) { file(sha256).takeIf { it.isFile && it.length() > 0 } }
            if (existing != null) {
                photo.value = existing.path
                return@withLock null
            }
            generation
        } ?: return
        val prepared = try {
            val body = tracker.call { account.photo() } ?: return
            prepare(body, sha256)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            if (e is RemoteException.NotFound && e.problem?.code == MobileErrorCodes.PHOTO_NOT_FOUND) {
                files.withLock { if (generation == started) removeAll() }
            }
            null
        } catch (_: IOException) {
            null
        } ?: return
        files.withLock {
            withContext(io) {
                if (generation != started) {
                    prepared.delete()
                } else {
                    val target = file(sha256)
                    if (!prepared.renameTo(target)) {
                        prepared.copyTo(target, overwrite = true)
                        prepared.delete()
                    }
                    stored().filter { it != target }.forEach(File::delete)
                    photo.value = target.path
                }
            }
        }
    }

    /** Signing out, an expired session or another account: the photo belongs to the ended session. */
    suspend fun clear() = files.withLock {
        generation++
        removeAll()
    }

    /** Downloads into a temporary file, checks the hash and writes the reduced copy; null when the bytes are not the photo. */
    private suspend fun prepare(body: ResponseBody, sha256: String): File? = withContext(io) {
        val folder = directory.apply { mkdirs() }
        val download = File(folder, DOWNLOAD)
        val reduced = File(folder, REDUCED)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            body.use { response ->
                response.byteStream().use { input ->
                    download.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_DOWNLOAD_BYTES) return@withContext null
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (digest.digest().toHex() != sha256) return@withContext null
            reduced.takeIf { images.reduce(download, it, MAX_EDGE_PX) && it.length() > 0 }
        } finally {
            download.delete()
        }
    }

    private suspend fun removeAll() {
        withContext(io) { directory.deleteRecursively() }
        photo.value = null
    }

    private fun stored(): List<File> = directory.listFiles { file -> file.name.endsWith(JPEG) }.orEmpty().toList()

    private fun file(sha256: String) = File(directory, "$sha256$JPEG")

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private companion object {
        const val DIRECTORY = "employee_photo"
        const val JPEG = ".jpg"
        const val DOWNLOAD = "download.part"
        const val REDUCED = "reduced.part"
        const val BUFFER_BYTES = 64 * 1024

        /** The web accepts larger files than a photo needs; anything beyond this is not kept. */
        const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024

        /** Twice the largest size the app shows the photo at, for sharp results on dense screens. */
        const val MAX_EDGE_PX = 512
    }
}
