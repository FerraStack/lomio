package dev.lomio.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lomio.data.db.LomioDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration scaffold. The exported Room schema (app/schemas, committed) must match the entities
 * in code: if this fails, you changed the schema without bumping the database version and
 * exporting a new JSON. When version 2 exists, add a MigrationTestHelper test here that creates
 * the DB at version 1 from the committed JSON and runs the migration.
 */
@RunWith(AndroidJUnit4::class)
class SchemaTest {
  private val version = 1
  private val schema = File("schemas/dev.lomio.data.db.LomioDatabase/$version.json")

  @Test
  fun committedSchemaMatchesCode() {
    assumeTrue("schema not exported yet: ${schema.absolutePath}", schema.exists())
    val json = schema.readText()
    val committedHash = Regex("\"identityHash\":\\s*\"([0-9a-f]+)\"").find(json)!!.groupValues[1]

    val db = LomioDatabase.inMemory(ApplicationProvider.getApplicationContext())
    try {
      db.openHelper.writableDatabase.query("SELECT identity_hash FROM room_master_table").use { c ->
        c.moveToFirst()
        assertEquals("Room schema changed without a version bump", committedHash, c.getString(0))
      }
      assertEquals(version, db.openHelper.writableDatabase.version)
    } finally {
      db.close()
    }
  }
}
