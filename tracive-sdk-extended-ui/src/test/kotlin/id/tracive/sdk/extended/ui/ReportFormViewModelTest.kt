package id.tracive.sdk.extended.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import id.tracive.sdk.extended.ReportErrorCode
import id.tracive.sdk.extended.ReportFieldProblem
import id.tracive.sdk.extended.ReportRequirements
import id.tracive.sdk.extended.ReportStatus
import id.tracive.sdk.extended.RequiredTerm
import id.tracive.sdk.extended.SubmitResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

private val TODAY = LocalDate(2026, 11, 6)
private const val KEY = "BANK_IDN_BCA_0000000001"
private const val STORY = "Dia menawarkan barang murah lewat DM, saya transfer, lalu saya diblokir."

private fun file() = EvidenceInput { PickedFile(byteArrayOf(1), "image/png", "a.png", null) }

@OptIn(ExperimentalCoroutinesApi::class)
class ReportFormViewModelTest {

  private val backend = FakeBackend()
  private val store = ViewModelStore()
  private val handle = SavedStateHandle()

  @BeforeTest
  fun main() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @AfterTest
  fun resetMain() {
    store.clear()
    Dispatchers.resetMain()
  }

  private fun provider(
    prefill: List<EvidenceInput>,
    savedState: SavedStateHandle,
    identifiers: List<String>,
    viewModels: ViewModelStore,
  ) =
    ViewModelProvider.create(
      viewModels,
      viewModelFactory {
        initializer {
          ReportFormViewModel(
            savedState,
            backend,
            identifiers,
            initialTitle = null,
            initialDate = null,
            prefill = prefill,
            today = { TODAY },
          )
        }
      },
    )

  private fun form(
    prefill: List<EvidenceInput> = emptyList(),
    savedState: SavedStateHandle = handle,
    identifiers: List<String> = listOf(KEY),
    viewModels: ViewModelStore = store,
  ) = provider(prefill, savedState, identifiers, viewModels)[ReportFormViewModel::class]

  private fun filledForm(): ReportFormViewModel =
    form().apply {
      onTitleChanged("Penipuan jual beli")
      onChronologyChanged(STORY)
      onDateChanged("2026-11-05")
      addEvidence(listOf(file()))
    }

  @Test
  fun aDoubleTapFilesOneReport() {
    val parked = ParkedSubmit(SubmitResult.Submitted("report-1", ReportStatus.SUBMITTED))
    backend.submitAnswers += parked.call
    val model = filledForm()

    model.send()
    model.send()
    assertTrue(model.sending)
    assertFalse(model.canSend)

    parked.release()
    assertEquals(1, backend.submitted.size)
    assertEquals(SentReport("report-1", ReportStatus.SUBMITTED), model.sent)
  }

  @Test
  fun aRetryKeepsTheDraftAndItsKey() {
    backend.submitAnswers += { SubmitResult.Failed(failure(ReportErrorCode.NETWORK)) }
    val model = filledForm()

    model.send()
    assertEquals(ReportErrorCode.NETWORK, model.failure?.code)
    assertTrue(isRetryable(ReportErrorCode.NETWORK))
    model.send()

    assertEquals(2, backend.submitted.size)
    assertSame(backend.submitted[0], backend.submitted[1])
    assertNull(model.failure)
  }

  @Test
  fun anEditAfterAFailureIsANewDraft() {
    backend.submitAnswers += { SubmitResult.Failed(failure(ReportErrorCode.INVALID_REPORT)) }
    val model = filledForm()

    model.send()
    model.onChronologyChanged("$STORY Ditambah detail baru.")
    model.send()

    assertEquals(2, backend.submitted.size)
    assertNotEquals(backend.submitted[0].idempotencyKey, backend.submitted[1].idempotencyKey)
    assertEquals(model.chronology, backend.submitted[1].chronology)
  }

  @Test
  fun outdatedTermsAreReadAgainAndTheNextTapBuildsANewDraft() {
    val newTerms = listOf(RequiredTerm("TERMS_OF_SERVICE", "v2", "https://tracive.id/terms-2"))
    backend.submitAnswers += { SubmitResult.Failed(failure(ReportErrorCode.TERMS_OUTDATED)) }
    val model = filledForm()
    backend.requirementAnswers += { REQUIREMENTS.copy(terms = newTerms) }

    model.send()
    assertEquals(newTerms, model.requirements?.terms)
    assertEquals(ReportErrorCode.TERMS_OUTDATED, model.failure?.code)
    assertEquals(1, backend.submitted.size)

    model.send()
    assertEquals(2, backend.submitted.size)
    assertNotEquals(backend.submitted[0].idempotencyKey, backend.submitted[1].idempotencyKey)
    assertEquals(newTerms, backend.submitted[1].acceptedTerms)
  }

  @Test
  fun outdatedTermsBuildANewDraftEvenWhenTheSameTermsComeBack() {
    backend.submitAnswers += { SubmitResult.Failed(failure(ReportErrorCode.TERMS_OUTDATED)) }
    val model = filledForm()

    model.send()
    model.send()

    assertNotEquals(backend.submitted[0].idempotencyKey, backend.submitted[1].idempotencyKey)
  }

  @Test
  fun termsThatCouldNotBeReadAreNotAcceptedByTheNextTap() {
    backend.submitAnswers += { SubmitResult.Failed(failure(ReportErrorCode.TERMS_OUTDATED)) }
    val model = filledForm()
    backend.requirementAnswers += { throw failure(ReportErrorCode.NETWORK) }

    model.send()
    assertEquals(ReportErrorCode.NETWORK, model.failure?.code)

    model.send()
    assertEquals(1, backend.submitted.size)
    assertEquals(3, backend.requirementCalls)
  }

  @Test
  fun closingTheFormCancelsTheUpload() {
    val parked = ParkedSubmit(SubmitResult.Submitted("report-1", ReportStatus.SUBMITTED))
    backend.submitAnswers += parked.call
    val model = filledForm()
    model.send()
    assertTrue(model.sending)

    store.clear()

    assertTrue(parked.cancelled)
    assertNull(model.sent)
  }

  @Test
  fun rotationKeepsTextAttachmentsAndAnUploadInFlight() {
    val parked = ParkedSubmit(SubmitResult.Submitted("report-1", ReportStatus.SUBMITTED))
    backend.submitAnswers += parked.call
    val model = filledForm()
    model.onCommentChanged("Hati-hati")
    model.send()

    val rotated = form()

    assertSame(model, rotated)
    assertEquals(1, rotated.evidence.size)
    assertFalse(parked.cancelled)
    parked.release()
    assertEquals("report-1", rotated.sent?.reportId)
  }

  @Test
  fun textComesBackFromSavedStateInANewViewModel() {
    val model = filledForm()
    model.onCommentChanged("Hati-hati")

    val restored = form(viewModels = ViewModelStore())

    assertEquals("Penipuan jual beli", restored.title)
    assertEquals(STORY, restored.chronology)
    assertEquals("Hati-hati", restored.comment)
    assertEquals("2026-11-05", restored.incidentDate)
  }

  @Test
  fun aHugePasteIsCutSoSavedStateStaysSmall() {
    val model = filledForm()

    model.onChronologyChanged("x".repeat(2_000_000))

    assertTrue(model.chronology.length < 3000)
    assertEquals(model.chronology, handle["chronology"])
  }

  @Test
  fun needsConsentFromSubmitEndsTheForm() {
    backend.submitAnswers += { SubmitResult.NeedsConsent }
    val model = filledForm()

    model.send()

    assertTrue(model.needsConsent)
    assertNull(model.failure)
  }

  @Test
  fun needsConsentFromRequirementsEndsTheForm() {
    backend.requirementAnswers += { throw failure(ReportErrorCode.NEEDS_CONSENT) }

    assertTrue(form().needsConsent)
  }

  @Test
  fun aFailedLoadCanBeRetried() {
    backend.requirementAnswers += { throw failure(ReportErrorCode.NETWORK) }
    val model = form()
    assertEquals(FormLoad.Failed(ReportErrorCode.NETWORK), model.load)

    model.retryLoad()

    assertEquals(FormLoad.Ready, model.load)
    assertEquals(REQUIREMENTS, model.requirements)
  }

  @Test
  fun reportingSwitchedOffBlocksSend() {
    backend.requirementAnswers += { REQUIREMENTS.copy(enabled = false) }
    val model = filledForm()

    model.send()

    assertEquals(SendBlock.REPORTING_DISABLED, model.sendBlock)
    assertFalse(model.canSend)
    assertTrue(backend.submitted.isEmpty())
  }

  @Test
  fun noReportsLeftTodayBlocksSend() {
    backend.requirementAnswers += { REQUIREMENTS.copy(remainingToday = 0) }
    val model = filledForm()

    model.send()

    assertEquals(SendBlock.DAILY_LIMIT, model.sendBlock)
    assertTrue(backend.submitted.isEmpty())
  }

  @Test
  fun theServerSayingTheLimitIsReachedBlocksSendAndKeepsItsWait() {
    backend.submitAnswers += {
      SubmitResult.Failed(failure(ReportErrorCode.DAILY_LIMIT_REACHED, retryAfterSeconds = 7200))
    }
    val model = filledForm()

    model.send()

    assertEquals(7200, model.failure?.retryAfterSeconds)
    assertEquals(SendBlock.DAILY_LIMIT, model.sendBlock)
  }

  @Test
  fun anUnverifiedEmailCanBeRetriedWithTheSameDraft() {
    backend.submitAnswers += { SubmitResult.Failed(failure(ReportErrorCode.EMAIL_NOT_VERIFIED)) }
    val model = filledForm()

    model.send()
    assertEquals(ReportErrorCode.EMAIL_NOT_VERIFIED, model.failure?.code)
    assertTrue(model.canSend)
    model.send()

    assertSame(backend.submitted[0], backend.submitted[1])
  }

  @Test
  fun anUnexpectedExceptionBecomesAFailureAndNotACrash() {
    backend.submitAnswers += { throw IllegalStateException("from a partner's token closure") }
    val model = filledForm()

    model.send()

    assertEquals(ReportErrorCode.SERVER, model.failure?.code)
    assertFalse(model.sending)
  }

  @Test
  fun problemsAreShownOnlyAfterATapAndClearAsTheyAreFixed() {
    val model = form()
    assertTrue(model.problems.isEmpty())

    model.send()

    assertEquals(
      listOf(
        ReportFieldProblem.INCIDENT_DATE_INVALID,
        ReportFieldProblem.TITLE_MISSING,
        ReportFieldProblem.CHRONOLOGY_TOO_SHORT,
        ReportFieldProblem.NO_EVIDENCE,
      ),
      model.problems,
    )
    assertEquals(1, model.attentionRequests)
    assertTrue(backend.submitted.isEmpty())

    model.onTitleChanged("Judul")
    assertFalse(ReportFieldProblem.TITLE_MISSING in model.problems)
  }

  @Test
  fun aDateInTheFutureIsRefused() {
    val model = filledForm()

    model.onDateChanged("2026-11-07")
    model.send()

    assertTrue(ReportFieldProblem.INCIDENT_DATE_IN_FUTURE in model.problems)
    assertTrue(backend.submitted.isEmpty())
  }

  @Test
  fun aFileStillPreparingHoldsTheSendBack() {
    val gate = CompletableDeferred<Unit>()
    backend.prepareAnswer = {
      gate.await()
      evidence()
    }
    val model = filledForm()

    model.send()
    assertEquals(EvidenceBlock.STILL_PREPARING, model.evidenceBlock)
    assertTrue(backend.submitted.isEmpty())

    gate.complete(Unit)
    model.send()
    assertEquals(1, backend.submitted.size)
  }

  @Test
  fun eachFileFailsOnItsOwn() {
    backend.prepareAnswer = { mimeType ->
      if (mimeType == MIME_PDF) throw failure(ReportErrorCode.FILE_TOO_LARGE) else evidence()
    }
    val model = filledForm()
    val pdf = EvidenceInput { PickedFile(byteArrayOf(1), MIME_PDF, "b.pdf", null) }

    model.addEvidence(listOf(pdf))

    val failed = model.evidence[1]
    assertEquals(ReportErrorCode.FILE_TOO_LARGE, assertIs<EvidenceState.Failed>(failed.state).code)
    assertIs<EvidenceState.Ready>(model.evidence[0].state)

    model.send()
    assertEquals(EvidenceBlock.FILE_FAILED, model.evidenceBlock)
    assertTrue(backend.submitted.isEmpty())

    model.removeEvidence(failed.id)
    model.send()
    assertEquals(1, backend.submitted.size)
  }

  @Test
  fun theFileLimitComesFromTheServerAndCountsAPhone() {
    val bank = form(viewModels = ViewModelStore())
    val phone =
      form(
        identifiers = listOf("PHONE_+6281234567891"),
        savedState = SavedStateHandle(),
        viewModels = ViewModelStore(),
      )
    assertEquals(2, bank.maxEvidence)
    assertEquals(5, phone.maxEvidence)

    bank.addEvidence(listOf(file(), file(), file()))

    assertEquals(2, bank.evidence.size)
    assertTrue(bank.evidenceOverflow)
    assertEquals(0, bank.evidenceRoom)
  }

  @Test
  fun aFileThatFailedGivesItsPlaceBack() {
    backend.prepareAnswer = { throw failure(ReportErrorCode.IMAGE_UNREADABLE) }
    val model = form()

    model.addEvidence(listOf(file()))

    assertEquals(2, model.evidenceRoom)
  }

  @Test
  fun removingAFileStopsItsCompression() {
    var cancelled = false
    val gate = CompletableDeferred<Unit>()
    backend.prepareAnswer = {
      try {
        gate.await()
        evidence()
      } catch (cancellation: kotlinx.coroutines.CancellationException) {
        cancelled = true
        throw cancellation
      }
    }
    val model = filledForm()

    model.removeEvidence(model.evidence.single().id)

    assertTrue(cancelled)
    assertTrue(model.evidence.isEmpty())
  }

  @Test
  fun prefilledAttachmentsWaitForRequirementsThenCompress() {
    val gate = CompletableDeferred<ReportRequirements>()
    backend.requirementAnswers += { gate.await() }
    val model = form(prefill = listOf(file()))
    assertTrue(model.evidence.isEmpty())

    gate.complete(REQUIREMENTS)

    assertIs<EvidenceState.Ready>(model.evidence.single().state)
  }

  @Test
  fun sendingShowsProgress() {
    val parked = ParkedSubmit(SubmitResult.Submitted("report-1", ReportStatus.SUBMITTED))
    backend.progressDuringSubmit = listOf(0.25f, 0.5f)
    backend.submitAnswers += parked.call
    val model = filledForm()

    model.send()

    assertEquals(0.5f, model.progress)
    parked.release()
  }

  @Test
  fun aSentReportShowsTheCheckedStatus() {
    backend.statusAnswer = { ReportStatus.IN_REVIEW }
    val model = filledForm()

    model.send()

    assertEquals(listOf("report-1"), backend.statusCalls)
    assertEquals(ReportStatus.IN_REVIEW, model.checkedStatus)
  }

  @Test
  fun withoutReportReadTheStatusStaysHidden() {
    backend.statusAnswer = { throw failure(ReportErrorCode.NEEDS_CONSENT) }
    val model = filledForm()

    model.send()

    assertEquals("report-1", model.sent?.reportId)
    assertNull(model.checkedStatus)
    assertFalse(model.needsConsent)
  }

  @Test
  fun theDraftCarriesTrimmedTextAndTheTermsShown() {
    val model = filledForm()
    model.onTitleChanged("  Judul  ")
    model.onCommentChanged("   ")

    model.send()

    val draft = backend.submitted.single()
    assertEquals("Judul", draft.title)
    assertNull(draft.comment)
    assertEquals(TERMS, draft.acceptedTerms)
    assertEquals(listOf(KEY), draft.identifiers)
  }
}
