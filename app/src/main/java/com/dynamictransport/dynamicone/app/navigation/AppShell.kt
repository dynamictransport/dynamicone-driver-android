package com.dynamictransport.dynamicone.app.navigation

import com.dynamictransport.dynamicone.domain.model.AppModule

/**
 * Top-level areas of the app and the module that brings them. Declaration order is the bottom bar order; a lower
 * [priority] wins a place in the bar when more areas are enabled than fit.
 */
enum class AppSection(val module: AppModule?, val priority: Int) {
    HOME(AppModule.DRIVER, 0),
    TRIPS(AppModule.DRIVER, 2),
    DOCUMENTS(AppModule.DRIVER, 4),
    MESSAGES(AppModule.DRIVER, 3),
    ATTENDANCE(AppModule.OFFICE_ATTENDANCE, 1),
    ATTENDANCE_RECORDS(AppModule.OFFICE_ATTENDANCE, 5),

    /** Every account; always the last item of the bar. */
    PROFILE(null, Int.MAX_VALUE),
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
        val enabled = AppSection.entries.filter { section -> section.module != null && section.module in modules }
        val inBar = enabled.sortedBy { it.priority }.take(MAX_BOTTOM_BAR_ITEMS - 1).toSet()
        return ShellLayout(
            bottomBar = enabled.filter { it in inBar } + AppSection.PROFILE,
            overflow = enabled.filterNot { it in inBar },
        )
    }
}
