package dev.lomio.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * v2: messages become a tree (regenerate and edit add versions instead of deleting history).
 * History so far is linear, so each message's parent is the one before it, and the newest
 * message is the leaf.
 */
val MIGRATION_1_2 =
  object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE conversation ADD COLUMN leaf_id TEXT")
      db.execSQL(
        """
        UPDATE message SET parent_id = (
          SELECT p.id FROM message p
          WHERE p.conversation_id = message.conversation_id
            AND (p.created_at < message.created_at OR (p.created_at = message.created_at AND p.rowid < message.rowid))
          ORDER BY p.created_at DESC, p.rowid DESC LIMIT 1)
        WHERE parent_id IS NULL
        """
      )
      db.execSQL(
        """
        UPDATE conversation SET leaf_id = (
          SELECT m.id FROM message m WHERE m.conversation_id = conversation.id
          ORDER BY m.created_at DESC, m.rowid DESC LIMIT 1)
        """
      )
      db.execSQL("CREATE INDEX IF NOT EXISTS index_message_parent_id ON message (parent_id)")
    }
  }

class Converters {
  @TypeConverter fun modelSource(v: ModelSource): String = v.name

  @TypeConverter fun modelSource(v: String): ModelSource = ModelSource.valueOf(v)

  @TypeConverter fun modelState(v: ModelState): String = v.name

  @TypeConverter fun modelState(v: String): ModelState = ModelState.valueOf(v)

  @TypeConverter fun role(v: MessageRole): String = v.wire

  @TypeConverter
  fun role(v: String): MessageRole = MessageRole.entries.first { it.wire == v }

  @TypeConverter fun status(v: MessageStatus): String = v.name

  @TypeConverter fun status(v: String): MessageStatus = MessageStatus.valueOf(v)
}

@Database(
  entities =
    [
      ModelEntity::class,
      ModelSettingsEntity::class,
      ConversationEntity::class,
      MessageEntity::class,
      MessageFts::class,
    ],
  version = 2,
  exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LomioDatabase : RoomDatabase() {
  abstract fun models(): ModelDao

  abstract fun conversations(): ConversationDao

  abstract fun messages(): MessageDao

  companion object {
    fun create(context: Context): LomioDatabase =
      Room.databaseBuilder(context, LomioDatabase::class.java, "lomio.db").addMigrations(MIGRATION_1_2).build()

    fun inMemory(context: Context): LomioDatabase =
      Room.inMemoryDatabaseBuilder(context, LomioDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }
}
