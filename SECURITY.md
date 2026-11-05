# Security policy

## Reporting a vulnerability

**Email <developer@pleistocent.com>.** Please do not open a public GitHub issue for a security problem, and
please do not post it to a forum or a chat group first.

If you want the report encrypted, say so in a first message with no details and we will send you a
key.

What helps, roughly in order of usefulness:

- What an attacker gains, and what access they need to start.
- Affected version(s) and platform (Android / iOS).
- A reproduction — a failing test against this repo is ideal, since everything here runs without a
  network.
- Anything you have already ruled out.

What to expect:

| | |
|---|---|
| Acknowledgement | within 3 working days |
| Initial assessment | within 10 working days |
| Fix or a dated plan | depends on severity; we will tell you which |

We will credit you in the [CHANGELOG](CHANGELOG.md) unless you would rather we did not. We will not
take legal action over good-faith research that stays within the scope below.

## Scope

**In scope** — this repository: the SDK, its build configuration, and the sample app. Concretely, the
things most worth attacking:

- The cache's encryption and key handling (`cache/`) — envelope forgery, nonce reuse, a way to make
  one record open under another key.
- The OAuth flow (`oauth/`) — anything that lets another app on the device obtain a code, a token, or
  a refresh token; anything that defeats the `state` check or the PKCE binding.
- Query folding (`query/`) — an input that produces a key for a *different* identifier than the one
  supplied, or that escapes the document-id guard.
- Any path that writes a raw identifier or a token somewhere it can be read: a log, a crash report, a
  `toString()`, an unencrypted file.

**Out of scope** — the backend API (there is no live one yet; report backend issues to the same
address and we will route them), and third-party dependencies, which should go to their maintainers
unless our *use* of them is the flaw.

Please also do not report, as we already know and have documented the reasoning:

- **The AES implementation is not constant-time.** It is software AES with table lookups. An attacker
  who can measure this process's cache timing is already inside the app sandbox, where both the
  plaintext (a risk tier and some counts the user already knows) and the data key are reachable by
  much easier means. The header comment in `AesGcm.kt` says so, and says not to reuse the code where
  the plaintext is a secret the local user must not learn.
- **A cache key can be confirmed by brute force.** `SHA-256(searchQueryIndex)` of a phone number is
  guessable from a candidate list, and a salt the device stores is a salt the attacker also has. The
  hash defends against *bulk extraction* — identifiers cannot be enumerated from the file, only
  confirmed one guess at a time. That is the property claimed, and it is the one that matters for the
  realistic threat.

A finding that *breaks* either of those claims — enumerating identifiers from the database file, or
extracting a key without code execution as the app — is very much in scope.

## What the SDK guarantees

These are design invariants. A build that violates one is a bug worth reporting even without a full
exploit:

1. **No API key parameter exists in the public surface, in any mode.** A key shipped in an APK or an
   IPA is a published key. OAuth mode issues short-lived user-scoped tokens; Proxy mode (phase 2)
   keeps credentials on the partner's server.
2. **Raw identifiers and tokens are never logged.** Not in `toString()`, not in an exception message.
   `TraciveQuery.toString()` prints the kind and nothing else; `CheckRequestBody.toString()` redacts
   the query; `CacheDataKey.toString()` redacts the key; `TraciveMode.Proxy.toString()` prints header
   names and never their values.
3. **The identifier is never written to disk.** The cache key is `SHA-256(searchQueryIndex)`, and the
   payload is AES-256-GCM. The schema has no plaintext column for a tier, a `found` flag or a report
   count, deliberately.
4. **Tokens live in the Android Keystore / iOS Keychain**, never `SharedPreferences`, `UserDefaults`,
   or a plain file.
5. **PKCE is S256 only.** `plain` is not supported and will not be added.
6. **The authorize page opens in Custom Tabs / a Universal Link, never a WebView** (RFC 8252 §8.12).
7. **Token refresh is single-flight.** Concurrent 401s trigger exactly one refresh. Two simultaneous
   refreshes would present the same rotating token twice, which the server's reuse detection reads as
   a stolen-token replay and answers by revoking the whole grant.
8. **Unknown JSON fields are ignored, never fatal**, so a build shipped today survives phase 2 adding
   fields.

## Supported versions

| Version | Supported |
|---|---|
| 0.2.x | Yes |
| 0.1.x | No |

Pre-1.0. Fixes land on the latest `0.x` and there is no backport branch yet.

## A note on what "secure" means here

Both environments of the Open API are live, but this SDK has seen little of them: it has not been
through a review against the real token endpoint or against real adversarial traffic. The local half
— the cache, the folding grammar, the error mapping — is tested and reviewed; the integrated whole
has had far less exposure. Weigh a deployment decision accordingly.
