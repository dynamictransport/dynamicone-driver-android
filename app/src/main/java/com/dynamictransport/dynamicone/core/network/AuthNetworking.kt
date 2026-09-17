package com.dynamictransport.dynamicone.core.network

import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.security.TokenManager
import com.dynamictransport.dynamicone.core.security.TokenPair
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.time.Clock

/** Client identification headers (docs/06_BACKEND_INTEGRATION.md). No personal data. */
class DriverHeadersInterceptor(
    private val appVersion: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain.request().newBuilder()
                .header("X-App-Version", appVersion)
                .header("X-Platform", "android")
                .build(),
        )
}

/** Adds `Authorization: Bearer` to protected requests. Cookies are never used. */
class AuthHeaderInterceptor(
    private val tokenManager: TokenManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.isAnonymousAuthEndpoint() || request.header(AUTHORIZATION) != null) return chain.proceed(request)
        val token = tokenManager.currentAccessToken() ?: return chain.proceed(request)
        return chain.proceed(request.withBearer(token))
    }
}

/**
 * Refreshes once on 401 and retries the request with the rotated token.
 *
 * The backend rotates refresh tokens with a compare-and-swap: a token can be consumed only once.
 * All refreshes therefore run under one lock. Requests that were waiting reuse the token obtained
 * by the first refresh instead of sending their own refresh. A rejected refresh ends the session;
 * nothing is retried in a loop.
 */
class TokenRefreshAuthenticator(
    private val tokenManager: TokenManager,
    private val authApi: DriverAuthApi,
    private val clock: Clock,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (request.isAnonymousAuthEndpoint() || response.priorResponse != null) return null

        synchronized(lock) {
            val sentToken = request.header(AUTHORIZATION)?.removePrefix(BEARER)
            val current = tokenManager.currentAccessToken()
            if (current != null && current != sentToken) return request.withBearer(current)

            val refreshToken = tokenManager.currentRefreshToken() ?: return null

            val refreshed = try {
                authApi.refresh(DriverRefreshRequestDto(refreshToken)).execute()
            } catch (_: IOException) {
                // Outcome unknown: the token may be consumed. Do not retry; the caller shows an error.
                return null
            }
            val tokens = refreshed.body()?.takeIf { refreshed.isSuccessful }?.toTokenPair(clock)
            if (tokens == null) {
                if (refreshed.code() in 400..403) tokenManager.onRefreshRejected()
                return null
            }
            tokenManager.replaceTokensBlocking(tokens)
            return request.withBearer(tokens.accessToken)
        }
    }
}

/** Detects `403 password_change_required` on any protected call and informs the session. */
class PasswordChangeRequiredInterceptor(
    private val tokenManager: TokenManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 403 && !chain.request().isAnonymousAuthEndpoint()) {
            val problem = ApiProblemParser.parse(403, response.headers, response.peekBody(8_192).string())
            if (problem.code == DriverErrorCodes.PASSWORD_CHANGE_REQUIRED) tokenManager.onPasswordChangeRequired()
        }
        return response
    }
}

/** Maps a token response to the stored pair. Returns null when the contract fields are missing. */
fun DriverTokenResponseDto.toTokenPair(clock: Clock): TokenPair? {
    val access = accessToken?.takeIf { it.isNotBlank() } ?: return null
    val refresh = refreshToken?.takeIf { it.isNotBlank() } ?: return null
    val now = clock.instant()
    return TokenPair(
        accessToken = access,
        refreshToken = refresh,
        accessTokenExpiresAt = now.plusSeconds(expiresIn.toLong().coerceAtLeast(0)),
        refreshTokenExpiresAt = refreshTokenExpiresAtUtc?.toInstantOrNull(),
        mustChangePassword = mustChangePassword,
    )
}

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "

/** Login and refresh are anonymous; logout and change-password require the bearer token. */
internal fun Request.isAnonymousAuthEndpoint(): Boolean =
    url.encodedPath.endsWith("/auth/login") || url.encodedPath.endsWith("/auth/refresh")

private fun Request.withBearer(token: String): Request =
    newBuilder().header(AUTHORIZATION, BEARER + token).build()
