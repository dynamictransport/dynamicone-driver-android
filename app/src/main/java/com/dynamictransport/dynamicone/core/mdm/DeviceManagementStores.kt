package com.dynamictransport.dynamicone.core.mdm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dynamictransport.dynamicone.core.network.DeviceAuthorizationSource
import com.dynamictransport.dynamicone.core.security.CryptoBox
import com.dynamictransport.dynamicone.domain.model.DeviceCommandResult
import com.dynamictransport.dynamicone.domain.model.DeviceCommandStatus
import com.dynamictransport.dynamicone.domain.model.DeviceCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** The device management file: the encrypted credential and what the phone remembers between check-ins. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeviceManagementDataStore

/**
 * `deviceId` and `deviceSecret` (docs/29 §2), encrypted with the Keystore key like the session tokens and kept in a
 * file of their own: signing out never touches it.
 */
@Singleton
class DeviceCredentialStore @Inject constructor(
    @param:DeviceManagementDataStore private val dataStore: DataStore<Preferences>,
    private val cryptoBox: CryptoBox,
) : DeviceAuthorizationSource {

    @Serializable
    private data class Stored(val deviceId: String, val deviceSecret: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    @Volatile
    private var loaded = false

    @Volatile
    private var cached: DeviceCredential? = null

    val enrolled: Flow<Boolean> = dataStore.data.map { it[CREDENTIAL] != null }.distinctUntilChanged()

    suspend fun read(): DeviceCredential? {
        if (loaded) return cached
        val stored = dataStore.data.first()[CREDENTIAL]?.let { encrypted ->
            runCatching {
                val plain = cryptoBox.decrypt(Base64.getDecoder().decode(encrypted)).decodeToString()
                json.decodeFromString(Stored.serializer(), plain).let { DeviceCredential(it.deviceId, it.deviceSecret) }
            }.getOrNull()
        }
        synchronized(lock) {
            if (!loaded) {
                cached = stored
                loaded = true
            }
        }
        // A value the Keystore can no longer read is useless: the device enrols again with a new code.
        if (stored == null) dataStore.edit { it.remove(CREDENTIAL) }
        return cached
    }

    suspend fun write(credential: DeviceCredential) {
        val plain = json.encodeToString(Stored.serializer(), Stored(credential.deviceId, credential.deviceSecret)).encodeToByteArray()
        val encrypted = Base64.getEncoder().encodeToString(cryptoBox.encrypt(plain))
        synchronized(lock) {
            cached = credential
            loaded = true
        }
        dataStore.edit { it[CREDENTIAL] = encrypted }
    }

    suspend fun clear() {
        synchronized(lock) {
            cached = null
            loaded = true
        }
        dataStore.edit { it.remove(CREDENTIAL) }
    }

    /** Called from OkHttp threads only. */
    override fun currentAuthorization(): String? = (if (loaded) cached else runBlocking { read() })?.authorization

    private companion object {
        val CREDENTIAL = stringPreferencesKey("device_credential")
    }
}

/** What the phone remembers between check-ins; no secrets. */
@Singleton
class DeviceManagementStateStore @Inject constructor(
    @param:DeviceManagementDataStore private val dataStore: DataStore<Preferences>,
) {
    val lastCheckInAt: Flow<Instant?> = dataStore.data.map { it[LAST_CHECK_IN]?.let(Instant::ofEpochMilli) }.distinctUntilChanged()

    suspend fun setLastCheckIn(at: Instant) {
        dataStore.edit { it[LAST_CHECK_IN] = at.toEpochMilli() }
    }

    suspend fun checkInSeconds(): Long = (dataStore.data.first()[CHECK_IN_SECONDS] ?: MIN_CHECK_IN_SECONDS).coerceAtLeast(MIN_CHECK_IN_SECONDS)

    suspend fun setCheckInSeconds(seconds: Long) {
        dataStore.edit { it[CHECK_IN_SECONDS] = seconds.coerceAtLeast(MIN_CHECK_IN_SECONDS) }
    }

    suspend fun policyVersionApplied(): Int? = dataStore.data.first()[POLICY_VERSION]

    suspend fun setPolicyVersionApplied(version: Int) {
        dataStore.edit { it[POLICY_VERSION] = version }
    }

    /**
     * True while the push token of this phone is registered for a signed-in account. Only then must the token be
     * replaced at sign-out; a managed phone nobody is signed in to keeps its token for `device.command`.
     */
    suspend fun accountPushActive(): Boolean = dataStore.data.first()[ACCOUNT_PUSH_ACTIVE] ?: false

    suspend fun setAccountPushActive(active: Boolean) {
        dataStore.edit { it[ACCOUNT_PUSH_ACTIVE] = active }
    }

    /** The device was retired: nothing of the old enrolment is kept. */
    suspend fun clearEnrolment() {
        dataStore.edit {
            it.remove(LAST_CHECK_IN)
            it.remove(CHECK_IN_SECONDS)
            it.remove(POLICY_VERSION)
        }
    }

    companion object {
        /** WorkManager's shortest period, and the contract's minimum (docs/29 §3.2). */
        const val MIN_CHECK_IN_SECONDS = 900L

        private val LAST_CHECK_IN = longPreferencesKey("last_check_in")
        private val CHECK_IN_SECONDS = longPreferencesKey("check_in_seconds")
        private val POLICY_VERSION = intPreferencesKey("policy_version_applied")
        private val ACCOUNT_PUSH_ACTIVE = booleanPreferencesKey("account_push_active")
    }
}

/** A command this phone has started; [result] is null until it is known. */
data class HandledCommand(val id: String, val result: DeviceCommandResult?)

/**
 * The ids of the commands this phone has handled (docs/29 §3.2): a command comes back with every check-in until its
 * result reaches the server, and must run only once. An id is stored before the command runs, so a command that
 * ended the process (a reboot) is not run again either.
 */
@Singleton
class DeviceCommandLedger @Inject constructor(
    @param:DeviceManagementDataStore private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
) {
    @Serializable
    private data class Entry(
        val id: String,
        val status: String? = null,
        val detail: String? = null,
        val keepUntilEpochMillis: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())

    suspend fun find(id: String): HandledCommand? = entries(dataStore.data.first()).firstOrNull { it.id == id }?.let { entry ->
        HandledCommand(entry.id, DeviceCommandStatus.fromWire(entry.status)?.let { DeviceCommandResult(it, entry.detail) })
    }

    /** Remembers [id] before the command runs. */
    suspend fun begin(id: String, expiresAt: Instant?) = update { entries ->
        if (entries.any { it.id == id }) entries else entries + Entry(id = id, keepUntilEpochMillis = keepUntil(expiresAt))
    }

    suspend fun finish(id: String, result: DeviceCommandResult) = update { entries ->
        val known = entries.firstOrNull { it.id == id }
        entries.filterNot { it.id == id } + Entry(
            id = id,
            status = result.status.wireName,
            detail = result.detail,
            keepUntilEpochMillis = known?.keepUntilEpochMillis ?: keepUntil(null),
        )
    }

    suspend fun clear() {
        dataStore.edit { it.remove(HANDLED) }
    }

    private suspend fun update(change: (List<Entry>) -> List<Entry>) {
        val now = clock.millis()
        dataStore.edit { prefs ->
            val kept = change(entries(prefs)).filter { it.keepUntilEpochMillis > now }.takeLast(MAX_ENTRIES)
            prefs[HANDLED] = json.encodeToString(serializer, kept)
        }
    }

    private fun entries(prefs: Preferences): List<Entry> =
        prefs[HANDLED]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /** The server stops returning a command at its expiry; the id is kept a while longer for a slow clock. */
    private fun keepUntil(expiresAt: Instant?): Long =
        maxOf(expiresAt ?: Instant.EPOCH, clock.instant()).plus(KEEP_AFTER_EXPIRY).toEpochMilli()

    private companion object {
        val HANDLED = stringPreferencesKey("handled_commands")
        val KEEP_AFTER_EXPIRY: Duration = Duration.ofDays(7)
        const val MAX_ENTRIES = 200
    }
}
