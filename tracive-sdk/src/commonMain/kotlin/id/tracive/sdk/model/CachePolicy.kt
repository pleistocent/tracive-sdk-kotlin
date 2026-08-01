package id.tracive.sdk.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

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
   * Whether a response's `cacheTtlSeconds` overrides the tier default.
   *
   * Leave this on. It is the fleet-wide invalidation lever: when the backend needs
   * already-installed apps to stop trusting cached answers, shortening this field in its responses
   * is the only way to do it without shipping a release. Turning it off opts this app out of that.
   */
  val honorServerTtlOverride: Boolean = true,
) {

  /** The default lifetime for [riskTier], ignoring any server override. */
  fun ttlFor(riskTier: RiskTier): Duration =
    when (riskTier) {
      RiskTier.HIGH -> highTtl
      RiskTier.ELEVATED -> elevatedTtl
      RiskTier.CAUTION -> cautionTtl
      RiskTier.NONE -> noneTtl
    }

  /**
   * The lifetime to actually store an entry with: the server's [cacheTtlSeconds] when it sent one
   * and [honorServerTtlOverride] is on, otherwise the tier default.
   *
   * A server TTL of zero means "do not keep this answer" and yields [Duration.ZERO], which makes
   * the store delete any row that is already there — the retraction lever, and the only way the
   * server can take back an answer it has already put on a device.
   *
   * **A negative value is clamped to zero, so it retracts too.** It used to be read as malformed
   * and fall through to the tier default, which is the one reading that cannot be right: the two
   * plausible intentions behind a negative number are "expired already" and "a bug", and the answer
   * that serves both is to stop trusting the entry. Falling back to the tier default does the
   * opposite — it keeps serving a `HIGH` verdict for thirty more days on the strength of a response
   * the SDK has just decided it does not understand. Clamping matches the Swift and React Native
   * SDKs, and it fails in the direction that costs a credit rather than the direction that shows a
   * stale fraud verdict.
   */
  fun effectiveTtl(riskTier: RiskTier, cacheTtlSeconds: Long?): Duration =
    if (honorServerTtlOverride && cacheTtlSeconds != null) {
      cacheTtlSeconds.coerceAtLeast(0L).seconds
    } else {
      ttlFor(riskTier)
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
