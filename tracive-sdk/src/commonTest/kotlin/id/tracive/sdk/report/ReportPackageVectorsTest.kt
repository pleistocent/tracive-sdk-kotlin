package id.tracive.sdk.report

import id.tracive.sdk.client.RecordingAuthorizeLauncher
import id.tracive.sdk.client.RecordingReportFormLauncher
import id.tracive.sdk.client.reportClient
import id.tracive.sdk.client.runSdkTest
import id.tracive.sdk.model.ReportAttachment
import id.tracive.sdk.model.ReportFormError
import id.tracive.sdk.model.ReportFormResult
import id.tracive.sdk.platform.ReportHandoff
import id.tracive.sdk.query.TraciveQuery
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drives every case of `report-package-vectors.json` through the public `openReportForm`, against a
 * launcher that accepts the package, and checks what it received as the fixture's `checking` says.
 */
class ReportPackageVectorsTest {

  private val fixture: JsonObject = Json.parseToJsonElement(REPORT_PACKAGE_VECTORS_JSON).jsonObject

  private val cases: List<JsonObject> = fixture.getValue("cases").jsonArray.map { it.jsonObject }

  @Test
  fun theEmbeddedFixtureIsTheWholeVendoredFile() {
    assertEquals(1, fixture.getValue("version").jsonPrimitive.int, "fixture version")
    assertEquals(19, cases.size, "case count — did the embedded copy get truncated?")
    assertEquals(5, fixture.getValue("reportLinks").jsonArray.size, "report link count")
    assertEquals(
      listOf(
        "INCIDENT_DATE_IN_FUTURE",
        "TOO_MANY_ATTACHMENTS",
        "per attachment in order: UNSUPPORTED_TYPE, CONTENT_MISMATCH, PDF_TOO_LARGE",
        "PACKAGE_TOO_LARGE",
      ),
      fixture.getValue("validationOrder").jsonArray.map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun theSdkLimitsAreTheFixtureLimits() {
    val limits = fixture.getValue("limits").jsonObject
    assertEquals(limits.int("pdfMaxBytesExclusive"), ReportPackage.PDF_MAX_BYTES_EXCLUSIVE)
    assertEquals(limits.int("maxAttachments"), ReportPackage.MAX_ATTACHMENTS)
    assertEquals(limits.int("maxAttachmentsPhone"), ReportPackage.MAX_ATTACHMENTS_PHONE)
    assertEquals(limits.int("packageMaxBytes"), ReportPackage.MAX_PACKAGE_BYTES)
  }

  @Test
  fun everyPackageCaseHandsOverExactlyTheExpectedPackage() {
    val packageCases = cases.filter { it.getValue("expect").jsonObject.containsKey("package") }
    assertEquals(8, packageCases.size)

    for (case in packageCases) {
      val name = case.string("name")
      val launcher = RecordingReportFormLauncher(ReportHandoff.PACKAGE)
      val result = open(case, launcher)

      assertEquals(ReportFormResult.Opened(attachmentsDelivered = true), result, name)
      assertEquals(1, launcher.packages.size, "$name: one package delivered")
      val delivered =
        Json.parseToJsonElement(launcher.packages.single().decodeToString()).jsonObject
      val expected = case.getValue("expect").jsonObject.getValue("package").jsonObject

      val expectedKeys = buildSet {
        addAll(listOf("v", "query", "attachments"))
        if (expected.containsKey("incidentDate")) add("incidentDate")
      }
      assertEquals(expectedKeys, delivered.keys, "$name: top-level keys")
      assertEquals(expected.int("v"), delivered.int("v"), "$name: v")
      assertEquals(expected.string("query"), delivered.string("query"), "$name: query")
      assertEquals(
        expected["incidentDate"]?.jsonPrimitive?.content,
        delivered["incidentDate"]?.jsonPrimitive?.content,
        "$name: incidentDate",
      )

      val inputs = case.getValue("attachments").jsonArray
      val expectedAttachments = expected.getValue("attachments").jsonArray
      val deliveredAttachments = delivered.getValue("attachments").jsonArray
      assertEquals(expectedAttachments.size, deliveredAttachments.size, "$name: attachment count")
      deliveredAttachments.forEachIndexed { index, element ->
        val attachment = element.jsonObject
        val want = expectedAttachments[index].jsonObject
        val label = "$name[$index]"
        assertEquals(setOf("name", "mimeType", "data"), attachment.keys, "$label: keys")
        assertEquals(want.string("name"), attachment.string("name"), "$label: name")
        assertEquals(want.string("mimeType"), attachment.string("mimeType"), "$label: mimeType")

        val data = attachment.string("data")
        assertTrue(STANDARD_BASE64.matches(data), "$label: standard base64, padded, one line")
        assertEquals(0, data.length % 4, "$label: padded to a multiple of four")
        assertContentEquals(
          generatedBytes(inputs[index].jsonObject),
          Base64.Default.decode(data),
          "$label: data decodes to the generated bytes",
        )
      }
    }
  }

  @Test
  fun everyErrorCaseFailsWithItsTypedErrorAndOpensNothing() {
    val errorCases = cases.filter { it.getValue("expect").jsonObject.containsKey("error") }
    assertEquals(11, errorCases.size)

    for (case in errorCases) {
      val name = case.string("name")
      val expect = case.getValue("expect").jsonObject
      val launcher = RecordingReportFormLauncher(ReportHandoff.PACKAGE)
      val installs = RecordingAuthorizeLauncher()

      val result = open(case, launcher, installs)

      val error = assertIs<ReportFormResult.Failed>(result, name).error
      assertEquals(expect.string("error"), error.code, "$name: code")
      val index = expect["index"]?.jsonPrimitive?.int
      val max = expect["max"]?.jsonPrimitive?.int
      when (error) {
        ReportFormError.IncidentDateInFuture -> assertNull(index ?: max, name)
        is ReportFormError.TooManyAttachments -> assertEquals(max, error.max, name)
        is ReportFormError.UnsupportedType -> assertEquals(index, error.index, name)
        is ReportFormError.ContentMismatch -> assertEquals(index, error.index, name)
        is ReportFormError.PdfTooLarge -> assertEquals(index, error.index, name)
        is ReportFormError.PackageTooLarge -> assertEquals(max, error.max, name)
        is ReportFormError.PlatformFailure -> fail("$name: $error is not a validation error")
      }
      assertTrue(launcher.prepared.isEmpty(), "$name: nothing was asked of the platform")
      assertTrue(launcher.packages.isEmpty() && launcher.links.isEmpty(), "$name: nothing opened")
      assertTrue(installs.installUrls.isEmpty(), "$name: no install page")
    }
  }

  @Test
  fun everyReportLinkMatchesItsVector() {
    for (element in fixture.getValue("reportLinks").jsonArray) {
      val vector = element.jsonObject
      assertEquals(
        vector.string("url"),
        reportLink(vector.string("scheme"), vector.string("query")),
        vector.string("query"),
      )
    }
  }

  @Test
  fun aPhoneLinkFromTheClientKeepsThePlusAsPercent2B() = runSdkTest {
    val vector =
      fixture
        .getValue("reportLinks")
        .jsonArray
        .map { it.jsonObject }
        .first { it.string("query").startsWith("PHONE_+") }
    val launcher = RecordingReportFormLauncher(ReportHandoff.LINK)

    val result =
      reportClient().openReportForm(queryFor(vector.string("query")), launcher = launcher)

    assertEquals(ReportFormResult.Opened(attachmentsDelivered = false), result)
    assertEquals(listOf(vector.string("url")), launcher.links)
    assertTrue(launcher.links.single().contains("%2B62"))
  }

  private fun open(
    case: JsonObject,
    launcher: RecordingReportFormLauncher,
    installs: RecordingAuthorizeLauncher = RecordingAuthorizeLauncher(),
  ): ReportFormResult {
    var result: ReportFormResult? = null
    runSdkTest {
      result =
        reportClient(installLauncher = installs)
          .openReportForm(
            query = queryFor(case.string("query")),
            incidentDate = case["incidentDate"]?.jsonPrimitive?.content?.let(LocalDate::parse),
            attachments =
              case.getValue("attachments").jsonArray.map { attachmentFor(it.jsonObject) },
            launcher = launcher,
          )
    }
    return result!!
  }

  private companion object {
    val STANDARD_BASE64 = Regex("[A-Za-z0-9+/]*={0,2}")

    /** The fixture's two query shapes, built through the public factories. */
    fun queryFor(raw: String): TraciveQuery {
      val query =
        when {
          raw.startsWith("PHONE_") -> TraciveQuery.phone(raw.removePrefix("PHONE_"))
          raw.startsWith("BANK_IDN_BCA_") ->
            TraciveQuery.bankAccount("IDN", "BCA", raw.removePrefix("BANK_IDN_BCA_"))
          else -> fail("No factory mapped for the fixture query $raw")
        }
      assertEquals(raw, query.searchQueryIndex, "the factory must fold to the fixture's query")
      return query
    }

    fun attachmentFor(input: JsonObject): ReportAttachment {
      val mimeType = input.string("mimeType")
      val bytes = generatedBytes(input)
      return if (mimeType.equals("application/pdf", ignoreCase = true)) {
        ReportAttachment.pdf(bytes)
      } else {
        ReportAttachment.image(bytes, mimeType)
      }
    }

    /**
     * Byte i is head[i] while i is inside the head, else i % 251, as the fixture's `bytes` says.
     */
    fun generatedBytes(input: JsonObject): ByteArray {
      val head = input.string("head").chunked(2).map { it.toInt(16).toByte() }
      return ByteArray(input.int("sizeBytes")) { i ->
        if (i < head.size) head[i] else (i % 251).toByte()
      }
    }

    fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
  }
}
