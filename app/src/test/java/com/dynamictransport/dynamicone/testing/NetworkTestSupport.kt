package com.dynamictransport.dynamicone.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.dynamictransport.dynamicone.core.database.PendingCommandDao
import com.dynamictransport.dynamicone.core.database.PendingCommandEntity
import com.dynamictransport.dynamicone.core.database.PendingCommandKind
import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.DriverAuthApi
import com.dynamictransport.dynamicone.core.network.DriverHttpClients
import com.dynamictransport.dynamicone.core.network.MobileApi
import com.dynamictransport.dynamicone.core.security.TokenManager
import com.dynamictransport.dynamicone.core.security.TokenPair
import com.dynamictransport.dynamicone.core.security.TokenStore
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

class InMemoryTokenStore : TokenStore {
    @Volatile
    var tokens: TokenPair? = null
    var writes = 0

    override suspend fun read(): TokenPair? = tokens

    override suspend fun write(tokens: TokenPair) {
        this.tokens = tokens
        writes++
    }

    override suspend fun clear() {
        tokens = null
    }
}

/**
 * DataStore without files. DataStore's JVM file storage replaces the file by renaming a temp file,
 * which Windows does not allow over an existing file; on Android the rename is atomic.
 */
class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}

/** Same queries as the Room DAO, for JVM tests without a database. PendingCommandDaoTest covers the SQL. */
class InMemoryPendingCommandDao : PendingCommandDao {
    private val rows = MutableStateFlow<List<PendingCommandEntity>>(emptyList())

    val all: List<PendingCommandEntity> get() = rows.value

    private fun List<PendingCommandEntity>.ordered() = sortedWith(compareBy<PendingCommandEntity> { it.createdAt }.thenBy { it.commandId })

    private fun List<PendingCommandEntity>.tripActions(tripId: String) =
        filter { it.kind == PendingCommandKind.TRIP_ACTION && it.tripId == tripId }.ordered()

    override fun observeTripActions(tripId: String): Flow<List<PendingCommandEntity>> = rows.map { it.tripActions(tripId) }

    override suspend fun getTripActions(tripId: String): List<PendingCommandEntity> = rows.value.tripActions(tripId)

    override fun observeMessages(tripId: String?): Flow<List<PendingCommandEntity>> =
        rows.map { list -> list.filter { it.kind == PendingCommandKind.MESSAGE && it.tripId == tripId }.ordered() }

    override suspend fun get(commandId: String): PendingCommandEntity? = rows.value.firstOrNull { it.commandId == commandId }

    override fun observeUnconfirmedCount(): Flow<Int> = rows.map { list -> list.count { it.state != PendingCommandState.RECORDED } }

    override suspend fun insert(command: PendingCommandEntity) {
        check(rows.value.none { it.commandId == command.commandId }) { "duplicate command id" }
        rows.update { it + command }
    }

    override suspend fun updateState(commandId: String, state: PendingCommandState, updatedAt: Instant) =
        rows.update { list -> list.map { if (it.commandId == commandId) it.copy(state = state, updatedAt = updatedAt) else it } }

    override suspend fun delete(commandId: String) = rows.update { list -> list.filterNot { it.commandId == commandId } }

    override suspend fun delete(commandIds: List<String>) = rows.update { list -> list.filterNot { it.commandId in commandIds } }

    override suspend fun deleteTripActions(tripId: String) =
        rows.update { list -> list.filterNot { it.kind == PendingCommandKind.TRIP_ACTION && it.tripId == tripId } }

    override suspend fun deleteForOtherDrivers(driverId: String) = rows.update { list -> list.filter { it.driverId == driverId } }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

/** Same JSON settings as the app. */
val TEST_JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** The production client stack (headers, bearer, refresh, password-change detection) against a MockWebServer. */
class DriverApiTestClient(server: MockWebServer, tokenManager: TokenManager, clock: Clock, authBasePath: String = "/api/driver/v1/") {
    private val base = DriverHttpClients.base(appVersion = "1.2.3", logRequests = false)
    val authApi: DriverAuthApi = retrofit(server, base, authBasePath).create(DriverAuthApi::class.java)
    private val authenticated = DriverHttpClients.authenticated(base, tokenManager, authApi, clock)
    val api: DriverApi = retrofit(server, authenticated, "/api/driver/v1/").create(DriverApi::class.java)
    val mobileApi: MobileApi = retrofit(server, authenticated, "/api/mobile/v1/").create(MobileApi::class.java)

    private fun retrofit(server: MockWebServer, client: OkHttpClient, basePath: String): Retrofit = Retrofit.Builder()
        .baseUrl(server.url(basePath))
        .client(client)
        .addConverterFactory(TEST_JSON.asConverterFactory("application/json".toMediaType()))
        .build()
}

/** Routes by method, exact path and optional query values; the last matching route wins. */
class RoutingDispatcher : Dispatcher() {
    private data class Route(val method: String, val path: String, val query: Map<String, String>, val response: (RecordedRequest) -> MockResponse)

    private val routes = java.util.concurrent.CopyOnWriteArrayList<Route>()

    fun on(method: String, path: String, query: Map<String, String> = emptyMap(), response: () -> MockResponse) {
        routes += Route(method, fullPath(path), query) { response() }
    }

    /** Route that inspects the request (e.g. the body of a command). */
    fun onRequest(method: String, path: String, response: (RecordedRequest) -> MockResponse) {
        routes += Route(method, fullPath(path), emptyMap(), response)
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl
        val route = routes.lastOrNull { route ->
            request.method == route.method && url?.encodedPath == route.path &&
                route.query.all { (name, value) -> url.queryParameter(name) == value }
        }
        return route?.response?.invoke(request) ?: MockResponse().setResponseCode(599)
    }

    companion object {
        const val API = "/api/driver/v1"
        const val MOBILE_API = "/api/mobile/v1"

        /** Paths are relative to the Driver API unless they name another API (e.g. [MOBILE_API]). */
        private fun fullPath(path: String) = if (path.startsWith("/api/")) path else API + path
    }
}

fun MockWebServer.drainRequests(): List<RecordedRequest> =
    generateSequence { takeRequest(200, TimeUnit.MILLISECONDS) }.toList()

val RecordedRequest.route: String get() = "$method ${requestUrl?.encodedPath}"

fun jsonResponse(body: String, code: Int = 200): MockResponse =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

fun problemResponse(status: Int, code: String, retryAfterSeconds: Long? = null, errors: List<String>? = null): MockResponse = MockResponse()
    .setResponseCode(status)
    .setHeader("Content-Type", "application/problem+json")
    .setBody(
        """{"type":"about:blank","title":"Problem","status":$status,"code":"$code","traceId":"00-trace-01","instance":"/api/driver/v1"""" +
            (errors?.let { list -> ""","errors":[${list.joinToString(",") { "\"$it\"" }}]""" } ?: "") + "}",
    )
    .apply { if (retryAfterSeconds != null) setHeader("Retry-After", retryAfterSeconds.toString()) }

/**
 * Driver API v1 response bodies in the contract shape (TH-2026-0439). Identifiers and values are invented
 * samples for tests; they are not production data.
 */
object DriverApiJson {
    private fun q(value: String?) = value?.let { "\"$it\"" } ?: "null"

    fun tokens(access: String = "access-1", refresh: String = "refresh-1", mustChangePassword: Boolean = false) =
        """{"tokenType":"Bearer","accessToken":"$access","expiresIn":900,"refreshToken":"$refresh","refreshTokenExpiresAtUtc":"2026-10-12T08:00:00Z","mustChangePassword":$mustChangePassword}"""

    const val PROFILE = """{"userId":"user-1","driverId":"driver-1","displayName":"Anna Nowak","preferredLanguageCode":"pl"}"""

    fun location(stopId: String, name: String, latitude: Double?, longitude: Double?) =
        """{"stopId":"$stopId","name":"$name","latitude":${latitude ?: "null"},"longitude":${longitude ?: "null"},"coordinateStatus":"${if (latitude != null && longitude != null) "Available" else "Missing"}"}"""

    fun summary(
        id: String,
        assignmentStatus: String,
        status: String,
        origin: String? = null,
        destination: String? = null,
        nextStop: String? = null,
    ) = """{"id":"$id","tripNumber":"TRP-$id","orderNumber":"ORD-$id","status":"$status","assignmentStatus":"$assignmentStatus","vehicleRegistration":"WX 9234K","assignedFromUtc":"2026-09-12T05:00:00Z","origin":${origin ?: "null"},"destination":${destination ?: "null"},"nextStop":${nextStop ?: "null"}}"""

    fun page(items: List<String>, page: Int = 1, hasMore: Boolean = false) =
        """{"items":[${items.joinToString(",")}],"page":$page,"pageSize":25,"hasMore":$hasMore}"""

    fun detail(summary: String, stops: String = "[]", containers: String = "[]", instructions: String? = null) =
        """{"trip":$summary,"instructions":${q(instructions)},"stops":$stops,"containers":$containers}"""

    fun status(status: String, reportedAtUtc: String) =
        """{"id":"status-$status","status":"$status","note":null,"reportedAtUtc":"$reportedAtUtc"}"""

    const val COMMAND_OK = """{"succeeded":true,"message":"OK"}"""

    /** Without [blockingSlots] the action has the shape of backends before docs/18 (no `canExecute`). */
    fun action(
        code: String,
        label: String,
        stopId: String? = null,
        containerId: String? = null,
        blockingSlots: List<String>? = null,
        blockingReason: String? = null,
        sealNumber: String? = null,
        containerNumber: String? = null,
        stopName: String? = null,
    ) = """{"code":"$code","label":"$label","stopId":${q(stopId)},"containerId":${q(containerId)}""" +
        (blockingSlots?.let { keys -> ""","blockingDocumentSlots":[${keys.joinToString(",") { q(it) }}]""" } ?: "") +
        (blockingReason?.let { ""","blockingReason":${q(it)}""" } ?: "") +
        (if (blockingSlots == null && blockingReason == null) "" else ""","canExecute":${blockingSlots.isNullOrEmpty() && blockingReason == null}""") +
        (sealNumber?.let { ""","sealNumber":${q(it)}""" } ?: "") +
        (containerNumber?.let { ""","containerNumber":${q(it)}""" } ?: "") +
        (stopName?.let { ""","stopName":${q(it)}""" } ?: "") +
        "}"

    fun slot(
        key: String,
        type: String,
        state: String,
        required: Boolean,
        stopId: String? = null,
        containerId: String? = null,
        documentId: String? = null,
        reviewNote: String? = null,
    ) = """{"slot":"$key","type":"$type","label":"$type label","stopId":${q(stopId)},"containerId":${q(containerId)},"required":$required,"state":"$state","documentId":${q(documentId)},"fileName":${q(documentId?.let { "$it.jpg" })},"reviewNote":${q(reviewNote)},"revision":${q(documentId?.let { "rev-$it" })}}"""

    fun execution(
        tripId: String = "t1",
        version: String = "v1",
        status: String = "InProgress",
        workflow: String = "TripActions",
        actions: List<String> = emptyList(),
        documents: List<String> = emptyList(),
        trailerConfirmationRequired: Boolean = false,
    ) = """{"tripId":"$tripId","version":"$version","status":"$status","availableActions":[${actions.joinToString(",")}],""" +
        """"documents":[${documents.joinToString(",")}],"workflow":"$workflow","trailerConfirmationRequired":$trailerConfirmationRequired}"""

    fun trailerOption(id: String, registrationNumber: String?, companyCode: String? = null) =
        """{"id":"$id","registrationNumber":${q(registrationNumber)},"companyCode":${q(companyCode)}}"""

    fun trailers(
        tripId: String = "t1",
        version: String = "v1",
        plannedTrailerId: String? = null,
        confirmedTrailerId: String? = null,
        confirmedAtUtc: String? = null,
        canConfirm: Boolean = true,
        trailers: List<String> = emptyList(),
    ) = """{"tripId":"$tripId","version":"$version","plannedTrailerId":${q(plannedTrailerId)},""" +
        """"confirmedTrailerId":${q(confirmedTrailerId)},"confirmedAtUtc":${q(confirmedAtUtc)},""" +
        """"canConfirm":$canConfirm,"trailers":[${trailers.joinToString(",")}]}"""

    fun result(code: String = "recorded", version: String? = "v2") =
        """{"succeeded":true,"code":"$code","message":"Action recorded.","version":${q(version)}}"""

    fun receipt(id: String, action: String, receivedAtUtc: String, stopId: String? = null, containerId: String? = null, observedAtUtc: String? = null) =
        """{"id":"$id","action":"$action","stopId":${q(stopId)},"containerId":${q(containerId)},"note":null,"receivedAtUtc":"$receivedAtUtc","observedAtUtc":${q(observedAtUtc)},"latitude":null,"longitude":null,"accuracyMeters":null}"""

    fun message(
        id: String,
        direction: String,
        body: String,
        createdAtUtc: String,
        tripId: String? = null,
        readAtUtc: String? = null,
        senderDisplayName: String? = null,
    ) =
        """{"id":"$id","driverId":"driver-1","tripId":${q(tripId)},"body":"$body","direction":"$direction","createdAtUtc":"$createdAtUtc","readAtUtc":${q(readAtUtc)},"senderDisplayName":${q(senderDisplayName)}}"""

    const val PAYROLL_UNPUBLISHED = """{"payroll":null,"publishedAtUtc":null,"state":"Unpublished"}"""

    /** Two currencies, decimals that are not exact in binary floating point, and separate finalized amounts. */
    fun payrollPublished(year: Int = 2026, month: Int = 9) = """{
        "payroll":{"year":$year,"month":$month,"state":"CompensationLedger","currencies":[
          {"currencyCode":"PLN","baseGross":5000.10,"additions":[{"type":"Bonus","amount":0.20,"effectiveFrom":"2026-09-01"},{"type":"FutureAllowance","amount":12.345,"effectiveFrom":"2026-09-15"}],
           "deductions":[{"type":"TrafficFine","amount":300.00,"effectiveFrom":"2026-09-10"}],"grossEarnings":5000.30,"totalDeductions":300.00,"balanceBeforeStatutoryDeductions":4700.30},
          {"currencyCode":"EUR","baseGross":0,"additions":[{"type":"AdrMonthlyBonus","amount":150.00,"effectiveFrom":"2026-09-01"}],"deductions":[],"grossEarnings":150.00,"totalDeductions":0,"balanceBeforeStatutoryDeductions":150.00}],
         "finalizedBaseGross":5000.10,"finalizedOvertimeGross":null,"finalizedOvertimeHours":7.5,"finalizedCurrency":"PLN"},
        "publishedAtUtc":"2026-10-02T09:30:00Z","state":"Published"}"""

    fun signedDocument(id: String, type: String, signedAtUtc: String) = """{"id":"$id","type":"$type","signedAtUtc":"$signedAtUtc"}"""
}
