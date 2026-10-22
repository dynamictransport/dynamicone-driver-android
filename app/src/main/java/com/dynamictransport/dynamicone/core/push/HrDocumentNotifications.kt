package com.dynamictransport.dynamicone.core.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.network.HrDocumentWireNames
import com.dynamictransport.dynamicone.domain.model.HrDocumentLookup
import com.dynamictransport.dynamicone.domain.model.HrDocumentStatus
import com.dynamictransport.dynamicone.domain.repository.HrDocumentRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** A personnel document to sign was sent, is due soon, is overdue or was withdrawn (docs/17 §6). */
const val TYPE_HR_DOCUMENT = "hr.document"
const val EXTRA_DOCUMENT_ID = "documentId"
const val EXTRA_REASON = "reason"

/** The tag the backend's system notification uses too, so the app replaces and removes it. */
fun hrDocumentTag(documentId: String): String = "hr-document-$documentId"

enum class HrDocumentNotice {
    NEW,
    REMINDER,
    OVERDUE,
}

interface HrDocumentNotifier {
    fun ensureChannel()

    /** One notification per document; a reminder replaces the earlier one. */
    fun show(documentId: String, notice: HrDocumentNotice)

    fun cancel(documentId: String)
}

@Singleton
class AndroidHrDocumentNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : HrDocumentNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.notification_channel_hr_documents))
                .build(),
        )
    }

    @SuppressLint("MissingPermission") // canNotify() checks the permission right before posting.
    override fun show(documentId: String, notice: HrDocumentNotice) {
        if (!canNotify()) return
        val (title, body) = when (notice) {
            HrDocumentNotice.NEW -> R.string.push_hr_document_title to R.string.push_hr_document_body
            HrDocumentNotice.REMINDER -> R.string.push_hr_document_reminder_title to R.string.push_hr_document_reminder_body
            HrDocumentNotice.OVERDUE -> R.string.push_hr_document_overdue_title to R.string.push_hr_document_overdue_body
        }
        // The same texts as the backend's system notification: no document title or personal data.
        val posted = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openDocument(documentId))
            .build()
        manager.notify(hrDocumentTag(documentId), AndroidMessageNotifier.NOTIFICATION_ID, posted)
    }

    override fun cancel(documentId: String) = manager.cancel(hrDocumentTag(documentId), AndroidMessageNotifier.NOTIFICATION_ID)

    private fun canNotify(): Boolean = manager.areNotificationsEnabled() &&
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    private fun openDocument(documentId: String): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        val intent = launch
            .putExtra(EXTRA_TYPE, TYPE_HR_DOCUMENT)
            .putExtra(EXTRA_DOCUMENT_ID, documentId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            hrDocumentTag(documentId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Also the `channel_id` of the backend's push (docs/17 §6). */
        const val CHANNEL_ID = "hr_documents"
    }
}

/**
 * A push only says that something changed (docs/06): the document is read from the API, and it is announced only
 * while it still waits for the signature. A withdrawn or already signed document loses its notification.
 */
@Singleton
class HrDocumentSignalHandler @Inject constructor(
    private val repository: HrDocumentRepository,
    private val notifier: HrDocumentNotifier,
) {
    suspend fun onSignal(documentId: String?, reason: String?) {
        if (documentId.isNullOrBlank()) {
            repository.refresh()
            return
        }
        repository.refreshDocument(documentId)
        // The list behind the badge and the home card follows too.
        repository.refresh()
        val waiting = when (val lookup = repository.observeDocument(documentId).first()) {
            is HrDocumentLookup.Found -> lookup.document.status == HrDocumentStatus.PENDING && lookup.local == null
            HrDocumentLookup.Missing -> false
            // Could not be read (the connection dropped again): the push itself says there is something to sign.
            HrDocumentLookup.Loading -> true
        }
        if (!waiting || reason == HrDocumentWireNames.REASON_WITHDRAWN) {
            notifier.cancel(documentId)
            return
        }
        notifier.show(
            documentId,
            when (reason) {
                HrDocumentWireNames.REASON_REMINDER -> HrDocumentNotice.REMINDER
                HrDocumentWireNames.REASON_OVERDUE -> HrDocumentNotice.OVERDUE
                else -> HrDocumentNotice.NEW
            },
        )
    }
}
