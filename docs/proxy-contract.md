# Proxy mode — the phase 2 contract

**Not implemented.** Every call made in `TraciveMode.Proxy` throws
`TraciveException.NotImplemented("Proxy mode arrives in phase 2")` today.

This document exists anyway, and early, for two reasons: so a partner planning a backend integration
can build against a written contract instead of guessing, and so the shape is fixed before anyone
depends on an accident.

---

## Why the type exists before the behaviour

`TraciveMode` is a sealed hierarchy, and `TraciveMode.Proxy` is in it now. Adding a case to a sealed
type later is a **breaking change** — every exhaustive `when` in every partner app stops compiling.
Shipping the case now, throwing, means phase 2 is an implementation change rather than a major
version bump.

The same reasoning applies to `TraciveClient.oauth` being nullable: it is `null` in Proxy mode,
because a proxied client has no OAuth grant of its own. Callers who write `client.oauth?.…` today
keep working when Proxy mode starts working.

```kotlin
val client = TraciveClient(
  mode = TraciveMode.Proxy(
    baseUrl = "https://api.yourcompany.example/tracive",
    authHeaders = mapOf("Authorization" to "Bearer ${yourOwnSessionToken}"),
  ),
)

client.checkScammer(query)   // throws TraciveException.NotImplemented — today and until phase 2
```

`TraciveMode.Proxy` already behaves correctly in the ways it can: `baseUrl` is trimmed of a trailing
slash, `authHeaders` is defensively copied on construction, and `toString()` prints header **names**
only — the values are credentials and are never logged.

## When you will want it

Proxy mode is for a partner who has a backend and wants the checks billed to **the partner's**
balance rather than to each end user's credits. OAuth mode bills the user; that fits a consumer app
where the user already has a Tracive account. It does not fit a marketplace that wants to screen
every seller silently.

It is also the only correct home for a long-lived credential. There is no API key in the SDK, in any
mode, and there will not be one — a key in an APK is a published key. In Proxy mode the credential
lives on your server, where a secret can actually be kept, and the SDK sends only whatever session
header your own app already uses.

## What your proxy must implement

Your backend stands between the SDK and the Tracive API. The SDK will speak the same wire format to
your proxy that it speaks to Tracive, so the minimum is a pass-through that swaps credentials.

### Routes

| SDK calls | Your proxy does |
|---|---|
| `POST {baseUrl}/scammer-checks` | authenticate the caller, attach your Tracive credential, forward, return the response |
| `GET {baseUrl}/credits` | your balance, in the `CreditsInfo` shape |
| `GET {baseUrl}/profile` | optional; `ProfileInfo` shape |

`{baseUrl}` is exactly what you passed to `TraciveMode.Proxy`, with no trailing slash.

### Request the SDK sends

Headers: everything in `authHeaders`, plus `Idempotency-Key` when the caller supplied one.
Body:

```json
{ "creditSource": "PARTNER", "detail": "minimal", "locale": "id", "query": "<searchQueryIndex>" }
```

**`creditSource` is the one field here that is not yet frozen.** phase 1 sends only `USER`;
`PARTNER` and `AUTO` are reserved for Proxy mode but the backend has not fixed their semantics
(specifically, whether `AUTO` means "user credits, falling back to partner balance"). Treat the value
as informational until phase 2 lands, and have your proxy accept any of the three rather than
hard-matching on `PARTNER`. Everything else in this section follows from the phase 1 wire format and
is stable.

Two things carry over unchanged from OAuth mode and are worth calling out:

- **`query` is the folded key, not raw input.** The SDK has already normalised it, and that same
  string is what the cache is keyed on. Forward it verbatim. Re-folding it on your server would
  create a second implementation of the grammar, which is the exact class of bug
  `query-grammar-vectors.json` exists to prevent.
- **`locale` is omitted when `detail` is `minimal`**, because the server ignores it there.

### Response the SDK expects

The documented `CheckResult` shape. Unknown fields are ignored rather than fatal, so you may add
your own without breaking installed apps — that forward compatibility runs in your favour too.

### Errors

Preserve Tracive's error envelope, and preserve the status code:

```json
{ "error": { "code": "insufficient_partner_balance", "message": "…", "checkId": "…" } }
```

Do **not** collapse upstream errors into a generic 500. The SDK maps `error.code` to a typed
exception, and a partner app's handling of `InsufficientCredits` (prompt a top-up) versus
`RateLimited` (back off for `Retry-After`) versus `Server` (retry later) is entirely driven by what
you pass through. An unrecognised code maps to `Server`/`Unknown` carrying the raw code — it will not
crash, but it also will not be actionable.

Emit `Retry-After` on a 429. `TraciveException.RateLimited.retryAfter` is read straight from it.

## Two things your proxy must not do

**Do not retry a check on the SDK's behalf without an idempotency key.** A 5xx or a timeout from
Tracive does not say whether the check was processed before it failed. A blind retry is a request to
be charged twice, and your users cannot see it happening. Forward the SDK's `Idempotency-Key` header
when it is present, and only retry when it is.

**Do not log the `query` field.** It is a folded identifier — a phone number, a bank account, a wallet
address. The SDK goes to some length not to write it to disk (the cache stores `SHA-256` of it and
nothing else); a proxy that writes it to an access log gives that back, at server scale, in a place
with far broader access than a phone.

## What stays the same in Proxy mode

- **Query folding and validation** run client-side, before anything is sent. Unchanged.
- **The cache** works identically, with the same TTLs and the same encryption. A hit still costs
  nothing and still does no network I/O — the saving lands on your balance instead of the user's.
- **Error types** are the same. `InsufficientCredits.which` will report the partner-balance variant.
- **There is still no API key parameter.** `authHeaders` carries whatever your own app already uses
  to authenticate to your own backend. It is not a Tracive credential and the SDK never treats it as
  one.

## Until then

`TraciveMode.Proxy` is safe to reference, construct and pass around. It is not safe to call. If you
are choosing a mode today, the answer is OAuth. See the [README](../README.md) status section.
