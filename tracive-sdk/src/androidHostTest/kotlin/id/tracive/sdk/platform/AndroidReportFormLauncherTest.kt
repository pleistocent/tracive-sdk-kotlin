package id.tracive.sdk.platform

import android.content.ContextWrapper
import id.tracive.sdk.model.ReportTarget
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Runs against the mockable android.jar with default return values: [ContextWrapper] answers null
 * for everything this test does not override, so the package manager is absent and the target reads
 * as not installed. That is enough to reach the cleanup, which runs before any of it.
 */
class AndroidReportFormLauncherTest {

  private val cacheDirectory: File = createTempDirectory("tracive-cache").toFile()

  private val context =
    object : ContextWrapper(null) {
      override fun getCacheDir(): File = cacheDirectory
    }

  @AfterTest
  fun deleteCache() {
    cacheDirectory.deleteRecursively()
  }

  @Test
  fun theNextCallDeletesThePackageAnEarlierCallLeft() = runBlocking {
    val folder = File(cacheDirectory, ReportPackageFiles.DIRECTORY)
    val stale =
      File(folder, "report.tracivereport").apply {
        parentFile!!.mkdirs()
        writeText("""{"v":1,"query":"PHONE_+6281234567890","attachments":[]}""")
      }
    val hostFile = File(cacheDirectory, "host-cache.bin").apply { writeText("not ours") }

    val handoff = AndroidReportFormLauncher(context).prepare(ReportTarget.Production)

    assertFalse(stale.exists(), "the earlier package must be gone")
    assertFalse(folder.exists())
    assertTrue(hostFile.exists(), "only the SDK's own folder is touched")
    assertEquals(ReportHandoff.APP_NOT_INSTALLED, handoff)
  }
}
