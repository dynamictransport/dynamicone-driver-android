package com.dynamictransport.dynamicone.app.navigation

import com.dynamictransport.dynamicone.domain.model.AppModule

/**
 * Top-level areas of the app and the modules that bring them (any one of [modules] is enough; empty: every account).
 * Declaration order is the bottom bar order; a lower [priority] wins a place in the bar when more areas are enabled
 * than fit.
 */
enum class AppSection(val modules: Set<AppModule>, val priority: Int) {
    HOME(setOf(AppModule.DRIVER), 0),
    TRIPS(setOf(AppModule.DRIVER), 2),
    DOCUMENTS(setOf(AppModule.DRIVER), 4),
    MESSAGES(setOf(AppModule.DRIVER), 3),
    ATTENDANCE(setOf(AppModule.OFFICE_ATTENDANCE), 1),
    ATTENDANCE_RECORDS(setOf(AppModule.OFFICE_ATTENDANCE), 5),

    /** Personnel documents to sign (docs/17): in the office bar, from the profile and the home card for drivers. */
    HR_DOCUMENTS(setOf(AppModule.HR_DOCUMENTS), 6),

    /** Files of the own personnel file: the history (`hrDocuments`) and the driver's required documents (`driver`). */
    MY_DOCUMENTS(setOf(AppModule.HR_DOCUMENTS, AppModule.DRIVER), 7),

    /** Files of the driver's assigned vehicle. */
    VEHICLE_DOCUMENTS(setOf(AppModule.DRIVER), 8),

    /** Cash approved by finance that this account hands over to personnel (docs/28). */
    CASH_DISBURSEMENTS(setOf(AppModule.CASH_DISBURSEMENTS), 9),

    /** The account's own cash payments and signed receipts (docs/28). */
    MY_CASH_PAYMENTS(setOf(AppModule.CASH_PAYMENTS), 10),

    /** Every account; always the last item of the bar. */
    PROFILE(emptySet(), Int.MAX_VALUE),
}

data class ShellLayout(
    val bottomBar: List<AppSection>,
    /** Enabled areas without a place in the bar; the profile lists them. */
    val overflow: List<AppSection>,
) {
    /** The first screen after signing in. */
    val start: AppSection get() = bottomBar.first()
}

/** Builds the navigation of the signed-in account from its server modules (CLAUDE.md §7: at most 5 bar items). */
object AppShell {
    const val MAX_BOTTOM_BAR_ITEMS = 5

    fun layout(modules: Set<AppModule>): ShellLayout {
        val enabled = AppSection.entries.filter { section -> section.modules.any { it in modules } }
        val inBar = enabled.sortedBy { it.priority }.take(MAX_BOTTOM_BAR_ITEMS - 1).toSet()
        return ShellLayout(
            bottomBar = enabled.filter { it in inBar } + AppSection.PROFILE,
            overflow = enabled.filterNot { it in inBar },
        )
    }
}
