package com.dynamictransport.dynamicone.testing

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.dynamictransport.dynamicone.core.database.HrDocumentCacheEntity
import com.dynamictransport.dynamicone.core.database.HrDocumentDao
import com.dynamictransport.dynamicone.core.database.HrSignatureEntity
import com.dynamictransport.dynamicone.core.pdf.PdfDocumentOpener
import com.dynamictransport.dynamicone.core.pdf.PdfPageSource
import com.dynamictransport.dynamicone.core.push.HrDocumentNotice
import com.dynamictransport.dynamicone.core.push.HrDocumentNotifier
import com.dynamictransport.dynamicone.core.sync.HrDocumentSyncScheduler
import com.dynamictransport.dynamicone.data.real.FreeSpace
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.DrawnSignature
import com.dynamictransport.dynamicone.domain.model.HrContentResult
import com.dynamictransport.dynamicone.domain.model.HrDocument
import com.dynamictransport.dynamicone.domain.model.HrDocumentLanguage
import com.dynamictransport.dynamicone.domain.model.HrDocumentLists
import com.dynamictransport.dynamicone.domain.model.HrDocumentLookup
import com.dynamictransport.dynamicone.domain.model.HrDocumentStatus
import com.dynamictransport.dynamicone.domain.model.HrSignResult
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SignatureFailure
import com.dynamictransport.dynamicone.domain.model.SignaturePoint
import com.dynamictransport.dynamicone.domain.repository.HrDocumentRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.security.MessageDigest
import java.time.Instant

/** Same queries as the Room DAO, for JVM tests without a database. HrDocumentDaoTest covers the SQL. */
class InMemoryHrDocumentDao : HrDocumentDao {
    private val rows = MutableStateFlow<List<HrSignatureEntity>>(emptyList())
    private val caches = MutableStateFlow<Map<String, HrDocumentCacheEntity>>(emptyMap())

    val signatures: List<HrSignatureEntity> get() = rows.value
    val cache: Map<String, HrDocumentCacheEntity> get() = caches.value

    private fun List<HrSignatureEntity>.ordered() = sortedWith(compareBy<HrSignatureEntity> { it.signedAt }.thenBy { it.commandId })

    private val HrSignatureEntity.waiting get() = state == LocalSignatureState.PENDING || state == LocalSignatureState.SENDING

    override fun observeSignatures(userId: String): Flow<List<HrSignatureEntity>> = rows.map { list -> list.filter { it.userId == userId }.ordered() }

    override suspend fun getSignature(commandId: String): HrSignatureEntity? = rows.value.firstOrNull { it.commandId == commandId }

    override suspend fun getWaitingSignature(userId: String, documentId: String): HrSignatureEntity? =
        rows.value.filter { it.userId == userId && it.documentId == documentId && it.waiting }.ordered().firstOrNull()

    override suspend fun getUnsent(userId: String): List<HrSignatureEntity> = rows.value.filter { it.userId == userId && it.waiting }.ordered()

    override suspend fun insertSignature(signature: HrSignatureEntity) {
        check(rows.value.none { it.commandId == signature.commandId }) { "duplicate command id" }
        rows.update { it + signature }
    }

    override suspend fun updateSignatureState(commandId: String, state: LocalSignatureState, failure: SignatureFailure?, updatedAt: Instant) =
        rows.update { list -> list.map { if (it.commandId == commandId) it.copy(state = state, failure = failure, updatedAt = updatedAt) else it } }

    override suspend fun deleteSignature(commandId: String) = rows.update { list -> list.filterNot { it.commandId == commandId } }

    override suspend fun deleteFailedSignatures(userId: String, documentId: String) =
        rows.update { list -> list.filterNot { it.userId == userId && it.documentId == documentId && it.state == LocalSignatureState.FAILED } }

    override suspend fun deleteSignaturesOf(userId: String, documentId: String) =
        rows.update { list -> list.filterNot { it.userId == userId && it.documentId == documentId } }

    override suspend fun deleteSignaturesForOtherUsers(userId: String) = rows.update { list -> list.filter { it.userId == userId } }

    override suspend fun getCache(userId: String): HrDocumentCacheEntity? = caches.value[userId]

    override suspend fun upsertCache(cache: HrDocumentCacheEntity) = caches.update { it + (cache.userId to cache) }

    override suspend fun deleteCacheForOtherUsers(userId: String) = caches.update { map -> map.filterKeys { it == userId } }

    override suspend fun deleteAllCache() {
        caches.value = emptyMap()
    }
}

class RecordingHrDocumentSyncScheduler : HrDocumentSyncScheduler {
    val delaysSeconds = mutableListOf<Long>()
    val requests: Int get() = delaysSeconds.size

    override fun requestSend(delaySeconds: Long) {
        delaysSeconds += delaySeconds
    }
}

class FakeFreeSpace(var value: Long = 10L * 1024 * 1024 * 1024) : FreeSpace {
    override fun bytes(): Long = value
}

/** Pages drawn as blank bitmaps (Robolectric); [closed] tells whether the screen released them. */
class FakePdfPages(override val pageCount: Int = 3) : PdfPageSource {
    var closed = false
        private set

    override fun aspectRatio(index: Int): Float = 210f / 297f

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? =
        if (closed) null else Bitmap.createBitmap(widthPx.coerceIn(1, 400), widthPx.coerceIn(1, 400) * 297 / 210, Bitmap.Config.ARGB_8888).asImageBitmap()

    override fun close() {
        closed = true
    }
}

class FakePdfOpener(var open: (String) -> PdfPageSource? = { FakePdfPages() }) : PdfDocumentOpener {
    val opened = mutableListOf<String>()
    val sources = mutableListOf<PdfPageSource>()

    override suspend fun open(filePath: String): PdfPageSource? {
        opened += filePath
        return open.invoke(filePath)?.also { sources += it }
    }
}

class RecordingHrDocumentNotifier : HrDocumentNotifier {
    val shown = mutableListOf<Pair<String, HrDocumentNotice>>()
    val cancelled = mutableListOf<String>()

    override fun ensureChannel() = Unit

    override fun show(documentId: String, notice: HrDocumentNotice) {
        shown += documentId to notice
    }

    override fun cancel(documentId: String) {
        cancelled += documentId
    }
}

class FakeHrDocumentRepository : HrDocumentRepository {
    val lists = MutableStateFlow(HrDocumentLists.EMPTY)
    val single = MutableStateFlow<Map<String, HrDocument>>(emptyMap())
    val missing = MutableStateFlow<Set<String>>(emptySet())
    var refreshResult = RefreshResult.SUCCESS
    var documentRefreshResult = RefreshResult.SUCCESS
    var loadMoreResult = RefreshResult.SUCCESS
    var contentResult: (String, String) -> HrContentResult = { _, language -> HrContentResult.Available("/files/$language.pdf", language, SHA_A) }
    var signResult: (String) -> HrSignResult = { HrSignResult.Saved("sig-1") }
    var signGate: CompletableDeferred<Unit>? = null
    var receiptResult: DocumentContentResult = DocumentContentResult.Available("/cache/receipt.pdf", "application/pdf")
    val signed = mutableListOf<Signed>()
    val contentRequests = mutableListOf<Pair<String, String>>()
    val refreshedDocuments = mutableListOf<String>()
    var refreshes = 0

    data class Signed(val documentId: String, val languageCode: String, val contentSha256: String, val signature: DrawnSignature)

    override fun observeLists(): Flow<HrDocumentLists> = lists

    override fun observeDocument(documentId: String): Flow<HrDocumentLookup> = combine(lists, single, missing) { current, one, gone ->
        val document = current.pending?.firstOrNull { it.id == documentId } ?: current.signed?.firstOrNull { it.id == documentId } ?: one[documentId]
        when {
            document != null -> HrDocumentLookup.Found(document, current.localFor(documentId))
            documentId in gone -> HrDocumentLookup.Missing
            else -> HrDocumentLookup.Loading
        }
    }

    override suspend fun refresh(): RefreshResult {
        refreshes++
        return refreshResult
    }

    override suspend fun loadMoreSigned(): RefreshResult = loadMoreResult

    override suspend fun refreshDocument(documentId: String): RefreshResult {
        refreshedDocuments += documentId
        return documentRefreshResult
    }

    override suspend fun content(documentId: String, languageCode: String): HrContentResult {
        contentRequests += documentId to languageCode
        return contentResult(documentId, languageCode)
    }

    override suspend fun sign(documentId: String, languageCode: String, contentSha256: String, signature: DrawnSignature): HrSignResult {
        signed += Signed(documentId, languageCode, contentSha256, signature)
        signGate?.await()
        return signResult(documentId)
    }

    override suspend fun sendPending(): Boolean = true

    override suspend fun openReceipt(documentId: String): DocumentContentResult = receiptResult

    fun pending(vararg documents: HrDocument) = lists.update { it.copy(pending = documents.toList(), updatedAt = TEST_NOW) }

    companion object {
        val SHA_A = "A".repeat(64)
    }
}

/** A domain document for view model and screen tests. */
fun hrDocument(
    id: String = "doc-1",
    status: HrDocumentStatus = HrDocumentStatus.PENDING,
    dueAt: Instant? = Instant.parse("2026-09-20T21:59:00Z"),
    languages: List<HrDocumentLanguage> = listOf(
        hrLanguage("pl", "Klauzula informacyjna RODO", "A"),
        hrLanguage("tr", "RODO aydınlatma metni", "B"),
        hrLanguage("en", "GDPR privacy notice", "C"),
    ),
    signedAt: Instant? = null,
    signedLanguage: String? = null,
    receiptAvailable: Boolean = false,
) = HrDocument(
    id = id,
    version = "1.0",
    referenceLanguage = "pl",
    sentAt = Instant.parse("2026-09-11T08:00:00Z"),
    dueAt = dueAt,
    status = status,
    firstOpenedAt = null,
    signedAt = signedAt,
    signedLanguage = signedLanguage,
    receiptAvailable = receiptAvailable,
    languages = languages,
)

fun hrLanguage(code: String, title: String, shaLetter: String, declaration: String? = "Potwierdzam / Onaylıyorum / I confirm ($code)") =
    HrDocumentLanguage(code, title, declaration, shaLetter.repeat(64), 1_000L, 2)

/** A stroke long and high enough for the server's rules. */
val VALID_STROKE = listOf(
    SignaturePoint(0.1f, 0.5f),
    SignaturePoint(0.3f, 0.3f),
    SignaturePoint(0.5f, 0.6f),
)

/**
 * Mobile API signing bodies in the docs/17 contract shape. The backend does not have these endpoints yet: invented
 * samples for tests, not production data and not the backend's raw answers.
 */
object HrDocumentJson {
    /** A small file standing in for a PDF; the repository only checks its checksum. */
    val PDF_PL: ByteArray = "%PDF-1.7 Klauzula informacyjna RODO".toByteArray()
    val PDF_TR: ByteArray = "%PDF-1.7 RODO aydinlatma metni".toByteArray()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    fun language(code: String, title: String, bytes: ByteArray, declaration: String = "Potwierdzam ($code)") =
        """{"languageCode":"$code","title":"$title","declaration":"$declaration","contentSha256":"${sha256(bytes)}","sizeBytes":${bytes.size},"pageCount":6}"""

    fun document(
        id: String,
        status: String = "Pending",
        dueAtUtc: String? = "2026-09-20T21:59:00Z",
        sentAtUtc: String = "2026-09-11T08:00:00Z",
        signedAtUtc: String? = null,
        signedLanguageCode: String? = null,
        receiptAvailable: Boolean = false,
        languages: List<String> = listOf(language("pl", "Klauzula informacyjna RODO", PDF_PL), language("tr", "RODO aydınlatma metni", PDF_TR)),
    ) = """{"id":"$id","version":"1.0","referenceLanguageCode":"pl","sentAtUtc":"$sentAtUtc","dueAtUtc":${q(dueAtUtc)},""" +
        """"status":"$status","firstOpenedAtUtc":null,"signedAtUtc":${q(signedAtUtc)},"signedLanguageCode":${q(signedLanguageCode)},""" +
        """"receiptAvailable":$receiptAvailable,"languages":[${languages.joinToString(",")}]}"""

    fun page(items: List<String>, page: Int = 1, pageSize: Int = 100, hasMore: Boolean = false) =
        """{"items":[${items.joinToString(",")}],"page":$page,"pageSize":$pageSize,"hasMore":$hasMore}"""

    fun signed(document: String) = """{"document":$document}"""

    private fun q(value: String?) = value?.let { "\"$it\"" } ?: "null"
}
