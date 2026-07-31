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
   * A server TTL of zero means "do not cache this" and yields [Duration.ZERO]. A negative value is
   * ignored as malformed rather than being treated as "already expired", so a bad response degrades
   * to the tier default instead of disabling caching.
   */
  fun effectiveTtl(riskTier: RiskTier, cacheTtlSeconds: Long?): Duration =
    if (honorServerTtlOverride && cacheTtlSeconds != null && cacheTtlSeconds >= 0L) {
      cacheTtlSeconds.seconds
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
