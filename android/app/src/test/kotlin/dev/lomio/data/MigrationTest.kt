package dev.lomio.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lomio.data.db.LomioDatabase
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds a version-1 database from the committed schema JSON (the exact SQL Room 2.8 generated
 * for v1), fills it with a linear chat, runs every migration (v2: message tree, v3: vision
 * columns) and opens it with Room, which validates every table against the current entities.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val name = "migration-test.db"

  private fun createV1() {
    context.deleteDatabase(name)
    val schema = Json.parseToJsonElement(File("schemas/dev.lomio.data.db.LomioDatabase/1.json").readText()).jsonObject
    val database = schema["database"]!!.jsonObject
    val sql = buildList {
      database["entities"]!!.jsonArray.forEach { e ->
        val o = e.jsonObject
        val table = o["tableName"]!!.jsonPrimitive.content
        fun list(key: String) = (o[key]?.jsonArray ?: emptyList())
        add(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
        list("indices").forEach { add((it as JsonObject)["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        list("contentSyncTriggers").forEach { add(it.jsonPrimitive.content) }
      }
      database["setupQueries"]!!.jsonArray.forEach { add(it.jsonPrimitive.content) }
    }
    val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
    SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
      sql.forEach(db::execSQL)
      db.execSQL("INSERT INTO conversation (id, title, pinned, created_at, updated_at) VALUES ('c1', 'Trip', 0, 1, 4)")
      db.execSQL("INSERT INTO conversation (id, title, pinned, created_at, updated_at) VALUES ('c2', 'Empty', 0, 1, 1)")
      for ((i, role) in listOf("user", "assistant", "user", "assistant").withIndex()) {
        db.execSQL(
          "INSERT INTO message (id, conversation_id, role, content, status, created_at) " +
            "VALUES ('m$i', 'c1', '$role', 'text $i about kyoto', 'COMPLETE', ${i + 1})"
        )
      }
      db.version = 1
    }
  }

  @Test
  fun v1LinearHistoryBecomesASingleBranch() = runTest {
    createV1()
    val db =
      Room.databaseBuilder(context, LomioDatabase::class.java, name)
        .addMigrations(*LomioDatabase.MIGRATIONS)
        .allowMainThreadQueries()
        .build()
    try {
      val chats = ChatRepository(db)
      // Room validated the migrated schema when it opened; now check the data.
      assertEquals(listOf("m0", "m1", "m2", "m3"), chats.messages("c1").map { it.id })
      assertEquals(listOf(null, "m0", "m1", "m2"), chats.allMessages("c1").map { it.parentId })
      assertEquals("m3", chats.conversation("c1")?.leafId)
      assertEquals(null, chats.conversation("c2")?.leafId)
      // Full-text search still works on migrated rows.
      assertEquals(4, chats.searchMessages("kyoto").first().size)
      // v3: no projector and no attachments on old rows.
      assertEquals(null, db.messages().get("m0")!!.attachments)
    } finally {
      db.close()
      context.deleteDatabase(name)
    }
  }
}
