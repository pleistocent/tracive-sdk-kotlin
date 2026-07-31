package id.tracive.sdk.sample

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import id.tracive.sdk.cache.CacheDataKey
import id.tracive.sdk.cache.CacheDataKeyStore
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [CacheDataKeyStore] backed by the Android Keystore — the reference implementation a partner app
 * is expected to copy.
 *
 * ### Why the app supplies this rather than the SDK
 *
 * The SDK's cache cipher is common Kotlin so that a record written on Android opens byte-for-byte
 * on iOS. That leaves exactly one thing the platform must do, and it is this one: produce key
 * material from a real entropy source and keep it somewhere the operating system protects.
 *
 * ### The wrapping pattern, and why the raw key is not in the Keystore itself
 *
 * A Keystore key is deliberately non-exportable — you can ask it to encrypt, never to hand over its
 * bytes. The SDK's cipher is software AES and needs the 32 bytes. So the key kept in the Keystore
 * is a *wrapping* key, and what lands on disk is the cache's data key sealed under it:
 *
 * ```
 * data key (32 random bytes, in memory only)
 *   └─ AES-256-GCM ──> wrapped blob in the app's private files directory
 *        under a non-exportable AndroidKeystore key
 * ```
 *
 * The file is worthless on its own. Extracting the data key means executing code as this app on
 * this device, because the wrapping key cannot leave the Keystore — on a device with a TEE or a
 * StrongBox it cannot leave the secure hardware at all. That is the same protection level as
 * putting the raw key in the Keystore would have given, and it is the only shape that works with a
 * software cipher.
 *
 * ### The failure this is designed around
 *
 * [loadKey] returns null rather than throwing when the wrapping key is gone — an OS keystore reset,
 * a restore onto new hardware, a lock-screen change on some devices. The SDK treats that as an
 * empty cache, drops the unreadable rows and starts over with a fresh key. The user is never shown
 * an error; it costs one re-fetch.
 */
class KeystoreCacheDataKeyStore(context: Context) : CacheDataKeyStore {

  private val appContext = context.applicationContext
  private val directory = File(appContext.filesDir, "tracive-cache-keys")

  override suspend fun createKey(): CacheDataKey =
    withContext(Dispatchers.IO) {
      val raw = ByteArray(CacheDataKey.KEY_SIZE)
      // Never kotlin.random.Random: it is not a cryptographically secure source, and a key drawn
      // from one looks exactly like a real key right up until someone reproduces it.
      SecureRandom().nextBytes(raw)

      val id = "cache-key-" + System.currentTimeMillis() + "-" + SecureRandom().nextInt(Int.MAX_VALUE)
      directory.mkdirs()
      fileFor(id).writeBytes(wrap(raw))

      CacheDataKey(id, raw)
    }

  override suspend fun loadKey(id: String): CacheDataKey? =
    withContext(Dispatchers.IO) {
      val file = fileFor(id)
      if (!file.exists()) return@withContext null
      val raw = unwrap(file.readBytes()) ?: return@withContext null
      if (raw.size != CacheDataKey.KEY_SIZE) null else CacheDataKey(id, raw)
    }

  override suspend fun deleteKey(id: String) {
    withContext(Dispatchers.IO) { fileFor(id).delete() }
  }

  private fun fileFor(id: String): File {
    // The id is generated here and never comes from outside, but it does become a path segment, so
    // it is constrained rather than trusted.
    require(id.all { it.isLetterOrDigit() || it == '-' }) { "Unexpected data key id." }
    return File(directory, "$id.bin")
  }

  private fun wrap(raw: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    // No IV is supplied: the Keystore generates one, and reusing an IV with a Keystore key is an
    // error it will not let you make.
    cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
    val iv = cipher.iv
    val sealed = cipher.doFinal(raw)
    return byteArrayOf(iv.size.toByte()) + iv + sealed
  }

  private fun unwrap(blob: ByteArray): ByteArray? =
    try {
      val ivLength = blob[0].toInt()
      val iv = blob.copyOfRange(1, 1 + ivLength)
      val sealed = blob.copyOfRange(1 + ivLength, blob.size)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, iv))
      cipher.doFinal(sealed)
    } catch (_: Exception) {
      // Deliberately broad. An invalidated key, a truncated file and a failed tag check all mean
      // the same thing to the caller — the key is unavailable — and the SDK already handles that
      // as an empty cache. Distinguishing them here would only produce error text nobody can act
      // on.
      null
    }

  private fun wrappingKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getEntry(WRAPPING_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
      return it.secretKey
    }

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(
          WRAPPING_KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        // No `setUserAuthenticationRequired(true)`: the cache is read on a background thread with
        // no UI attached, so requiring a biometric prompt would fail every read that happens
        // outside a foreground screen. Tie authentication to the *tokens* if you need it, not to
        // the cache of results the user already saw.
        .build()
    )
    return generator.generateKey()
  }

  private companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val WRAPPING_KEY_ALIAS = "id.tracive.sdk.sample.cacheWrappingKey"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val TAG_BITS = 128
  }
}
