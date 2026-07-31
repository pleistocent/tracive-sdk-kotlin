package id.tracive.sdk.cache

import id.tracive.sdk.model.CachePolicy
import kotlinx.datetime.Clock

/**
 * A [CheckCache] that keeps its records in memory and writes nothing to disk.
 *
 * Two honest uses:
 * - **Tests.** The whole cache — key derivation, the AES-GCM envelope, TTL per tier, schema
 *   invalidation — behaves exactly as it does on SQLDelight, with no database, no platform key
 *   store and no file system.
 * - **Hosts that will not persist check history.** A cache that dies with the process still saves
 *   the repeat lookups inside one session, which is where most of the duplication is (a user
 *   re-checking the number they are currently being called by), without leaving an encrypted record
 *   of what was checked on the device at all.
 *
 * Records are still encrypted, which is not theatre: [dataKeyStore] is the same interface the
 * persistent cache uses, so the code path under test is the production one rather than a
 * plaintext-in-a-map lookalike that would never catch an envelope bug.
 *
 * Pair it with [InMemoryCacheDataKeyStore]; a persistent key store would work too but buys nothing,
 * since the records it protects do not outlive the process.
 */
class InMemoryCheckCache(
  dataKeyStore: CacheDataKeyStore,
  policy: CachePolicy = CachePolicy.Default,
  clock: Clock = Clock.System,
) :
  CheckCache by DefaultCheckCache(
    store = InMemoryCacheRecordStore(),
    dataKeyStore = dataKeyStore,
    policy = policy,
    clock = clock,
  ) {

  override fun toString(): String = "InMemoryCheckCache"
}
