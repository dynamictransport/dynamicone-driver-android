package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.database.DriverCacheDao
import com.dynamictransport.dynamicone.core.database.DriverCacheEntity
import com.dynamictransport.dynamicone.core.network.DriverApi
import com.dynamictransport.dynamicone.core.network.DriverErrorCodes
import com.dynamictransport.dynamicone.core.network.DriverShiftErrorCodes
import com.dynamictransport.dynamicone.core.network.DriverTimesResponseDto
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.core.network.VehicleCheckTemplateDto
import com.dynamictransport.dynamicone.core.security.SessionPreferences
import com.dynamictransport.dynamicone.domain.model.CheckTemplate
import com.dynamictransport.dynamicone.domain.model.DriverTimes
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.VehicleLookup
import com.dynamictransport.dynamicone.domain.repository.DriverTimesRepository
import com.dynamictransport.dynamicone.domain.repository.VehicleCheckRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The latest server answers of the signed-in driver, kept on the phone: the alarm needs the tachograph summary after a
 * restart without network, and the vehicle check needs its list at a truck without signal.
 */
@Singleton
class DriverCacheStore @Inject constructor(
    private val dao: DriverCacheDao,
    private val json: Json,
    private val clock: Clock,
) {
    suspend fun <T> read(driverId: String, key: String, serializer: KSerializer<T>): T? =
        dao.get(driverId, key)?.let { entry -> runCatching { json.decodeFromString(serializer, entry.json) }.getOrNull() }

    suspend fun <T> write(driverId: String, key: String, serializer: KSerializer<T>, value: T) =
        dao.upsert(DriverCacheEntity(driverId, key, json.encodeToString(serializer, value), clock.instant()))

    /** Another driver signed in on this phone. */
    suspend fun keepOnly(driverId: String) = dao.deleteForOtherDrivers(driverId)

    suspend fun clear() = dao.deleteAll()
}

/**
 * Server data of one driver held in memory and loaded from [DriverCacheStore] on first use. Keyed by driver id, so a
 * different account never sees another driver's data.
 */
internal class DriverScopedCache<T>(
    private val store: DriverCacheStore,
    private val key: String,
    private val serializer: KSerializer<T>,
) {
    val values = MutableStateFlow<Map<String, T>>(emptyMap())

    suspend fun load(driverId: String): T? {
        values.value[driverId]?.let { return it }
        val stored = store.read(driverId, key, serializer) ?: return null
        values.update { if (driverId in it) it else it + (driverId to stored) }
        return values.value[driverId]
    }

    suspend fun put(driverId: String, value: T) {
        values.update { it + (driverId to value) }
        store.write(driverId, key, serializer, value)
    }

    fun clearMemory() {
        values.value = emptyMap()
    }
}

/** Reads end with a [RefreshResult]; `403` other than a password change means the driver features are closed. */
internal inline fun driverRead(tracker: ApiActivityTracker, block: () -> Unit): RefreshResult = try {
    block()
    tracker.markReadSucceeded()
    RefreshResult.SUCCESS
} catch (e: CancellationException) {
    throw e
} catch (e: RemoteException) {
    when {
        e is RemoteException.Forbidden && e.problem?.code != DriverErrorCodes.PASSWORD_CHANGE_REQUIRED -> RefreshResult.ACCESS_REQUIRED
        // The endpoint is not live on this server yet (docs/16 is a proposal until the backend ships it).
        e is RemoteException.NotFound && !e.isApiDisabled -> RefreshResult.NOT_SUPPORTED
        else -> e.toRefreshResult()
    }
}

/** Tachograph summary of the signed-in driver (docs/16 §3). */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ApiDriverTimesRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
    private val sessionPreferences: SessionPreferences,
    store: DriverCacheStore,
) : DriverTimesRepository {

    private val cache = DriverScopedCache(store, DriverCacheEntity.DRIVER_TIMES, DriverTimesResponseDto.serializer())

    override fun observeDriverTimes(): Flow<DriverTimes?> =
        sessionPreferences.driverIdChanges.flatMapLatest { driverId ->
            if (driverId == null) {
                flowOf(null)
            } else {
                cache.values.onStart { cache.load(driverId) }.map { it[driverId]?.let(DriverShiftMapping::driverTimes) }
            }
        }.distinctUntilChanged()

    override suspend fun current(): DriverTimes? {
        val driverId = sessionPreferences.driverId() ?: return null
        return cache.load(driverId)?.let(DriverShiftMapping::driverTimes)
    }

    override suspend fun refresh(): RefreshResult {
        val driverId = sessionPreferences.driverId() ?: return RefreshResult.SESSION_EXPIRED
        return driverRead(tracker) {
            val response = tracker.call { api.driverTimes() }
            DriverShiftMapping.driverTimes(response) ?: throw RemoteException.InvalidResponse()
            cache.put(driverId, response)
        }
    }

    fun clear() = cache.clearMemory()
}

/** Daily check list and tag lookup (docs/16 §5, §6). */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ApiVehicleCheckRepository @Inject constructor(
    private val api: DriverApi,
    private val tracker: ApiActivityTracker,
    private val sessionPreferences: SessionPreferences,
    store: DriverCacheStore,
) : VehicleCheckRepository {

    private val cache = DriverScopedCache(store, DriverCacheEntity.CHECK_TEMPLATE, VehicleCheckTemplateDto.serializer())

    override fun observeTemplate(): Flow<CheckTemplate> =
        sessionPreferences.driverIdChanges.flatMapLatest { driverId ->
            if (driverId == null) {
                flowOf(CheckTemplate.Default)
            } else {
                cache.values.onStart { cache.load(driverId) }
                    .map { it[driverId]?.let(DriverShiftMapping::template) ?: CheckTemplate.Default }
            }
        }.distinctUntilChanged()

    override suspend fun refreshTemplate(): RefreshResult {
        val driverId = sessionPreferences.driverId() ?: return RefreshResult.SESSION_EXPIRED
        return driverRead(tracker) {
            val response = tracker.call { api.vehicleCheckTemplate() }
            // An unusable list keeps the one the phone has.
            DriverShiftMapping.template(response) ?: throw RemoteException.InvalidResponse()
            cache.put(driverId, response)
        }
    }

    override suspend fun lookupVehicle(tagUid: String): VehicleLookup = try {
        val response = tracker.call { api.vehicleTag(tagUid) }
        DriverShiftMapping.vehicleTag(response)?.let { VehicleLookup.Found(it, isPlanned = response.isPlannedVehicle == true) }
            ?: VehicleLookup.Unverified
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        if (e is RemoteException.NotFound && e.problem?.code == DriverShiftErrorCodes.VEHICLE_TAG_UNKNOWN) {
            VehicleLookup.UnknownTag
        } else {
            // Offline or the server cannot answer now: the tag is checked again when the start is sent.
            VehicleLookup.Unverified
        }
    }

    fun clear() = cache.clearMemory()
}
