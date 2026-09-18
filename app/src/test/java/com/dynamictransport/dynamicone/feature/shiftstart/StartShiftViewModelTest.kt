package com.dynamictransport.dynamicone.feature.shiftstart

import androidx.lifecycle.SavedStateHandle
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.nfc.NfcAvailability
import com.dynamictransport.dynamicone.domain.model.CheckResult
import com.dynamictransport.dynamicone.domain.model.CheckTemplate
import com.dynamictransport.dynamicone.domain.model.ShiftCommandFailure
import com.dynamictransport.dynamicone.domain.model.ShiftCommandResult
import com.dynamictransport.dynamicone.domain.model.VehicleInfo
import com.dynamictransport.dynamicone.domain.model.VehicleLookup
import com.dynamictransport.dynamicone.testing.FakeCheckPhotoStore
import com.dynamictransport.dynamicone.testing.FakeDailyShiftRepository
import com.dynamictransport.dynamicone.testing.FakeDriverTimesRepository
import com.dynamictransport.dynamicone.testing.FakeNetworkMonitor
import com.dynamictransport.dynamicone.testing.FakeNfcStatusProvider
import com.dynamictransport.dynamicone.testing.FakeVehicleCheckRepository
import com.dynamictransport.dynamicone.testing.MainDispatcherRule
import com.dynamictransport.dynamicone.testing.MutableClock
import com.dynamictransport.dynamicone.testing.SequenceIdGenerator
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.keepCollecting
import com.dynamictransport.dynamicone.testing.restingTimes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration

class StartShiftViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val shift = FakeDailyShiftRepository()
    private val checks = FakeVehicleCheckRepository()
    private val times = FakeDriverTimesRepository()
    private val nfc = FakeNfcStatusProvider()
    private val clock = MutableClock()
    private val ids = SequenceIdGenerator("cmd")
    private val saved = SavedStateHandle()
    private val photos by lazy { FakeCheckPhotoStore(File(folder.root, "vehicle_check_media")) }

    private val tagBytes = byteArrayOf(0x04, 0xA2.toByte(), 0x24, 0xB2.toByte(), 0xC3.toByte(), 0x5E, 0x80.toByte())
    private val items = CheckTemplate.Default.items

    private fun TestScope.viewModel(handle: SavedStateHandle = saved): StartShiftViewModel =
        StartShiftViewModel(handle, shift, checks, times, photos, nfc, FakeNetworkMonitor(), ids, clock).also { keepCollecting(it.uiState) }

    private fun StartShiftViewModel.state() = uiState.value

    /** Scans the tag and opens the check. */
    private fun StartShiftViewModel.toCheck() {
        onTagRead(tagBytes)
        onStartCheck()
    }

    private fun StartShiftViewModel.answerAllOk() = repeat(items.size) { onAnswerOk() }

    @Test
    fun `before the end of the rest the driver confirms first`() = runTest {
        times.times.value = restingTimes(end = TEST_NOW.plus(Duration.ofMinutes(25)))
        val viewModel = viewModel()

        assertEquals(StartShiftStep.REST_WARNING, viewModel.state().step)
        assertEquals(TEST_NOW.plus(Duration.ofMinutes(25)), viewModel.state().restEndsAt)

        viewModel.onRestWarningConfirmed()
        assertEquals(StartShiftStep.SCAN, viewModel.state().step)
        assertTrue(viewModel.state().restWarningAcknowledged)
    }

    @Test
    fun `after the rest, or without tachograph data, it starts at the tag`() = runTest {
        times.times.value = restingTimes(end = TEST_NOW.minusSeconds(60))
        assertEquals(StartShiftStep.SCAN, viewModel().state().step)
        times.times.value = null
        assertEquals(StartShiftStep.SCAN, viewModel(SavedStateHandle()).state().step)
        assertTrue(checks.templateRefreshes > 0)
    }

    @Test
    fun `the tag names the vehicle, a mismatch with the plan is shown`() = runTest {
        shift.planned.value = VehicleInfo("veh-2", "WX 99999")
        checks.lookup = { VehicleLookup.Found(VehicleInfo("veh-1", "WX 12345"), isPlanned = false) }
        val viewModel = viewModel()

        viewModel.onTagRead(tagBytes)

        assertEquals(listOf("04A224B2C35E80"), checks.lookedUp)
        val state = viewModel.state()
        assertEquals(StartShiftStep.VEHICLE, state.step)
        assertEquals("WX 12345", state.vehicle?.registrationNumber)
        assertTrue(state.vehicleVerified)
        assertTrue(state.otherThanPlanned)
        // Reading stops once a tag was taken: a second tag changes nothing.
        viewModel.onTagRead(byteArrayOf(1, 2, 3, 4))
        assertEquals(1, checks.lookedUp.size)
    }

    @Test
    fun `unknown and unreadable tags stay at the scan, offline the tag is checked later`() = runTest {
        val viewModel = viewModel()
        viewModel.onTagRead(byteArrayOf(1, 2))
        assertEquals(ScanProblem.UNREADABLE, viewModel.state().scanProblem)

        checks.lookup = { VehicleLookup.UnknownTag }
        viewModel.onTagRead(tagBytes)
        assertEquals(StartShiftStep.SCAN, viewModel.state().step)
        assertEquals(ScanProblem.UNKNOWN_TAG, viewModel.state().scanProblem)

        checks.lookup = { VehicleLookup.Unverified }
        viewModel.onTagRead(tagBytes)
        assertEquals(StartShiftStep.VEHICLE, viewModel.state().step)
        assertFalse(viewModel.state().vehicleVerified)
        assertNull(viewModel.state().scanProblem)
    }

    @Test
    fun `when the tag does not read the planned vehicle can be used`() = runTest {
        nfc.value = NfcAvailability.NOT_SUPPORTED
        val viewModel = viewModel()
        assertFalse(viewModel.state().canUsePlannedVehicle)
        shift.planned.value = VehicleInfo("veh-1", "WX 12345")
        assertTrue(viewModel.state().canUsePlannedVehicle)

        viewModel.onUsePlannedVehicle()

        assertEquals(StartShiftStep.VEHICLE, viewModel.state().step)
        assertTrue(viewModel.state().manual)
        viewModel.onStartCheck()
        viewModel.answerAllOk()
        viewModel.onSubmit()
        val draft = shift.started.single()
        assertNull(draft.tagUid)
        assertEquals("veh-1", draft.manualVehicle?.vehicleId)
    }

    @Test
    fun `all items fine lead to the summary and one confirmation sends everything`() = runTest {
        val viewModel = viewModel()
        viewModel.toCheck()
        assertEquals(StartShiftStep.CHECK, viewModel.state().step)
        assertEquals(items.first(), viewModel.state().currentItem)

        viewModel.answerAllOk()
        assertEquals(StartShiftStep.SUMMARY, viewModel.state().step)
        assertTrue(viewModel.state().defects.isEmpty())

        viewModel.onSubmit()

        val draft = shift.started.single()
        assertEquals("cmd-1", draft.commandId)
        assertEquals("04A224B2C35E80", draft.tagUid)
        assertEquals(TEST_NOW, draft.tagScannedAt)
        assertEquals(items.map { it.key }, draft.answers.map { it.key })
        assertTrue(draft.answers.all { it.result == CheckResult.OK })
        assertEquals(StartShiftStep.DONE, viewModel.state().step)
        assertTrue(viewModel.state().recorded)
    }

    @Test
    fun `a defect needs its photo, the note is optional`() = runTest {
        val viewModel = viewModel()
        viewModel.toCheck()
        viewModel.onDefect()
        assertEquals(CheckResult.DEFECT, viewModel.state().currentAnswer?.result)
        assertFalse(viewModel.state().canContinue)

        viewModel.onDefectDone()
        assertEquals(R.string.check_photo_required, viewModel.state().message)
        assertEquals(0, viewModel.state().itemIndex)

        viewModel.onTakePhoto()
        val path = viewModel.state().cameraRequest!!
        viewModel.onCameraLaunched()
        File(path).writeBytes(byteArrayOf(1, 2, 3))
        viewModel.onPhotoResult(true)
        viewModel.onNoteChanged("  Scratch on the left door  ")
        assertTrue(viewModel.state().canContinue)

        viewModel.onDefectDone()
        assertEquals(1, viewModel.state().itemIndex)
        repeat(items.size - 1) { viewModel.onAnswerOk() }
        val defects = viewModel.state().defects
        assertEquals(listOf("no_new_damage"), defects.map { it.first.key })

        viewModel.onSubmit()
        val answer = shift.started.single().answers.first()
        assertEquals("Scratch on the left door", answer.note)
        assertEquals(path, answer.photos.single().filePath)
    }

    @Test
    fun `a cancelled camera leaves no file, a fine answer drops the defect photos`() = runTest {
        val viewModel = viewModel()
        viewModel.toCheck()
        viewModel.onDefect()
        viewModel.onTakePhoto()
        val cancelled = viewModel.state().cameraRequest!!
        viewModel.onCameraLaunched()
        viewModel.onPhotoResult(false)
        assertFalse(File(cancelled).exists())
        assertTrue(viewModel.state().currentAnswer!!.photos.isEmpty())

        viewModel.onTakePhoto()
        val taken = viewModel.state().cameraRequest!!
        viewModel.onCameraLaunched()
        File(taken).writeBytes(byteArrayOf(1))
        viewModel.onPhotoResult(true)
        viewModel.onAnswerOk()

        assertFalse(File(taken).exists())
        assertEquals(CheckResult.OK, viewModel.state().answers.getValue("no_new_damage").result)
    }

    @Test
    fun `not applicable only where the item allows it`() = runTest {
        val viewModel = viewModel()
        viewModel.toCheck()
        viewModel.onAnswerNotApplicable()
        assertEquals(0, viewModel.state().itemIndex)

        val couplingIndex = items.indexOfFirst { it.key == "coupling" }
        repeat(couplingIndex) { viewModel.onAnswerOk() }
        viewModel.onAnswerNotApplicable()
        assertEquals(CheckResult.NOT_APPLICABLE, viewModel.state().answers.getValue("coupling").result)
    }

    @Test
    fun `an item can be changed from the summary and back returns step by step`() = runTest {
        val viewModel = viewModel()
        viewModel.toCheck()
        viewModel.answerAllOk()

        viewModel.onEditItem("lights")
        assertEquals(StartShiftStep.CHECK, viewModel.state().step)
        assertEquals("lights", viewModel.state().currentItem?.key)
        viewModel.onAnswerOk()
        // Every item is answered: back to the summary, not through the rest of the list.
        assertEquals(StartShiftStep.SUMMARY, viewModel.state().step)

        assertTrue(viewModel.onBack())
        assertEquals(items.last(), viewModel.state().currentItem)
        repeat(items.size) { assertTrue(viewModel.onBack()) }
        assertEquals(StartShiftStep.VEHICLE, viewModel.state().step)
        assertTrue(viewModel.onBack())
        assertEquals(StartShiftStep.SCAN, viewModel.state().step)
        assertFalse(viewModel.onBack())
    }

    @Test
    fun `holding the button twice sends one start`() = runTest {
        val gate = CompletableDeferred<Unit>()
        shift.startGate = gate
        val viewModel = viewModel()
        viewModel.toCheck()
        viewModel.answerAllOk()

        viewModel.onSubmit()
        viewModel.onSubmit()
        assertTrue(viewModel.state().submitting)
        gate.complete(Unit)

        assertEquals(1, shift.started.size)
        assertEquals(StartShiftStep.DONE, viewModel.state().step)
    }

    @Test
    fun `offline the start is saved on the phone, a rejection offers a new start`() = runTest {
        shift.startResult = ShiftCommandResult.Saved(null)
        val viewModel = viewModel()
        viewModel.toCheck()
        viewModel.answerAllOk()
        viewModel.onSubmit()
        assertEquals(StartShiftStep.DONE, viewModel.state().step)
        assertFalse(viewModel.state().recorded)

        shift.startResult = ShiftCommandResult.Failed(ShiftCommandFailure.TAG_UNKNOWN)
        val rejected = viewModel(SavedStateHandle())
        rejected.toCheck()
        rejected.answerAllOk()
        rejected.onSubmit()
        assertEquals(StartShiftStep.SUMMARY, rejected.state().step)
        assertEquals(ShiftCommandFailure.TAG_UNKNOWN, rejected.state().failure)

        rejected.onStartOver()
        assertEquals(listOf("cmd-2"), shift.discarded)
        assertEquals(StartShiftStep.SCAN, rejected.state().step)
        rejected.toCheck()
        rejected.answerAllOk()
        rejected.onSubmit()
        assertNotEquals("cmd-2", shift.started.last().commandId)
    }

    @Test
    fun `the check survives the app being closed by the system while the camera is open`() = runTest {
        val first = viewModel()
        first.toCheck()
        first.onAnswerOk()
        first.onDefect()
        first.onTakePhoto()
        val path = first.state().cameraRequest!!
        first.onCameraLaunched()
        File(path).writeBytes(byteArrayOf(9, 9))

        // Same saved state, new view model: the process was recreated.
        val restored = viewModel()
        assertEquals(StartShiftStep.CHECK, restored.state().step)
        assertEquals(1, restored.state().itemIndex)
        restored.onPhotoResult(true)

        assertEquals(path, restored.state().currentAnswer!!.photos.single().filePath)
        restored.onDefectDone()
        repeat(items.size - 2) { restored.onAnswerOk() }
        restored.onSubmit()
        assertEquals("cmd-1", shift.started.single().commandId)
    }

    @Test
    fun `cancelling removes the photos taken in the check`() = runTest {
        val viewModel = viewModel()
        viewModel.toCheck()
        viewModel.onDefect()
        viewModel.onTakePhoto()
        val path = viewModel.state().cameraRequest!!
        viewModel.onCameraLaunched()
        File(path).writeBytes(byteArrayOf(1))
        viewModel.onPhotoResult(true)

        viewModel.onCancel()

        assertFalse(File(path).exists())
        assertTrue(shift.started.isEmpty())
    }

    @Test
    fun `low storage is reported before the camera opens`() = runTest {
        photos.enoughSpace = false
        val viewModel = viewModel()
        viewModel.toCheck()
        viewModel.onDefect()
        viewModel.onTakePhoto()
        assertNull(viewModel.state().cameraRequest)
        assertEquals(R.string.error_low_storage, viewModel.state().message)
    }
}
