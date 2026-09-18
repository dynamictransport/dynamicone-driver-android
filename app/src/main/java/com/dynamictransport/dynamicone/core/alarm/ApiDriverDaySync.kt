package com.dynamictransport.dynamicone.core.alarm

import com.dynamictransport.dynamicone.core.sync.DriverDaySync
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.repository.DailyShiftRepository
import com.dynamictransport.dynamicone.domain.repository.DriverTimesRepository
import com.dynamictransport.dynamicone.domain.repository.VehicleCheckRepository
import javax.inject.Inject

/**
 * One read of the driver's day: tachograph summary, shift and check list, then the alarm is set from the result
 * (docs/16 §3, §4, §6). Runs with every server read of the driver screens and in the background.
 */
class ApiDriverDaySync @Inject constructor(
    private val driverTimes: DriverTimesRepository,
    private val dailyShift: DailyShiftRepository,
    private val vehicleCheck: VehicleCheckRepository,
    private val restAlarm: RestAlarmController,
) : DriverDaySync {

    override suspend fun refresh(): RefreshResult {
        val times = driverTimes.refresh()
        val shift = dailyShift.refresh()
        // The list the driver needs at the vehicle, also without signal there.
        vehicleCheck.refreshTemplate()
        // Works on the stored data when a read failed.
        restAlarm.reconcile()
        return listOf(shift, times).firstOrNull { it != RefreshResult.SUCCESS } ?: RefreshResult.SUCCESS
    }
}
