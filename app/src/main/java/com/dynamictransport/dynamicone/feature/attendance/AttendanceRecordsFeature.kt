package com.dynamictransport.dynamicone.feature.attendance

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.InfoRow
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.SectionCard
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.domain.model.AttendanceDayState
import com.dynamictransport.dynamicone.domain.model.AttendanceDirection
import com.dynamictransport.dynamicone.domain.model.AttendanceMonth
import com.dynamictransport.dynamicone.domain.model.LocalPunch
import com.dynamictransport.dynamicone.domain.model.LocalPunchState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.repository.AttendanceRepository
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.StatusUi
import com.dynamictransport.dynamicone.feature.common.StatusUiBadge
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.formatDuration
import com.dynamictransport.dynamicone.feature.common.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/** One row of a day: a server punch (no badge) or a punch still known only on the phone (with its send state). */
data class RecordPunchUi(
    val key: String,
    val direction: AttendanceDirection,
    val time: Instant,
    val pointName: String?,
    val syncState: SyncState?,
)

data class RecordDayUi(
    val date: LocalDate,
    /** Null for a day the server has not reported yet (only punches on the phone). */
    val state: AttendanceDayState?,
    val workedMinutes: Int?,
    /** Oldest first. */
    val punches: List<RecordPunchUi>,
)

data class AttendanceRecordsUiState(
    val month: YearMonth,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    /** Null until the month was read. Newest day first. */
    val days: List<RecordDayUi>? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val refreshing: Boolean = false,
    @param:StringRes val loadError: Int? = null,
    val stale: Boolean = false,
    val unavailable: Boolean = false,
    /** The account may not see office attendance (any more): `403 attendance_access_required`. */
    val accessRemoved: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AttendanceRecordsViewModel @Inject constructor(
    private val attendance: AttendanceRepository,
    clock: Clock,
) : ViewModel() {

    private val latestMonth: YearMonth = YearMonth.from(clock.instant().atZone(ZoneId.systemDefault()))
    private val month = MutableStateFlow(latestMonth)
    private val refreshing = MutableStateFlow(false)
    private val results = MutableStateFlow<Map<YearMonth, RefreshResult>>(emptyMap())

    private val data = month.flatMapLatest { selected -> attendance.observeMonth(selected).map { selected to it } }

    val uiState: StateFlow<AttendanceRecordsUiState> =
        combine(data, attendance.observeLocalPunches(), refreshing, results) { (selected, server), local, busy, all ->
            val result = all[selected]
            val failed = result?.takeIf {
                it != RefreshResult.SUCCESS && it != RefreshResult.NOT_SUPPORTED && it != RefreshResult.ACCESS_REQUIRED
            }
            val zone = server?.zone ?: ZoneId.systemDefault()
            AttendanceRecordsUiState(
                month = selected,
                canGoBack = selected > EARLIEST_MONTH,
                canGoForward = selected < latestMonth,
                days = if (server == null && result == null) null else mergeDays(selected, server, local, zone),
                zone = zone,
                refreshing = busy,
                loadError = failed?.takeIf { server == null }?.messageRes(),
                stale = failed != null && server != null,
                unavailable = result == RefreshResult.NOT_SUPPORTED,
                accessRemoved = result == RefreshResult.ACCESS_REQUIRED,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AttendanceRecordsUiState(month = latestMonth, canGoBack = true, canGoForward = false),
        )

    fun onPreviousMonth() {
        if (month.value <= EARLIEST_MONTH) return
        month.value = month.value.minusMonths(1)
        refresh()
    }

    fun onNextMonth() {
        if (month.value >= latestMonth) return
        month.value = month.value.plusMonths(1)
        refresh()
    }

    fun refresh() {
        val selected = month.value
        viewModelScope.launch {
            refreshing.value = true
            try {
                val result = attendance.refreshMonth(selected)
                results.update { it + (selected to result) }
            } finally {
                refreshing.value = false
            }
        }
    }

    companion object {
        val EARLIEST_MONTH: YearMonth = YearMonth.of(2000, 1)

        /**
         * Server days plus punches the server list does not contain yet (waiting on the phone, or recorded after the
         * month was read). Rejected punches are shown on the attendance screen only. Durations come from the server.
         */
        fun mergeDays(month: YearMonth, server: AttendanceMonth?, local: List<LocalPunch>, zone: ZoneId): List<RecordDayUi> {
            val serverDays = server?.days.orEmpty()
            val knownCommands = serverDays.flatMap { day -> day.punches.mapNotNull { it.commandId } }.toSet()
            val phoneOnly = local
                .filter { it.state != LocalPunchState.FAILED && it.commandId !in knownCommands }
                .filter { YearMonth.from(it.time.atZone(zone)) == month }
                .groupBy { it.time.atZone(zone).toLocalDate() }
            val dates = (serverDays.map { it.workDate } + phoneOnly.keys).distinct().sortedDescending()
            return dates.map { date ->
                val day = serverDays.firstOrNull { it.workDate == date }
                val fromServer = day?.punches.orEmpty().map { RecordPunchUi(it.id, it.direction, it.occurredAt, it.pointName, null) }
                val fromPhone = phoneOnly[date].orEmpty().map { punch ->
                    RecordPunchUi(
                        key = punch.commandId,
                        direction = punch.direction,
                        time = punch.time,
                        pointName = punch.pointName,
                        syncState = if (punch.state == LocalPunchState.SYNCED) SyncState.SYNCED else SyncState.PENDING,
                    )
                }
                RecordDayUi(date, day?.state, day?.workedMinutes, (fromServer + fromPhone).sortedBy { it.time })
            }
        }
    }
}

@Composable
fun AttendanceRecordsRoute(onBack: (() -> Unit)?, viewModel: AttendanceRecordsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    AttendanceRecordsScreen(
        state = state,
        onBack = onBack,
        onPreviousMonth = viewModel::onPreviousMonth,
        onNextMonth = viewModel::onNextMonth,
        onRefresh = viewModel::refresh,
    )
}

@Composable
fun AttendanceRecordsScreen(
    state: AttendanceRecordsUiState,
    onBack: (() -> Unit)?,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onRefresh: () -> Unit,
) {
    val locale = currentLocale()
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.attendance_records_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
                actions = {
                    if (!state.unavailable && !state.accessRemoved) {
                        IconButton(onClick = onRefresh, enabled = !state.refreshing, modifier = Modifier.size(56.dp)) {
                            if (state.refreshing) {
                                CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        ) {
            MonthSelector(state, locale, onPreviousMonth, onNextMonth)
            if (state.stale) {
                StatusBanner(
                    message = stringResource(R.string.data_stale),
                    icon = Icons.Filled.SyncProblem,
                    tone = StatusTone.Warning,
                    actionLabel = if (state.refreshing) null else stringResource(R.string.common_retry),
                    onAction = onRefresh,
                    modifier = Modifier.testTag("records_stale"),
                )
            }
            val days = state.days
            val loadError = state.loadError
            when {
                state.accessRemoved -> EmptyState(
                    icon = Icons.Filled.PhonelinkErase,
                    title = stringResource(R.string.attendance_access_removed),
                    body = null,
                    modifier = Modifier.testTag("records_access_removed"),
                )
                state.unavailable -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(R.string.feature_not_connected),
                    body = null,
                    modifier = Modifier.testTag("records_unavailable"),
                )
                loadError != null && days.isNullOrEmpty() -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(loadError),
                    body = null,
                    modifier = Modifier.testTag("records_error"),
                ) {
                    Spacer(Modifier.height(12.dp))
                    SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRefresh, icon = Icons.Filled.Refresh)
                }
                days == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("records_loading"))
                days.isEmpty() -> EmptyState(
                    icon = Icons.Filled.EventBusy,
                    title = stringResource(R.string.attendance_records_empty),
                    body = null,
                    modifier = Modifier.testTag("records_empty"),
                )
                else -> days.forEach { DayCard(it, state.zone, locale) }
            }
        }
    }
}

@Composable
private fun MonthSelector(state: AttendanceRecordsUiState, locale: Locale, onPreviousMonth: () -> Unit, onNextMonth: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onPreviousMonth, enabled = state.canGoBack, modifier = Modifier.size(56.dp).testTag("records_previous")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.payroll_previous_month))
        }
        Text(
            text = DriverFormats.month(state.month, locale).replaceFirstChar { it.titlecase(locale) },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .testTag("records_month"),
        )
        IconButton(onClick = onNextMonth, enabled = state.canGoForward, modifier = Modifier.size(56.dp).testTag("records_next")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.payroll_next_month))
        }
    }
}

@Composable
private fun DayCard(day: RecordDayUi, zone: ZoneId, locale: Locale) {
    SectionCard(modifier = Modifier.testTag("records_day_${day.date}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DriverFormats.date(day.date, locale),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            day.state?.let { StatusUiBadge(it.toStatusUi()) }
        }
        day.punches.forEach { PunchRow(it.direction, it.time, it.pointName, it.syncState, zone) }
        // The server calculates the worked time; a day without one shows "—" (never 0, docs/15 §5.5).
        InfoRow(
            icon = Icons.Filled.Timer,
            label = stringResource(R.string.attendance_worked),
            value = day.workedMinutes?.let { formatDuration(Duration.ofMinutes(it.toLong())) }
                ?: stringResource(R.string.value_unknown),
        )
    }
}

private fun AttendanceDayState.toStatusUi(): StatusUi = when (this) {
    AttendanceDayState.COMPLETE -> StatusUi(R.string.attendance_day_complete, Icons.Filled.CheckCircle, StatusTone.Positive)
    AttendanceDayState.OPEN -> StatusUi(R.string.attendance_day_open, Icons.Filled.Work, StatusTone.Info)
    AttendanceDayState.INCOMPLETE -> StatusUi(R.string.attendance_day_incomplete, Icons.Filled.Warning, StatusTone.Warning)
    AttendanceDayState.NEEDS_REVIEW -> StatusUi(R.string.attendance_day_review, Icons.Filled.RateReview, StatusTone.Warning)
    AttendanceDayState.UNKNOWN -> StatusUi(R.string.attendance_day_unknown, Icons.AutoMirrored.Filled.HelpOutline, StatusTone.Neutral)
}

