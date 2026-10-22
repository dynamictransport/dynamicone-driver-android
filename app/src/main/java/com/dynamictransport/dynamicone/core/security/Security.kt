package com.dynamictransport.dynamicone.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.dynamictransport.dynamicone.domain.model.AppModule
import com.dynamictransport.dynamicone.domain.model.AssignedVehicle
import com.dynamictransport.dynamicone.domain.model.AssignedVehicleState
import com.dynamictransport.dynamicone.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SessionDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TokenDataStore

interface CryptoBox {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(data: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable key in the Android Keystore. */
class KeystoreCryptoBox(private val alias: String = "dynamicone_driver_session") : CryptoBox {

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val ivSize = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + ivSize)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(data, 1 + ivSize, data.size - 1 - ivSize)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

/** Access + refresh token of one Driver API session. Never logged. */
class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshTokenExpiresAt: Instant?,
    val mustChangePassword: Boolean = false,
) {
    override fun toString(): String =
        "TokenPair(accessTokenExpiresAt=$accessTokenExpiresAt, mustChangePassword=$mustChangePassword)"
}

interface TokenStore {
    suspend fun read(): TokenPair?

    /** Replaces both tokens in a single write. */
    suspend fun write(tokens: TokenPair)

    suspend fun clear()
}

/**
 * Both tokens are serialized together, encrypted with a Keystore key and stored as one DataStore
 * value, so a rotation replaces access and refresh token atomically. No plain SharedPreferences.
 */
class EncryptedTokenStore(
    private val dataStore: DataStore<Preferences>,
    private val cryptoBox: CryptoBox,
) : TokenStore {

    @Serializable
    private data class StoredTokens(
        val accessToken: String,
        val refreshToken: String,
        val accessTokenExpiresAtEpochMillis: Long,
        val refreshTokenExpiresAtEpochMillis: Long? = null,
        val mustChangePassword: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(): TokenPair? {
        val encrypted = dataStore.data.first()[TOKENS] ?: return null
        return runCatching {
            val plain = cryptoBox.decrypt(Base64.getDecoder().decode(encrypted)).decodeToString()
            val stored = json.decodeFromString(StoredTokens.serializer(), plain)
            TokenPair(
                accessToken = stored.accessToken,
                refreshToken = stored.refreshToken,
                accessTokenExpiresAt = Instant.ofEpochMilli(stored.accessTokenExpiresAtEpochMillis),
                refreshTokenExpiresAt = stored.refreshTokenExpiresAtEpochMillis?.let(Instant::ofEpochMilli),
                mustChangePassword = stored.mustChangePassword,
            )
        }.getOrElse {
            // Key invalidated (e.g. device credential reset) or corrupt value: a new login is required.
            clear()
            null
        }
    }

    override suspend fun write(tokens: TokenPair) {
        val plain = json.encodeToString(
            StoredTokens.serializer(),
            StoredTokens(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                accessTokenExpiresAtEpochMillis = tokens.accessTokenExpiresAt.toEpochMilli(),
                refreshTokenExpiresAtEpochMillis = tokens.refreshTokenExpiresAt?.toEpochMilli(),
                mustChangePassword = tokens.mustChangePassword,
            ),
        ).encodeToByteArray()
        val encrypted = Base64.getEncoder().encodeToString(cryptoBox.encrypt(plain))
        dataStore.edit {
            it[TOKENS] = encrypted
            it.remove(LEGACY_REFRESH_TOKEN)
        }
    }

    override suspend fun clear() {
        dataStore.edit {
            it.remove(TOKENS)
            it.remove(LEGACY_REFRESH_TOKEN)
        }
    }

    private companion object {
        val TOKENS = stringPreferencesKey("driver_api_tokens")
        val LEGACY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    }
}

enum class SessionEvent {
    /** Refresh was rejected: the session is gone on the server. */
    EXPIRED,

    /** A protected call answered `403 password_change_required`. */
    PASSWORD_CHANGE_REQUIRED,
}

/** Single owner of the token pair in memory and in secure storage. */
@Singleton
class TokenManager @Inject constructor(
    private val tokenStore: TokenStore,
) {
    private val lock = Any()

    @Volatile
    private var loaded = false

    @Volatile
    private var cached: TokenPair? = null

    private val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)
    val sessionEvents: SharedFlow<SessionEvent> = events

    suspend fun load(): TokenPair? {
        if (!loaded) {
            val stored = tokenStore.read()
            synchronized(lock) {
                if (!loaded) {
                    cached = stored
                    loaded = true
                }
            }
        }
        return cached
    }

    /** Called from OkHttp threads only. */
    private fun snapshot(): TokenPair? = if (loaded) cached else runBlocking { load() }

    fun currentAccessToken(): String? = snapshot()?.accessToken

    fun currentRefreshToken(): String? = snapshot()?.refreshToken

    suspend fun hasSession(): Boolean = load() != null

    /** Stores a new pair (login or rotation); the previous refresh token is never reused. */
    suspend fun store(tokens: TokenPair) {
        synchronized(lock) {
            cached = tokens
            loaded = true
        }
        tokenStore.write(tokens)
    }

    fun replaceTokensBlocking(tokens: TokenPair) = runBlocking { store(tokens) }

    suspend fun clear() {
        synchronized(lock) {
            cached = null
            loaded = true
        }
        tokenStore.clear()
    }

    fun onRefreshRejected() {
        runBlocking { clear() }
        events.tryEmit(SessionEvent.EXPIRED)
    }

    fun onPasswordChangeRequired() {
        events.tryEmit(SessionEvent.PASSWORD_CHANGE_REQUIRED)
    }
}

/** Non-secret session data. Passwords are never stored. */
@Singleton
class SessionPreferences @Inject constructor(
    @param:SessionDataStore private val dataStore: DataStore<Preferences>,
) {
    val sessionExpired: Flow<Boolean> = dataStore.data.map { it[SESSION_EXPIRED] ?: false }

    /** Session key of the signed-in account ([UserProfile.id]). */
    suspend fun userId(): String? = dataStore.data.first()[USER_ID]

    val userIdChanges: Flow<String?> = dataStore.data.map { it[USER_ID] }.distinctUntilChanged()

    /** Driver API identity of the signed-in account; null without the driver module. Driver commands are bound to it. */
    suspend fun driverId(): String? = dataStore.data.first()[DRIVER_ID]

    val driverIdChanges: Flow<String?> = dataStore.data.map { it[DRIVER_ID] }.distinctUntilChanged()

    suspend fun lastDriverId(): String? = dataStore.data.first()[LAST_DRIVER_ID]

    suspend fun modules(): Set<AppModule> = AppModule.fromWire(dataStore.data.first()[MODULES])

    /** Stores who is signed in, with the modules, name and assigned vehicle the app needs to open offline. */
    suspend fun setLoggedInUser(profile: UserProfile) {
        dataStore.edit {
            it[USER_ID] = profile.id
            if (profile.driverId == null) {
                it.remove(DRIVER_ID)
            } else {
                it[DRIVER_ID] = profile.driverId
                it[LAST_DRIVER_ID] = profile.driverId
            }
            it[SESSION_EXPIRED] = false
            it.writeProfile(profile)
        }
    }

    /** A newer `me` of the same account: name, modules and the assigned vehicle may have changed. */
    suspend fun updateProfile(profile: UserProfile) {
        dataStore.edit { it.writeProfile(profile) }
    }

    suspend fun clearLoggedInUser(expired: Boolean) {
        dataStore.edit {
            it.remove(USER_ID)
            it.remove(DRIVER_ID)
            it.remove(MODULES)
            it.remove(DISPLAY_NAME)
            it.remove(JOB_TITLE)
            it.removeAssignedVehicle()
            it[SESSION_EXPIRED] = expired
        }
    }

    /** Display name from `me`, kept so the app can greet the user offline. */
    suspend fun displayName(): String? = dataStore.data.first()[DISPLAY_NAME]

    /** Job title from `me` (docs/22), kept with the name for offline starts. */
    suspend fun jobTitle(): String? = dataStore.data.first()[JOB_TITLE]

    /** `me.assignedVehicle` of the signed-in account; null until `me` has answered for it. */
    val assignedVehicleChanges: Flow<AssignedVehicleState?> = dataStore.data.map { it.assignedVehicle() }.distinctUntilChanged()

    suspend fun assignedVehicle(): AssignedVehicleState? = dataStore.data.first().assignedVehicle()

    /** [profile] is an answer of `me`: a null vehicle means that none is assigned. */
    private fun MutablePreferences.writeProfile(profile: UserProfile) {
        this[MODULES] = profile.modules.mapTo(LinkedHashSet()) { module -> module.wireName }
        if (profile.displayName.isBlank()) remove(DISPLAY_NAME) else this[DISPLAY_NAME] = profile.displayName
        if (profile.jobTitle == null) remove(JOB_TITLE) else this[JOB_TITLE] = profile.jobTitle
        removeAssignedVehicle()
        val vehicle = profile.assignedVehicle
        this[VEHICLE_ASSIGNED] = vehicle != null
        vehicle?.vehicleId?.let { this[VEHICLE_ID] = it }
        vehicle?.companyCode?.let { this[VEHICLE_COMPANY_CODE] = it }
        vehicle?.registrationNumber?.let { this[VEHICLE_REGISTRATION] = it }
    }

    private fun MutablePreferences.removeAssignedVehicle() {
        remove(VEHICLE_ASSIGNED)
        remove(VEHICLE_ID)
        remove(VEHICLE_COMPANY_CODE)
        remove(VEHICLE_REGISTRATION)
    }

    private fun Preferences.assignedVehicle(): AssignedVehicleState? = when (this[VEHICLE_ASSIGNED]) {
        null -> null
        false -> AssignedVehicleState.NotAssigned
        true -> AssignedVehicleState.Assigned(AssignedVehicle(this[VEHICLE_ID], this[VEHICLE_COMPANY_CODE], this[VEHICLE_REGISTRATION]))
    }

    suspend fun lastUsername(): String? = dataStore.data.first()[LAST_USERNAME]

    suspend fun setLastUsername(username: String) {
        dataStore.edit { it[LAST_USERNAME] = username }
    }

    /** Privacy-friendly installation id (docs/05_SECURITY.md), not a hardware identifier. */
    suspend fun installationId(): String {
        dataStore.data.first()[INSTALLATION_ID]?.let { return it }
        val created = UUID.randomUUID().toString()
        dataStore.edit { prefs -> if (prefs[INSTALLATION_ID] == null) prefs[INSTALLATION_ID] = created }
        return dataStore.data.first()[INSTALLATION_ID] ?: created
    }

    private companion object {
        val USER_ID = stringPreferencesKey("user_id")
        val DRIVER_ID = stringPreferencesKey("driver_id")
        val MODULES = stringSetPreferencesKey("modules")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val JOB_TITLE = stringPreferencesKey("job_title")
        val LAST_DRIVER_ID = stringPreferencesKey("last_driver_id")
        val LAST_USERNAME = stringPreferencesKey("last_username")
        val INSTALLATION_ID = stringPreferencesKey("installation_id")
        val SESSION_EXPIRED = booleanPreferencesKey("session_expired")
        val VEHICLE_ASSIGNED = booleanPreferencesKey("assigned_vehicle")
        val VEHICLE_ID = stringPreferencesKey("assigned_vehicle_id")
        val VEHICLE_COMPANY_CODE = stringPreferencesKey("assigned_vehicle_company_code")
        val VEHICLE_REGISTRATION = stringPreferencesKey("assigned_vehicle_registration")
    }
}
