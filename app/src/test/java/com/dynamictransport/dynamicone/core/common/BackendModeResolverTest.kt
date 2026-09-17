package com.dynamictransport.dynamicone.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendModeResolverTest {

    @Test
    fun `release builds always use the real api`() {
        assertEquals(BackendMode.REAL, BackendModeResolver.resolve(isDebugBuild = false, demoModeByDefault = true, override = "demo"))
        assertEquals(BackendMode.REAL, BackendModeResolver.resolve(isDebugBuild = false, demoModeByDefault = false, override = null))
    }

    @Test
    fun `debug builds use the real api unless demo is selected explicitly`() {
        assertEquals(BackendMode.REAL, BackendModeResolver.resolve(isDebugBuild = true, demoModeByDefault = false, override = null))
        assertEquals(BackendMode.DEMO, BackendModeResolver.resolve(isDebugBuild = true, demoModeByDefault = true, override = null))
        assertEquals(BackendMode.DEMO, BackendModeResolver.resolve(isDebugBuild = true, demoModeByDefault = false, override = "demo"))
        assertEquals(BackendMode.REAL, BackendModeResolver.resolve(isDebugBuild = true, demoModeByDefault = true, override = "REAL"))
        assertEquals(BackendMode.REAL, BackendModeResolver.resolve(isDebugBuild = true, demoModeByDefault = false, override = "unknown"))
    }
}
