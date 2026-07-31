package id.tracive.sdk.platform

import id.tracive.sdk.oauth.TokenSet
import id.tracive.sdk.oauth.TokenSetCodec
import id.tracive.sdk.oauth.TokenStore
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
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * The iOS token store: one generic-password Keychain item, protected by the system.
 *
 * The Keychain is the only place on iOS where a credential belongs. `UserDefaults` is a plist in
 * the app container, readable by anything that can read the container — a jailbreak, a device
 * backup, a file-sharing-enabled app.
 *
 * Two attribute choices are load-bearing:
 * - **`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.** `AfterFirstUnlock` rather than
 *   `WhenUnlocked` because the SDK refreshes tokens from background work, which would otherwise
 *   fail on a locked device and sign the user out for no reason they can see. `ThisDeviceOnly`
 *   because it keeps the item out of iCloud Keychain and out of encrypted backups: a refresh token
 *   is bound to *this* device's grant, and a copy restored onto a second device would be spent in
 *   parallel — which the server's reuse detection reads as theft and answers by revoking the whole
 *   grant.
 * - **A fixed service and account.** One grant per app, replaced in place, so an old refresh token
 *   is never left behind to be found and replayed.
 *
 * On a Secure-Enclave-equipped device the class key protecting this item is enclave-backed; that is
 * the platform's doing, not something the SDK has to opt into.
 *
 * Everything here is plain CoreFoundation, with no `NSString`/`NSData` bridging. That is
 * deliberate: `someKotlinString as NSString` is a cast the Kotlin/Native compiler reports as one
 * that can never succeed, and a Keychain write is exactly the wrong place to discover a class-cast
 * failure — it would take the user's grant with it. `CFStringCreateWithBytes` and `CFDataCreate`
 * copy the bytes handed to them, so the pinned Kotlin arrays below can be released immediately.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosKeychainTokenStore(
  private val service: String = DEFAULT_SERVICE,
  private val account: String = DEFAULT_ACCOUNT,
) : TokenStore {

  override suspend fun load(): TokenSet? {
    val blob = read() ?: return null
    val tokens = TokenSetCodec.decode(blob)
    if (tokens == null) {
      // Unreadable: written by a schema this build does not know, or corrupt. Drop it rather than
      // leaving a value that can only ever fail again.
      delete()
    }
    return tokens
  }

  override suspend fun save(tokens: TokenSet) {
    write(TokenSetCodec.encode(tokens))
  }

  override suspend fun clear() {
    delete()
  }

  override fun toString(): String = "IosKeychainTokenStore"

  // ---------------------------------------------------------------- keychain ----

  /** `kSecClass` + service + account: the three attributes that identify this app's one item. */
  private fun identity(): CFMutableDictionaryRef {
    val query =
      CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        DICTIONARY_CAPACITY.convert(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
      ) ?: error("The Tracive SDK could not allocate a Keychain query.")
    CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
    query.putOwned(kSecAttrService, cfString(service))
    query.putOwned(kSecAttrAccount, cfString(account))
    return query
  }

  private fun read(): String? = memScoped {
    val query = identity()
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
    bytes.decodeToString()
  }

  private fun write(text: String) {
    val data = cfData(text.encodeToByteArray()) ?: return

    // Update in place when an item already exists. Deleting first and re-adding would leave a
    // window — however short — in which the device has no grant at all, and a crash inside it would
    // sign the user out for no reason.
    val attributes =
      CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        DICTIONARY_CAPACITY.convert(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
      )
    if (attributes == null) {
      CFRelease(data)
      return
    }
    CFDictionarySetValue(attributes, kSecValueData, data)

    val existing = identity()
    val updated = SecItemUpdate(existing, attributes)
    CFRelease(existing)
    CFRelease(attributes)

    if (updated == errSecItemNotFound) {
      val insert = identity()
      CFDictionarySetValue(insert, kSecValueData, data)
      CFDictionarySetValue(
        insert,
        kSecAttrAccessible,
        kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
      )
      SecItemAdd(insert, null)
      CFRelease(insert)
    }

    // The dictionaries retained the payload for as long as they needed it; this drops the reference
    // created above. Leaving it would leak the token bytes for the life of the process, which is
    // the one thing they must not do.
    CFRelease(data)
  }

  private fun delete() {
    val query = identity()
    SecItemDelete(query)
    CFRelease(query)
  }

  /**
   * Stores [value] under [key] and drops this function's reference to it.
   *
   * The dictionary takes its own reference through `kCFTypeDictionaryValueCallBacks`, so releasing
   * here leaves exactly one owner: the dictionary.
   */
  private fun CFMutableDictionaryRef.putOwned(key: CFStringRef?, value: CFStringRef?) {
    if (value == null) return
    CFDictionarySetValue(this, key, value)
    CFRelease(value)
  }

  /** [value] as a CoreFoundation string. The bytes are copied, so the pinning ends here. */
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

  /** [bytes] as a `CFData`. Copied, for the same reason as [cfString]. */
  private fun cfData(bytes: ByteArray): CFDataRef? {
    if (bytes.isEmpty()) return CFDataCreate(kCFAllocatorDefault, null, 0)
    return bytes.usePinned { pinned ->
      CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), bytes.size.convert())
    }
  }

  /** The bytes of a `CFData`, copied out before the reference is released. */
  private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length <= 0) return ByteArray(0)
    val pointer = CFDataGetBytePtr(this) ?: return ByteArray(0)
    return ByteArray(length) { index -> pointer[index].toByte() }
  }

  private companion object {
    /** One item per app; the bundle id keeps two apps on one device apart. */
    const val DEFAULT_SERVICE = "id.tracive.sdk.tokens"

    /** The stored shape's version, so a future format can coexist rather than be misread. */
    const val DEFAULT_ACCOUNT = "grant.v1"

    const val DICTIONARY_CAPACITY = 6
  }
}

/**
 * The Keychain-backed token store.
 *
 * Takes no `Context`-equivalent: on iOS the Keychain is reachable from anywhere in the process, so
 * there is nothing to initialise and nothing a host has to remember.
 */
actual fun SecureTokenStore(): TokenStore = IosKeychainTokenStore()
