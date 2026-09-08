package id.tracive.sdk.sample

import id.tracive.sdk.cache.CacheDataKey
import id.tracive.sdk.cache.CacheDataKeyStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithBytes
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * A [CacheDataKeyStore] backed by the iOS Keychain — the reference implementation a partner app is
 * expected to copy.
 *
 * ### Why this is so much shorter than its Android counterpart
 *
 * `KeystoreCacheDataKeyStore` in the Android sample wraps the data key under a second,
 * non-exportable Keystore key and writes the sealed blob to a file. It has to: an Android Keystore
 * key can be asked to encrypt but never to hand over its bytes, and the SDK's cache cipher is
 * software AES that needs the 32 bytes.
 *
 * The iOS Keychain has no such restriction. A generic-password item stores arbitrary bytes and
 * gives them back, so the data key itself is the item and there is nothing to wrap. Same protection
 * level, half the machinery — and on a Secure-Enclave device the class key protecting the item is
 * enclave-backed, which is the platform's doing rather than something to opt into.
 *
 * ### The two attribute choices that carry weight
 *
 * - **`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.** `AfterFirstUnlock`, not `WhenUnlocked`,
 *   because the cache is read from background work; `WhenUnlocked` would fail those reads on a
 *   locked device and silently turn every one into a billed re-fetch. `ThisDeviceOnly` keeps the
 *   key out of iCloud Keychain and encrypted backups — a cache restored onto a second device would
 *   carry another user's results with it.
 * - **No `kSecAccessControl` with biometry.** Same reason as the Android sample: a cache read that
 *   needs a Face ID prompt fails whenever no UI is in front of it. Gate the *tokens* on
 *   authentication if you need it, not the cache of results the user has already seen.
 *
 * ### The failure this is designed around
 *
 * [loadKey] returns null rather than throwing when the item is gone — a restore onto new hardware,
 * a keychain reset. The SDK treats that as an empty cache, drops the rows it can no longer read and
 * starts over with a fresh key. The user sees nothing; it costs one re-fetch.
 *
 * Plain CoreFoundation throughout, with no `NSData` bridging, for the same reason the SDK's own
 * `IosKeychainTokenStore` does it that way: an `as NSData` cast is one the Kotlin/Native compiler
 * reports as never succeeding, and key storage is the wrong place to find that out.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KeychainCacheDataKeyStore(private val service: String = DEFAULT_SERVICE) :
  CacheDataKeyStore {

  override suspend fun createKey(): CacheDataKey {
    val raw = ByteArray(CacheDataKey.KEY_SIZE)

    // SecRandomCopyBytes, never kotlin.random.Random: the latter is not a cryptographically secure
    // source, and a key drawn from one looks exactly like a real key right up until someone
    // reproduces it. A refusal here is not recoverable — a key that is not random is worse than no
    // encryption, because it still looks encrypted.
    val drawn = raw.usePinned {
      SecRandomCopyBytes(kSecRandomDefault, raw.size.convert(), it.addressOf(0))
    }
    check(drawn == errSecSuccess) { "The system refused to generate cache key material ($drawn)." }

    val id = "cache-key-" + randomHex()
    val status = insert(id, raw)
    check(status == errSecSuccess) { "The Keychain refused to store the cache data key ($status)." }

    return CacheDataKey(id, raw)
  }

  override suspend fun loadKey(id: String): CacheDataKey? {
    val raw = read(id) ?: return null
    // A wrong-sized item is not this store's key — a leftover from another format, or truncated.
    // Null means "start over", which is the recoverable answer; CacheDataKey would throw instead.
    return if (raw.size != CacheDataKey.KEY_SIZE) null else CacheDataKey(id, raw)
  }

  override suspend fun deleteKey(id: String) {
    val query = identity(id)
    // The status is deliberately ignored: errSecItemNotFound is the desired end state, not an
    // error.
    SecItemDelete(query)
    CFRelease(query)
  }

  // ---------------------------------------------------------------- keychain ----

  /** `kSecClass` + service + account: the three attributes that name one of this app's items. */
  private fun identity(account: String): CFMutableDictionaryRef {
    val query =
      CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        DICTIONARY_CAPACITY.convert(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
      ) ?: error("Could not allocate a Keychain query.")
    CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
    query.putOwned(kSecAttrService, cfString(service))
    query.putOwned(kSecAttrAccount, cfString(account))
    return query
  }

  private fun read(account: String): ByteArray? = memScoped {
    val query = identity(account)
    CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
    CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)

    val found = alloc<CFTypeRefVar>()
    val status = SecItemCopyMatching(query, found.ptr)
    CFRelease(query)
    if (status != errSecSuccess) return@memScoped null

    // SecItemCopyMatching returns a +1 reference; this function owns it and releases it below.
    val data: CFDataRef = found.value?.reinterpret() ?: return@memScoped null
    val bytes = data.toByteArray()
    CFRelease(data)
    bytes
  }

  private fun insert(account: String, raw: ByteArray): Int {
    val data = cfData(raw) ?: error("Could not allocate the Keychain payload.")
    val item = identity(account)
    CFDictionarySetValue(item, kSecValueData, data)
    CFDictionarySetValue(item, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
    val status = SecItemAdd(item, null)
    CFRelease(item)
    // Drops this function's reference. Leaving it would keep the key bytes alive for the life of
    // the process, which is the one thing they must not do.
    CFRelease(data)
    return status
  }

  /** Sixteen random bytes as hex, so two ids never collide within one app. */
  private fun randomHex(): String {
    val bytes = ByteArray(ID_ENTROPY_BYTES)
    val drawn = bytes.usePinned {
      SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), it.addressOf(0))
    }
    check(drawn == errSecSuccess) { "The system refused to generate a key id ($drawn)." }
    return bytes.joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
  }

  /**
   * Stores [value] under [key] and drops this function's reference to it.
   *
   * The dictionary takes its own through `kCFTypeDictionaryValueCallBacks`, so releasing here
   * leaves exactly one owner: the dictionary.
   */
  private fun CFMutableDictionaryRef.putOwned(key: CFStringRef?, value: CFStringRef?) {
    if (value == null) return
    CFDictionarySetValue(this, key, value)
    CFRelease(value)
  }

  private fun cfString(value: String): CFStringRef? {
    val bytes = value.encodeToByteArray()
    if (bytes.isEmpty()) {
      return CFStringCreateWithBytes(kCFAllocatorDefault, null, 0, kCFStringEncodingUTF8, false)
    }
    return bytes.usePinned { pinned ->
      CFStringCreateWithBytes(
        kCFAllocatorDefault,
        pinned.addressOf(0).reinterpret(),
        bytes.size.convert(),
        kCFStringEncodingUTF8,
        false,
      )
    }
  }

  private fun cfData(bytes: ByteArray): CFDataRef? {
    if (bytes.isEmpty()) return CFDataCreate(kCFAllocatorDefault, null, 0)
    return bytes.usePinned { pinned ->
      CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), bytes.size.convert())
    }
  }

  private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length <= 0) return ByteArray(0)
    val pointer = CFDataGetBytePtr(this) ?: return ByteArray(0)
    return ByteArray(length) { index -> pointer[index].toByte() }
  }

  companion object {
    /** One namespace per app; the bundle id keeps two apps on a device apart. */
    const val DEFAULT_SERVICE: String = "id.tracive.sdk.sample.cacheKeys"

    private const val DICTIONARY_CAPACITY = 6
    private const val ID_ENTROPY_BYTES = 16
  }
}
