package id.tracive.sdk.client

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration the SDK decodes with.
 *
 * `ignoreUnknownKeys = true` is a forward-compatibility requirement, not a convenience: a phase 2
 * server that adds a field to `CheckResult` must not break apps already in the field, and those
 * apps cannot be updated on the server's schedule.
 *
 * `coerceInputValues` is deliberately **off**. Turning it on would map a `riskTier` this release
 * has never heard of onto the enum's first value — reporting "no evidence found" for something the
 * server considered severe enough to invent a new tier for. A loud decode failure is the correct
 * outcome there, and it is the reason this object exists rather than a `Json {}` at each call site
 * where someone could quietly add the flag.
 */
internal val SdkJson: Json = Json {
  ignoreUnknownKeys = true
  isLenient = false
  explicitNulls = false
}
