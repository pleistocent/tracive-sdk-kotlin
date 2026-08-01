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

The single allowed exception is the Android application id `com.pleistocent.tracive`, which the
app-presence check genuinely needs, plus this repository's own GitHub path. No other application id
belongs here — a non-production variant appears nowhere in shipping code, so naming one only
discloses that it exists.

The GitHub path is not a loophole, it is the install instruction: a published package names the
repository it is published from, in its POM metadata and in the changelog's release links, and a
reader who cannot find the source cannot audit the SDK they are being asked to embed. The rule as
written forbade what the build file necessarily ships, which makes the rule wrong rather than the
repository.

There is no automated scan in this repository, which puts it on you and on the reviewer. Read your
diff the way a stranger would, before it is public and permanent.
