package com.dynamictransport.dynamicone.app.navigation

import com.dynamictransport.dynamicone.app.navigation.AppSection.ATTENDANCE
import com.dynamictransport.dynamicone.app.navigation.AppSection.ATTENDANCE_RECORDS
import com.dynamictransport.dynamicone.app.navigation.AppSection.CASH_DISBURSEMENTS
import com.dynamictransport.dynamicone.app.navigation.AppSection.DOCUMENTS
import com.dynamictransport.dynamicone.app.navigation.AppSection.HOME
import com.dynamictransport.dynamicone.app.navigation.AppSection.HR_DOCUMENTS
import com.dynamictransport.dynamicone.app.navigation.AppSection.MESSAGES
import com.dynamictransport.dynamicone.app.navigation.AppSection.MY_CASH_PAYMENTS
import com.dynamictransport.dynamicone.app.navigation.AppSection.MY_DOCUMENTS
import com.dynamictransport.dynamicone.app.navigation.AppSection.PROFILE
import com.dynamictransport.dynamicone.app.navigation.AppSection.TRIPS
import com.dynamictransport.dynamicone.app.navigation.AppSection.VEHICLE_DOCUMENTS
import com.dynamictransport.dynamicone.domain.model.AppModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellTest {

    @Test
    fun `drivers keep their navigation and reach their own and vehicle files from the profile`() {
        val layout = AppShell.layout(setOf(AppModule.DRIVER))

        assertEquals(listOf(HOME, TRIPS, DOCUMENTS, MESSAGES, PROFILE), layout.bottomBar)
        assertEquals(listOf(MY_DOCUMENTS, VEHICLE_DOCUMENTS), layout.overflow)
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
        assertEquals(listOf(DOCUMENTS, ATTENDANCE_RECORDS, MY_DOCUMENTS, VEHICLE_DOCUMENTS), layout.overflow)
    }

    @Test
    fun `office staff find their documents to sign and their personnel file in the bar`() {
        val layout = AppShell.layout(setOf(AppModule.OFFICE_ATTENDANCE, AppModule.HR_DOCUMENTS))

        assertEquals(listOf(ATTENDANCE, ATTENDANCE_RECORDS, HR_DOCUMENTS, MY_DOCUMENTS, PROFILE), layout.bottomBar)
        assertTrue(layout.overflow.isEmpty())
        assertEquals(ATTENDANCE, layout.start)
    }

    @Test
    fun `drivers keep their bar and reach documents to sign from the profile`() {
        val layout = AppShell.layout(setOf(AppModule.DRIVER, AppModule.HR_DOCUMENTS))

        assertEquals(listOf(HOME, TRIPS, DOCUMENTS, MESSAGES, PROFILE), layout.bottomBar)
        assertEquals(listOf(HR_DOCUMENTS, MY_DOCUMENTS, VEHICLE_DOCUMENTS), layout.overflow)
        assertEquals(setOf(AppModule.HR_DOCUMENTS), AppModule.fromWire(listOf("hrDocuments")))
    }

    @Test
    fun `vehicle files are for drivers only, the personnel file for either module`() {
        val office = AppShell.layout(setOf(AppModule.OFFICE_ATTENDANCE))
        assertFalse(MY_DOCUMENTS in office.bottomBar + office.overflow)
        assertFalse(VEHICLE_DOCUMENTS in office.bottomBar + office.overflow)

        val hrOnly = AppShell.layout(setOf(AppModule.HR_DOCUMENTS))
        assertEquals(listOf(HR_DOCUMENTS, MY_DOCUMENTS, PROFILE), hrOnly.bottomBar)
    }

    @Test
    fun `cash hand-over and own cash payments come only from their server modules`() {
        assertEquals(setOf(AppModule.CASH_DISBURSEMENTS, AppModule.CASH_PAYMENTS), AppModule.fromWire(listOf("cashDisbursements", "cashPayments")))

        // An office cashier: the queue fits in the bar; a driver reaches it from the profile.
        val cashier = AppShell.layout(setOf(AppModule.OFFICE_ATTENDANCE, AppModule.CASH_DISBURSEMENTS))
        assertEquals(listOf(ATTENDANCE, ATTENDANCE_RECORDS, CASH_DISBURSEMENTS, PROFILE), cashier.bottomBar)
        val driverCashier = AppShell.layout(setOf(AppModule.DRIVER, AppModule.CASH_DISBURSEMENTS))
        assertEquals(listOf(HOME, TRIPS, DOCUMENTS, MESSAGES, PROFILE), driverCashier.bottomBar)
        assertEquals(listOf(MY_DOCUMENTS, VEHICLE_DOCUMENTS, CASH_DISBURSEMENTS), driverCashier.overflow)

        // A recipient sees only the own payments; without the modules neither area exists.
        val recipient = AppShell.layout(setOf(AppModule.HR_DOCUMENTS, AppModule.CASH_PAYMENTS))
        assertEquals(listOf(HR_DOCUMENTS, MY_DOCUMENTS, MY_CASH_PAYMENTS, PROFILE), recipient.bottomBar)
        assertFalse(CASH_DISBURSEMENTS in recipient.bottomBar + recipient.overflow)
        val plain = AppShell.layout(setOf(AppModule.OFFICE_ATTENDANCE))
        assertFalse(MY_CASH_PAYMENTS in plain.bottomBar + plain.overflow)
    }

    @Test
    fun `module keys of newer app versions are ignored`() {
        assertEquals(setOf(AppModule.OFFICE_ATTENDANCE), AppModule.fromWire(listOf("officeAttendance", "warehouse", "Driver")))
        assertTrue(AppModule.fromWire(null).isEmpty())
    }
}
