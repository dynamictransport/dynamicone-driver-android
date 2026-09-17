package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.DocumentContentResult
import com.dynamictransport.dynamicone.domain.model.MonthlyPayroll
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SignedDocument
import com.dynamictransport.dynamicone.domain.repository.PayrollRepository
import com.dynamictransport.dynamicone.domain.repository.SignedDocumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Published monthly payroll snapshots of the signed-in driver. Kept in memory only, cleared at session end. */
@Singleton
class RealPayrollRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
) : PayrollRepository {

    private val months = MutableStateFlow<Map<YearMonth, MonthlyPayroll>>(emptyMap())

    override fun observePayroll(month: YearMonth): Flow<MonthlyPayroll?> = months.map { it[month] }.distinctUntilChanged()

    override suspend fun refresh(month: YearMonth): RefreshResult {
        if (month.year !in MIN_YEAR..MAX_YEAR) return RefreshResult.FAILED
        return try {
            val response = tracker.call { api.payroll(month.year, month.monthValue) }
            val payroll = DriverApiMapping.payroll(month, response)
            months.update { it + (month to payroll) }
            tracker.markReadSucceeded()
            RefreshResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            e.toRefreshResult()
        }
    }

    fun clear() {
        months.value = emptyMap()
    }

    companion object {
        const val MIN_YEAR = 2000
        const val MAX_YEAR = 2100
    }
}

/** Signed personnel documents; the content is the PDF stored when it was signed. */
@Singleton
class RealSignedDocumentRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
    private val downloader: ProtectedFileDownloader,
) : SignedDocumentRepository {

    private val documents = MutableStateFlow<List<SignedDocument>?>(null)
    private val canLoadMore = MutableStateFlow(false)
    private val mutex = Mutex()

    @Volatile
    private var nextPage = 2

    override fun observeDocuments(): Flow<List<SignedDocument>?> = documents

    override fun observeCanLoadMore(): Flow<Boolean> = canLoadMore

    override suspend fun refresh(): RefreshResult = mutex.withLock {
        read {
            val response = tracker.call { api.signedDocuments(1, PAGE_SIZE) }
            documents.value = response.items.orEmpty().map(DriverApiMapping::signedDocument)
            nextPage = 2
            canLoadMore.value = response.hasMore
        }
    }

    override suspend fun loadMore(): RefreshResult = mutex.withLock {
        if (!canLoadMore.value) return@withLock RefreshResult.SUCCESS
        read {
            val page = nextPage
            val response = tracker.call { api.signedDocuments(page, PAGE_SIZE) }
            documents.update { (it.orEmpty() + response.items.orEmpty().map(DriverApiMapping::signedDocument)).distinctBy { document -> document.id } }
            nextPage = page + 1
            canLoadMore.value = response.hasMore
        }
    }

    override suspend fun open(documentId: String): DocumentContentResult =
        downloader.download("signed-$documentId", PDF) { api.signedDocumentContent(documentId) }

    fun clear() {
        documents.value = null
        canLoadMore.value = false
        nextPage = 2
    }

    private suspend fun read(block: suspend () -> Unit): RefreshResult = try {
        block()
        tracker.markReadSucceeded()
        RefreshResult.SUCCESS
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        e.toRefreshResult()
    }

    private companion object {
        const val PAGE_SIZE = 25
        const val PDF = "application/pdf"
    }
}
