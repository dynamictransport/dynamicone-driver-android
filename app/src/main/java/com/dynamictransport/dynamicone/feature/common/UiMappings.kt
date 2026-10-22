package com.dynamictransport.dynamicone.feature.common

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AssignmentLate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CarCrash
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.ui.graphics.vector.ImageVector
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ActionTone
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.BreakState
import com.dynamictransport.dynamicone.domain.model.DocumentSlot
import com.dynamictransport.dynamicone.domain.model.DocumentSlotState
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.FailedAction
import com.dynamictransport.dynamicone.domain.model.IncidentCategory
import com.dynamictransport.dynamicone.domain.model.PendingActionType
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.RejectReason
import com.dynamictransport.dynamicone.domain.model.SignedDocumentType
import com.dynamictransport.dynamicone.domain.model.StopActivity
import com.dynamictransport.dynamicone.domain.model.StopStatus
import com.dynamictransport.dynamicone.domain.model.SyncErrorCode
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.scan.ScanIssue
import com.dynamictransport.dynamicone.domain.statemachine.PrimaryAction

// Single mapping from domain concepts to label/icon/tone. Screens never hard-code these per state.

data class ActionUi(
    @param:StringRes val label: Int,
    val icon: ImageVector,
    val tone: ActionTone,
    /** Hard-to-undo steps are confirmed by holding the button instead of a modal. */
    val requiresHold: Boolean,
)

data class StatusUi(
    @param:StringRes val label: Int,
    val icon: ImageVector,
    val tone: StatusTone,
)

fun PrimaryAction.toActionUi(): ActionUi = when (this) {
    PrimaryAction.StartShift -> ActionUi(R.string.action_start_shift, Icons.Filled.PlayCircle, ActionTone.Positive, false)
    PrimaryAction.EndShift -> ActionUi(R.string.action_end_shift, Icons.Filled.StopCircle, ActionTone.Neutral, false)
    is PrimaryAction.ReviewNewTrip -> ActionUi(R.string.action_review_new_trip, Icons.Filled.Visibility, ActionTone.Positive, false)
    is PrimaryAction.ContinueTrip -> ActionUi(R.string.action_continue_trip, Icons.Filled.PlayArrow, ActionTone.Positive, false)
    is PrimaryAction.OnTrip -> action.toActionUi()
}

fun TripActionType.toActionUi(): ActionUi = when (this) {
    TripActionType.ACCEPT_TRIP -> ActionUi(R.string.action_accept_trip, Icons.Filled.CheckCircle, ActionTone.Positive, false)
    TripActionType.REJECT_TRIP -> ActionUi(R.string.action_reject_trip, Icons.Filled.Block, ActionTone.Danger, true)
    TripActionType.START_TRIP -> ActionUi(R.string.action_start_trip, Icons.Filled.PlayArrow, ActionTone.Positive, false)
    TripActionType.ARRIVE_PICKUP -> ActionUi(R.string.action_arrive_pickup, Icons.Filled.Place, ActionTone.Positive, false)
    TripActionType.COMPLETE_PICKUP -> ActionUi(R.string.action_complete_pickup, Icons.Filled.Inventory, ActionTone.Positive, false)
    TripActionType.START_DELIVERY_LEG -> ActionUi(R.string.action_start_delivery_leg, Icons.Filled.LocalShipping, ActionTone.Positive, false)
    TripActionType.ARRIVE_DELIVERY -> ActionUi(R.string.action_arrive_delivery, Icons.Filled.Flag, ActionTone.Positive, false)
    TripActionType.COMPLETE_DELIVERY -> ActionUi(R.string.action_complete_delivery, Icons.Filled.TaskAlt, ActionTone.Positive, true)
    TripActionType.FINISH_TRIP -> ActionUi(R.string.action_finish_trip, Icons.Filled.DoneAll, ActionTone.Positive, true)
    TripActionType.START_BREAK -> ActionUi(R.string.action_start_break, Icons.Filled.FreeBreakfast, ActionTone.Neutral, false)
    TripActionType.END_BREAK -> ActionUi(R.string.action_end_break, Icons.Filled.PlayArrow, ActionTone.Positive, false)
    TripActionType.UNKNOWN -> ActionUi(R.string.action_unknown, Icons.Filled.Info, ActionTone.Neutral, false)
}

/**
 * Localized label of a server action. `CompleteStop` labels depend on the job (pickup, loading, full/empty
 * container), so the backend's English label selects the translation. An unknown label is shown as the
 * backend sent it: the load state is never guessed on the device.
 */
@StringRes
fun ExecutionAction.knownLabelRes(): Int? = when (code) {
    ExecutionActionCode.START -> R.string.exec_start
    ExecutionActionCode.REST -> R.string.exec_rest
    ExecutionActionCode.PROBLEM -> R.string.exec_problem
    ExecutionActionCode.COMPLETE_STOP -> label?.let { COMPLETE_STOP_LABELS[it] }
}

private val COMPLETE_STOP_LABELS = mapOf(
    "I picked up the full container" to R.string.exec_pickup_full,
    "I picked up the empty container" to R.string.exec_pickup_empty,
    "I picked up the container" to R.string.exec_pickup,
    "Container loading completed" to R.string.exec_load_done,
    "Container unloading completed" to R.string.exec_unload_done,
    "I returned the empty container" to R.string.exec_empty_return,
    "I delivered the full container" to R.string.exec_deliver_full,
    "I delivered the empty container" to R.string.exec_deliver_empty,
    "I delivered the container" to R.string.exec_deliver,
    "Stop completed" to R.string.exec_stop_done,
)

fun ExecutionActionCode.icon(): ImageVector = when (this) {
    ExecutionActionCode.START -> Icons.Filled.PlayArrow
    ExecutionActionCode.COMPLETE_STOP -> Icons.Filled.TaskAlt
    ExecutionActionCode.REST -> Icons.Filled.FreeBreakfast
    ExecutionActionCode.PROBLEM -> Icons.Filled.ReportProblem
}

@StringRes
fun ExecutionActionCode?.receiptLabelRes(): Int = when (this) {
    ExecutionActionCode.START -> R.string.receipt_start
    ExecutionActionCode.COMPLETE_STOP -> R.string.receipt_complete_stop
    ExecutionActionCode.REST -> R.string.receipt_rest
    ExecutionActionCode.PROBLEM -> R.string.receipt_problem
    null -> R.string.receipt_unknown
}

@StringRes
fun PendingCommandState.messageRes(): Int = when (this) {
    PendingCommandState.SENDING -> R.string.pending_state_sending
    PendingCommandState.OUTCOME_UNKNOWN -> R.string.pending_state_unknown
    PendingCommandState.NOT_SENT -> R.string.pending_state_not_sent
    PendingCommandState.RECORDED -> R.string.pending_state_recorded
}

fun TripState.toStatusUi(): StatusUi = when (this) {
    TripState.ASSIGNED -> StatusUi(R.string.trip_state_assigned, Icons.Filled.NewReleases, StatusTone.Warning)
    TripState.ACCEPTED -> StatusUi(R.string.trip_state_accepted, Icons.Filled.ThumbUp, StatusTone.Info)
    TripState.STARTED -> StatusUi(R.string.trip_state_started, Icons.Filled.LocalShipping, StatusTone.Positive)
    TripState.AT_PICKUP -> StatusUi(R.string.trip_state_at_pickup, Icons.Filled.Place, StatusTone.Positive)
    TripState.PICKUP_COMPLETED -> StatusUi(R.string.trip_state_pickup_completed, Icons.Filled.Inventory, StatusTone.Positive)
    TripState.EN_ROUTE_DELIVERY -> StatusUi(R.string.trip_state_en_route_delivery, Icons.Filled.LocalShipping, StatusTone.Positive)
    TripState.AT_DELIVERY -> StatusUi(R.string.trip_state_at_delivery, Icons.Filled.Flag, StatusTone.Positive)
    TripState.DELIVERY_COMPLETED -> StatusUi(R.string.trip_state_delivery_completed, Icons.Filled.TaskAlt, StatusTone.Positive)
    TripState.FINISHED -> StatusUi(R.string.trip_state_finished, Icons.Filled.CheckCircle, StatusTone.Neutral)
    TripState.CANCELLED -> StatusUi(R.string.trip_state_cancelled, Icons.Filled.Cancel, StatusTone.Danger)
    TripState.UNKNOWN -> StatusUi(R.string.trip_state_unknown, Icons.Filled.Info, StatusTone.Neutral)
}

fun Trip.toStatusUi(): StatusUi =
    if (breakState == BreakState.ON_BREAK && !state.isTerminal) {
        StatusUi(R.string.trip_state_on_break, Icons.Filled.FreeBreakfast, StatusTone.Warning)
    } else {
        state.toStatusUi()
    }

fun SyncState.toStatusUi(): StatusUi = when (this) {
    SyncState.SYNCED -> StatusUi(R.string.sync_state_synced, Icons.Filled.CloudDone, StatusTone.Positive)
    SyncState.PENDING -> StatusUi(R.string.sync_state_pending, Icons.Filled.CloudUpload, StatusTone.Info)
    SyncState.FAILED -> StatusUi(R.string.sync_state_failed, Icons.Filled.SyncProblem, StatusTone.Danger)
}

@StringRes
fun StopActivity.labelRes(): Int = when (this) {
    StopActivity.PICKUP -> R.string.stop_activity_pickup
    StopActivity.LOAD -> R.string.stop_activity_load
    StopActivity.UNLOAD -> R.string.stop_activity_unload
    StopActivity.DELIVER -> R.string.stop_activity_deliver
    StopActivity.EMPTY_RETURN -> R.string.stop_activity_empty_return
    StopActivity.FUEL -> R.string.stop_activity_fuel
    StopActivity.REST -> R.string.stop_activity_rest
    StopActivity.OTHER -> R.string.stop_activity_other
}

/** Null for statuses this app version does not know: nothing is shown instead of a guess. */
fun StopStatus.toStatusUi(): StatusUi? = when (this) {
    StopStatus.PLANNED -> StatusUi(R.string.stop_status_planned, Icons.Filled.Schedule, StatusTone.Neutral)
    StopStatus.EN_ROUTE -> StatusUi(R.string.stop_status_en_route, Icons.Filled.LocalShipping, StatusTone.Info)
    StopStatus.ARRIVED -> StatusUi(R.string.stop_status_arrived, Icons.Filled.Place, StatusTone.Info)
    StopStatus.COMPLETED -> StatusUi(R.string.stop_status_completed, Icons.Filled.CheckCircle, StatusTone.Positive)
    StopStatus.SKIPPED -> StatusUi(R.string.stop_status_skipped, Icons.Filled.Block, StatusTone.Neutral)
    StopStatus.UNKNOWN -> null
}

/** Null type: a category this app version does not know. */
@StringRes
fun DocumentType?.labelRes(): Int = when (this) {
    DocumentType.CMR -> R.string.document_type_cmr
    DocumentType.CUSTOMS -> R.string.document_type_customs
    DocumentType.INTERCHANGE -> R.string.document_type_interchange
    DocumentType.CONTAINER_PHOTO -> R.string.document_type_container_photo
    DocumentType.SEAL_PHOTO -> R.string.document_type_seal_photo
    DocumentType.OTHER -> R.string.document_type_other
    null -> R.string.document_type_unknown
}

fun DocumentType?.icon(): ImageVector = when (this) {
    DocumentType.CMR -> Icons.Filled.Description
    DocumentType.CUSTOMS -> Icons.Filled.Policy
    DocumentType.INTERCHANGE -> Icons.Filled.SwapHoriz
    DocumentType.CONTAINER_PHOTO -> Icons.Filled.Inventory2
    DocumentType.SEAL_PHOTO -> Icons.Filled.Lock
    DocumentType.OTHER, null -> Icons.Filled.AttachFile
}

fun DocumentSlotState.toStatusUi(): StatusUi = when (this) {
    DocumentSlotState.MISSING -> StatusUi(R.string.slot_state_missing, Icons.Filled.AddAPhoto, StatusTone.Warning)
    DocumentSlotState.NOT_REQUIRED -> StatusUi(R.string.slot_state_not_required, Icons.Filled.RemoveCircleOutline, StatusTone.Neutral)
    DocumentSlotState.PENDING_REVIEW -> StatusUi(R.string.slot_state_pending_review, Icons.Filled.HourglassTop, StatusTone.Info)
    DocumentSlotState.APPROVED -> StatusUi(R.string.slot_state_approved, Icons.Filled.Verified, StatusTone.Positive)
    DocumentSlotState.REJECTED -> StatusUi(R.string.slot_state_rejected, Icons.Filled.Error, StatusTone.Danger)
    DocumentSlotState.UNKNOWN -> StatusUi(R.string.slot_state_unknown, Icons.Filled.Info, StatusTone.Neutral)
}

/** The demo upload queue is shown before the review state. */
fun DocumentSlot.toStatusUi(): StatusUi = when (localSync) {
    SyncState.PENDING -> SyncState.PENDING.toStatusUi()
    SyncState.FAILED -> SyncState.FAILED.toStatusUi()
    SyncState.SYNCED, null -> state.toStatusUi()
}

@StringRes
fun SignedDocumentType.labelRes(): Int = when (this) {
    SignedDocumentType.COMPENSATION -> R.string.signed_type_compensation
    SignedDocumentType.LEAVE -> R.string.signed_type_leave
    SignedDocumentType.UNKNOWN -> R.string.signed_type_unknown
}

/** Localized payroll line types; null for a type this app version does not know (shown as sent). */
@StringRes
fun payrollTypeLabelRes(type: String): Int? = PAYROLL_TYPE_LABELS[type]

private val PAYROLL_TYPE_LABELS = mapOf(
    "Salary" to R.string.payroll_type_salary,
    "AdditionalPayment" to R.string.payroll_type_additional_payment,
    "Bonus" to R.string.payroll_type_bonus,
    "ExamBonus" to R.string.payroll_type_exam_bonus,
    "TrafficFine" to R.string.payroll_type_traffic_fine,
    "Overtime" to R.string.payroll_type_overtime,
    "DamageCost" to R.string.payroll_type_damage_cost,
    "LegalDocumentFee" to R.string.payroll_type_legal_document_fee,
    "PortCardFee" to R.string.payroll_type_port_card_fee,
    "VisaTravelExpense" to R.string.payroll_type_visa_travel_expense,
    "SalaryAdvance" to R.string.payroll_type_salary_advance,
    "LoyaltyBonus" to R.string.payroll_type_loyalty_bonus,
    "CashPayment" to R.string.payroll_type_cash_payment,
    "AdrMonthlyBonus" to R.string.payroll_type_adr_monthly_bonus,
    "CombiChassisMonthlyBonus" to R.string.payroll_type_combi_chassis_monthly_bonus,
    "PurchaseRequestDeduction" to R.string.payroll_type_purchase_request_deduction,
)

/** Scanner guidance; the same text explains why a finished photo was not accepted. */
fun ScanIssue.toStatusUi(): StatusUi = when (this) {
    ScanIssue.NO_DOCUMENT -> StatusUi(R.string.scan_issue_no_document, Icons.Filled.DocumentScanner, StatusTone.Warning)
    ScanIssue.CUT_OFF -> StatusUi(R.string.scan_issue_cut_off, Icons.Filled.CropFree, StatusTone.Warning)
    ScanIssue.TOO_FAR -> StatusUi(R.string.scan_issue_too_far, Icons.Filled.ZoomIn, StatusTone.Warning)
    ScanIssue.TILTED -> StatusUi(R.string.scan_issue_tilted, Icons.Filled.Straighten, StatusTone.Warning)
    ScanIssue.TOO_DARK -> StatusUi(R.string.scan_issue_too_dark, Icons.Filled.FlashlightOn, StatusTone.Warning)
    ScanIssue.GLARE -> StatusUi(R.string.scan_issue_glare, Icons.Filled.WbSunny, StatusTone.Warning)
    ScanIssue.BLURRY -> StatusUi(R.string.scan_issue_blurry, Icons.Filled.BlurOn, StatusTone.Warning)
    ScanIssue.LOW_RESOLUTION -> StatusUi(R.string.scan_issue_low_resolution, Icons.Filled.ZoomIn, StatusTone.Warning)
}

@StringRes
fun IncidentCategory.labelRes(): Int = when (this) {
    IncidentCategory.DELAY -> R.string.incident_category_delay
    IncidentCategory.TRAFFIC -> R.string.incident_category_traffic
    IncidentCategory.TERMINAL_QUEUE -> R.string.incident_category_terminal_queue
    IncidentCategory.VEHICLE_BREAKDOWN -> R.string.incident_category_vehicle_breakdown
    IncidentCategory.ACCIDENT -> R.string.incident_category_accident
    IncidentCategory.DOCUMENT -> R.string.incident_category_document
    IncidentCategory.CONTAINER_SEAL -> R.string.incident_category_container_seal
    IncidentCategory.CUSTOMER -> R.string.incident_category_customer
    IncidentCategory.OTHER -> R.string.incident_category_other
}

fun IncidentCategory.icon(): ImageVector = when (this) {
    IncidentCategory.DELAY -> Icons.Filled.HourglassTop
    IncidentCategory.TRAFFIC -> Icons.Filled.Traffic
    IncidentCategory.TERMINAL_QUEUE -> Icons.Filled.FormatListNumbered
    IncidentCategory.VEHICLE_BREAKDOWN -> Icons.Filled.Build
    IncidentCategory.ACCIDENT -> Icons.Filled.CarCrash
    IncidentCategory.DOCUMENT -> Icons.Filled.Description
    IncidentCategory.CONTAINER_SEAL -> Icons.Filled.Inventory2
    IncidentCategory.CUSTOMER -> Icons.Filled.Person
    IncidentCategory.OTHER -> Icons.Filled.MoreHoriz
}

@StringRes
fun RejectReason.labelRes(): Int = when (this) {
    RejectReason.VEHICLE_PROBLEM -> R.string.reject_reason_vehicle_problem
    RejectReason.LEGAL_DRIVING_TIME -> R.string.reject_reason_legal_driving_time
    RejectReason.HEALTH -> R.string.reject_reason_health
    RejectReason.WRONG_ASSIGNMENT -> R.string.reject_reason_wrong_assignment
    RejectReason.OTHER -> R.string.reject_reason_other
}

fun RejectReason.icon(): ImageVector = when (this) {
    RejectReason.VEHICLE_PROBLEM -> Icons.Filled.Build
    RejectReason.LEGAL_DRIVING_TIME -> Icons.Filled.Timer
    RejectReason.HEALTH -> Icons.Filled.LocalHospital
    RejectReason.WRONG_ASSIGNMENT -> Icons.Filled.AssignmentLate
    RejectReason.OTHER -> Icons.Filled.MoreHoriz
}

@StringRes
fun ActionRejection.messageRes(): Int = when (this) {
    ActionRejection.NOT_ALLOWED -> R.string.error_not_allowed
    ActionRejection.SHIFT_NOT_ACTIVE -> R.string.error_shift_not_active
    ActionRejection.TRIP_IN_PROGRESS -> R.string.error_trip_in_progress
    ActionRejection.REASON_REQUIRED -> R.string.error_reason_required
    ActionRejection.NOTE_REQUIRED -> R.string.error_note_required
    ActionRejection.NOTE_TOO_LONG -> R.string.error_note_too_long
    ActionRejection.NOT_FOUND -> R.string.error_not_found
    ActionRejection.NOT_LOGGED_IN -> R.string.error_not_logged_in
    ActionRejection.LOW_STORAGE -> R.string.error_low_storage
    ActionRejection.FILE_ERROR -> R.string.error_file
    ActionRejection.NOT_SUPPORTED -> R.string.feature_not_connected
    ActionRejection.TRIP_REQUIRED -> R.string.error_trip_required
    ActionRejection.FILE_TOO_LARGE -> R.string.error_file_too_large
    ActionRejection.UNSUPPORTED_FILE -> R.string.error_unsupported_file
    ActionRejection.CONFLICT -> R.string.error_conflict
    ActionRejection.PLAN_CHANGED -> R.string.error_plan_changed
    ActionRejection.OUTCOME_UNKNOWN -> R.string.error_outcome_unknown
    ActionRejection.PENDING_ACTION -> R.string.error_pending_action
    ActionRejection.DOCUMENTS_REQUIRED -> R.string.error_documents_required
    ActionRejection.PHOTO_REQUIRED -> R.string.error_photo_required
    ActionRejection.OFFLINE -> R.string.error_offline
    ActionRejection.SERVICE_UNAVAILABLE -> R.string.refresh_service_unavailable
    ActionRejection.RATE_LIMITED -> R.string.refresh_rate_limited
    ActionRejection.SESSION_EXPIRED -> R.string.refresh_session_expired
    ActionRejection.FORBIDDEN -> R.string.error_forbidden
    ActionRejection.FAILED -> R.string.error_failed
}

/** Message for a failed server read; null when the read succeeded. */
@StringRes
fun RefreshResult.messageRes(): Int? = when (this) {
    RefreshResult.SUCCESS -> null
    RefreshResult.OFFLINE -> R.string.refresh_offline
    RefreshResult.SESSION_EXPIRED -> R.string.refresh_session_expired
    RefreshResult.SERVICE_UNAVAILABLE -> R.string.refresh_service_unavailable
    RefreshResult.RATE_LIMITED -> R.string.refresh_rate_limited
    RefreshResult.NOT_SUPPORTED -> R.string.feature_not_connected
    RefreshResult.ACCESS_REQUIRED -> R.string.feature_access_required
    RefreshResult.FAILED -> R.string.refresh_failed
}

@StringRes
fun SyncErrorCode.messageRes(): Int = when (this) {
    SyncErrorCode.OFFLINE -> R.string.sync_error_offline
    SyncErrorCode.SERVER_UNAVAILABLE -> R.string.sync_error_server
    SyncErrorCode.CONFLICT -> R.string.sync_error_conflict
    SyncErrorCode.REJECTED_BY_SERVER -> R.string.sync_error_rejected
    SyncErrorCode.SESSION_EXPIRED -> R.string.sync_error_session
    SyncErrorCode.PRECEDING_ACTION_FAILED -> R.string.sync_error_preceding
    SyncErrorCode.NOT_SUPPORTED_YET -> R.string.sync_error_not_supported
    SyncErrorCode.UNKNOWN -> R.string.sync_error_unknown
}

@StringRes
fun FailedAction.labelRes(): Int = when (type) {
    PendingActionType.TRIP_ACTION -> tripAction?.toActionUi()?.label ?: R.string.pending_type_trip
    PendingActionType.SHIFT_START -> R.string.pending_type_shift_start
    PendingActionType.SHIFT_END -> R.string.pending_type_shift_end
    PendingActionType.INCIDENT_REPORT -> R.string.pending_type_incident
    PendingActionType.DOCUMENT_UPLOAD -> R.string.pending_type_document
}
