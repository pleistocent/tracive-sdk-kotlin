package id.tracive.sdk.platform

import id.tracive.sdk.model.TraciveEnvironment
import id.tracive.sdk.oauth.AuthorizeUrlBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library manifest is merged into every partner's shipped app, so what it declares is published
 * inside someone else's manifest. Two things are asserted about it, and neither is about secrecy —
 * an application id is not a secret, and each authorize host publishes its own at
 * `/.well-known/assetlinks.json` because that is what an App Link association is.
 *
 * **No `<queries><package>` entry.** A bank auditing its merged manifest has to account for every
 * line, and a visibility declaration naming a Tracive build is a line it cannot. This went wrong
 * once: a non-production `<package>` entry shipped inside every partner's manifest before anyone
 * noticed.
 *
 * **A `<queries><intent>` for each environment's authorize App Link.** This is what replaced the
 * `<package>` entry, so the first assertion alone is a check that cannot fail — a manifest that
 * lost its `<queries>` block entirely would satisfy it while reporting "not installed" for every
 * user on API 30+. The hosts and paths are compared against the constants the SDK actually builds
 * its authorize URL from, so the manifest and that URL cannot drift apart.
 *
 * This is a JVM-only test because the manifest is Android's. It reads files from the working tree,
 * which no other test here does; the alternative is a rule enforced only by whoever reviews the
 * diff, and this repository is published.
 */
class PublishedApplicationIdTest {

  @Test
  fun theLibraryManifestDeclaresNoPackageVisibilityEntry() {
    val declared = QUERIED_PACKAGE.findAll(manifest()).map { it.groupValues[1] }.toList()

    assertEquals(
      emptyList(),
      declared,
      "This manifest merges into every partner's app, so each package named here is a visibility " +
        "declaration published in someone else's shipped manifest. Visibility comes from the " +
        "authorize App Link <intent> queries instead — see CONTRIBUTING.md.",
    )
  }

  @Test
  fun theLibraryManifestQueriesEveryEnvironmentAuthorizeLink() {
    val queried = QUERIED_DATA.findAll(manifest()).map { it.groupValues[1] }.toList()

    val expected =
      listOf(TraciveEnvironment.Production, TraciveEnvironment.Sandbox).map { environment ->
        val endpoint = AuthorizeUrlBuilder.endpointFor(environment)
        val afterScheme = endpoint.removePrefix("https://")
        afterScheme.substringBefore('/') to "/" + afterScheme.substringAfter('/')
      }

    for ((host, path) in expected) {
      assertTrue(
        queried.any { it.contains("android:host=\"$host\"") && it.contains("android:path=\"$path\"") },
        "No <queries><intent> matches $host$path, so the SDK cannot see the build that claims it " +
          "and reports \"not installed\" for every user on API 30+. Found: $queried",
      )
    }
  }

  /**
   * The manifest, read from the repository root — found by walking up from the test's working
   * directory, because which directory that is depends on how the tests are launched. A search that
   * finds nothing fails here rather than leaving both assertions quietly reading an empty string.
   */
  private fun manifest(): String {
    var directory: File? = File(System.getProperty("user.dir")!!).absoluteFile
    while (directory != null) {
      val candidate = File(directory, MANIFEST_PATH)
      if (candidate.exists()) return candidate.readText()
      directory = directory.parentFile
    }
    throw AssertionError(
      "Could not find $MANIFEST_PATH above ${System.getProperty("user.dir")}, so nothing was checked."
    )
  }

  private companion object {
    const val MANIFEST_PATH = "tracive-sdk/src/androidMain/AndroidManifest.xml"

    /** The `android:name` of a `<queries><package>` entry. */
    val QUERIED_PACKAGE = Regex("""<package\s+android:name="([^"]+)"""")

    /**
     * A whole `<data>` element, so host and path are read as the pair they have to be. `[^>]` and
     * not `[^/]`: a path attribute contains slashes, and the narrower class stopped inside it and
     * matched nothing.
     */
    val QUERIED_DATA = Regex("""<data\s+([^>]*?)\s*/>""")
  }
}
