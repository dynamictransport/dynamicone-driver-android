package com.dynamictransport.driver.core.network

import com.dynamictransport.driver.core.security.TokenManager
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.time.Clock
import java.util.concurrent.TimeUnit

/** Driver API HTTP clients, shared by the DI graph and the tests so both use exactly the same rules. */
object DriverHttpClients {

    /**
     * HTTPS with platform certificate validation (network_security_config.xml) and no cookie jar.
     * Connection failures are never retried transparently: a status update, accept, upload or token
     * rotation whose outcome is unknown must not be sent a second time. Idle connections are dropped
     * before typical server keep-alive limits so a stale connection is not reused.
     */
    fun base(appVersion: String, logRequests: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
        .addInterceptor(DriverHeadersInterceptor(appVersion))
        .apply {
            if (logRequests) {
                // Method, URL and status only: never headers or bodies (tokens, passwords, documents).
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("Authorization")
                    },
                )
            }
        }
        .build()

    /** Bearer token on protected calls, a single serialized refresh on 401 and password-change detection. */
    fun authenticated(
        base: OkHttpClient,
        tokenManager: TokenManager,
        authApi: DriverAuthApi,
        clock: Clock,
    ): OkHttpClient = base.newBuilder()
        .addInterceptor(AuthHeaderInterceptor(tokenManager))
        .addInterceptor(PasswordChangeRequiredInterceptor(tokenManager))
        .authenticator(TokenRefreshAuthenticator(tokenManager, authApi, clock))
        .build()

    private const val MAX_IDLE_CONNECTIONS = 5
    private const val KEEP_ALIVE_SECONDS = 60L
}
