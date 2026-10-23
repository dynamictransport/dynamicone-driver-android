package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.core.common.toInstantOrNull
import com.dynamictransport.dynamicone.core.network.DriverActionReceiptDto
import com.dynamictransport.dynamicone.core.network.DriverAvailableActionDto
import com.dynamictransport.dynamicone.core.network.DriverContainerResponseDto
import com.dynamictransport.dynamicone.core.network.DriverDocumentResponseDto
import com.dynamictransport.dynamicone.core.network.DriverDocumentSlotDto
import com.dynamictransport.dynamicone.core.network.DriverDocumentTypeNames
import com.dynamictransport.dynamicone.core.network.DriverExecutionNames
import com.dynamictransport.dynamicone.core.network.DriverExecutionResponseDto
import com.dynamictransport.dynamicone.core.network.DriverLocationDto
import com.dynamictransport.dynamicone.core.network.DriverMessageDto
import com.dynamictransport.dynamicone.core.network.DriverMessageNames
import com.dynamictransport.dynamicone.core.network.DriverPayrollCurrencyDto
import com.dynamictransport.dynamicone.core.network.DriverPayrollLineDto
import com.dynamictransport.dynamicone.core.network.DriverPayrollResponseDto
import com.dynamictransport.dynamicone.core.network.DriverPayrollStateNames
import com.dynamictransport.dynamicone.core.network.DriverSignedDocumentDto
import com.dynamictransport.dynamicone.core.network.DriverSignedDocumentTypeNames
import com.dynamictransport.dynamicone.core.network.DriverSlotStateNames
import com.dynamictransport.dynamicone.core.network.DriverStatusNames
import com.dynamictransport.dynamicone.core.network.DriverStatusResponseDto
import com.dynamictransport.dynamicone.core.network.DriverStopResponseDto
import com.dynamictransport.dynamicone.core.network.DriverStopTarDto
import com.dynamictransport.dynamicone.core.network.DriverTrailerOptionDto
import com.dynamictransport.dynamicone.core.network.DriverTrailerStateDto
import com.dynamictransport.dynamicone.core.network.DriverTripSummaryResponseDto
import com.dynamictransport.dynamicone.core.network.RemoteException
import com.dynamictransport.dynamicone.domain.model.ActionReceipt
import com.dynamictransport.dynamicone.domain.model.BreakState
import com.dynamictransport.dynamicone.domain.model.DocumentSlot
import com.dynamictransport.dynamicone.domain.model.DocumentSlotState
import com.dynamictransport.dynamicone.domain.model.DocumentType
import com.dynamictransport.dynamicone.domain.model.DriverMessage
import com.dynamictransport.dynamicone.domain.model.ExecutionAction
import com.dynamictransport.dynamicone.domain.model.ExecutionActionCode
import com.dynamictransport.dynamicone.domain.model.ExecutionBlock
import com.dynamictransport.dynamicone.domain.model.FinalizedMonthlyPay
import com.dynamictransport.dynamicone.domain.model.GeoPoint
import com.dynamictransport.dynamicone.domain.model.MessageDirection
import com.dynamictransport.dynamicone.domain.model.MonthlyPayroll
import com.dynamictransport.dynamicone.domain.model.PayrollCurrency
import com.dynamictransport.dynamicone.domain.model.PayrollLine
import com.dynamictransport.dynamicone.domain.model.SignedDocument
import com.dynamictransport.dynamicone.domain.model.SignedDocumentType
import com.dynamictransport.dynamicone.domain.model.StopActivity
import com.dynamictransport.dynamicone.domain.model.StopStatus
import com.dynamictransport.dynamicone.domain.model.TerminalAppointment
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.model.TrailerOption
import com.dynamictransport.dynamicone.domain.model.Trip
import com.dynamictransport.dynamicone.domain.model.TripActionRecord
import com.dynamictransport.dynamicone.domain.model.TripActionType
import com.dynamictransport.dynamicone.domain.model.TripContainer
import com.dynamictransport.dynamicone.domain.model.TripDocument
import com.dynamictransport.dynamicone.domain.model.TripExecution
import com.dynamictransport.dynamicone.domain.model.TripLocation
import com.dynamictransport.dynamicone.domain.model.TripState
import com.dynamictransport.dynamicone.domain.model.TripStop
import com.dynamictransport.dynamicone.domain.model.TripTrailerState
import com.dynamictransport.dynamicone.domain.model.TripWorkflow
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

/**
 * Maps Driver API projections to the app's domain. Trip steps come from the server: the execution plan for
 * trip actions, the persisted status history for legacy trips. Nothing is guessed; missing data stays null.
 */
internal object DriverApiMapping {

    // Backend enum names (TripAssignmentStatus, TransportTripStatus).
    const val ASSIGNMENT_CONFIRMED = "Confirmed"
    private const val ASSIGNMENT_ACTIVE = "Active"
    private const val ASSIGNMENT_COMPLETED = "Completed"
    private const val ASSIGNMENT_CANCELLED = "Cancelled"
    private const val TRIP_ASSIGNED = "Assigned"
    private const val TRIP_IN_PROGRESS = "InProgress"
    private const val TRIP_COMPLETED = "Completed"
    private const val TRIP_CANCELLED = "Cancelled"
    private val PICKUP_ACTIVITIES = setOf(StopActivity.PICKUP, StopActivity.LOAD)
    private val DELIVERY_ACTIVITIES = setOf(StopActivity.DELIVER, StopActivity.UNLOAD)

    data class DerivedState(val state: TripState, val allowedActions: Set<TripActionType>)

    /**
     * Trip actions (and trips whose plan was not read yet): the status only says whether the trip is new,
     * accepted, running or closed. The step itself comes from `execution`, so no local action is allowed.
     */
    fun baseState(assignmentStatus: String?, tripStatus: String?): DerivedState {
        closedState(assignmentStatus, tripStatus)?.let { return it }
        return when (assignmentStatus) {
            ASSIGNMENT_CONFIRMED -> DerivedState(TripState.ASSIGNED, setOf(TripActionType.ACCEPT_TRIP))
            ASSIGNMENT_ACTIVE -> when (tripStatus) {
                TRIP_ASSIGNED -> DerivedState(TripState.ACCEPTED, emptySet())
                TRIP_IN_PROGRESS -> DerivedState(TripState.STARTED, emptySet())
                else -> DerivedState(TripState.UNKNOWN, emptySet())
            }
            else -> DerivedState(TripState.UNKNOWN, emptySet())
        }
    }

    /** Legacy status workflow: the assignment status plus the persisted status history decide the step. */
    fun deriveState(assignmentStatus: String?, tripStatus: String?, reportedStatuses: Set<String>): DerivedState {
        closedState(assignmentStatus, tripStatus)?.let { return it }
        return when (assignmentStatus) {
            ASSIGNMENT_CONFIRMED -> DerivedState(TripState.ASSIGNED, setOf(TripActionType.ACCEPT_TRIP))
            ASSIGNMENT_ACTIVE -> {
                val lastDone = DriverStatusNames.PROGRESSION.indexOfLast { it in reportedStatuses }
                val state = when (lastDone) {
                    -1 -> TripState.ACCEPTED
                    0 -> TripState.STARTED
                    1 -> TripState.AT_PICKUP
                    2 -> TripState.EN_ROUTE_DELIVERY
                    3 -> TripState.AT_DELIVERY
                    else -> TripState.DELIVERY_COMPLETED
                }
                // Status reports need an assigned or running trip; the next step must follow the backend order.
                val canReport = tripStatus == TRIP_ASSIGNED || tripStatus == TRIP_IN_PROGRESS
                val allowed = if (!canReport) {
                    emptySet()
                } else {
                    buildSet {
                        DriverStatusNames.PROGRESSION.getOrNull(lastDone + 1)?.let(::actionForStatus)?.let(::add)
                        add(TripActionType.START_BREAK)
                    }
                }
                DerivedState(state, allowed)
            }
            else -> DerivedState(TripState.UNKNOWN, emptySet())
        }
    }

    private fun closedState(assignmentStatus: String?, tripStatus: String?): DerivedState? = when {
        tripStatus == TRIP_CANCELLED || assignmentStatus == ASSIGNMENT_CANCELLED -> DerivedState(TripState.CANCELLED, emptySet())
        tripStatus == TRIP_COMPLETED || assignmentStatus == ASSIGNMENT_COMPLETED -> DerivedState(TripState.FINISHED, emptySet())
        else -> null
    }

    fun actionForStatus(status: String): TripActionType? = when (status) {
        DriverStatusNames.DEPARTED -> TripActionType.START_TRIP
        DriverStatusNames.ARRIVED_AT_LOADING -> TripActionType.ARRIVE_PICKUP
        DriverStatusNames.LOADING_COMPLETED -> TripActionType.COMPLETE_PICKUP
        DriverStatusNames.ARRIVED_AT_DELIVERY -> TripActionType.ARRIVE_DELIVERY
        DriverStatusNames.DELIVERY_COMPLETED -> TripActionType.COMPLETE_DELIVERY
        DriverStatusNames.RESTING -> TripActionType.START_BREAK
        else -> null
    }

    /** Driver API status for a trip action; null when the backend has no such operation. */
    fun statusForAction(action: TripActionType): String? = when (action) {
        TripActionType.START_TRIP -> DriverStatusNames.DEPARTED
        TripActionType.ARRIVE_PICKUP -> DriverStatusNames.ARRIVED_AT_LOADING
        TripActionType.COMPLETE_PICKUP -> DriverStatusNames.LOADING_COMPLETED
        TripActionType.ARRIVE_DELIVERY -> DriverStatusNames.ARRIVED_AT_DELIVERY
        TripActionType.COMPLETE_DELIVERY -> DriverStatusNames.DELIVERY_COMPLETED
        TripActionType.START_BREAK -> DriverStatusNames.RESTING
        else -> null
    }

    fun workflow(wire: String?): TripWorkflow = when (wire) {
        DriverExecutionNames.WORKFLOW_TRIP_ACTIONS -> TripWorkflow.TRIP_ACTIONS
        DriverExecutionNames.WORKFLOW_LEGACY_STATUSES -> TripWorkflow.LEGACY_STATUSES
        // A workflow this app version does not know: no step is offered.
        else -> TripWorkflow.UNRESOLVED
    }

    /** The plan needs its opaque version; without it no action could ever be sent. */
    fun execution(tripId: String, dto: DriverExecutionResponseDto): TripExecution {
        val version = dto.version?.takeIf { it.isNotBlank() } ?: throw RemoteException.InvalidResponse()
        val workflow = workflow(dto.workflow)
        return TripExecution(
            tripId = tripId,
            version = version,
            status = dto.status?.takeIf { it.isNotBlank() },
            workflow = workflow,
            availableActions = if (workflow == TripWorkflow.TRIP_ACTIONS) dto.availableActions.orEmpty().mapNotNull(::action) else emptyList(),
            documents = dto.documents.orEmpty().mapNotNull(::slot),
            trailerConfirmationRequired = dto.trailerConfirmationRequired,
        )
    }

    /**
     * Unknown codes are dropped: an option whose meaning is unknown is never offered. Without `canExecute`
     * (backends before docs/18) the action is not blocked; the server still refuses it if evidence is missing.
     */
    fun action(dto: DriverAvailableActionDto): ExecutionAction? {
        val code = actionCode(dto.code) ?: return null
        val reason = blockingReason(dto.blockingReason)
        return ExecutionAction(
            code = code,
            label = dto.label?.trim()?.takeIf { it.isNotEmpty() },
            stopId = dto.stopId,
            containerId = dto.containerId,
            blockingDocumentSlots = dto.blockingDocumentSlots.orEmpty().filter { it.isNotBlank() }.distinct(),
            canExecute = dto.canExecute ?: true,
            blockedByTrailer = reason == ExecutionBlock.TRAILER_CONFIRMATION_REQUIRED,
            blockingReason = reason,
            // The seal is shown as recorded; the case is the server's.
            sealNumber = dto.sealNumber?.trim()?.takeIf { it.isNotEmpty() },
            containerNumber = dto.containerNumber?.trim()?.takeIf { it.isNotEmpty() },
            stopName = dto.stopName?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** A stated reason that this app does not know still blocks; an empty one is no reason. */
    fun blockingReason(wire: String?): ExecutionBlock? = when (wire?.trim()) {
        null, "" -> null
        DriverExecutionNames.BLOCKED_TRAILER_CONFIRMATION -> ExecutionBlock.TRAILER_CONFIRMATION_REQUIRED
        DriverExecutionNames.BLOCKED_LOADING_CONFIRMATION -> ExecutionBlock.LOADING_CONFIRMATION_REQUIRED
        DriverExecutionNames.BLOCKED_SEAL_NUMBER -> ExecutionBlock.SEAL_NUMBER_REQUIRED
        else -> ExecutionBlock.UNKNOWN
    }

    /**
     * Trailer options of a trip (docs/20). An option without a registration is dropped: the driver has to
     * read the registration of the trailer in front of them.
     */
    fun trailerState(tripId: String, dto: DriverTrailerStateDto): TripTrailerState {
        val version = dto.version?.takeIf { it.isNotBlank() } ?: throw RemoteException.InvalidResponse()
        return TripTrailerState(
            tripId = tripId,
            version = version,
            plannedTrailerId = dto.plannedTrailerId?.takeIf { it.isNotBlank() },
            confirmedTrailerId = dto.confirmedTrailerId?.takeIf { it.isNotBlank() },
            confirmedAt = dto.confirmedAtUtc?.toInstantOrNull(),
            canConfirm = dto.canConfirm,
            trailers = dto.trailers.orEmpty().mapNotNull(::trailerOption),
        )
    }

    fun trailerOption(dto: DriverTrailerOptionDto): TrailerOption? {
        val registration = dto.registrationNumber?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return TrailerOption(
            id = dto.id,
            registrationNumber = registration,
            companyCode = dto.companyCode?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    fun actionCode(wire: String?): ExecutionActionCode? = when (wire) {
        DriverExecutionNames.ACTION_START -> ExecutionActionCode.START
        DriverExecutionNames.ACTION_COMPLETE_STOP -> ExecutionActionCode.COMPLETE_STOP
        DriverExecutionNames.ACTION_REST -> ExecutionActionCode.REST
        DriverExecutionNames.ACTION_PROBLEM -> ExecutionActionCode.PROBLEM
        DriverExecutionNames.ACTION_CONFIRM_LOADING -> ExecutionActionCode.CONFIRM_LOADING
        DriverExecutionNames.ACTION_RECORD_SEAL_NUMBER -> ExecutionActionCode.RECORD_SEAL_NUMBER
        else -> null
    }

    fun actionName(code: ExecutionActionCode): String = when (code) {
        ExecutionActionCode.START -> DriverExecutionNames.ACTION_START
        ExecutionActionCode.COMPLETE_STOP -> DriverExecutionNames.ACTION_COMPLETE_STOP
        ExecutionActionCode.REST -> DriverExecutionNames.ACTION_REST
        ExecutionActionCode.PROBLEM -> DriverExecutionNames.ACTION_PROBLEM
        ExecutionActionCode.CONFIRM_LOADING -> DriverExecutionNames.ACTION_CONFIRM_LOADING
        ExecutionActionCode.RECORD_SEAL_NUMBER -> DriverExecutionNames.ACTION_RECORD_SEAL_NUMBER
    }

    fun receipt(dto: DriverActionReceiptDto) = ActionReceipt(
        id = dto.id,
        action = actionCode(dto.action),
        stopId = dto.stopId,
        containerId = dto.containerId,
        note = dto.note?.takeIf { it.isNotBlank() },
        receivedAt = dto.receivedAtUtc?.toInstantOrNull(),
        observedAt = dto.observedAtUtc?.toInstantOrNull(),
        latitude = dto.latitude,
        longitude = dto.longitude,
        accuracyMeters = dto.accuracyMeters,
    )

    /** `Missing`, partial or out-of-range coordinates are missing: never replaced by 0,0. */
    fun location(dto: DriverLocationDto?): TripLocation? {
        if (dto == null) return null
        val coordinates = if (dto.coordinateStatus == DriverExecutionNames.COORDINATES_MISSING) {
            null
        } else {
            geoPoint(dto.latitude, dto.longitude)
        }
        return TripLocation(
            stopId = dto.stopId?.takeIf { it.isNotBlank() },
            name = dto.name?.takeIf { it.isNotBlank() },
            coordinates = coordinates,
        )
    }

    fun geoPoint(latitude: Double?, longitude: Double?): GeoPoint? =
        if (latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            GeoPoint(latitude, longitude)
        } else {
            null
        }

    fun stop(dto: DriverStopResponseDto): TripStop {
        val point = geoPoint(dto.latitude, dto.longitude)
        return TripStop(
            id = dto.id,
            sequence = dto.sequence,
            activity = stopActivity(dto.activity),
            status = stopStatus(dto.status),
            locationName = dto.location?.takeIf { it.isNotBlank() },
            address = dto.address?.takeIf { it.isNotBlank() },
            latitude = point?.latitude,
            longitude = point?.longitude,
            plannedArrivalAt = dto.plannedArrivalUtc?.toInstantOrNull(),
            plannedDepartureAt = dto.plannedDepartureUtc?.toInstantOrNull(),
            actualArrivalAt = dto.actualArrivalUtc?.toInstantOrNull(),
            actualDepartureAt = dto.actualDepartureUtc?.toInstantOrNull(),
            reference = dto.reference?.takeIf { it.isNotBlank() },
            tar = dto.tar?.let(::terminalAppointment),
        )
    }

    /**
     * A TAR is shown only when the record is complete: code, a parsable window and a known time zone. Anything
     * else is treated as "no TAR" rather than shown with invented parts. The code is kept exactly as sent.
     */
    fun terminalAppointment(dto: DriverStopTarDto): TerminalAppointment? {
        val code = dto.code?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val start = dto.startUtc?.toInstantOrNull() ?: return null
        val end = dto.endUtc?.toInstantOrNull() ?: return null
        val zone = dto.timeZone?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
        return TerminalAppointment(code = code, startAt = start, endAt = end, timeZone = zone)
    }

    fun stopActivity(wire: String?): StopActivity = when (wire) {
        "Pickup" -> StopActivity.PICKUP
        "Load" -> StopActivity.LOAD
        "Unload" -> StopActivity.UNLOAD
        "Deliver" -> StopActivity.DELIVER
        "EmptyReturn" -> StopActivity.EMPTY_RETURN
        "Fuel" -> StopActivity.FUEL
        "Rest" -> StopActivity.REST
        else -> StopActivity.OTHER
    }

    fun stopStatus(wire: String?): StopStatus = when (wire) {
        "Planned" -> StopStatus.PLANNED
        "EnRoute" -> StopStatus.EN_ROUTE
        "Arrived" -> StopStatus.ARRIVED
        "Completed" -> StopStatus.COMPLETED
        "Skipped" -> StopStatus.SKIPPED
        else -> StopStatus.UNKNOWN
    }

    fun container(dto: DriverContainerResponseDto) = TripContainer(
        id = dto.id?.takeIf { it.isNotBlank() },
        number = dto.number?.takeIf { it.isNotBlank() },
        sealNumber = dto.sealNumber?.takeIf { it.isNotBlank() },
    )

    fun trip(
        summary: DriverTripSummaryResponseDto,
        derived: DerivedState,
        workflow: TripWorkflow,
        instructions: String? = null,
        stops: List<TripStop> = emptyList(),
        containers: List<DriverContainerResponseDto> = emptyList(),
    ): Trip {
        val ordered = stops.sortedBy { it.sequence }
        val pickup = ordered.firstOrNull { it.activity in PICKUP_ACTIVITIES }
        val delivery = ordered.lastOrNull { it.activity in DELIVERY_ACTIVITIES }
        val units = containers.map(::container)
        return Trip(
            id = summary.id,
            tripNo = summary.tripNumber?.takeIf { it.isNotBlank() } ?: summary.orderNumber.orEmpty(),
            state = derived.state,
            breakState = BreakState.NONE,
            pickupName = pickup?.locationName,
            deliveryName = delivery?.locationName,
            plannedStartAt = ordered.firstNotNullOfOrNull { it.plannedArrivalAt },
            containerNo = units.firstNotNullOfOrNull { it.number },
            pickupAddress = pickup?.address,
            deliveryAddress = delivery?.address,
            vehiclePlate = summary.vehicleRegistration?.takeIf { it.isNotBlank() },
            vehicleCompanyCode = summary.vehicleCompanyCode?.takeIf { it.isNotBlank() },
            trailerPlate = null,
            sealNo = units.firstNotNullOfOrNull { it.sealNumber },
            instructions = instructions?.takeIf { it.isNotBlank() },
            serverAllowedActions = derived.allowedActions,
            syncState = SyncState.SYNCED,
            orderNumber = summary.orderNumber?.takeIf { it.isNotBlank() },
            stops = ordered,
            workflow = workflow,
            origin = location(summary.origin),
            destination = location(summary.destination),
            nextStop = location(summary.nextStop),
            containers = units,
        )
    }

    /** Keeps detail-only fields of an already loaded trip when a list page refreshes it. */
    fun Trip.withDetailsFrom(previous: Trip?): Trip {
        if (previous == null || previous.stops.isEmpty()) return this
        return copy(
            pickupName = pickupName ?: previous.pickupName,
            deliveryName = deliveryName ?: previous.deliveryName,
            plannedStartAt = plannedStartAt ?: previous.plannedStartAt,
            containerNo = containerNo ?: previous.containerNo,
            pickupAddress = pickupAddress ?: previous.pickupAddress,
            deliveryAddress = deliveryAddress ?: previous.deliveryAddress,
            sealNo = sealNo ?: previous.sealNo,
            instructions = instructions ?: previous.instructions,
            stops = stops.ifEmpty { previous.stops },
            containers = containers.ifEmpty { previous.containers },
        )
    }

    /** Timeline entries use the server's `reportedAtUtc`, never the device clock. */
    fun history(statuses: List<DriverStatusResponseDto>): List<TripActionRecord> =
        statuses.mapNotNull { dto ->
            val action = dto.status?.let(::actionForStatus) ?: return@mapNotNull null
            val reportedAt = dto.reportedAtUtc?.toInstantOrNull() ?: return@mapNotNull null
            TripActionRecord(action, reportedAt, SyncState.SYNCED)
        }.sortedBy { it.occurredAt }

    /** Slots without a key cannot be addressed and are skipped. */
    fun slot(dto: DriverDocumentSlotDto): DocumentSlot? {
        val key = dto.slot?.takeIf { it.isNotBlank() } ?: return null
        return DocumentSlot(
            key = key,
            type = documentType(dto.type),
            label = dto.label?.takeIf { it.isNotBlank() },
            stopId = dto.stopId,
            containerId = dto.containerId,
            required = dto.required,
            state = slotState(dto.state),
            documentId = dto.documentId?.takeIf { it.isNotBlank() },
            fileName = dto.fileName?.takeIf { it.isNotBlank() },
            reviewNote = dto.reviewNote?.takeIf { it.isNotBlank() },
            revision = dto.revision?.takeIf { it.isNotBlank() },
        )
    }

    fun slotState(wire: String?): DocumentSlotState = when (wire) {
        DriverSlotStateNames.MISSING -> DocumentSlotState.MISSING
        DriverSlotStateNames.NOT_REQUIRED -> DocumentSlotState.NOT_REQUIRED
        DriverSlotStateNames.PENDING_REVIEW -> DocumentSlotState.PENDING_REVIEW
        DriverSlotStateNames.APPROVED -> DocumentSlotState.APPROVED
        DriverSlotStateNames.REJECTED -> DocumentSlotState.REJECTED
        else -> DocumentSlotState.UNKNOWN
    }

    /** Null for a category this app version does not know. */
    fun documentType(wire: String?): DocumentType? = when (wire) {
        DriverDocumentTypeNames.CMR -> DocumentType.CMR
        DriverDocumentTypeNames.CUSTOMS -> DocumentType.CUSTOMS
        DriverDocumentTypeNames.INTERCHANGE -> DocumentType.INTERCHANGE
        DriverDocumentTypeNames.CONTAINER -> DocumentType.CONTAINER_PHOTO
        DriverDocumentTypeNames.SEAL -> DocumentType.SEAL_PHOTO
        DriverDocumentTypeNames.OTHER -> DocumentType.OTHER
        else -> null
    }

    fun document(tripId: String, dto: DriverDocumentResponseDto) = TripDocument(
        id = dto.id,
        tripId = tripId,
        type = documentType(dto.type),
        fileName = dto.fileName?.takeIf { it.isNotBlank() },
        contentType = dto.contentType?.takeIf { it.isNotBlank() },
        submittedAt = dto.submittedAtUtc?.toInstantOrNull(),
    )

    /** An inconsistent answer is an invalid response, never an empty or zero payroll. */
    fun payroll(month: YearMonth, dto: DriverPayrollResponseDto): MonthlyPayroll = when (dto.state) {
        DriverPayrollStateNames.UNPUBLISHED -> MonthlyPayroll.Unpublished(month)
        DriverPayrollStateNames.PUBLISHED -> {
            val snapshot = dto.payroll ?: throw RemoteException.InvalidResponse()
            val snapshotMonth = if (snapshot.year != null && snapshot.month != null) {
                runCatching { YearMonth.of(snapshot.year, snapshot.month) }.getOrNull() ?: throw RemoteException.InvalidResponse()
            } else {
                null
            }
            if (snapshotMonth != null && snapshotMonth != month) throw RemoteException.InvalidResponse()
            val finalized = FinalizedMonthlyPay(
                baseGross = snapshot.finalizedBaseGross,
                overtimeGross = snapshot.finalizedOvertimeGross,
                overtimeHours = snapshot.finalizedOvertimeHours,
                currency = snapshot.finalizedCurrency?.takeIf { it.isNotBlank() },
            ).takeIf { it.baseGross != null || it.overtimeGross != null || it.overtimeHours != null }
            MonthlyPayroll.Published(
                month = month,
                publishedAt = dto.publishedAtUtc?.toInstantOrNull(),
                currencies = snapshot.currencies.orEmpty().map(::payrollCurrency),
                finalized = finalized,
            )
        }
        else -> throw RemoteException.InvalidResponse()
    }

    private fun payrollCurrency(dto: DriverPayrollCurrencyDto) = PayrollCurrency(
        currencyCode = dto.currencyCode?.trim().orEmpty(),
        baseGross = dto.baseGross,
        additions = dto.additions.orEmpty().map(::payrollLine),
        deductions = dto.deductions.orEmpty().map(::payrollLine),
        grossEarnings = dto.grossEarnings,
        totalDeductions = dto.totalDeductions,
        balanceBeforeStatutoryDeductions = dto.balanceBeforeStatutoryDeductions,
    )

    private fun payrollLine(dto: DriverPayrollLineDto) = PayrollLine(
        type = dto.type?.trim().orEmpty(),
        amount = dto.amount,
        effectiveFrom = dto.effectiveFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    )

    fun signedDocument(dto: DriverSignedDocumentDto) = SignedDocument(
        id = dto.id,
        type = when (dto.type) {
            DriverSignedDocumentTypeNames.COMPENSATION -> SignedDocumentType.COMPENSATION
            DriverSignedDocumentTypeNames.LEAVE -> SignedDocumentType.LEAVE
            else -> SignedDocumentType.UNKNOWN
        },
        signedAt = dto.signedAtUtc?.toInstantOrNull(),
    )

    /** Null for a direction this app version does not know: the message could not be shown on the right side. */
    fun message(dto: DriverMessageDto): DriverMessage? {
        val direction = when (dto.direction) {
            DriverMessageNames.FROM_DRIVER -> MessageDirection.FROM_DRIVER
            DriverMessageNames.TO_DRIVER -> MessageDirection.TO_DRIVER
            else -> return null
        }
        return DriverMessage(
            id = dto.id,
            tripId = dto.tripId?.takeIf { it.isNotBlank() },
            body = dto.body.orEmpty(),
            direction = direction,
            createdAt = dto.createdAtUtc?.toInstantOrNull(),
            readAt = dto.readAtUtc?.toInstantOrNull(),
            senderName = dto.senderDisplayName?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
