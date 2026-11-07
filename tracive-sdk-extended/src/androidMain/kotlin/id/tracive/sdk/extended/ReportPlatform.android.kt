package id.tracive.sdk.extended

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import id.tracive.sdk.TraciveAndroid
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import java.util.Locale
import java.util.UUID

private const val PREFERENCES = "id.tracive.sdk.extended"
private const val INSTALL_ID = "installId"

internal actual fun platformDevice(): ReportDevice {
  val preferences =
    TraciveAndroid.requireContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
  val installId =
    preferences.getString(INSTALL_ID, null)
      ?: UUID.randomUUID().toString().also {
        preferences.edit().putString(INSTALL_ID, it).apply()
      }
  return ReportDevice(
    WireDevice(
      installId = installId,
      osName = "Android",
      osVersion = Build.VERSION.RELEASE.orEmpty(),
      manufacturer = Build.MANUFACTURER.orEmpty(),
      model = Build.MODEL.orEmpty(),
    ),
    language = if (Locale.getDefault().language == "in" || Locale.getDefault().language == "id") "id" else "en",
  )
}

internal actual fun openExternalUrl(url: String): Boolean =
  try {
    TraciveAndroid.requireContext()
      .startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    true
  } catch (_: ActivityNotFoundException) {
    false
  }

@Suppress("UNCHECKED_CAST")
internal actual fun reportHttpEngine(): HttpClientEngineFactory<HttpClientEngineConfig> =
  OkHttp as HttpClientEngineFactory<HttpClientEngineConfig>
