package id.tracive.sdk.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The only application id this repository may name is the production Tracive app's.
 *
 * Both halves of that rule have already been broken once, which is why they are asserted rather
 * than trusted. `androidMain/AndroidManifest.xml` declared a second, non-production package, and
 * because the manifest merger folds this library's manifest into the host app, that entry shipped
 * inside every partner's own manifest. A doc comment named the same id in prose. `CONTRIBUTING.md`
 * forbade both, and gave a reason that was not true while it was being read: "a non-production
 * variant appears nowhere in shipping code".
 *
 * The scan matters as much as the assertion. A sweep for the *allowed* string finds the forbidden
 * one and reports it as a match, because the forbidden id has the allowed one as a prefix — so the
 * obvious check is structurally incapable of failing. [APPLICATION_ID] therefore matches the whole
 * dotted id, greedily, and every match is compared for equality against the one that is permitted.
 *
 * This is a JVM-only test because the manifest is Android's. It reads files from the working tree,
 * which no other test here does; the alternative is a rule enforced only by whoever reviews the
 * diff, and this repository is published.
 */
class PublishedApplicationIdTest {

  @Test
  fun theLibraryManifestDeclaresOnlyTheProductionApplicationId() {
    val manifest = repositoryFile(MANIFEST_PATH).readText()

    val declared = QUERIED_PACKAGE.findAll(manifest).map { it.groupValues[1] }.toList()

    assertTrue(
      declared.isNotEmpty(),
      "No <queries><package> entry left in $MANIFEST_PATH. Without one the SDK reports " +
        "\"not installed\" for every user on API 30+, and this test would have nothing to check.",
    )
    assertEquals(
      listOf(AppPresenceChecker.ANDROID_PACKAGE),
      declared,
      "This manifest merges into every partner's app, so each package named here is published in " +
        "someone else's shipped manifest. Only the production application id belongs in it.",
    )
  }

  @Test
  fun noSourceFileNamesAnyOtherApplicationId() {
    val sources = repositoryFile(SOURCE_ROOT)
    var scanned = 0
    val offenders = mutableListOf<String>()

    val scannable = sources.walkTopDown().filter { it.isFile && it.extension in SCANNED_EXTENSIONS }
    for (file in scannable) {
      scanned += 1
      for (match in APPLICATION_ID.findAll(file.readText())) {
        if (match.value != AppPresenceChecker.ANDROID_PACKAGE) {
          offenders += "${file.name}: ${match.value}"
        }
      }
    }

    // Without this the test would pass on a mistyped path, having read nothing at all.
    assertTrue(scanned > 50, "Only $scanned files under $SOURCE_ROOT were read; the path is wrong.")
    assertEquals(
      emptyList(),
      offenders,
      "An application id other than the production one appears in published source. Naming a " +
        "non-production build discloses that it exists and nothing else — see CONTRIBUTING.md.",
    )
  }

  /**
   * [path] resolved against the repository root, found by walking up from the test's working
   * directory.
   *
   * Which directory that is depends on how the tests are launched, so it is searched for rather
   * than assumed — and a search that finds nothing fails here instead of leaving both tests above
   * quietly reading an empty tree.
   */
  private fun repositoryFile(path: String): File {
    var directory: File? = File(System.getProperty("user.dir")).absoluteFile
    while (directory != null) {
      val candidate = File(directory, path)
      if (candidate.exists()) return candidate
      directory = directory.parentFile
    }
    throw AssertionError(
      "Could not find $path above ${System.getProperty("user.dir")}, so nothing was checked."
    )
  }

  private companion object {
    const val MANIFEST_PATH = "tracive-sdk/src/androidMain/AndroidManifest.xml"
    const val SOURCE_ROOT = "tracive-sdk/src"

    val SCANNED_EXTENSIONS = setOf("kt", "kts", "xml")

    /** The `android:name` of a `<queries><package>` entry. */
    val QUERIED_PACKAGE = Regex("""<package\s+android:name="([^"]+)"""")

    /**
     * A whole dotted application id in this organisation's namespace.
     *
     * Greedy past the first dot on purpose: a pattern that stopped at the permitted id would match
     * the prefix of a forbidden one and report success.
     */
    val APPLICATION_ID = Regex("""com\.pleistocent(?:\.[A-Za-z0-9_]+)+""")
  }
}
