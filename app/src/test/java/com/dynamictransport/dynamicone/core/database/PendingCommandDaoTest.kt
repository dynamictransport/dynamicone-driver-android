package com.dynamictransport.dynamicone.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PendingCommandDaoTest {

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = database.pendingCommandDao()

    @After
    fun tearDown() = database.close()

    private fun command(
        id: String,
        kind: PendingCommandKind,
        tripId: String?,
        driverId: String = "driver-1",
        state: PendingCommandState = PendingCommandState.SENDING,
    ) = PendingCommandEntity(id, driverId, kind, tripId, "{}", state, Instant.parse("2026-09-12T08:00:00Z"), Instant.parse("2026-09-12T08:00:00Z"))

    @Test
    fun `trip actions and conversations are selected separately`() = runBlocking {
        dao.insert(command("a1", PendingCommandKind.TRIP_ACTION, "t1"))
        dao.insert(command("a2", PendingCommandKind.TRIP_ACTION, "t2"))
        dao.insert(command("m-general", PendingCommandKind.MESSAGE, null))
        dao.insert(command("m-trip", PendingCommandKind.MESSAGE, "t1", state = PendingCommandState.RECORDED))

        assertEquals(listOf("a1"), dao.observeTripActions("t1").first().map { it.commandId })
        assertEquals(listOf("m-general"), dao.observeMessages(null).first().map { it.commandId })
        assertEquals(listOf("m-trip"), dao.observeMessages("t1").first().map { it.commandId })
        assertEquals(3, dao.observeUnconfirmedCount().first())

        dao.updateState("a1", PendingCommandState.OUTCOME_UNKNOWN, Instant.parse("2026-09-12T08:01:00Z"))
        assertEquals(PendingCommandState.OUTCOME_UNKNOWN, dao.get("a1")?.state)
        dao.deleteTripActions("t1")
        dao.delete(listOf("m-trip"))
        assertEquals(listOf("a2", "m-general"), listOfNotNull(dao.get("a1"), dao.get("a2"), dao.get("m-general"), dao.get("m-trip")).map { it.commandId })
    }

    @Test
    fun `commands of other drivers are removed`() = runBlocking {
        dao.insert(command("own", PendingCommandKind.MESSAGE, null, driverId = "driver-1"))
        dao.insert(command("other", PendingCommandKind.MESSAGE, null, driverId = "driver-2"))

        dao.deleteForOtherDrivers("driver-1")

        assertEquals(listOf("own"), dao.observeMessages(null).first().map { it.commandId })
        dao.deleteAll()
        assertTrue(dao.observeMessages(null).first().isEmpty())
    }
}

/**
 * Builds a real version 1 database from the exported schema (app/schemas) and opens it with the current
 * Room database, which runs the auto-migration and validates the resulting schema.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun `version 1 keeps demo documents under the Driver API categories and drops the earnings cache`() {
        createVersion1 { db ->
            listOf("d1" to "A1", "d2" to "INTERCHANGE_EIR", "d3" to "POD", "d4" to "CMR").forEach { (id, type) ->
                db.execSQL(
                    "INSERT INTO documents (localId, tripId, type, clientActionId, localFilePath, fileName, contentType, capturedAt, " +
                        "containerNo, sealNo, remoteId, remoteStatus, thumbnailUrl, syncState) " +
                        "VALUES ('$id', 't1', '$type', 'c-$id', NULL, '$id.jpg', 'image/jpeg', NULL, NULL, NULL, NULL, NULL, NULL, 'PENDING')",
                )
            }
            db.execSQL(
                "INSERT INTO earnings (month, currency, baseAmount, bonuses, deductions, total, workDays, fetchedAt) " +
                    "VALUES ('2026-09', 'EUR', '1', '0', '0', '1', 1, 0)",
            )
        }

        // WAL cannot be switched on for this file under Robolectric on Windows; the journal mode does not affect migrations.
        val room = Room.databaseBuilder(context, AppDatabase::class.java, NAME)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            db.query("SELECT localId, type FROM documents ORDER BY localId").use { cursor ->
                val types = buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
                assertEquals(listOf("d1" to "CUSTOMS", "d2" to "INTERCHANGE", "d3" to "OTHER", "d4" to "CMR"), types)
            }
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'earnings'").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
            assertEquals(8, db.version)
            assertTrue(runBlocking { room.pendingCommandDao().observeMessages(null).first() }.isEmpty())
            // v4: the driver daily shift tables (docs/16) exist and start empty; v5: personnel document signatures (docs/17);
            // v6: road snapshot requests (docs/24); v7: wake-up calls (docs/25).
            listOf(
                "shift_commands", "check_photos", "driver_cache", "hr_signatures", "hr_document_cache", "snapshot_requests", "wake_up_calls",
            ).forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use {
                    it.moveToFirst()
                    assertEquals(0, it.getInt(0))
                }
            }
        } finally {
            room.close()
        }
    }

    private fun createVersion1(seed: (SQLiteDatabase) -> Unit) {
        val schemaFile = listOf("schemas", "app/schemas")
            .map { File(it, "com.dynamictransport.dynamicone.core.database.AppDatabase/1.json") }
            .first { it.exists() }
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(NAME).apply {
            parentFile?.mkdirs()
            delete()
        }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schema.getValue("entities").jsonArray.forEach { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = 1
            seed(db)
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
