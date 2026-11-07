package id.tracive.sdk.extended

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.delay

/** A session the server no longer knows. The caller opens new ones. */
internal class UploadSessionGone : Exception("The upload session has ended.")

/**
 * Sends one file to a resumable upload session.
 *
 * The session URL is itself the credential, so no request here carries a token: a bearer token must
 * never reach Cloud Storage.
 */
internal class EvidenceUploader(
  private val http: HttpClient,
  private val backoffMillis: Long = 1_000,
) {

  suspend fun upload(url: String, bytes: ByteArray) {
    var offset = 0
    var attempt = 0
    while (true) {
      val failure: Throwable? =
        try {
          val response = http.put(url) { bodyFrom(bytes, offset) }
          when {
            response.isComplete -> return
            response.status.value == RESUME_INCOMPLETE -> offset = response.nextOffset()
            response.isGone -> throw UploadSessionGone()
            response.status.value < 500 -> throw refused(response.status.value)
          }
          null
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
          throw cancelled
        } catch (gone: UploadSessionGone) {
          throw gone
        } catch (failure: TraciveReportException) {
          throw failure
        } catch (failure: Exception) {
          failure
        }

      attempt++
      if (attempt >= MAX_ATTEMPTS) {
        throw TraciveReportException(
          ReportErrorCode.NETWORK,
          "The evidence could not be uploaded.",
          cause = failure,
        )
      }
      delay(backoffMillis * attempt)
      offset = committedOffset(url, bytes.size, offset) ?: return
    }
  }

  private fun io.ktor.client.request.HttpRequestBuilder.bodyFrom(bytes: ByteArray, offset: Int) {
    if (offset > 0) {
      header(HttpHeaders.ContentRange, "bytes $offset-${bytes.size - 1}/${bytes.size}")
    }
    setBody(ByteArrayContent(if (offset == 0) bytes else bytes.copyOfRange(offset, bytes.size)))
  }

  /** How much the session holds, or null when it already has the whole file. */
  private suspend fun committedOffset(url: String, size: Int, known: Int): Int? =
    try {
      val response =
        http.put(url) {
          header(HttpHeaders.ContentRange, "bytes */$size")
          setBody(ByteArrayContent(ByteArray(0)))
        }
      when {
        response.isComplete -> null
        response.status.value == RESUME_INCOMPLETE -> response.nextOffset()
        response.isGone -> throw UploadSessionGone()
        else -> known
      }
    } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
      throw cancelled
    } catch (gone: UploadSessionGone) {
      throw gone
    } catch (_: Exception) {
      known
    }

  private val HttpResponse.isComplete: Boolean
    get() = status.value == 200 || status.value == 201

  private val HttpResponse.isGone: Boolean
    get() = status.value == 404 || status.value == 410

  /** `Range: bytes=0-524287` means the next byte to send is 524288. No header means none. */
  private fun HttpResponse.nextOffset(): Int =
    headers[HttpHeaders.Range]?.substringAfterLast('-')?.toIntOrNull()?.plus(1) ?: 0

  private fun refused(status: Int) =
    TraciveReportException(
      ReportErrorCode.ATTACHMENT_INVALID,
      "Cloud Storage refused the evidence ($status).",
      status,
    )

  private companion object {
    const val MAX_ATTEMPTS = 4
    const val RESUME_INCOMPLETE = 308
  }
}
