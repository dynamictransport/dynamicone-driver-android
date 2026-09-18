package com.dynamictransport.dynamicone.domain.model

import java.time.Instant

/** One question of the daily vehicle check (docs/16 §6). Titles by language code; `en` is the fallback. */
data class CheckItem(
    val key: String,
    val titles: Map<String, String>,
    val hints: Map<String, String> = emptyMap(),
    val photoRequiredOnDefect: Boolean = true,
    val notApplicableAllowed: Boolean = false,
) {
    fun title(language: String): String = titles[language] ?: titles[FALLBACK_LANGUAGE] ?: titles.values.firstOrNull() ?: key

    fun hint(language: String): String? = hints[language] ?: hints[FALLBACK_LANGUAGE]

    private companion object {
        const val FALLBACK_LANGUAGE = "en"
    }
}

data class CheckTemplate(
    /** Null: the built-in default of docs/16 §6.1, used until the server's list was read once. */
    val templateId: String?,
    val version: Int,
    val items: List<CheckItem>,
) {
    companion object {
        /** docs/16 §6.1, version 1. The backend accepts these keys also without a template id. */
        val Default = CheckTemplate(
            templateId = null,
            version = 1,
            items = listOf(
                CheckItem(
                    key = "no_new_damage",
                    titles = mapOf(
                        "en" to "No new damage on the truck or trailer",
                        "pl" to "Brak nowych uszkodzeń ciągnika i naczepy",
                        "tr" to "Çekici ve dorsede yeni hasar yok",
                    ),
                    hints = mapOf(
                        "en" to "Body, cab, glass, bumpers",
                        "pl" to "Nadwozie, kabina, szyby, zderzaki",
                        "tr" to "Kasa, kabin, camlar, tamponlar",
                    ),
                ),
                CheckItem(
                    key = "tyres",
                    titles = mapOf(
                        "en" to "Tyres: pressure normal, no cuts or damage",
                        "pl" to "Opony: ciśnienie w normie, bez przecięć i uszkodzeń",
                        "tr" to "Lastikler: basınç normal, kesik veya hasar yok",
                    ),
                ),
                CheckItem(
                    key = "lights",
                    titles = mapOf(
                        "en" to "Lights and indicators work",
                        "pl" to "Światła i kierunkowskazy działają",
                        "tr" to "Işıklar ve sinyaller çalışıyor",
                    ),
                ),
                CheckItem(
                    key = "mirrors_windows",
                    titles = mapOf(
                        "en" to "Mirrors and windows clean and intact",
                        "pl" to "Lusterka i szyby czyste i całe",
                        "tr" to "Aynalar ve camlar temiz ve sağlam",
                    ),
                ),
                CheckItem(
                    key = "no_leaks",
                    titles = mapOf(
                        "en" to "No leaks (oil, fuel, air, AdBlue)",
                        "pl" to "Brak wycieków (olej, paliwo, powietrze, AdBlue)",
                        "tr" to "Sızıntı yok (yağ, yakıt, hava, AdBlue)",
                    ),
                ),
                CheckItem(
                    key = "brakes_air",
                    titles = mapOf(
                        "en" to "Brakes and air pressure normal",
                        "pl" to "Hamulce i ciśnienie powietrza w normie",
                        "tr" to "Frenler ve hava basıncı normal",
                    ),
                ),
                CheckItem(
                    key = "coupling",
                    titles = mapOf(
                        "en" to "Trailer coupling, lines and connectors secure",
                        "pl" to "Sprzęg, przewody i złącza naczepy sprawne",
                        "tr" to "Dorse bağlantısı, hortumlar ve soketler sağlam",
                    ),
                    notApplicableAllowed = true,
                ),
                CheckItem(
                    key = "plates_reflectors",
                    titles = mapOf(
                        "en" to "Number plates and reflectors in place",
                        "pl" to "Tablice rejestracyjne i odblaski na miejscu",
                        "tr" to "Plakalar ve reflektörler yerinde",
                    ),
                ),
                CheckItem(
                    key = "dashboard_warnings",
                    titles = mapOf(
                        "en" to "No warning lights on the dashboard",
                        "pl" to "Brak kontrolek ostrzegawczych na desce",
                        "tr" to "Gösterge panelinde uyarı lambası yok",
                    ),
                ),
                CheckItem(
                    key = "safety_equipment",
                    titles = mapOf(
                        "en" to "Safety equipment complete (vest, triangle, extinguisher, first aid)",
                        "pl" to "Wyposażenie bezpieczeństwa kompletne (kamizelka, trójkąt, gaśnica, apteczka)",
                        "tr" to "Güvenlik ekipmanı tam (yelek, üçgen, söndürücü, ilk yardım)",
                    ),
                ),
                CheckItem(
                    key = "container_locks",
                    titles = mapOf(
                        "en" to "Container locks (twist-locks) closed",
                        "pl" to "Zamki kontenera (twist-locki) zamknięte",
                        "tr" to "Konteyner kilitleri (twist-lock) kapalı",
                    ),
                    hints = mapOf(
                        "en" to "Only when a container is loaded",
                        "pl" to "Tylko gdy kontener jest załadowany",
                        "tr" to "Sadece konteyner yüklüyse",
                    ),
                    notApplicableAllowed = true,
                ),
            ),
        )
    }
}

enum class CheckResult {
    OK,
    DEFECT,

    /** Only for items that allow it (e.g. container locks without a container). */
    NOT_APPLICABLE,
}

/** A defect photo on the phone; [photoId] is the id the server expects for it (docs/16 §7, §8). */
data class CheckPhoto(val photoId: String, val filePath: String)

data class CheckAnswer(
    val key: String,
    val result: CheckResult,
    /** Defects only. */
    val note: String? = null,
    /** Defects only. */
    val photos: List<CheckPhoto> = emptyList(),
)

/** A vehicle as the server names it. */
data class VehicleInfo(
    val vehicleId: String?,
    val registrationNumber: String?,
    val displayName: String? = null,
    val trailerRegistrationNumber: String? = null,
)

enum class VehicleIdentification {
    /** The vehicle's tag was read. */
    NFC,

    /** The tag could not be read; the planned vehicle was chosen and the office reviews the shift. */
    MANUAL,
}

sealed interface VehicleLookup {
    data class Found(val vehicle: VehicleInfo, val isPlanned: Boolean) : VehicleLookup

    /** No active vehicle has this tag. */
    data object UnknownTag : VehicleLookup

    /** The server could not be asked now; it checks the tag when the start is sent. */
    data object Unverified : VehicleLookup
}

/** Everything the driver confirmed at the vehicle; stored on the phone before it is sent (docs/16 §7). */
data class ShiftStartDraft(
    val commandId: String,
    /** Exactly one of [tagUid] and [manualVehicle]. */
    val tagUid: String?,
    val manualVehicle: VehicleInfo?,
    val tagScannedAt: Instant,
    /** What the phone knows about the vehicle, for the screens until the server answers. */
    val vehicle: VehicleInfo?,
    val restWarningAcknowledged: Boolean,
    /** The list the driver answered, as it was when the check began. */
    val template: CheckTemplate,
    val answers: List<CheckAnswer>,
) {
    val defectCount: Int get() = answers.count { it.result == CheckResult.DEFECT }

    val photos: List<CheckPhoto> get() = answers.flatMap { it.photos }
}

/** Server summary of a shift's vehicle check. */
data class VehicleCheckSummary(
    val defectCount: Int,
    val photosExpected: Int,
    val photosReceived: Int,
)
