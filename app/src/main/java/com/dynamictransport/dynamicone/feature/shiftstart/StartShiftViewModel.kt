package com.dynamictransport.dynamicone.feature.shiftstart

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.common.IdGenerator
import com.dynamictransport.dynamicone.core.network.VehicleCheckItemDto
import com.dynamictransport.dynamicone.core.network.VehicleCheckTemplateDto
import com.dynamictransport.dynamicone.core.nfc.NfcAvailability
import com.dynamictransport.dynamicone.core.nfc.NfcStatusProvider
import com.dynamictransport.dynamicone.data.real.DriverShiftMapping
import com.dynamictransport.dynamicone.domain.model.CheckAnswer
import com.dynamictransport.dynamicone.domain.model.CheckItem
import com.dynamictransport.dynamicone.domain.model.CheckPhoto
import com.dynamictransport.dynamicone.domain.model.CheckResult
import com.dynamictransport.dynamicone.domain.model.CheckTemplate
import com.dynamictransport.dynamicone.domain.model.ShiftCommandFailure
import com.dynamictransport.dynamicone.domain.model.ShiftCommandResult
import com.dynamictransport.dynamicone.domain.model.ShiftStartDraft
import com.dynamictransport.dynamicone.domain.model.VehicleInfo
import com.dynamictransport.dynamicone.domain.model.VehicleLookup
import com.dynamictransport.dynamicone.domain.repository.CheckPhotoStore
import com.dynamictransport.dynamicone.domain.repository.DailyShiftRepository
import com.dynamictransport.dynamicone.domain.repository.DriverTimesRepository
import com.dynamictransport.dynamicone.domain.repository.NetworkMonitor
import com.dynamictransport.dynamicone.domain.repository.VehicleCheckRepository
import com.dynamictransport.dynamicone.domain.rules.AttendanceRules
import com.dynamictransport.dynamicone.domain.rules.DailyShiftRules
import com.dynamictransport.dynamicone.domain.rules.VehicleCheckRules
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

enum class StartShiftStep {
    LOADING,

    /** The tachograph rest is not over: the driver confirms before going on (docs/16 §1). */
    REST_WARNING,

    /** Waiting for the vehicle's tag. */
    SCAN,

    /** The vehicle behind the tag, before the check. */
    VEHICLE,
    CHECK,
    SUMMARY,

    /** Recorded by the server, or stored on the phone to be sent. */
    DONE,
}

enum class ScanProblem {
    UNREADABLE,
    UNKNOWN_TAG,
}

@Serializable
data class DraftPhoto(val photoId: String, val path: String)

@Serializable
data class DraftAnswer(val key: String, val result: CheckResult, val note: String? = null, val photos: List<DraftPhoto> = emptyList())

@Serializable
data class DraftVehicle(
    val vehicleId: String? = null,
    val registrationNumber: String? = null,
    val displayName: String? = null,
    val trailerRegistrationNumber: String? = null,
)

/**
 * The flow's progress, kept in the saved state: the camera app may push this app out of memory, and the driver must
 * not lose the check or get a second command id for the same start.
 */
@Serializable
data class StartShiftDraftState(
    val commandId: String,
    val step: StartShiftStep = StartShiftStep.LOADING,
    val restWarningAcknowledged: Boolean = false,
    val tagUid: String? = null,
    val tagScannedAtMillis: Long? = null,
    val manual: Boolean = false,
    val vehicle: DraftVehicle? = null,
    /** The server named the vehicle behind the tag. */
    val vehicleVerified: Boolean = false,
    /** The server said whether it is the planned vehicle; null when not asked. */
    val vehiclePlanned: Boolean? = null,
    /** The list as it was when the check began. */
    val template: VehicleCheckTemplateDto? = null,
    val itemIndex: Int = 0,
    val answers: List<DraftAnswer> = emptyList(),
    /** The camera app writes this file right now. */
    val pendingPhoto: DraftPhoto? = null,
    val scanProblem: ScanProblem? = null,
    /** DONE: false when it is stored on the phone and waits to be sent. */
    val recorded: Boolean = false,
    val failure: ShiftCommandFailure? = null,
    val holdReason: ShiftCommandFailure? = null,
)

data class StartShiftUiState(
    val step: StartShiftStep = StartShiftStep.LOADING,
    val restEndsAt: Instant? = null,
    val nfc: NfcAvailability = NfcAvailability.AVAILABLE,
    val lookingUp: Boolean = false,
    val scanProblem: ScanProblem? = null,
    val plannedVehicle: VehicleInfo? = null,
    val vehicle: VehicleInfo? = null,
    val vehicleVerified: Boolean = false,
    val vehiclePlanned: Boolean? = null,
    val manual: Boolean = false,
    val template: CheckTemplate? = null,
    val itemIndex: Int = 0,
    val answers: Map<String, CheckAnswer> = emptyMap(),
    val submitting: Boolean = false,
    val recorded: Boolean = false,
    val failure: ShiftCommandFailure? = null,
    val holdReason: ShiftCommandFailure? = null,
    val restWarningAcknowledged: Boolean = false,
    /** A file the camera should write now. */
    val cameraRequest: String? = null,
    val isOnline: Boolean = true,
    @param:StringRes val message: Int? = null,
) {
    val currentItem: CheckItem? get() = template?.items?.getOrNull(itemIndex)

    val currentAnswer: CheckAnswer? get() = currentItem?.let { answers[it.key] }

    val canContinue: Boolean get() = currentItem?.let { VehicleCheckRules.isItemComplete(it, currentAnswer) } == true

    val canAddPhoto: Boolean get() = (currentAnswer?.photos?.size ?: 0) < VehicleCheckRules.MAX_PHOTOS_PER_ITEM

    val defects: List<Pair<CheckItem, CheckAnswer>>
        get() = template?.items.orEmpty().mapNotNull { item -> answers[item.key]?.takeIf { it.result == CheckResult.DEFECT }?.let { item to it } }

    /** A mismatch the driver should see: the server knows the vehicle, and it is not the planned one. */
    val otherThanPlanned: Boolean get() = vehiclePlanned == false && plannedVehicle != null

    /** The tag cannot be read at all: the planned vehicle can be chosen instead (reviewed by the office). */
    val canUsePlannedVehicle: Boolean get() = plannedVehicle?.vehicleId != null
}

/**
 * Daily shift start at the vehicle (docs/16 §1, §7): rest warning when the tachograph rest is not over, the vehicle's
 * NFC tag, the daily check one item at a time, then one confirmation that stores and sends everything together.
 */
@HiltViewModel
class StartShiftViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val dailyShift: DailyShiftRepository,
    private val vehicleCheck: VehicleCheckRepository,
    private val driverTimes: DriverTimesRepository,
    private val photos: CheckPhotoStore,
    private val nfcStatus: NfcStatusProvider,
    networkMonitor: NetworkMonitor,
    private val ids: IdGenerator,
    private val clock: Clock,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val draft = MutableStateFlow(restoreDraft() ?: StartShiftDraftState(commandId = ids.newId()))
    private val nfc = MutableStateFlow(nfcStatus.availability())
    private val lookingUp = MutableStateFlow(false)
    private val cameraRequest = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<Int?>(null)
    private val restEndsAt = MutableStateFlow<Instant?>(null)
    private val singleFlight = SingleFlight()

    private val controls = combine(nfc, lookingUp, cameraRequest, message, restEndsAt) { nfcState, lookup, camera, text, restEnd ->
        Controls(nfcState, lookup, camera, text, restEnd)
    }

    private data class Controls(
        val nfc: NfcAvailability,
        val lookingUp: Boolean,
        val cameraRequest: String?,
        val message: Int?,
        val restEndsAt: Instant?,
    )

    val uiState: StateFlow<StartShiftUiState> = combine(
        draft,
        dailyShift.observePlannedVehicle(),
        networkMonitor.isOnline,
        singleFlight.busy,
        controls,
    ) { state, planned, online, busy, control ->
        val template = state.template?.let(DriverShiftMapping::template)
        StartShiftUiState(
            step = state.step,
            restEndsAt = control.restEndsAt,
            nfc = control.nfc,
            lookingUp = control.lookingUp,
            scanProblem = state.scanProblem,
            plannedVehicle = planned,
            vehicle = state.vehicle?.toInfo(),
            vehicleVerified = state.vehicleVerified,
            vehiclePlanned = state.vehiclePlanned,
            manual = state.manual,
            template = template,
            itemIndex = state.itemIndex,
            answers = state.answers.associate { it.key to it.toAnswer() },
            submitting = busy,
            recorded = state.recorded,
            failure = state.failure,
            holdReason = state.holdReason,
            restWarningAcknowledged = state.restWarningAcknowledged,
            cameraRequest = control.cameraRequest,
            isOnline = online,
            message = control.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartShiftUiState())

    init {
        viewModelScope.launch {
            val times = driverTimes.current()
            val remaining = DailyShiftRules.restNotOver(times, clock.instant())
            restEndsAt.value = times?.restingRest?.endsAt
            if (draft.value.step == StartShiftStep.LOADING) {
                update { it.copy(step = if (remaining != null && !it.restWarningAcknowledged) StartShiftStep.REST_WARNING else StartShiftStep.SCAN) }
            }
        }
        // The newest list for the check; the phone's copy is used when this fails.
        viewModelScope.launch { vehicleCheck.refreshTemplate() }
    }

    /** The screen came to the front: NFC may have been switched on in the settings. */
    fun onResume() {
        nfc.value = nfcStatus.availability()
    }

    fun onRestWarningConfirmed() = update { it.copy(restWarningAcknowledged = true, step = StartShiftStep.SCAN) }

    /** The first tag read counts; reading stops while its vehicle is looked up. */
    fun onTagRead(tagId: ByteArray) {
        val state = draft.value
        if (state.step != StartShiftStep.SCAN || lookingUp.value) return
        val uid = AttendanceRules.tagUid(tagId)
        if (uid == null) {
            update { it.copy(scanProblem = ScanProblem.UNREADABLE) }
            return
        }
        val scannedAt = clock.instant()
        lookingUp.value = true
        viewModelScope.launch {
            try {
                when (val lookup = vehicleCheck.lookupVehicle(uid)) {
                    is VehicleLookup.Found -> update {
                        it.copy(
                            step = StartShiftStep.VEHICLE,
                            tagUid = uid,
                            tagScannedAtMillis = scannedAt.toEpochMilli(),
                            manual = false,
                            vehicle = lookup.vehicle.toDraft(),
                            vehicleVerified = true,
                            vehiclePlanned = lookup.isPlanned,
                            scanProblem = null,
                        )
                    }
                    VehicleLookup.UnknownTag -> update { it.copy(scanProblem = ScanProblem.UNKNOWN_TAG) }
                    // Checked by the server when the start is sent.
                    VehicleLookup.Unverified -> update {
                        it.copy(
                            step = StartShiftStep.VEHICLE,
                            tagUid = uid,
                            tagScannedAtMillis = scannedAt.toEpochMilli(),
                            manual = false,
                            vehicle = null,
                            vehicleVerified = false,
                            vehiclePlanned = null,
                            scanProblem = null,
                        )
                    }
                }
            } finally {
                lookingUp.value = false
            }
        }
    }

    /** The tag cannot be read: start with the planned vehicle; the office reviews it (docs/16 §7). */
    fun onUsePlannedVehicle() {
        if (draft.value.step != StartShiftStep.SCAN) return
        viewModelScope.launch {
            val planned = dailyShift.observePlannedVehicle().first()?.takeIf { it.vehicleId != null } ?: return@launch
            update {
                if (it.step != StartShiftStep.SCAN) return@update it
                it.copy(
                    step = StartShiftStep.VEHICLE,
                    tagUid = null,
                    tagScannedAtMillis = clock.instant().toEpochMilli(),
                    manual = true,
                    vehicle = planned.toDraft(),
                    vehicleVerified = true,
                    vehiclePlanned = true,
                    scanProblem = null,
                )
            }
        }
    }

    fun onRescan() = update {
        it.copy(step = StartShiftStep.SCAN, tagUid = null, tagScannedAtMillis = null, manual = false, vehicle = null, vehicleVerified = false, vehiclePlanned = null)
    }

    fun onStartCheck() {
        viewModelScope.launch {
            val template = draft.value.template?.let(DriverShiftMapping::template) ?: vehicleCheck.observeTemplate().first()
            update {
                it.copy(
                    step = StartShiftStep.CHECK,
                    template = it.template ?: template.toDto(),
                    itemIndex = firstUnanswered(template, it.answers, from = 0) ?: 0,
                )
            }
        }
    }

    fun onAnswerOk() = answer(CheckResult.OK)

    fun onAnswerNotApplicable() {
        if (currentItem()?.notApplicableAllowed != true) return
        answer(CheckResult.NOT_APPLICABLE)
    }

    /** Opens the defect part of the item: photo and optional note. */
    fun onDefect() {
        val item = currentItem() ?: return
        update { state ->
            val existing = state.answers.firstOrNull { it.key == item.key }
            if (existing?.result == CheckResult.DEFECT) return@update state
            state.copy(answers = state.answers.filterNot { it.key == item.key } + DraftAnswer(item.key, CheckResult.DEFECT))
        }
    }

    fun onNoteChanged(note: String) {
        val item = currentItem() ?: return
        update { state ->
            state.copy(
                answers = state.answers.map {
                    if (it.key == item.key && it.result == CheckResult.DEFECT) it.copy(note = note.take(VehicleCheckRules.NOTE_MAX_LENGTH)) else it
                },
            )
        }
    }

    /** The defect is complete (photo if required): next item. */
    fun onDefectDone() {
        val item = currentItem() ?: return
        if (!VehicleCheckRules.isItemComplete(item, currentAnswer())) {
            message.value = R.string.check_photo_required
            return
        }
        advance()
    }

    fun onTakePhoto() {
        val answer = currentAnswer()
        if (answer?.result != CheckResult.DEFECT || answer.photos.size >= VehicleCheckRules.MAX_PHOTOS_PER_ITEM) return
        if (draft.value.pendingPhoto != null) return
        if (!photos.hasEnoughSpace()) {
            message.value = R.string.error_low_storage
            return
        }
        viewModelScope.launch {
            val path = photos.createPhotoFile()
            update { it.copy(pendingPhoto = DraftPhoto(ids.newId(), path)) }
            cameraRequest.value = path
        }
    }

    fun onCameraLaunched() {
        cameraRequest.value = null
    }

    /** The camera app answered. The photo belongs to the item that was open when it was taken. */
    fun onPhotoResult(taken: Boolean) {
        val pending = draft.value.pendingPhoto ?: return
        val item = currentItem()
        viewModelScope.launch {
            val usable = taken && File(pending.path).length() > 0
            if (!usable || item == null) {
                photos.discard(pending.path)
                if (taken && !usable) message.value = R.string.check_photo_failed
            }
            update { state ->
                state.copy(
                    pendingPhoto = null,
                    answers = if (usable && item != null) {
                        state.answers.map { if (it.key == item.key && it.result == CheckResult.DEFECT) it.copy(photos = it.photos + pending) else it }
                    } else {
                        state.answers
                    },
                )
            }
        }
    }

    fun onCameraUnavailable() {
        onPhotoResult(false)
        cameraRequest.value = null
        message.value = R.string.check_camera_unavailable
    }

    fun onCameraPermissionDenied() {
        message.value = R.string.check_camera_permission
    }

    fun onRemovePhoto(photoId: String) {
        val item = currentItem() ?: return
        val photo = draft.value.answers.firstOrNull { it.key == item.key }?.photos?.firstOrNull { it.photoId == photoId } ?: return
        viewModelScope.launch { photos.discard(photo.path) }
        update { state ->
            state.copy(answers = state.answers.map { if (it.key == item.key) it.copy(photos = it.photos.filterNot { p -> p.photoId == photoId }) else it })
        }
    }

    /** From the summary: change the answer of this item. */
    fun onEditItem(key: String) {
        val index = template()?.items?.indexOfFirst { it.key == key }?.takeIf { it >= 0 } ?: return
        update { it.copy(step = StartShiftStep.CHECK, itemIndex = index) }
    }

    /** One step back; false when the flow should close. */
    fun onBack(): Boolean {
        val state = draft.value
        when (state.step) {
            StartShiftStep.CHECK -> update {
                if (it.itemIndex > 0) it.copy(itemIndex = it.itemIndex - 1) else it.copy(step = StartShiftStep.VEHICLE)
            }
            StartShiftStep.SUMMARY -> update {
                it.copy(step = StartShiftStep.CHECK, itemIndex = (template()?.items?.size ?: 1) - 1)
            }
            StartShiftStep.VEHICLE -> onRescan()
            StartShiftStep.LOADING, StartShiftStep.REST_WARNING, StartShiftStep.SCAN, StartShiftStep.DONE -> return false
        }
        return true
    }

    /** The driver leaves without starting: photos of this check are not needed. */
    fun onCancel() {
        val state = draft.value
        if (state.step == StartShiftStep.DONE) return
        val paths = state.answers.flatMap { it.photos }.map { it.path } + listOfNotNull(state.pendingPhoto?.path)
        viewModelScope.launch { paths.forEach { photos.discard(it) } }
        update { StartShiftDraftState(commandId = ids.newId(), step = StartShiftStep.SCAN) }
    }

    /** Stores the start with the check and sends it (docs/16 §7). The same command id is never stored twice. */
    fun onSubmit() {
        val state = draft.value
        if (state.step != StartShiftStep.SUMMARY) return
        val start = buildDraft(state) ?: return
        singleFlight.launch(viewModelScope) {
            when (val result = dailyShift.startShift(start)) {
                ShiftCommandResult.Recorded -> update { it.copy(step = StartShiftStep.DONE, recorded = true, failure = null) }
                is ShiftCommandResult.Saved -> update { it.copy(step = StartShiftStep.DONE, recorded = false, failure = null, holdReason = result.hold) }
                is ShiftCommandResult.Failed -> update { it.copy(failure = result.failure) }
                is ShiftCommandResult.NotAllowed -> message.value = result.reason.messageRes()
                ShiftCommandResult.NotLoggedIn -> message.value = R.string.refresh_session_expired
            }
        }
    }

    /**
     * After a rejection (e.g. the tag is not registered): the rejected start is removed from the phone and the driver
     * starts again with a new command id. The rest warning answer is kept.
     */
    fun onStartOver() {
        val state = draft.value
        singleFlight.launch(viewModelScope) {
            dailyShift.discard(state.commandId)
            update { StartShiftDraftState(commandId = ids.newId(), step = StartShiftStep.SCAN, restWarningAcknowledged = it.restWarningAcknowledged) }
        }
    }

    fun onMessageShown() {
        message.value = null
    }

    private fun answer(result: CheckResult) {
        val item = currentItem() ?: return
        val previous = draft.value.answers.firstOrNull { it.key == item.key }
        // A defect answered as fine after all: its photos are not needed.
        previous?.photos?.forEach { photo -> viewModelScope.launch { photos.discard(photo.path) } }
        update { state -> state.copy(answers = state.answers.filterNot { it.key == item.key } + DraftAnswer(item.key, result)) }
        advance()
    }

    /** The next unanswered item after the current one, else the summary. */
    private fun advance() {
        val template = template() ?: return
        update { state ->
            val next = firstUnanswered(template, state.answers, from = state.itemIndex + 1)
            if (next == null) state.copy(step = StartShiftStep.SUMMARY) else state.copy(itemIndex = next)
        }
    }

    private fun firstUnanswered(template: CheckTemplate, answers: List<DraftAnswer>, from: Int): Int? {
        val answered = answers.associateBy { it.key }
        val order = template.items.indices.let { (from until it.last + 1) + (0 until from) }
        return order.firstOrNull { index ->
            val item = template.items[index]
            !VehicleCheckRules.isItemComplete(item, answered[item.key]?.toAnswer())
        }
    }

    private fun buildDraft(state: StartShiftDraftState): ShiftStartDraft? {
        val template = state.template?.let(DriverShiftMapping::template) ?: return null
        val scannedAt = state.tagScannedAtMillis?.let(Instant::ofEpochMilli) ?: return null
        val vehicle = state.vehicle?.toInfo()
        return ShiftStartDraft(
            commandId = state.commandId,
            tagUid = state.tagUid,
            manualVehicle = vehicle.takeIf { state.manual },
            tagScannedAt = scannedAt,
            vehicle = vehicle,
            restWarningAcknowledged = state.restWarningAcknowledged,
            template = template,
            answers = template.items.mapNotNull { item ->
                state.answers.firstOrNull { it.key == item.key }?.toAnswer()?.let { answer ->
                    answer.copy(note = VehicleCheckRules.normalizeNote(answer.note))
                }
            },
        )
    }

    // Read from the draft, not from uiState: a camera result can arrive before the screen state is rebuilt.
    private fun template(): CheckTemplate? = draft.value.template?.let(DriverShiftMapping::template)

    private fun currentItem(): CheckItem? = template()?.items?.getOrNull(draft.value.itemIndex)

    private fun currentAnswer(): CheckAnswer? =
        currentItem()?.let { item -> draft.value.answers.firstOrNull { it.key == item.key }?.toAnswer() }

    private fun update(transform: (StartShiftDraftState) -> StartShiftDraftState) {
        draft.update(transform)
        savedStateHandle[DRAFT_KEY] = json.encodeToString(StartShiftDraftState.serializer(), draft.value)
    }

    private fun restoreDraft(): StartShiftDraftState? = savedStateHandle.get<String>(DRAFT_KEY)?.let { stored ->
        runCatching { json.decodeFromString(StartShiftDraftState.serializer(), stored) }.getOrNull()
    }

    private companion object {
        const val DRAFT_KEY = "start_shift_draft"
    }
}

private fun DraftAnswer.toAnswer() = CheckAnswer(
    key = key,
    result = result,
    note = note,
    photos = photos.map { CheckPhoto(it.photoId, it.path) },
)

private fun DraftVehicle.toInfo() = VehicleInfo(vehicleId, registrationNumber, displayName, trailerRegistrationNumber)

private fun VehicleInfo.toDraft() = DraftVehicle(vehicleId, registrationNumber, displayName, trailerRegistrationNumber)

/** The list in its wire form, so the saved state can hold it. */
private fun CheckTemplate.toDto() = VehicleCheckTemplateDto(
    templateId = templateId,
    version = version,
    items = items.map { item ->
        VehicleCheckItemDto(
            key = item.key,
            titles = item.titles,
            hints = item.hints,
            photoRequiredOnDefect = item.photoRequiredOnDefect,
            notApplicableAllowed = item.notApplicableAllowed,
        )
    },
)
