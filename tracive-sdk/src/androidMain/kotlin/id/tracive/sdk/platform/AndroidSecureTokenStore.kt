package id.tracive.sdk.platform

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import id.tracive.sdk.TraciveAndroid
import id.tracive.sdk.TraciveException
import id.tracive.sdk.oauth.PendingAuthorization
import id.tracive.sdk.oauth.PendingAuthorizationCodec
import id.tracive.sdk.oauth.TokenSet
import id.tracive.sdk.oauth.TokenSetCodec
import id.tracive.sdk.oauth.TokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Android token store: AES-256-GCM under a key that never leaves the Android Keystore.
 *
 * The grant is serialized to JSON, encrypted, and the ciphertext is what lands in
 * `SharedPreferences`. The preference file is only a container — the key material lives in the
 * Keystore, backed by the StrongBox or TEE where the device has one, and is not extractable by this
 * process or any other. That is the difference between "stored in SharedPreferences", which this SDK
 * does not permit, and this: an attacker with the preference file has ciphertext and nothing else.
 *
 * The refresh token is the reason the bar is this high. It is long-lived, it rotates, and the
 * server treats a *reused* one as evidence of theft by revoking the whole grant — so a leaked copy
 * costs the user their session even if the thief never spends it.
 *
 * The pending authorization is kept the same way, in a second entry under the same Keystore key. It
 * carries a PKCE verifier, which is the value that makes a returned authorization code redeemable,
 * so nothing about it may be written in the clear either.
 *
 * Every operation runs off the caller's thread: Keystore work involves IPC to `keystore2` and
 * occasionally hardware, neither of which belongs on a UI thread.
 */
internal class AndroidKeystoreTokenStore(context: Context) : TokenStore {

  private val preferences: SharedPreferences =
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  /** Serializes Keystore access: two concurrent key creations would race to the same alias. */
  private val mutex = Mutex()

  override suspend fun load(): TokenSet? =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val blob = preferences.getString(PREFERENCE_KEY, null) ?: return@withLock null
        val plaintext = decrypt(blob)
        if (plaintext == null) {
          // Unreadable: the Keystore key was invalidated (a backup restore, a factory reset of the
          // secure hardware, a lock-screen change), or the blob is corrupt. Either way the grant is
          // gone. Drop it rather than leaving a value that can only ever fail again.
          preferences.edit().remove(PREFERENCE_KEY).apply()
          return@withLock null
        }
        TokenSetCodec.decode(plaintext)
      }
    }

  override suspend fun save(tokens: TokenSet) {
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val blob = encrypt(TokenSetCodec.encode(tokens))
        // commit(), not apply(): the caller has just spent a rotating refresh token, and losing the
        // replacement to a process death would leave this device holding a token the server has
        // already retired — which its reuse detection reads as theft. commit() also *reports*
        // whether the write reached disk, which apply() cannot, and that report is the only
        // difference between failing now and failing later with the whole grant revoked.
        val stored = preferences.edit().putString(PREFERENCE_KEY, blob).commit()
        if (!stored) {
          throw TraciveException.TokenStorageFailed(
            message = "The Tracive SDK could not write the authorization grant to disk."
          )
        }
      }
    }
  }

  override suspend fun clear() {
    withContext(Dispatchers.IO) {
      mutex.withLock {
        preferences.edit().remove(PREFERENCE_KEY).remove(PENDING_KEY).commit()
        try {
          keyStore().deleteEntry(KEY_ALIAS)
        } catch (_: Throwable) {
          // A missing key is the desired end state; the ciphertext is already gone regardless.
        }
      }
    }
  }

  override suspend fun loadPending(): PendingAuthorization? =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val blob = preferences.getString(PENDING_KEY, null) ?: return@withLock null
        val plaintext = decrypt(blob)
        if (plaintext == null) {
          // Same reasoning as the grant: an unreadable blob can only ever fail again, so drop it
          // rather than keep answering a question with a value nothing can use.
          preferences.edit().remove(PENDING_KEY).apply()
          return@withLock null
        }
        PendingAuthorizationCodec.decode(plaintext)
      }
    }

  override suspend fun savePending(pending: PendingAuthorization?) {
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val editor = preferences.edit()
        if (pending == null) {
          editor.remove(PENDING_KEY)
        } else {
          editor.putString(PENDING_KEY, encrypt(PendingAuthorizationCodec.encode(pending)))
        }
        // commit(), not apply(), for the same reason the grant uses it — more sharply here. This
        // record exists to survive a process death that can happen the instant the browser takes
        // the foreground, and apply() writes on a background thread that such a death would take
        // with it. commit() also reports whether the write landed, which is the only way the caller
        // can know its recovery guarantee is real.
        val stored = editor.commit()
        if (!stored) {
          throw TraciveException.TokenStorageFailed(
            message = "The Tracive SDK could not write the pending authorization to disk."
          )
        }
      }
    }
  }

  private fun encrypt(plaintext: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    // No IV is supplied: a Keystore key requires randomized encryption, and letting the provider
    // generate the nonce is what guarantees it is never repeated under this key.
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val nonce = cipher.iv
    val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
    return Base64Url.encode(nonce + ciphertext)
  }

  private fun decrypt(blob: String): String? =
    try {
      val bytes = Base64Url.decode(blob)
      if (bytes == null || bytes.size <= NONCE_BYTES) {
        null
      } else {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
          Cipher.DECRYPT_MODE,
          secretKey(),
          GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES),
        )
        cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES).decodeToString()
      }
    } catch (_: Throwable) {
      // Includes AEADBadTagException — a tampered or truncated blob. Never distinguish the reasons
      // to a caller: "unreadable" is the only fact that matters and the only one safe to report.
      null
    }

  private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

  @Suppress("NewApi")
  private fun secretKey(): SecretKey {
    val store = keyStore()
    (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let {
      return it
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(KEY_BITS)
        // Deliberately not user-authentication-bound. A grant that needs the lock screen every
        // hour, at refresh time, would fail in the background and log the user out of the partner
        // app for reasons they never see.
        .build()
    )
    return generator.generateKey()
  }

  override fun toString(): String = "AndroidKeystoreTokenStore"

  private companion object {
    const val PREFERENCES_NAME = "id.tracive.sdk.tokens"
    const val PREFERENCE_KEY = "grant.v1"

    /**
     * The in-flight authorization, under the same Keystore key as the grant.
     *
     * A separate entry rather than a field inside the grant blob: the two have unrelated lifetimes,
     * and a pending record is written and deleted several times over the life of one grant.
     */
    const val PENDING_KEY = "pending.v1"
    const val KEY_ALIAS = "id.tracive.sdk.token_key.v1"
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val TAG_BITS = 128
    const val NONCE_BYTES = 12
    const val KEY_BITS = 256
  }
}

/**
 * The Android Keystore-backed token store.
 *
 * The Keystore can hold an AES key from API 23 onwards, and this SDK's `minSdk` is 24 — so there is
 * no runtime level check here, because there is no level it could reject. That floor is not
 * negotiable downwards: there is no plaintext fallback, since storing a refresh token in the clear
 * to support an older device would put every user's grant at the mercy of any app with file access
 * on it. Lowering `android-minSdk` below 24 means restoring a guard here first.
 *
 * @throws IllegalStateException if the SDK has no application `Context` (see [TraciveAndroid]).
 */
actual fun SecureTokenStore(): TokenStore =
  AndroidKeystoreTokenStore(TraciveAndroid.requireContext())
