package id.tracive.sdk.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * How long a cached [CheckResult] stays usable.
 *
 * The defaults scale with the tier, because tiers age at very different rates: a `HIGH` verdict
 * rests on a body of corroborated reports and almost never flips, while a `NONE` answer flips the
 * moment the first report lands. Caching them for the same period would either serve stale clean
 * answers or throw away the cheap ones.
 *
 * A cache hit costs no credit and does no network I/O, so these numbers are the main lever on both
 * the bill and offline behaviour.
 */
data class CachePolicy(
  /** When false the cache is neither read nor written, and every check goes to the server. */
  val enabled: Boolean = true,

  /** Default lifetime of a `HIGH` result. */
  val highTtl: Duration = 30.days,

  /** Default lifetime of an `ELEVATED` result. */
  val elevatedTtl: Duration = 7.days,

  /** Default lifetime of a `CAUTION` result. */
  val cautionTtl: Duration = 24.hours,

  /** Default lifetime of a `NONE` result: the tier most likely to be overtaken by a new report. */
  val noneTtl: Duration = 1.hours,

  /**
   * The most entries the cache keeps. Zero or less means no cap.
   *
   * Without a bound the store grows for the life of the install: nothing but a TTL ever removes a
   * row, and a `HIGH` verdict lives thirty days, so an app that checks every number in an incoming
   * call log accumulates an encrypted database on the user's phone that no one asked for and no one
   * is watching. 5000 rows is the same default the other Tracive SDKs use.
   *
   * It is a **soft** cap, enforced by the prune pass — when the cache is opened, and on every
   * explicit `prune()` — rather than on every write. A write already does one insert and, on a
   * persistent store, one disk flush; making it also count rows and delete the loser would put a
   * second query in the path of every check for a bound that is about disk space rather than
   * correctness. A host that expects to write thousands of entries in one session should call
   * `prune()` on a schedule, which is what that method is reachable for.
   *
   * Eviction drops the rows **closest to expiry first**, because they have the least value left. In
   * practice that means a `NONE` answer (one hour) goes long before a `HIGH` one (thirty days),
   * which is also the right way round for the bill: a `HIGH` verdict is the one a user is most
   * likely to ask about twice.
   */
  val maxEntries: Int = 5000,

  /**
   * How long a transaction record — the answer that was shown for one of your transactions — is
   * kept. Zero or less stores none at all.
   *
   * A separate knob from the TTLs above because it answers a different question. A TTL asks "is
   * this verdict still worth trusting?", and an hour is right for a `NONE`. A retention asks "can
   * the user still see what they were told before they sent the money?", and the answer to that
   * does not decay: the record is a fact about the past, so the only thing that should end it is
   * the host's own data-minimisation policy.
   *
   * 90 days matches how far back a transaction list is usually browsed. Raise it if your history
   * screen goes back further — and raise [maxEntries] with it, since records are counted by the
   * same cap as cache entries.
   */
  val transactionCheckRetention: Duration = 90.days,
) {

  /**
   * How long a result of [riskTier] stays usable — the whole of the cache's lifetime policy.
   *
   * There is no per-response override. The backend used to be able to send a `cacheTtlSeconds` that
   * replaced this, which duplicated the ladder below on the server; cache lifetime is the SDK's own
   * business, so the field is gone and a host that wants different numbers passes them here.
   */
  fun ttlFor(riskTier: RiskTier): Duration =
    when (riskTier) {
      RiskTier.HIGH -> highTtl
      RiskTier.ELEVATED -> elevatedTtl
      RiskTier.CAUTION -> cautionTtl
      RiskTier.NONE -> noneTtl
    }

  companion object {
    /** The defaults above. */
    val Default: CachePolicy = CachePolicy()

    /**
     * Bump this whenever the cached payload's shape or the folding grammar changes.
     *
     * The cache store must treat a stored version other than this one as "discard everything". That
     * is what makes a grammar change safe: entries written under the old folding rules are keyed on
     * strings the new rules would never produce, so without the bump they would linger until their
     * TTL expired — up to 30 days of answers nobody can look up any more.
     */
    const val SCHEMA_VERSION: Int = 1
  }
}
