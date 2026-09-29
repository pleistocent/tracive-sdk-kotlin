package id.tracive.sdk.query

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * [BANK_CODES_JSON] is the vendored `bank-codes.json`, byte for byte.
 *
 * The constant is what ships; the file is what gets re-vendored from the Tracive API. If they
 * drifted, the SDK would translate codes with a table nobody reviewed. JVM-only because it reads the
 * working tree, which commonTest cannot.
 */
class BankCodesVendoredFileTest {

  @Test
  fun theEmbeddedTableIsTheVendoredFile() {
    assertContentEquals(
      vendoredFile().readBytes(),
      BANK_CODES_JSON.encodeToByteArray(),
      "BankCodesJson.kt is stale. Run `python3 tools/generate_bank_codes_kt.py`.",
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
    const val VENDORED_PATH = "tracive-sdk/src/commonTest/resources/bank-codes.json"
  }
}
