package dev.lomio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

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
  version = 1,
  exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LomioDatabase : RoomDatabase() {
  abstract fun models(): ModelDao

  abstract fun conversations(): ConversationDao

  abstract fun messages(): MessageDao

  companion object {
    fun create(context: Context): LomioDatabase =
      Room.databaseBuilder(context, LomioDatabase::class.java, "lomio.db").build()

    fun inMemory(context: Context): LomioDatabase =
      Room.inMemoryDatabaseBuilder(context, LomioDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }
}
