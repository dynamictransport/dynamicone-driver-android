package com.dynamictransport.driver.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** Parsed application/problem+json. Contains no credentials; `traceId` is kept for support. */
data class ApiProblem(
    val status: Int,
    val code: String?,
    val title: String?,
    val traceId: String?,
    val fields: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val retryAfterSeconds: Long? = null,
)

/** Transport-independent remote failures, shared by the real API client and the demo backend. */
sealed class RemoteException(cause: Throwable? = null) : Exception(cause) {
    open val problem: ApiProblem? get() = null

    class Offline(cause: Throwable? = null) : RemoteException(cause)
    class Unauthorized(override val problem: ApiProblem? = null) : RemoteException()
    class Forbidden(override val problem: ApiProblem? = null) : RemoteException()
    class NotFound(override val problem: ApiProblem? = null) : RemoteException()
    class Conflict(override val problem: ApiProblem? = null) : RemoteException()
    class Validation(override val problem: ApiProblem? = null) : RemoteException()
    class PayloadTooLarge(override val problem: ApiProblem? = null) : RemoteException()
    class RateLimited(val retryAfterSeconds: Long?, override val problem: ApiProblem? = null) : RemoteException()
    class ServerUnavailable(val code: Int, override val problem: ApiProblem? = null) : RemoteException()
    class ServerError(val code: Int, override val problem: ApiProblem? = null) : RemoteException()
    class Rejected(val code: Int, override val problem: ApiProblem? = null) : RemoteException()
    class InvalidResponse(cause: Throwable? = null) : RemoteException(cause)
    class NotSupported(val feature: String) : RemoteException()
}

fun httpError(code: Int, problem: ApiProblem? = null): RemoteException = when (code) {
    400 -> RemoteException.Validation(problem)
    401 -> RemoteException.Unauthorized(problem)
    403 -> RemoteException.Forbidden(problem)
    404 -> RemoteException.NotFound(problem)
    409 -> RemoteException.Conflict(problem)
    413 -> RemoteException.PayloadTooLarge(problem)
    429 -> RemoteException.RateLimited(problem?.retryAfterSeconds, problem)
    408, 502, 503, 504 -> RemoteException.ServerUnavailable(code, problem)
    in 500..599 -> RemoteException.ServerError(code, problem)
    else -> RemoteException.Rejected(code, problem)
}

object ApiProblemParser {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_BODY_CHARS = 8_192

    fun parse(status: Int, headers: Headers, body: String?): ApiProblem {
        val dto = body?.take(MAX_BODY_CHARS)?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(ProblemDetailsDto.serializer(), it) }.getOrNull()
        }
        return ApiProblem(
            status = dto?.status ?: status,
            code = dto?.code,
            title = dto?.title,
            traceId = dto?.traceId,
            fields = dto?.fields.orEmpty(),
            errors = dto?.errors.orEmpty(),
            retryAfterSeconds = headers["Retry-After"]?.trim()?.toLongOrNull()?.coerceAtLeast(0),
        )
    }

    fun from(response: Response<*>): ApiProblem {
        val body = runCatching { response.errorBody()?.string() }.getOrNull()
        return parse(response.code(), response.headers(), body)
    }
}

suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: RemoteException) {
    throw e
} catch (e: HttpException) {
    val response = e.response()
    throw httpError(e.code(), response?.let(ApiProblemParser::from))
} catch (e: SerializationException) {
    throw RemoteException.InvalidResponse(e)
} catch (e: IOException) {
    throw RemoteException.Offline(e)
}

fun Response<*>.requireSuccess() {
    if (!isSuccessful) throw httpError(code(), ApiProblemParser.from(this))
}
