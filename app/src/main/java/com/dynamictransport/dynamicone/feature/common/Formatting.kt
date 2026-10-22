package com.dynamictransport.dynamicone.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.domain.model.TerminalAppointment
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** Times are stored in UTC and always shown in the device time zone. */
object DriverFormats {

    fun time(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(instant)

    fun dayTime(instant: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", locale).withZone(zone).format(instant)

    fun month(month: YearMonth, locale: Locale): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month)

    /** Exact decimal formatting: digits the server sent are never rounded away and no binary float is used. */
    fun money(amount: BigDecimal, currency: String?, locale: Locale): String {
        val knownCurrency = currency?.let { runCatching { Currency.getInstance(it) }.getOrNull() }
        val significantScale = amount.stripTrailingZeros().scale().coerceAtLeast(0)
        return if (knownCurrency != null) {
            NumberFormat.getCurrencyInstance(locale).apply {
                this.currency = knownCurrency
                maximumFractionDigits = maxOf(maximumFractionDigits, significantScale)
            }.format(amount)
        } else {
            val code = currency?.takeIf { it.isNotBlank() }
            val number = NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = maxOf(2, significantScale)
            }.format(amount)
            if (code == null) number else "$number $code"
        }
    }

    fun decimal(value: BigDecimal, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = value.stripTrailingZeros().scale().coerceAtLeast(0)
        }.format(value)

    fun date(value: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(value)

    /**
     * A TAR window in the terminal's own time zone (docs/26): `22.10.2026 10:00 – 12:00`, or with both dates when
     * the window crosses midnight there: `22.10.2026 23:30 – 23.10.2026 01:00`.
     */
    fun tarWindow(tar: TerminalAppointment): String {
        val start = tar.startAt.atZone(tar.timeZone)
        val end = tar.endAt.atZone(tar.timeZone)
        val endText = if (tar.crossesMidnight) TAR_DATE_TIME.format(end) else TAR_TIME.format(end)
        return "${TAR_DATE_TIME.format(start)} \u2013 $endText"
    }

    /** `H:MM:SS` with unbounded hours; negative durations count as zero. */
    fun countdown(duration: Duration): String {
        val seconds = duration.seconds.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
    }

    /** A readable name of a time zone: the city of an IANA id (`Europe/Amsterdam` → `Amsterdam`), else the id. */
    fun zoneName(zone: ZoneId): String = zone.id.substringAfterLast('/').replace('_', ' ')

    private val TAR_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    private val TAR_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}

@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
fun formatDuration(duration: Duration): String {
    val safe = if (duration.isNegative) Duration.ZERO else duration
    return stringResource(R.string.duration_hours_minutes, safe.toHours().toInt(), (safe.toMinutes() % 60).toInt())
}

/** A ticking "now" for durations shown on screen; a changed [refreshMillis] restarts the ticker. */
@Composable
fun rememberCurrentInstant(refreshMillis: Long = 30_000L): Instant {
    val now by produceState(Instant.now(), refreshMillis) {
        while (true) {
            delay(refreshMillis)
            value = Instant.now()
        }
    }
    return now
}
