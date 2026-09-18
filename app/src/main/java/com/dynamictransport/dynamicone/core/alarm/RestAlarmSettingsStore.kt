package com.dynamictransport.dynamicone.core.alarm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.domain.repository.RestAlarmSettingsRepository
import com.dynamictransport.dynamicone.domain.rules.RestAlarmMode
import com.dynamictransport.dynamicone.domain.rules.RestAlarmSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RestAlarmDataStore

/** The wake-up alarm's choices and memory on this phone (not a secret, no personal data). */
@Singleton
class DataStoreRestAlarmSettings @Inject constructor(
    @param:RestAlarmDataStore private val dataStore: DataStore<Preferences>,
) : RestAlarmSettingsRepository {

    override val settings: Flow<RestAlarmSettings> = dataStore.data.map(::read).distinctUntilChanged()

    override suspend fun current(): RestAlarmSettings = settings.first()

    override suspend fun update(transform: (RestAlarmSettings) -> RestAlarmSettings) {
        dataStore.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(prefs: Preferences) = RestAlarmSettings(
        mode = prefs[MODE]?.let { stored -> RestAlarmMode.entries.firstOrNull { it.name == stored } } ?: RestAlarmMode.ALARM,
        lead = prefs[LEAD_MINUTES]?.let { Duration.ofMinutes(it) }?.takeIf { it in RestAlarmSettings.LEAD_OPTIONS } ?: Duration.ZERO,
        dismissed = window(prefs, DISMISSED_START, DISMISSED_END),
        rung = window(prefs, RUNG_START, RUNG_END),
        snoozedUntil = prefs[SNOOZED_UNTIL]?.let(Instant::ofEpochMilli),
    )

    private fun write(prefs: MutablePreferences, settings: RestAlarmSettings) {
        prefs[MODE] = settings.mode.name
        prefs[LEAD_MINUTES] = settings.lead.toMinutes()
        putWindow(prefs, DISMISSED_START, DISMISSED_END, settings.dismissed)
        putWindow(prefs, RUNG_START, RUNG_END, settings.rung)
        val snooze = settings.snoozedUntil
        if (snooze == null) prefs.remove(SNOOZED_UNTIL) else prefs[SNOOZED_UNTIL] = snooze.toEpochMilli()
    }

    private fun window(prefs: Preferences, start: Preferences.Key<Long>, end: Preferences.Key<Long>): RestWindow? {
        val from = prefs[start] ?: return null
        val to = prefs[end] ?: return null
        return RestWindow(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))
    }

    private fun putWindow(prefs: MutablePreferences, start: Preferences.Key<Long>, end: Preferences.Key<Long>, window: RestWindow?) {
        if (window == null) {
            prefs.remove(start)
            prefs.remove(end)
        } else {
            prefs[start] = window.start.toEpochMilli()
            prefs[end] = window.end.toEpochMilli()
        }
    }

    private companion object {
        val MODE = stringPreferencesKey("mode")
        val LEAD_MINUTES = longPreferencesKey("lead_minutes")
        val DISMISSED_START = longPreferencesKey("dismissed_start")
        val DISMISSED_END = longPreferencesKey("dismissed_end")
        val RUNG_START = longPreferencesKey("rung_start")
        val RUNG_END = longPreferencesKey("rung_end")
        val SNOOZED_UNTIL = longPreferencesKey("snoozed_until")
    }
}
