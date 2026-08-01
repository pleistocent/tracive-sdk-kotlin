package id.tracive.sdk.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The answer to one scammer check.
 *
 * Every field has a default, for two reasons. Unknown *fields* in a response are ignored (configure
 * the SDK's `Json` with `ignoreUnknownKeys = true`), and missing ones fall back to a safe value, so
 * a phase 2 server that adds or drops a field cannot break an already-installed app. The safe value
 * for a count is zero and for a tier is [RiskTier.NONE] — but note that [RiskTier.NONE] means "no
 * evidence found", never "verified safe".
 *
 * The one thing that must NOT be made forgiving is an unrecognised [riskTier] value. Configuring
 * the SDK's `Json` with `coerceInputValues = true` would turn a tier this release has never heard
 * of into [RiskTier.NONE] — silently reporting "no evidence found" for something the server
 * considered severe enough to invent a new tier for. Failing the decode is loud, and loud is
 * correct here.
 *
 * [origin] and [fetchedAt] are assigned by the client, not sent by the server; see their docs.
 */
@Serializable
data class CheckResult(
  /**
   * The server's id for this check, for support and for reconciling a charge.
   *
   * Null on a cache hit: no check happened, so there is nothing to reconcile.
   */
  @SerialName("checkId") val checkId: String? = null,

  /** Whether any report matched the query. */
  @SerialName("found") val found: Boolean = false,

  /** The server's verdict. See [RiskTier] — `NONE` is not a clean bill of health. */
  @SerialName("riskTier") val riskTier: RiskTier = RiskTier.NONE,

  /** Total reports matching the query, before any credibility weighting. */
  @SerialName("reportTotal") val reportTotal: Int = 0,

  /** Reports the server judged credible. */
  @SerialName("credibleReports") val credibleReports: Int = 0,

  /** Reports the server could not judge either way. Worth showing next to [credibleReports]. */
  @SerialName("ambiguousReports") val ambiguousReports: Int = 0,

  /** When the earliest matching report was filed. */
  @SerialName("firstReportOn") val firstReportOn: Instant? = null,

  /**
   * When the most recent matching report was filed — usually the more decision-relevant of the two.
   */
  @SerialName("lastReportOn") val lastReportOn: Instant? = null,

  /**
   * Total reported loss, keyed by ISO 4217 currency code.
   *
   * Multi-currency by construction: sums across currencies are meaningless, so the server never
   * collapses them and neither should a UI.
   */
  @SerialName("fraudAmount") val fraudAmount: Map<String, Double> = emptyMap(),

  /** Server-assigned labels for the kind of fraud reported. */
  @SerialName("tags") val tags: List<String> = emptyList(),

  /**
   * How many other flagged identifiers the server has linked to this one.
   *
   * A count and never a list, deliberately: returning the linked identifiers would let a caller
   * walk the graph and extract the database one edge at a time.
   */
  @SerialName("relatedScammerCount") val relatedScammerCount: Int = 0,

  /** Incident narratives. Only populated when `detail = FULL`; never cached. */
  @SerialName("topChronologies") val topChronologies: List<Chronology>? = null,

  /** Community comments. Only populated when `detail = FULL`; never cached. */
  @SerialName("comments") val comments: List<Comment>? = null,

  /** What this call cost. Null on a cache hit, which costs nothing. */
  @SerialName("billing") val billing: Billing? = null,

  /**
   * A server-driven lifetime for this entry, overriding the tier default in
   * [CachePolicy.effectiveTtl].
   *
   * This is the backend's fleet-wide invalidation lever — the only way to make already-installed
   * apps stop trusting a cached answer without shipping a release. Honour it.
   */
  @SerialName("cacheTtlSeconds") val cacheTtlSeconds: Long? = null,

  /**
   * The legal disclaimer that must accompany this result.
   *
   * **Surface this in any UI built on the SDK.** These results are user-generated allegations about
   * named people and accounts, not adjudicated findings, and the wording here is what makes that
   * clear to the person acting on them.
   */
  @SerialName("disclaimer") val disclaimer: String? = null,

  /**
   * Whether this came from the local cache or the server.
   *
   * Client-assigned: the server does not send it, so it decodes to [Origin.SERVER] and the layer
   * that produced the result overwrites it.
   */
  @SerialName("origin") val origin: Origin = Origin.SERVER,

  /**
   * When the underlying data was fetched from the server — the original fetch time, not the time of
   * the call that returned it, so a cache hit still reports how stale it is.
   *
   * Client-assigned. The wire payload does not carry it, so it decodes to [Instant.DISTANT_PAST];
   * the transport must replace that with the clock reading taken when the body arrived
   * (`decoded.copy(fetchedAt = clock.now())`). A `DISTANT_PAST` reaching a caller means some path
   * skipped that step.
   */
  @SerialName("fetchedAt") val fetchedAt: Instant = Instant.DISTANT_PAST,
) {

  /**
   * This result reduced to the fields that are safe to cache.
   *
   * The cache is keyed on the query alone, so it may only hold fields that do not vary with the
   * request's `detail` or `locale`. [topChronologies] and [comments] are locale-dependent narrative
   * text that `detail = MINIMAL` does not even fetch; [billing] and [checkId] describe one call
   * rather than the identifier. Caching any of them would let a `MINIMAL` request serve narrative
   * text in the wrong language, or replay another call's charge.
   *
   * **This projection is not by itself sufficient, and the gap is easy to miss.** It KEEPS [tags]
   * and [disclaimer], and those vary with `locale` too — so a full-detail answer, which is the only
   * kind that carries a locale, must never be written at all. `TraciveClient` enforces that
   * separately; this method cannot, because it cannot see the request. Do not read "the subset is
   * safe to cache" as "any result is safe to cache once projected".
   *
   * If a later version ever needs to cache full-detail content, the cache key must become the
   * triple of query, detail and locale. Not this method's business, but do not skip it.
   */
  fun toCacheable(): CheckResult =
    copy(checkId = null, topChronologies = null, comments = null, billing = null)
}
