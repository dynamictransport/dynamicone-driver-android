package com.dynamictransport.driver.core.common

import android.util.Log
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Source of `clientActionId`s and local ids. */
fun interface IdGenerator {
    fun newId(): String
}

class UuidIdGenerator @Inject constructor() : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/**
 * Logging abstraction. Only event names, action types and exception class names are logged:
 * never tokens, passwords, personal data or document content (CLAUDE.md §8).
 */
interface AppLogger {
    fun event(name: String, attributes: Map<String, String> = emptyMap())

    fun warn(message: String, throwable: Throwable? = null)
}

class AndroidAppLogger(private val enabled: Boolean) : AppLogger {

    override fun event(name: String, attributes: Map<String, String>) {
        if (enabled) Log.i(TAG, if (attributes.isEmpty()) name else "$name $attributes")
    }

    override fun warn(message: String, throwable: Throwable?) {
        if (enabled) Log.w(TAG, throwable?.let { "$message (${it.javaClass.simpleName})" } ?: message)
    }

    private companion object {
        const val TAG = "DriverApp"
    }
}

/** App-private directories as a plain type, so file handling can be tested without an Android context. */
class AppDirectories(val filesDir: java.io.File, val cacheDir: java.io.File)

/** Parses ISO-8601 timestamps with either an offset or a `Z` suffix. */
fun String.toInstantOrNull(): Instant? =
    runCatching { OffsetDateTime.parse(this).toInstant() }
        .recoverCatching { Instant.parse(this) }
        .getOrNull()
