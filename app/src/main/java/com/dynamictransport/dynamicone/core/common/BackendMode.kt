package com.dynamictransport.dynamicone.core.common

import com.dynamictransport.dynamicone.BuildConfig

/** Which backend implementation the repositories use. */
enum class BackendMode {
    /** The DynamicONE Driver REST API. Failures are errors, never replaced by demo data. */
    REAL,

    /** In-app demo backend, clearly marked DEMO. Only possible in debug builds. */
    DEMO,
}

object BackendModeResolver {

    /**
     * Release builds are always [BackendMode.REAL]. Debug builds use the Gradle switch
     * `driver.demoMode`, which tests may override with the `driver.backendMode` system property.
     */
    fun resolve(isDebugBuild: Boolean, demoModeByDefault: Boolean, override: String?): BackendMode {
        if (!isDebugBuild) return BackendMode.REAL
        return when (override?.lowercase()) {
            "demo" -> BackendMode.DEMO
            "real" -> BackendMode.REAL
            else -> if (demoModeByDefault) BackendMode.DEMO else BackendMode.REAL
        }
    }

    val current: BackendMode by lazy {
        resolve(BuildConfig.DEBUG, BuildConfig.DEMO_MODE, System.getProperty("driver.backendMode"))
    }
}
