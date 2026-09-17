package com.dynamictransport.dynamicone.app.navigation

import com.dynamictransport.dynamicone.app.navigation.AppSection.ATTENDANCE
import com.dynamictransport.dynamicone.app.navigation.AppSection.ATTENDANCE_RECORDS
import com.dynamictransport.dynamicone.app.navigation.AppSection.DOCUMENTS
import com.dynamictransport.dynamicone.app.navigation.AppSection.HOME
import com.dynamictransport.dynamicone.app.navigation.AppSection.MESSAGES
import com.dynamictransport.dynamicone.app.navigation.AppSection.PROFILE
import com.dynamictransport.dynamicone.app.navigation.AppSection.TRIPS
import com.dynamictransport.dynamicone.domain.model.AppModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellTest {

    @Test
    fun `drivers keep their navigation`() {
        val layout = AppShell.layout(setOf(AppModule.DRIVER))

        assertEquals(listOf(HOME, TRIPS, DOCUMENTS, MESSAGES, PROFILE), layout.bottomBar)
        assertTrue(layout.overflow.isEmpty())
        assertEquals(HOME, layout.start)
    }

    @Test
    fun `office staff open attendance first`() {
        val layout = AppShell.layout(setOf(AppModule.OFFICE_ATTENDANCE))

        assertEquals(listOf(ATTENDANCE, ATTENDANCE_RECORDS, PROFILE), layout.bottomBar)
        assertTrue(layout.overflow.isEmpty())
        assertEquals(ATTENDANCE, layout.start)
    }

    @Test
    fun `more areas than fit keep five bar items and list the rest in the profile`() {
        val layout = AppShell.layout(setOf(AppModule.OFFICE_ATTENDANCE, AppModule.DRIVER))

        assertEquals(AppShell.MAX_BOTTOM_BAR_ITEMS, layout.bottomBar.size)
        assertEquals(listOf(HOME, TRIPS, MESSAGES, ATTENDANCE, PROFILE), layout.bottomBar)
        assertEquals(listOf(DOCUMENTS, ATTENDANCE_RECORDS), layout.overflow)
    }

    @Test
    fun `module keys of newer app versions are ignored`() {
        assertEquals(setOf(AppModule.OFFICE_ATTENDANCE), AppModule.fromWire(listOf("officeAttendance", "warehouse", "Driver")))
        assertTrue(AppModule.fromWire(null).isEmpty())
    }
}
