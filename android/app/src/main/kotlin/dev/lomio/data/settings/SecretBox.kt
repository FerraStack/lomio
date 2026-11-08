package dev.lomio.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (the Hugging Face token) before they are written to disk. */
interface SecretBox {
  fun seal(plain: String): String

  /** Null when the ciphertext can't be opened (key gone after a restore, corrupt data). */
  fun open(sealed: String): String?
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore. Output is
 * `base64(iv ‖ ciphertext+tag)`. The key never leaves the Keystore (hardware-backed where the
 * device has it), so a copy of the app's files alone can't reveal the token.
 */
class KeystoreSecretBox(private val alias: String = "lomio_secrets_v1") : SecretBox {
  private fun key(): SecretKey {
    val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (ks.getKey(alias, null) as? SecretKey)?.let { return it }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    gen.init(
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return gen.generateKey()
  }

  override fun seal(plain: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
    val body = cipher.doFinal(plain.toByteArray())
    return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
  }

  override fun open(sealed: String): String? =
    runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
      }
      .getOrNull()

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
  }
}
