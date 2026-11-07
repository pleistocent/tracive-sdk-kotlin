package id.tracive.sdk.extended

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.datetime.LocalDate

private val LIMITS =
  ReportLimits(
    titleMaxLength = 50,
    chronologyMinLength = 50,
    chronologyMaxLength = 2000,
    commentMaxLength = 150,
    maxIdentifiers = 10,
    attachmentsPerIdentifier = 5,
    extraAttachmentsWithPhone = 5,
    maxAttachmentBytes = 1_048_575,
    attachmentTypes = emptyList(),
  )
private val TODAY = LocalDate(2026, 11, 6)
private val FILE = PreparedEvidence(byteArrayOf(1), "image/avif")

private fun draft(
  identifiers: List<String> = listOf("BANK_IDN_BCA_1"),
  title: String = "t",
  chronology: String = "c".repeat(50),
  comment: String? = null,
  incidentDate: String = "2026-11-06",
  evidence: List<PreparedEvidence> = listOf(FILE),
) =
  ReportDraft(
    identifiers = identifiers,
    title = title,
    chronology = chronology,
    comment = comment,
    incidentDate = incidentDate,
    evidence = evidence,
    acceptedTerms = emptyList(),
  )

class ReportDraftTest {

  private fun problems(draft: ReportDraft) = draft.problems(LIMITS, TODAY)

  @Test
  fun aGoodDraftHasNoProblems() {
    assertEquals(emptyList(), problems(draft()))
  }

  @Test
  fun eachLimitHasItsProblem() {
    val cases =
      listOf(
        draft(identifiers = emptyList()) to ReportFieldProblem.NO_IDENTIFIER,
        draft(identifiers = List(11) { "EMAIL_$it" }) to ReportFieldProblem.TOO_MANY_IDENTIFIERS,
        draft(title = " ") to ReportFieldProblem.TITLE_MISSING,
        draft(title = "t".repeat(51)) to ReportFieldProblem.TITLE_TOO_LONG,
        draft(chronology = "c".repeat(49)) to ReportFieldProblem.CHRONOLOGY_TOO_SHORT,
        draft(chronology = "c".repeat(2001)) to ReportFieldProblem.CHRONOLOGY_TOO_LONG,
        draft(comment = "c".repeat(151)) to ReportFieldProblem.COMMENT_TOO_LONG,
        draft(evidence = emptyList()) to ReportFieldProblem.NO_EVIDENCE,
        draft(evidence = List(6) { FILE }) to ReportFieldProblem.TOO_MANY_EVIDENCE,
        draft(incidentDate = "04/11/2026") to ReportFieldProblem.INCIDENT_DATE_INVALID,
        draft(incidentDate = "2026-11-07") to ReportFieldProblem.INCIDENT_DATE_IN_FUTURE,
      )
    cases.forEach { (draft, problem) -> assertEquals(listOf(problem), problems(draft)) }
  }

  @Test
  fun theBoundariesThemselvesPass() {
    assertEquals(
      emptyList(),
      problems(
        draft(title = "t".repeat(50), chronology = "c".repeat(2000), comment = "c".repeat(150))
      ),
    )
  }

  @Test
  fun aPhoneAddsTheExtraFiles() {
    val phone = listOf("PHONE_+6281234567891")
    assertEquals(emptyList(), problems(draft(identifiers = phone, evidence = List(10) { FILE })))
    assertEquals(
      listOf(ReportFieldProblem.TOO_MANY_EVIDENCE),
      problems(draft(identifiers = phone, evidence = List(11) { FILE })),
    )
  }

  @Test
  fun everyDraftHasItsOwnKey() {
    assertNotEquals(draft().idempotencyKey, draft().idempotencyKey)
  }
}
