package com.dynamictransport.dynamicone.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.dynamictransport.dynamicone.R
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
}

@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
fun formatDuration(duration: Duration): String {
    val safe = if (duration.isNegative) Duration.ZERO else duration
    return stringResource(R.string.duration_hours_minutes, safe.toHours().toInt(), (safe.toMinutes() % 60).toInt())
}

/** A slowly ticking "now" for durations shown on screen. */
@Composable
fun rememberCurrentInstant(refreshMillis: Long = 30_000L): Instant {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(refreshMillis)
            value = Instant.now()
        }
    }
    return now
}
