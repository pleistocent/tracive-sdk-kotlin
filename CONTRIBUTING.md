# Contributing

```sh
./gradlew check
```

That runs formatting, the public-API check, and the tests for both targets. It is what CI runs, in
the same order.

## Make sure the run was real

Gradle answers `BUILD SUCCESSFUL in 1s` when its cache is warm and nothing has changed. That is a
correct answer to "is anything out of date", and a useless one to "do the tests pass" — it means no
test executed. A one-second green after a change you believe is significant is a signal, not a
result.

To force a real run:

```sh
find . -type d -name test-results -exec rm -rf {} +
./gradlew check
```

Then read the counts out of the JUnit XML rather than the console summary, per target:

```sh
python3 -c "
import glob, re, xml.etree.ElementTree as ET
from collections import defaultdict
t = defaultdict(int)
for f in glob.glob('**/test-results/**/*.xml', recursive=True):
    r = ET.parse(f).getroot()
    if r.tag == 'testsuite':
        t[re.search(r'test-results/([^/]+)/', f)[1]] += int(r.get('tests', 0))
print(dict(t))"
```

Both targets must appear — `iosSimulatorArm64Test` and `testAndroidHostTest`. If only one does, half
the suite did not run and the build was green anyway.

---

## What CI enforces, and why

- **The embedded grammar fixture matches the JSON.** The vectors are compiled into the test source
  *and* kept as a JSON file; this step fails if they drift apart.
- **`ktfmtCheck`** — Google style, two-space indent. `./gradlew ktfmtFormat` fixes it.
- **`apiCheck`** — the public ABI dumps in `tracive-sdk/api/` must match the code. A deliberate
  surface change is accompanied by `./gradlew apiDump` and the regenerated dump in the same commit.
  **Never edit a dump by hand to make the check pass**, and never widen `apiValidation`'s ignore list
  to silence it. The ignores that exist are reasoned: the sample apps have no ABI to keep stable, and
  SQLDelight's generated database code is public only because the generator cannot make it anything
  else.
- **Tests on both targets**, JVM and iOS simulator. `commonMain` code that compiles for one and
  breaks on the other is exactly what a multiplatform SDK exists to catch early.
- **`./gradlew build` for every target**, including the ones no test runs on.

Nothing needs network access. The Tracive API is not live, and every test injects a fake transport.

---

## The grammar fixture is not yours to edit

`tracive-sdk/src/commonTest/resources/query-grammar-vectors.json` is vendored **byte-identically**
into four SDK repositories, and its expected values were produced by executing the server's own key
normalizer. If your implementation disagrees with a vector, your implementation is wrong.

A grammar change starts at the Tracive API and lands in all four SDKs together, or it drifts.

### And nothing may sit between a fixture value and the assertion that reads it

Pinning the file is only half of it. The pin proves the *bytes* did not move; it says nothing about
whether a test still asserts what they mean. So: no exception map, no ternary on a "pending" or
"known-failing" lookup, no allow-list of vector ids, no filter applied to the vector list before it
is iterated. A fixture value goes straight into the assertion, and if a vector genuinely cannot be
satisfied, a red build is the correct outcome and the conversation belongs upstream.

This is written down because it happened in the React Native SDK, and nothing about it was React
Native-specific. A test there declared an empty map of "pending" guard cases and consulted it before
each expectation:

```ts
expect(isValidRecordKey(value)).toBe(pending ? PENDING_GUARD_CASES[value] : safe);
```

It was empty, so it changed no outcome — and that is exactly what made it worth removing. The
fixture stayed byte-identical, its hash check passed, the pinned vector counts still matched, and
every one of those guards would have stayed green while a single entry in that map let that SDK
disagree with the shared contract in silence. A mechanism whose only purpose is to opt out of the
anti-drift contract while staying green should not exist, empty or not.

No tool catches this, and none can: while that map was empty the behaviour was identical, so there
was nothing for a check to observe — and once it held an entry, the test would pass *because* of it.
It is on you and on the reviewer, at the moment the line is written.

---

## Kotlin's string built-ins are not JavaScript's

The server folds identifiers in JavaScript, and the folded key **is** the record key. A key that
differs by a single code point resolves to a record that does not exist, so the check misses — and a
miss is still billed to the caller.

That is why the query folding hand-writes character classes instead of calling `trim()`,
`isDigit()`, `lowercase()` and friends. Each of those has a reasonable Kotlin definition that differs
from JavaScript's somewhere, and the edges are where fraud identifiers live, because that is where
someone trying not to be found puts them. The fixture's `crossLanguageSemantics` section states the
exact trim set, digit set, case mapping and NFC scope. Follow it, and prefer a table you can diff
against that section over a platform call that is *nearly* right.

Never use a locale-sensitive overload — `lowercase(Locale)`, `uppercase(Locale)`, or any
locale-aware comparison — in folding code. A Turkish locale silently changes what a key hashes to.

---

## Things that look like nits and are not

**Do not add an API key parameter anywhere, in either mode.** There is none, by design: a long-lived
secret shipped inside a mobile binary is a published secret.

**Do not retry a check without an idempotency key.** The SDK cannot tell whether the first attempt
was billed, so a blind retry risks charging a partner twice for one answer.

**Do not add a `domain()` query builder.** The Tracive API indexes five identifier types. A domain
key would match nothing, and the miss is billed.

**Do not let `Detail.FULL` touch the cache, in either direction.** The stored projection keeps
`tags` and `disclaimer`, both localized, under a key that carries no locale — and full detail is the
only level that sends one. The gate is the safety property; the projection is not locale-proof.

**Do not refresh a token on a timer.** The SDK sends the stored access token and refreshes only when
the server answers `401 expired_token`, single-flight, once. A margin based on the device clock
sounds like an optimisation and is not: the clock is not ours, and a fast one rotates the refresh
token far more often than the grant needs, which server-side reuse detection can punish with a
revocation.

**Never open a WebView**, and never add a method that would let a caller ask for one. App Links do
not fire from an embedded browser, and RFC 8252 §8.12 forbids it because the embedding app can read
the user's credentials.

---

## Comments, after ktfmt

ktfmt reflows `//` comment text to the column limit. When a nearly-full line pushes a word or two
onto the next one, it can split a phrase across three lines in a way that reads badly. Re-read the
comments in files you formatted, and reword so each line stands on its own rather than leaving the
machine-split version. It is cosmetic, but it looks unintentional in a diff, and it is the kind of
thing a reader assumes was deliberate.

---

---

## Differences from the sibling SDKs that are deliberate

Two separate audits have now raised these as findings. They are decisions, and this list exists so a
third does not spend its budget re-deriving them. Anything that differs across the four SDKs and is
**not** on this list is drift, and worth reporting.

- **`close()` exists here and not in Swift or React Native.** Ktor owns a connection pool that has
  to be released explicitly; `URLSession` and `fetch` do not, and React Native's link listener
  already returns its own unsubscribe. A no-op `close()` over there would be worse than its absence:
  it teaches a reader that calling it matters.

- **`cacheInitializationError` is Swift-only, and should stay there.** Swift is the only SDK that
  opens a default cache itself and degrades to a no-op one on failure, so it is the only one with a
  failure to report. This SDK opens nothing: `cache` is `DisabledCheckCache` until a host passes
  one.

- **`onAuthResult` and `startLinkListener` are React Native-only.** Android delivers the redirect as
  an intent and iOS as `onOpenURL`; React Native has neither, so its SDK owns the `Linking`
  subscription. Nothing to mirror here.

- **The client fronts consent in all four now.** `authorize`, `handleCallback`, `isAuthorized` and
  `clearCache` sit on the client everywhere, delegating to the OAuth flow. Kotlin gained them last;
  they were added rather than removed from the other three because the surface had already shipped
  to partners.

- **A 401 naming something other than the bearer still costs one refresh.** The rule is: the
  terminal codes surface, everything else refreshes once and replays. A hypothetical
  `invalid_api_key` would therefore spend a refresh — which buys nothing, and costs nothing either,
  because there is no API key in this SDK to be invalid and the second 401 surfaces. Narrowing the
  trigger to one exact code is the change that would actually hurt: a bare 401 from a gateway or a
  reverse proxy carries no code at all, and that is the case the rule exists for.

- **The spent-refresh-token history is not persisted.** It dies with the process, on purpose —
  `TraciveOAuth.spentRefreshTokens` says what that costs (one re-consent after a rolled-back store)
  and why paying it beats writing dead refresh tokens to disk forever.

- **The single-flight latch on checks covers different ground in each pair.** Kotlin and Swift keep
  the cache write inside the flight, so a collapsed check is stored once; Flutter and React Native
  latch the network call alone and let each caller write. The request count — the thing that is
  billed — is identical, and so is what a caller observes.
- **All four app-presence probes follow the environment, but Kotlin's asks a different question.**
  Flutter, React Native and Swift probe the app's URL scheme, which is all a pure-Dart or pure-TS
  package can reach. Kotlin has real Android code, so it checks the exact application id through
  `PackageManager` and takes its package visibility from a `<queries><intent>` on the authorize App
  Link — a scheme any app may declare, an App Link only the build named in the host's assetlinks
  can. Kotlin is therefore the strictest of the four, and the others cannot be brought up to it
  without the native module they deliberately do not ship. This used to read "Kotlin's probe does
  not follow the environment", which was true, and was caused by a hygiene rule that has since been
  corrected.

- **The pending-authorization window is configurable everywhere except Swift.** Kotlin, Flutter and
  React Native all take it as a parameter, so an integration spanning more than one can set the same
  number in each; Swift refuses on purpose, so there is exactly one place its number lives. Kotlin's
  parameter is spelled `private val`, which privatises the generated property and not the parameter
  — an earlier version of this list read that as "not configurable", which Kotlin's own CHANGELOG
  contradicts.

## Tests

No unit test opens a socket, touches a real keystore, or launches a browser. Every capability that
reaches outside the process is an injectable seam.

When you add a test, check that it can fail. Change the value it asserts, or delete the guard it
covers, and watch it go red before you commit it. Two examples from this repository's history make
the point: a pending-authorization window was "covered" by a test that advanced the clock far past
it, which passes at any window size; and a concurrency guard sat unpinned for a while because the
test harness completed continuations inline and could not produce the interleaving at all. Both
looked like coverage.

---

## The public-hygiene rule

**This repository is published.** Nothing in it — source, comments, documentation, test names, error
strings or commit messages — may name any of the following:

- an internal repository or an internal design document;
- a backend commit, source path or symbol;
- a server-side function name;
- the backend's database product, or any internal collection path;
- roadmap phase jargon in any language other than English.

Say **"the Tracive API"** or **"the server"**. Say **"record key"**. Say **"phase 1" / "phase 2"**.

Two things are exempt, and one of them was justified wrongly for a while.

**Android application ids**, production and sandbox alike. The app-presence check needs them, and
withholding them protects nothing: Tracive publishes both itself, unauthenticated, at a path whose
location is fixed by specification. `/.well-known/assetlinks.json` on each authorize host carries
the application id *and* the signing-certificate fingerprints, because that is what an App Link
association is, and Google's Digital Asset Links API serves the same statements to anyone who asks.
The rule used to forbid the sandbox id on the grounds that naming one "only discloses that it
exists". That reason was void from the day the sandbox App Link went live, and the rule outlived it
by pointing at a real concern that is *not* secrecy — see the manifest-merger rule below.

**This repository's own GitHub path.** Not a loophole, the install instruction: a published package
names the repository it is published from, in its POM metadata and in the changelog's release
links, and a reader who cannot find the source cannot audit the SDK they are being asked to embed.

## The manifest-merger rule

Separate from hygiene, and it is what actually shapes the Android code.

`tracive-sdk/src/androidMain/AndroidManifest.xml` is merged into **every partner's shipped app**, so
an entry added here is an entry published in someone else's manifest — and a bank auditing its
merged manifest has to be able to account for every line of it.

This manifest therefore declares **no `<queries><package>` entry at all**. Package visibility comes
from a `<queries><intent>` matching the authorize App Link: it names hosts this SDK already names in
its own source, and it grants visibility to whichever build claims that host, production or sandbox,
without either application id appearing in a partner's manifest. `PublishedApplicationIdTest`
asserts that absence, because this went wrong once — a non-production `<package>` entry shipped
inside every partner's manifest before anyone noticed.

`node tools/check-hygiene.js` scans for all of this, and CI runs it. It proves every one of its rules can still
fire — against its own self-test corpus — before it scans anything, and refuses to run if one
matches nothing: a check that cannot fail is worse than no check, because it reads as coverage.

It arrived on 2 August 2026, after the author shipped 39 Indonesian-language fixture notes into
four published repositories. Only React Native had a scanner; it caught them, and the other
three took the same file without a word. The scan does not replace reading your diff the way a
stranger would — it catches the class of mistake a tired reviewer waves through.
