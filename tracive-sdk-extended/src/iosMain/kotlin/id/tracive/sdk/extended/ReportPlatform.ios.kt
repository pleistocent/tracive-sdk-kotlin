@file:OptIn(ExperimentalForeignApi::class)

package id.tracive.sdk.extended

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.uname
import platform.posix.utsname

private const val INSTALL_ID = "id.tracive.sdk.extended.installId"

internal actual fun platformDevice(): ReportDevice {
  val defaults = NSUserDefaults.standardUserDefaults
  val installId =
    defaults.stringForKey(INSTALL_ID)
      ?: NSUUID().UUIDString.also { defaults.setObject(it, forKey = INSTALL_ID) }
  return ReportDevice(
    WireDevice(
      installId = installId,
      osName = "iOS",
      osVersion = UIDevice.currentDevice.systemVersion,
      manufacturer = "Apple",
      model = machineName(),
    ),
    language = if (NSLocale.currentLocale.languageCode == "id") "id" else "en",
  )
}

private fun machineName(): String = memScoped {
  val system = alloc<utsname>()
  uname(system.ptr)
  system.machine.toKString()
}

internal actual fun openExternalUrl(url: String): Boolean {
  val link = NSURL.URLWithString(url) ?: return false
  dispatch_async(dispatch_get_main_queue()) {
    UIApplication.sharedApplication.openURL(link, emptyMap<Any?, Any>(), null)
  }
  return true
}

@Suppress("UNCHECKED_CAST")
internal actual fun reportHttpEngine(): HttpClientEngineFactory<HttpClientEngineConfig> =
  Darwin as HttpClientEngineFactory<HttpClientEngineConfig>
