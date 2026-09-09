# Releasing the Kotlin SDK

Anyone with **write** access to this repository can cut a complete release: the gates, Maven
Central, the tag and the GitHub release all come out of one workflow dispatch. Nothing in the
procedure needs the repository owner or a Sonatype login.

Two things have to be set up once before the first real release, and neither is done yet. Read
[Before the first release](#before-the-first-release) before you try.

## Cut a release

1. **Move the entries out of `## [Unreleased]`** in `CHANGELOG.md` into a `## [0.1.1] — 2026-09-09`
   section, bump `tracive.version` in `gradle.properties` to match, and merge that to `main`. The
   changelog section becomes the GitHub release body, so this is where the notes are written.
2. **Actions → Release → Run workflow**, with `version` = `0.1.1` (no `v` prefix).
3. Wait — roughly an hour, most of it the macOS gates.

| Stage | What it does |
| --- | --- |
| `preflight` | Version format, `gradle.properties` agreement, changelog section, not already released, secrets present |
| `verify` | The full `ci.yml` gate set — ktfmt, `apiCheck`, common tests on JVM and on the iOS simulator, `build` |
| `publish` | `id.tracive:tracive-sdk` on Maven Central, then tag `v0.1.1` and the GitHub release |

Allow another 15–30 minutes after the run goes green before Gradle can resolve the artifact.

**Rehearse it with `dry_run`.** Same checks, same build, publishes nothing and tags nothing. It is
the only mode that works before the setup below is done, and it is worth a run before every first
release of a new major or minor.

## What the workflow will refuse to do

- **Release a version that is not `x.y.z`**, or one where `gradle.properties` disagrees with the
  version you typed. The coordinates come from `gradle.properties`; a mismatch would publish one
  version under another's tag, permanently.
- **Release a version with no changelog section.** This is the check that catches the commonest
  mistake here — publishing with the notes still under `## [Unreleased]`.
- **Release a version that already has a tag, or is already on Maven Central.** Central is
  immutable: a bad artifact can be superseded, never replaced or deleted.
- **Run without the four publishing secrets.** Checked in the first minute rather than inside
  Gradle an hour later.

## Before the first release

### 1. Verify the `id.tracive` namespace with Sonatype

`id.tracive` does not exist on Maven Central. Central will not accept a deployment under a
namespace nobody has claimed, and claiming it is a one-off manual step: sign in at
[central.sonatype.com](https://central.sonatype.com), add `id.tracive` as a namespace, and prove
the domain by publishing the TXT record it gives you on `tracive.id`. This needs DNS access, not
repository access, which is why it is not in the workflow.

### 2. Add four repository secrets

Settings → Secrets and variables → Actions. This needs repository **admin** rights, and is the only
part of releasing that does.

| Secret | What it is | Where it comes from |
| --- | --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token, username half | central.sonatype.com → your account → Generate User Token |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token, password half | the same token |
| `SIGNING_IN_MEMORY_KEY` | The GPG secret key that signs the artifacts, **ASCII-armored** | `gpg --export-secret-keys --armor <KEY_ID>` — paste the whole block, `-----BEGIN PGP PRIVATE KEY BLOCK-----` line included |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | Passphrase for that key | — |

The token is not the Sonatype account password. The GPG **public** key has to be on a public
keyserver, or Central rejects the deployment with a signature error that says nothing about
keyservers.

## Why the deployment publishes itself

The publish step runs `publishAndReleaseToMavenCentral`. The plain `publishToMavenCentral` uploads
the deployment as `USER_MANAGED`, where it waits in
[the Central Portal](https://central.sonatype.com/publishing/deployments) until whoever owns the
Sonatype account presses **Publish** — the one step in the pipeline a maintainer has no way to
perform, and the reason a release could previously be started but not finished. Central's
validation still runs either way, so a deployment Central rejects fails the job rather than
shipping.

If you want a checkpoint back, a GitHub
[environment](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
with a required reviewer on the `publish` job gives you an approve button without putting the
Sonatype login back in the loop.

## When something goes half-wrong

**Publish succeeded, tagging failed.** The artifact is on Central and cannot be withdrawn.
Re-running is blocked, correctly, by the "already on Maven Central" check. Push the tag and cut the
release by hand:

```bash
git tag -a v0.1.1 -m v0.1.1 <the SHA the run used>
git push origin v0.1.1
gh release create v0.1.1 --title v0.1.1 --notes-file <the changelog section>
```

**The gates failed.** Nothing was published and nothing was tagged. Fix and dispatch again with the
same version.

## Publishing by hand

`./gradlew :tracive-sdk:publishToMavenLocal` puts the artifacts in `~/.m2` — that is the local
build the README's install section describes, and it is unrelated to a release.
`./gradlew :tracive-sdk:publishToMavenCentral` uploads a deployment and deliberately does *not*
release it, so an accidental run from a laptop parks in the Portal rather than reaching partners.
