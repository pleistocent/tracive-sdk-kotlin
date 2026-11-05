package id.tracive.sdk.report

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * [REPORT_PACKAGE_VECTORS_JSON] is the vendored `report-package-vectors.json`, byte for byte, so
 * the common tests read the contract the other SDKs read. JVM-only: it reads the working tree.
 */
class ReportPackageVendoredFileTest {

  @Test
  fun theEmbeddedFixtureIsTheVendoredFile() {
    assertContentEquals(
      vendoredFile().readBytes(),
      REPORT_PACKAGE_VECTORS_JSON.encodeToByteArray(),
      "ReportPackageVectorsJson.kt is stale. Run `python3 tools/generate_report_vectors_kt.py`.",
    )
  }

  private fun vendoredFile(): File {
    var directory: File? = File(System.getProperty("user.dir")!!).absoluteFile
    while (directory != null) {
      val candidate = File(directory, VENDORED_PATH)
      if (candidate.exists()) return candidate
      directory = directory.parentFile
    }
    throw AssertionError("Could not find $VENDORED_PATH above ${System.getProperty("user.dir")}.")
  }

  private companion object {
    const val VENDORED_PATH = "tracive-sdk/src/commonTest/resources/report-package-vectors.json"
  }
}
