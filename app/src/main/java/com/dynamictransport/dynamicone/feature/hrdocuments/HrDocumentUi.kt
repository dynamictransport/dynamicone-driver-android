package com.dynamictransport.dynamicone.feature.hrdocuments

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.domain.model.HrContentProblem
import com.dynamictransport.dynamicone.domain.model.HrDocument
import com.dynamictransport.dynamicone.domain.model.HrDocumentDisplayState
import com.dynamictransport.dynamicone.domain.model.HrDocumentLanguage
import com.dynamictransport.dynamicone.domain.model.LocalSignature
import com.dynamictransport.dynamicone.domain.model.SignatureFailure
import com.dynamictransport.dynamicone.domain.rules.DeadlineKind
import com.dynamictransport.dynamicone.domain.rules.HrDocumentRules
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.currentLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Deadlines and signature times with the year: a document may stay unsigned across a new year. */
internal fun dateTime(instant: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).withZone(zone).format(instant)

/** The language's own name ("polski", "Türkçe"), so everyone finds theirs whatever the app language is. */
@Composable
@ReadOnlyComposable
internal fun languageName(code: String): String {
    if (code == HrDocumentLanguage.MULTILINGUAL) return stringResource(R.string.hr_language_multi)
    val locale = Locale.forLanguageTag(code)
    return locale.getDisplayLanguage(locale).takeIf { it.isNotBlank() && it != code }?.replaceFirstChar { it.titlecase(locale) }
        ?: code.uppercase(Locale.ROOT)
}

/** The version shown when nothing else was chosen: the app language, then the reference language. */
@Composable
@ReadOnlyComposable
internal fun preferredLanguage(document: HrDocument): HrDocumentLanguage? =
    HrDocumentRules.pickLanguage(document, listOf(currentLocale().language))

@Composable
@ReadOnlyComposable
internal fun deadlineText(dueAt: Instant?, now: Instant): String {
    val locale = currentLocale()
    val zone = ZoneId.systemDefault()
    return when (HrDocumentRules.deadline(dueAt, now, zone)) {
        DeadlineKind.NONE -> stringResource(R.string.hr_no_deadline)
        DeadlineKind.LATER -> stringResource(R.string.hr_due_later, dateTime(dueAt!!, locale, zone))
        DeadlineKind.TOMORROW -> stringResource(R.string.hr_due_tomorrow, DriverFormats.time(dueAt!!, zone))
        DeadlineKind.TODAY -> stringResource(R.string.hr_due_today, DriverFormats.time(dueAt!!, zone))
        DeadlineKind.OVERDUE -> stringResource(R.string.hr_overdue, dateTime(dueAt!!, locale, zone))
    }
}

/** The document's state as icon, text and colour: never colour alone (CLAUDE.md §7). */
@Composable
internal fun HrDocumentStatusLine(document: HrDocument, local: LocalSignature?, now: Instant, modifier: Modifier = Modifier) {
    val state = HrDocumentRules.displayState(document, local, now, ZoneId.systemDefault())
    val locale = currentLocale()
    when (state) {
        HrDocumentDisplayState.TO_SIGN ->
            StatusBanner(deadlineText(document.dueAt, now), Icons.Filled.Event, StatusTone.Info, modifier.testTag("hr_state_to_sign"))
        HrDocumentDisplayState.DUE_SOON ->
            StatusBanner(deadlineText(document.dueAt, now), Icons.Filled.Schedule, StatusTone.Warning, modifier.testTag("hr_state_due_soon"))
        // Late is a warning, not a danger: the signature is still accepted (ASSUMPTIONS D5).
        HrDocumentDisplayState.OVERDUE ->
            StatusBanner(deadlineText(document.dueAt, now), Icons.Filled.EventBusy, StatusTone.Warning, modifier.testTag("hr_state_overdue"))
        HrDocumentDisplayState.WAITING_TO_SEND -> StatusBanner(
            message = stringResource(
                if (local?.failure == SignatureFailure.ACCESS_REQUIRED) R.string.hr_state_waiting_access else R.string.hr_state_waiting,
            ),
            icon = Icons.Filled.CloudUpload,
            tone = StatusTone.Info,
            modifier = modifier.testTag("hr_state_waiting"),
        )
        HrDocumentDisplayState.SEND_FAILED -> StatusBanner(
            message = stringResource(local?.failure?.messageRes() ?: R.string.hr_state_failed),
            icon = Icons.Filled.ErrorOutline,
            tone = StatusTone.Danger,
            modifier = modifier.testTag("hr_state_failed"),
        )
        HrDocumentDisplayState.SIGNED -> StatusBanner(
            message = document.signedAt?.let { stringResource(R.string.hr_signed_at, dateTime(it, locale)) } ?: stringResource(R.string.hr_signed_header),
            icon = Icons.Filled.CheckCircle,
            tone = StatusTone.Positive,
            modifier = modifier.testTag("hr_state_signed"),
        )
        HrDocumentDisplayState.WITHDRAWN ->
            StatusBanner(stringResource(R.string.hr_withdrawn), Icons.Filled.Block, StatusTone.Neutral, modifier.testTag("hr_state_withdrawn"))
    }
}

@StringRes
internal fun SignatureFailure.messageRes(): Int = when (this) {
    SignatureFailure.DOCUMENT_CHANGED -> R.string.hr_failure_changed
    SignatureFailure.SIGNATURE_INVALID -> R.string.hr_failure_signature
    SignatureFailure.LANGUAGE_UNAVAILABLE -> R.string.hr_failure_language
    SignatureFailure.NOT_FOUND -> R.string.hr_missing
    SignatureFailure.ACCESS_REQUIRED -> R.string.hr_state_waiting_access
    SignatureFailure.CONFLICT, SignatureFailure.REJECTED -> R.string.hr_failure_rejected
}

@StringRes
internal fun HrContentProblem.messageRes(): Int = when (this) {
    HrContentProblem.OFFLINE -> R.string.hr_content_offline
    HrContentProblem.WITHDRAWN -> R.string.hr_withdrawn
    HrContentProblem.NOT_FOUND -> R.string.hr_missing
    HrContentProblem.INTEGRITY -> R.string.hr_content_integrity
    HrContentProblem.LOW_STORAGE -> R.string.error_low_storage
    HrContentProblem.SESSION_EXPIRED -> R.string.refresh_session_expired
    HrContentProblem.SERVICE_UNAVAILABLE -> R.string.refresh_service_unavailable
    HrContentProblem.RATE_LIMITED -> R.string.refresh_rate_limited
    HrContentProblem.FAILED -> R.string.refresh_failed
}
