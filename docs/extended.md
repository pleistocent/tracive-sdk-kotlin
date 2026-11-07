# Extended SDK: in-app reporting on Android and Kotlin Multiplatform

Lets a user report a scammer from inside your app, and check that report's status. Use Tracive's
form and status screens, or your own UI on the same calls.

```kotlin
dependencies {
  implementation("com.pleistocent:tracive-sdk-extended-ui:X.Y.Z") // brings the core
}
```

Replace `X.Y.Z` with the latest version from
[GitHub Releases](https://github.com/pleistocent/tracive-sdk-kotlin/releases). A Kotlin
Multiplatform app that writes its own screens needs only `tracive-sdk-extended`.

| | |
|---|---|
| minSdk | 24 |
| compileSdk | 36 or newer |
| Android Gradle Plugin | 8.9.1 or newer |
| Gradle | 8.11.1 or newer |
| Kotlin | 2.2 or newer |
| API 24 and 25 | Core library desugaring, because the screens read `java.time` |

```kotlin
android {
  compileOptions { isCoreLibraryDesugaringEnabled = true }
}

dependencies {
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

The libraries bring their own R8 rules. An R8 that predates Kotlin 2.3 prints
`An error occurred when parsing kotlin metadata` for them. These are warnings, and the build goes on.

## Connect it

Add the scopes to the client you already have. Tokens stay with `tracive-sdk`, which keeps doing the
refresh, because a reused refresh token revokes the grant.

```kotlin
val client = TraciveClient(
  mode = TraciveMode.OAuth(
    clientId = "trcvc_…",
    redirectUri = "https://your.app/oauth/callback",
    scopes = listOf(TraciveReportScopes.CREATE, TraciveReportScopes.READ),
    partnerUserId = customerId,
  ),
)
val reports = TraciveReports(client)
```

If your backend keeps the tokens, answer for them yourself, and refresh only when `stale` asks:

```kotlin
val reports = TraciveReports(TraciveEnvironment.Production) { stale -> backend.traciveToken(stale) }
```

## Tracive's screens

Configure once, for example in `Application.onCreate`. `TraciveReports` lives in your process, so
after process death an unconfigured form closes as `Cancelled`.

```kotlin
TraciveReportScreens.configure(reports)

val form = registerForActivityResult(TraciveReportForm.Contract()) { outcome ->
  when (outcome) {
    is ReportFormOutcome.Submitted -> keepWithTransaction(outcome.reportId)
    ReportFormOutcome.NeedsConsent -> authorize()
    ReportFormOutcome.Cancelled -> Unit
  }
}

form.launch(
  ReportFormRequest(
    ReportPrefill(
      query = TraciveQuery.bankAccount("IDN", "BCA", account),
      incidentDate = transaction.date,
      title = "Fake seller",
      attachments = listOf(ReportPrefillAttachment(receiptPng, "image/png")),
    ),
    TraciveReportTheme(primaryColor = 0xFF00695C.toInt(), cornerRadius = 16),
  )
)

val status = registerForActivityResult(TraciveReportStatus.Contract()) { }
status.launch(ReportStatusRequest(reportId, theme))
```

The chronology has no prefill on purpose: it's the user's own account of what happened.

## Your own UI

```kotlin
val requirements = reports.requirements()
val evidence = reports.prepare(bytes, "image/png")

val draft = ReportDraft(
  query = TraciveQuery.bankAccount("IDN", "BCA", account),
  title = title,
  chronology = chronology,
  incidentDate = date,
  evidence = listOf(evidence),
  acceptedTerms = requirements.terms,
)

val problems = draft.problems(requirements.limits, today)

when (val result = reports.submit(draft) { fraction -> showProgress(fraction) }) {
  is SubmitResult.Submitted -> keepWithTransaction(result.reportId)
  SubmitResult.NeedsConsent -> authorize()
  is SubmitResult.Failed -> show(result.error.code)
}

val status = reports.status(reportId)
reports.openInTracive(reportId)
```

Keep the same draft across retries. Its idempotency key is what stops a retry becoming a second
report. If the user edits the form after a failed send, build a new draft.

`prepare` compresses to AVIF, under 200 KB, 1024 px on the shortest side, with metadata dropped.
WebP and then JPEG run only when avifkit fails. A PDF under 1 MiB passes through.

## Errors

| `ReportErrorCode` | What to do |
|---|---|
| `NEEDS_CONSENT` | Authorize with the scope added, then call again |
| `EMAIL_NOT_VERIFIED` | Ask the user to verify in Tracive |
| `REPORT_NOT_FOUND` | Unknown, not filed through your app, or older than 30 days |
| `TERMS_OUTDATED` | Read the requirements, show the new links, build a new draft |
| `DAILY_LIMIT_REACHED` | Wait `retryAfterSeconds` |
| `REPORTING_DISABLED` | Try later |
| `NETWORK` | Submit the same draft again |
| `INVALID_REPORT`, `ATTACHMENT_INVALID` | The message names the field or the file |
| `UNSUPPORTED_FILE`, `FILE_TOO_LARGE`, `IMAGE_UNREADABLE` | Ask for another file |
