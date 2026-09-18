package com.dynamictransport.dynamicone.data.real

import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.CheckAnswer
import com.dynamictransport.dynamicone.domain.model.CheckPhoto
import com.dynamictransport.dynamicone.domain.model.CheckResult
import com.dynamictransport.dynamicone.domain.model.CheckTemplate
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.model.ShiftCommandFailure
import com.dynamictransport.dynamicone.domain.model.ShiftCommandResult
import com.dynamictransport.dynamicone.domain.model.ShiftCommandState
import com.dynamictransport.dynamicone.domain.model.ShiftCommandType
import com.dynamictransport.dynamicone.domain.model.ShiftStartDraft
import com.dynamictransport.dynamicone.domain.model.ShiftStatus
import com.dynamictransport.dynamicone.domain.model.SyncState
import com.dynamictransport.dynamicone.domain.model.VehicleIdentification
import com.dynamictransport.dynamicone.domain.model.VehicleInfo
import com.dynamictransport.dynamicone.domain.model.VehicleLookup
import com.dynamictransport.dynamicone.testing.DriverShiftJson
import com.dynamictransport.dynamicone.testing.InMemoryCheckPhotoDao
import com.dynamictransport.dynamicone.testing.InMemoryDriverCacheDao
import com.dynamictransport.dynamicone.testing.InMemoryShiftCommandDao
import com.dynamictransport.dynamicone.testing.RealApiHarness
import com.dynamictransport.dynamicone.testing.RecordingDriverDayWorkScheduler
import com.dynamictransport.dynamicone.testing.RecordingShiftSyncScheduler
import com.dynamictransport.dynamicone.testing.SequenceIdGenerator
import com.dynamictransport.dynamicone.testing.drainRequests
import com.dynamictransport.dynamicone.testing.jsonResponse
import com.dynamictransport.dynamicone.testing.problemResponse
import com.dynamictransport.dynamicone.testing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant

/** Daily shift against the docs/16 contract with the production HTTP stack (MockWebServer). */
class ApiShiftRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: RealApiHarness
    private val commandDao = InMemoryShiftCommandDao()
    private val photoDao = InMemoryCheckPhotoDao(commandDao)
    private val cacheDao = InMemoryDriverCacheDao()
    private val scheduler = RecordingShiftSyncScheduler()
    private val dayWork = RecordingDriverDayWorkScheduler()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private lateinit var store: DriverCacheStore
    private lateinit var times: ApiDriverTimesRepository
    private lateinit var checks: ApiVehicleCheckRepository
    private lateinit var repo: ApiShiftRepository

    private val tag = "04A224B2C35E80"
    private val scannedAt = Instant.parse("2026-09-12T07:55:00Z")

    @Before
    fun setUp() {
        h = RealApiHarness(folder)
        store = DriverCacheStore(cacheDao, json, h.clock)
        times = ApiDriverTimesRepository(h.api, h.tracker, h.sessionPreferences, store)
        checks = ApiVehicleCheckRepository(h.api, h.tracker, h.sessionPreferences, store)
        repo = newRepository()
        h.signIn()
    }

    @After
    fun tearDown() = h.close()

    /** A new instance over the same stored data: the app process was restarted. */
    private fun newRepository() = ApiShiftRepository(
        api = h.api,
        tracker = h.tracker,
        commandDao = commandDao,
        photoDao = photoDao,
        store = store,
        sessionPreferences = h.sessionPreferences,
        scheduler = scheduler,
        dayWork = dayWork,
        driverTimes = times,
        ids = SequenceIdGenerator("end"),
        clock = h.clock,
        uptime = h.uptime,
        json = json,
        directories = h.directories,
        io = Dispatchers.IO,
    )

    /** A photo file where the camera writes it; PNG content is uploaded as it is. */
    private fun photo(name: String): File = File(h.directories.filesDir, "vehicle_check_media/$name").apply {
        parentFile!!.mkdirs()
        writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4))
    }

    private fun draft(commandId: String = "start-1", defectPhoto: File? = null, tagUid: String? = tag, manual: VehicleInfo? = null): ShiftStartDraft {
        val template = CheckTemplate.Default
        return ShiftStartDraft(
            commandId = commandId,
            tagUid = tagUid,
            manualVehicle = manual,
            tagScannedAt = scannedAt,
            vehicle = VehicleInfo("veh-1", "WX 12345"),
            restWarningAcknowledged = true,
            template = template,
            answers = template.items.map { item ->
                if (item.key == "lights" && defectPhoto != null) {
                    CheckAnswer(item.key, CheckResult.DEFECT, "Left brake light", listOf(CheckPhoto("photo-1", defectPhoto.path)))
                } else if (item.key == "container_locks") {
                    CheckAnswer(item.key, CheckResult.NOT_APPLICABLE)
                } else {
                    CheckAnswer(item.key, CheckResult.OK)
                }
            },
        )
    }

    private fun startRecorded(defects: Int = 0) = jsonResponse(
        DriverShiftJson.startResult(DriverShiftJson.shift(commandId = "start-1", defects = defects), pendingPhotos = if (defects > 0) listOf("photo-1") else emptyList()),
        code = 201,
    )

    private fun RecordedRequest.jsonBody(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

    @Test
    fun `a start is stored first and sent with the tag, times, check and photos`() = runBlocking {
        h.routes.on("POST", "/shift/start") { startRecorded(defects = 1) }
        h.routes.on("POST", "/vehicle-checks/check-1/photos") { jsonResponse("""{"photoId":"photo-1","receivedAtUtc":"2026-09-12T08:00:01Z"}""", 201) }
        val defect = photo("check_1.png")

        val result = repo.startShift(draft(defectPhoto = defect))

        assertEquals(ShiftCommandResult.Recorded, result)
        val requests = h.server.drainRequests()
        assertEquals(listOf("POST /api/driver/v1/shift/start", "POST /api/driver/v1/vehicle-checks/check-1/photos"), requests.map { it.route })
        val body = requests[0].jsonBody()
        assertEquals("start-1", body.getValue("commandId").jsonPrimitive.content)
        assertEquals(tag, body.getValue("vehicleTagUid").jsonPrimitive.content)
        assertFalse(body.containsKey("manualVehicleId"))
        assertEquals("2026-09-12T07:55:00Z", body.getValue("tagScannedAtUtc").jsonPrimitive.content)
        assertEquals("2026-09-12T08:00:00Z", body.getValue("startedAtUtc").jsonPrimitive.content)
        assertEquals(0L, body.getValue("elapsedSinceStartMs").jsonPrimitive.long)
        assertEquals(h.sessionPreferences.installationId(), body.getValue("installationId").jsonPrimitive.content)
        assertTrue(body.getValue("restWarningAcknowledged").jsonPrimitive.boolean)
        val check = body.getValue("vehicleCheck").jsonObject
        assertFalse(check.containsKey("templateId"))
        assertEquals(1L, check.getValue("templateVersion").jsonPrimitive.long)
        val items = check.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(CheckTemplate.Default.items.map { it.key }, items.map { it.getValue("key").jsonPrimitive.content })
        val lights = items.single { it.getValue("key").jsonPrimitive.content == "lights" }
        assertEquals("Defect", lights.getValue("result").jsonPrimitive.content)
        assertEquals("Left brake light", lights.getValue("note").jsonPrimitive.content)
        assertEquals(listOf("photo-1"), lights.getValue("photoIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("NotApplicable", items.single { it.getValue("key").jsonPrimitive.content == "container_locks" }.getValue("result").jsonPrimitive.content)
        assertFalse(items.single { it.getValue("key").jsonPrimitive.content == "tyres" }.containsKey("photoIds"))

        val upload = requests[1].body.readUtf8()
        assertTrue(upload.contains("name=\"photoId\""))
        assertTrue(upload.contains("\r\n\r\nphoto-1\r\n"))
        assertTrue(upload.contains("name=\"photo\"; filename=\"check_1.png\""))

        val shift = repo.observeShift().first()
        assertEquals(ShiftStatus.ACTIVE, shift.status)
        assertEquals(SyncState.SYNCED, shift.syncState)
        assertEquals("WX 12345", shift.vehiclePlate)
        assertFalse(defect.exists())
        assertEquals(ShiftCommandState.SYNCED, photoDao.all.single().state)
    }

    @Test
    fun `offline the start waits on the phone and is sent later with the same command id`() = runBlocking {
        h.routes.on("POST", "/shift/start") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }

        val saved = repo.startShift(draft())

        assertEquals(ShiftCommandResult.Saved(null), saved)
        val shift = repo.observeShift().first()
        assertEquals(ShiftStatus.ACTIVE, shift.status)
        assertEquals(SyncState.PENDING, shift.syncState)
        assertEquals(listOf(0L), scheduler.delaysSeconds)

        h.server.drainRequests()
        h.routes.on("POST", "/shift/start") { startRecorded() }
        h.uptime.elapsedMillis += 90_000
        assertTrue(repo.sendPending())

        val body = h.server.drainRequests().single().jsonBody()
        assertEquals("start-1", body.getValue("commandId").jsonPrimitive.content)
        assertEquals(90_000L, body.getValue("elapsedSinceStartMs").jsonPrimitive.long)
        assertEquals("2026-09-12T08:00:00Z", body.getValue("startedAtUtc").jsonPrimitive.content)
        assertEquals(SyncState.SYNCED, repo.observeShift().first().syncState)
    }

    @Test
    fun `the same confirmation twice is one start`() = runBlocking {
        h.routes.on("POST", "/shift/start") { startRecorded() }

        assertEquals(ShiftCommandResult.Recorded, repo.startShift(draft()))
        assertEquals(ShiftCommandResult.Recorded, repo.startShift(draft()))

        assertEquals(1, commandDao.all.size)
        assertEquals(1, h.server.drainRequests().size)
        // A different start while the shift runs is refused on the phone.
        assertEquals(ShiftCommandResult.NotAllowed(ActionRejection.NOT_ALLOWED), repo.startShift(draft(commandId = "start-2")))
    }

    @Test
    fun `an unknown tag is a final answer, the start can be removed with its photos`() = runBlocking {
        h.routes.on("POST", "/shift/start") { problemResponse(400, "vehicle_tag_unknown") }
        val defect = photo("check_1.png")

        assertEquals(ShiftCommandResult.Failed(ShiftCommandFailure.TAG_UNKNOWN), repo.startShift(draft(defectPhoto = defect)))

        assertEquals(ShiftStatus.NOT_STARTED, repo.observeShift().first().status)
        val failed = repo.observeCommands().first().single()
        assertEquals(ShiftCommandState.FAILED, failed.state)
        assertEquals(ShiftCommandFailure.TAG_UNKNOWN, failed.failure)
        assertTrue(scheduler.delaysSeconds.isEmpty())

        repo.discard("start-1")
        assertTrue(commandDao.all.isEmpty())
        assertTrue(photoDao.all.isEmpty())
        assertFalse(defect.exists())
    }

    @Test
    fun `final answers of the contract`() = runBlocking {
        val cases = listOf(
            problemResponse(400, "vehicle_check_invalid") to ShiftCommandFailure.CHECK_INVALID,
            problemResponse(400, "shift_time_invalid") to ShiftCommandFailure.TIME_REJECTED,
            problemResponse(400, "shift_too_old") to ShiftCommandFailure.TIME_REJECTED,
            problemResponse(409, "command_conflict") to ShiftCommandFailure.CONFLICT,
            problemResponse(409, "shift_already_active") to ShiftCommandFailure.ALREADY_ACTIVE,
        )
        h.routes.on("GET", "/shift") { jsonResponse(DriverShiftJson.state(active = DriverShiftJson.shift(id = "other", commandId = "other-phone"))) }
        cases.forEachIndexed { index, (response, failure) ->
            commandDao.all.forEach { commandDao.delete(it.commandId) }
            h.routes.on("POST", "/shift/start") { response }
            assertEquals(ShiftCommandResult.Failed(failure), repo.startShift(draft(commandId = "start-$index")))
        }
        // "Already active" reads the server's shift: the one started on the other phone is shown.
        assertEquals("other", repo.observeShift().first().id)
    }

    @Test
    fun `without driver access the start keeps waiting with its reason`() = runBlocking {
        h.routes.on("POST", "/shift/start") { problemResponse(403, "driver_access_required") }

        assertEquals(ShiftCommandResult.Saved(ShiftCommandFailure.ACCESS_REQUIRED), repo.startShift(draft()))

        val waiting = repo.observeCommands().first().single()
        assertEquals(ShiftCommandState.PENDING, waiting.state)
        assertEquals(ShiftCommandFailure.ACCESS_REQUIRED, waiting.failure)
    }

    @Test
    fun `an end never overtakes its start and refers to it`() = runBlocking {
        h.routes.on("POST", "/shift/start") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        repo.startShift(draft())
        h.clock.now = h.clock.now.plus(Duration.ofHours(10))

        assertEquals(ActionResult.Queued, repo.endShift())

        // Only the start was tried (again, first): the end waits behind it.
        val tried = h.server.drainRequests().map { it.route }
        assertTrue(tried.isNotEmpty() && tried.all { it == "POST /api/driver/v1/shift/start" })
        assertEquals(ShiftStatus.FINISHED, repo.observeShift().first().status)
        assertEquals(listOf(15L), dayWork.delayedRefreshes)

        h.routes.on("POST", "/shift/start") { startRecorded() }
        h.routes.on("POST", "/shift/end") {
            jsonResponse(DriverShiftJson.endResult(DriverShiftJson.shift(commandId = "start-1", endedAt = "2026-09-12T18:00:00Z", endedBy = "Driver")))
        }
        assertTrue(repo.sendPending())

        val requests = h.server.drainRequests()
        assertEquals(listOf("POST /api/driver/v1/shift/start", "POST /api/driver/v1/shift/end"), requests.map { it.route })
        val end = requests[1].jsonBody()
        assertEquals("end-1", end.getValue("commandId").jsonPrimitive.content)
        assertEquals("start-1", end.getValue("startCommandId").jsonPrimitive.content)
        assertFalse(end.containsKey("shiftId"))
        assertEquals("2026-09-12T18:00:00Z", end.getValue("endedAtUtc").jsonPrimitive.content)
        val shift = repo.observeShift().first()
        assertEquals(ShiftStatus.FINISHED, shift.status)
        assertEquals(SyncState.SYNCED, shift.syncState)
    }

    @Test
    fun `an ended shift cannot be ended again, a closed shift on the server is a final answer`() = runBlocking {
        assertEquals(ActionResult.Rejected(ActionRejection.NOT_ALLOWED), repo.endShift())

        h.routes.on("GET", "/shift") { jsonResponse(DriverShiftJson.state(active = DriverShiftJson.shift())) }
        assertEquals(RefreshResult.SUCCESS, repo.refresh())
        h.routes.on("POST", "/shift/end") { problemResponse(409, "shift_not_active") }
        h.routes.on("GET", "/shift") { jsonResponse(DriverShiftJson.state(lastEnded = DriverShiftJson.shift(endedAt = "2026-09-12T08:00:00Z", endedBy = "Office"))) }

        assertEquals(ActionResult.Rejected(ActionRejection.CONFLICT), repo.endShift())

        val end = h.server.drainRequests().first { it.route == "POST /api/driver/v1/shift/end" }.jsonBody()
        assertEquals("shift-9", end.getValue("shiftId").jsonPrimitive.content)
        assertEquals(ShiftStatus.FINISHED, repo.observeShift().first().status)
    }

    @Test
    fun `after a restart waiting commands are sent without the elapsed time`() = runBlocking {
        h.routes.on("POST", "/shift/start") { problemResponse(503, "service_unavailable") }
        repo.startShift(draft())
        h.server.drainRequests()

        h.uptime.boot = 8
        h.uptime.elapsedMillis = 10_000
        val restarted = newRepository()
        assertEquals(ShiftStatus.ACTIVE, restarted.observeShift().first().status)
        h.routes.on("POST", "/shift/start") { startRecorded() }

        assertTrue(restarted.sendPending())

        val body = h.server.drainRequests().single().jsonBody()
        assertFalse(body.containsKey("elapsedSinceStartMs"))
    }

    @Test
    fun `the server shift and planned vehicle are kept on the phone`() = runBlocking {
        h.routes.on("GET", "/shift") { jsonResponse(DriverShiftJson.state(active = DriverShiftJson.shift(defects = 2))) }

        assertEquals(RefreshResult.SUCCESS, repo.refresh())

        val offline = newRepository()
        val shift = offline.observeShift().first()
        assertEquals(ShiftStatus.ACTIVE, shift.status)
        assertEquals(VehicleIdentification.NFC, shift.identifiedBy)
        assertEquals(2, shift.vehicleCheck?.defectCount)
        assertEquals("WGM 1234A", shift.trailerPlate)
        assertEquals(VehicleInfo("veh-1", "WX 12345", trailerRegistrationNumber = "WGM 1234A"), offline.observePlannedVehicle().first())
    }

    @Test
    fun `a daily rest after the start ends the shift on the phone`() = runBlocking {
        h.routes.on("GET", "/shift") { jsonResponse(DriverShiftJson.state(active = DriverShiftJson.shift(startedAt = "2026-09-12T05:00:00Z"))) }
        h.routes.on("GET", "/driver-times") { jsonResponse(DriverShiftJson.resting(start = "2026-09-12T17:30:00Z", end = "2026-09-13T04:30:00Z")) }
        repo.refresh()
        assertEquals(RefreshResult.SUCCESS, times.refresh())

        val shift = repo.observeShift().first()
        assertEquals(ShiftStatus.FINISHED, shift.status)
        assertTrue(shift.endedByTachograph)
        assertEquals(Instant.parse("2026-09-12T17:30:00Z"), shift.endedAt)
    }

    @Test
    fun `another driver on this phone removes the earlier driver's commands and photos`() = runBlocking {
        h.routes.on("POST", "/shift/start") { problemResponse(503, "service_unavailable") }
        val defect = photo("check_1.png")
        repo.startShift(draft(defectPhoto = defect))

        repo.keepOnly("driver-2")

        assertTrue(commandDao.all.isEmpty())
        assertTrue(photoDao.all.isEmpty())
        assertFalse(defect.exists())
    }

    @Test
    fun `the planned vehicle can be chosen when the tag does not read`() = runBlocking {
        h.routes.on("POST", "/shift/start") { startRecorded() }

        repo.startShift(draft(tagUid = null, manual = VehicleInfo("veh-1", "WX 12345")))

        val body = h.server.drainRequests().single().jsonBody()
        assertFalse(body.containsKey("vehicleTagUid"))
        assertEquals("veh-1", body.getValue("manualVehicleId").jsonPrimitive.content)
        assertEquals(VehicleIdentification.MANUAL, commandDao.all.single().identifiedBy)
    }

    @Test
    fun `a server without the shift endpoints shows the shift as not connected`() = runBlocking {
        h.routes.on("GET", "/shift") { problemResponse(404, "request_failed") }

        assertEquals(RefreshResult.NOT_SUPPORTED, repo.refresh())

        assertEquals(ShiftStatus.UNAVAILABLE, repo.observeShift().first().status)
        assertFalse(repo.currentShift().startRequired)
        h.routes.on("GET", "/shift") { jsonResponse(DriverShiftJson.state()) }
        assertEquals(RefreshResult.SUCCESS, repo.refresh())
        assertEquals(ShiftStatus.NOT_STARTED, repo.observeShift().first().status)
    }

    @Test
    fun `an incomplete check is never stored`() = runBlocking {
        val incomplete = draft().let { it.copy(answers = it.answers.drop(1)) }
        assertEquals(ShiftCommandResult.NotAllowed(ActionRejection.NOT_ALLOWED), repo.startShift(incomplete))
        assertTrue(commandDao.all.isEmpty())
        assertTrue(h.server.drainRequests().isEmpty())
    }

    @Test
    fun `a photo the server does not take is not retried, a lost connection is`() = runBlocking {
        h.routes.on("POST", "/shift/start") { startRecorded(defects = 1) }
        h.routes.on("POST", "/vehicle-checks/check-1/photos") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        val defect = photo("check_1.png")

        assertEquals(ShiftCommandResult.Recorded, repo.startShift(draft(defectPhoto = defect)))
        assertEquals(1, repo.observeCommands().first().single().photosWaiting)
        assertTrue(defect.exists())

        h.routes.on("POST", "/vehicle-checks/check-1/photos") { problemResponse(400, "photo_unexpected") }
        assertTrue(repo.sendPending())
        assertEquals(1, repo.observeCommands().first().single().photosFailed)
        assertFalse(defect.exists())
    }

    @Test
    fun `tachograph summary, tag lookup and check list follow the contract`() = runBlocking {
        h.routes.on("GET", "/driver-times") { jsonResponse(DriverShiftJson.resting(start = "2026-09-11T20:40:00Z", end = "2026-09-12T05:40:00Z")) }
        assertEquals(RefreshResult.SUCCESS, times.refresh())
        val rest = times.current()!!.restingRest!!
        assertEquals(Instant.parse("2026-09-12T05:40:00Z"), rest.endsAt)
        assertEquals(Duration.ofMinutes(540), rest.minimumDuration)

        h.routes.on("GET", "/driver-times") { jsonResponse("""{"status":"NotLinked"}""") }
        times.refresh()
        assertNull(times.current()!!.restingRest)
        h.routes.on("GET", "/driver-times") { problemResponse(404, "request_failed") }
        assertEquals(RefreshResult.NOT_SUPPORTED, times.refresh())

        h.routes.on("GET", "/vehicle-tags/$tag") { jsonResponse("""{"vehicleId":"veh-1","registrationNumber":"WX 12345","displayName":"DAF XF 480","isPlannedVehicle":false}""") }
        assertEquals(VehicleLookup.Found(VehicleInfo("veh-1", "WX 12345", "DAF XF 480"), isPlanned = false), checks.lookupVehicle(tag))
        h.routes.on("GET", "/vehicle-tags/$tag") { problemResponse(404, "vehicle_tag_unknown") }
        assertEquals(VehicleLookup.UnknownTag, checks.lookupVehicle(tag))
        h.routes.on("GET", "/vehicle-tags/$tag") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        assertEquals(VehicleLookup.Unverified, checks.lookupVehicle(tag))

        assertEquals(CheckTemplate.Default, checks.observeTemplate().first())
        h.routes.on("GET", "/vehicle-check/template") {
            jsonResponse("""{"templateId":"tpl-2","version":2,"items":[{"key":"tyres","titles":{"en":"Tyres","tr":"Lastikler"},"photoRequiredOnDefect":true}]}""")
        }
        assertEquals(RefreshResult.SUCCESS, checks.refreshTemplate())
        assertEquals("tpl-2", checks.observeTemplate().first().templateId)
        // A list without an English title is not used; the phone keeps the one it has.
        h.routes.on("GET", "/vehicle-check/template") { jsonResponse("""{"templateId":"tpl-3","version":3,"items":[{"key":"tyres","titles":{"tr":"Lastikler"}}]}""") }
        assertEquals(RefreshResult.FAILED, checks.refreshTemplate())
        assertEquals("tpl-2", checks.observeTemplate().first().templateId)
    }

    @Test
    fun `an end waits for its start also after the start was rejected, then gets the server's answer`() = runBlocking {
        h.routes.on("POST", "/shift/start") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        repo.startShift(draft())
        repo.endShift()
        h.routes.on("POST", "/shift/start") { problemResponse(400, "vehicle_tag_unknown") }
        h.routes.on("POST", "/shift/end") { problemResponse(409, "shift_not_active") }
        h.routes.on("GET", "/shift") { jsonResponse(DriverShiftJson.state()) }

        assertTrue(repo.sendPending())

        val commands = repo.observeCommands().first()
        assertEquals(ShiftCommandFailure.TAG_UNKNOWN, commands.single { it.type == ShiftCommandType.START }.failure)
        assertEquals(ShiftCommandFailure.NOT_ACTIVE, commands.single { it.type == ShiftCommandType.END }.failure)
        assertEquals(ShiftStatus.NOT_STARTED, repo.observeShift().first().status)
    }
}
