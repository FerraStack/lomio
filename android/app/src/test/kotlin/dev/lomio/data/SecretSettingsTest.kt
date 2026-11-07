package dev.lomio.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.lomio.data.settings.SecretBox
import dev.lomio.data.settings.SettingsRepository
import java.io.File
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The HF token reaches disk only as ciphertext (issue #5); the Keystore itself is tested on the emulator. */
class SecretSettingsTest {
  @get:Rule val tmp = TemporaryFolder()

  /** Stand-in for the Keystore box: reversible, but never the plain text. */
  private class FakeBox : SecretBox {
    var seals = 0

    override fun seal(plain: String): String {
      seals++
      return Base64.getEncoder().encodeToString(plain.reversed().toByteArray())
    }

    override fun open(sealed: String): String? =
      runCatching { String(Base64.getDecoder().decode(sealed)).reversed() }.getOrNull()
  }

  private val token = "hf_SECRETsecret123"

  @Test
  fun tokenIsStoredSealedAndReadBack() = runTest {
    val file = File(tmp.root, "s.preferences_pb")
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
    val box = FakeBox()
    val repo = SettingsRepository(store, box)
    repo.update { it.copy(hfToken = token) }
    assertEquals(token, repo.current().hfToken)
    assertFalse("plain token on disk", file.readBytes().decodeToString().contains(token))
    // Other settings changes don't re-encrypt an unchanged token.
    repo.update { it.copy(showStats = false) }
    assertEquals(1, box.seals)
    // Clearing removes it.
    repo.update { it.copy(hfToken = "") }
    assertEquals("", repo.current().hfToken)
    assertNull(store.data.first()[stringPreferencesKey("hf_token_sealed")])
  }

  @Test
  fun legacyPlaintextTokenIsMigrated() = runTest {
    val file = File(tmp.root, "legacy.preferences_pb")
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
    store.edit { it[stringPreferencesKey("hf_token")] = token }
    val repo = SettingsRepository(store, FakeBox())
    assertEquals(token, repo.current().hfToken)
    repo.migrateSecrets()
    assertNull(store.data.first()[stringPreferencesKey("hf_token")])
    assertEquals(token, repo.current().hfToken)
    assertFalse(file.readBytes().decodeToString().contains(token))
  }

  @Test
  fun unreadableCiphertextMeansNoToken() = runTest {
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "bad.preferences_pb") }
    store.edit { it[stringPreferencesKey("hf_token_sealed")] = "%%%not base64%%%" }
    assertEquals("", SettingsRepository(store, FakeBox()).current().hfToken)
  }
}
