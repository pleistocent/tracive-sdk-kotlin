package id.tracive.sdk.extended.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.tracive.sdk.extended.PreparedEvidence
import id.tracive.sdk.extended.ReportDraft
import id.tracive.sdk.extended.ReportErrorCode
import id.tracive.sdk.extended.ReportFieldProblem
import id.tracive.sdk.extended.ReportLimits
import id.tracive.sdk.extended.ReportRequirements
import id.tracive.sdk.extended.ReportStatus
import id.tracive.sdk.extended.SubmitResult
import id.tracive.sdk.extended.TraciveReportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

private const val KEY_TITLE = "title"
private const val KEY_CHRONOLOGY = "chronology"
private const val KEY_COMMENT = "comment"
private const val KEY_DATE = "incidentDate"

// Typing past a limit shows its problem, so the text may run a little over. It stops here because
// text goes into saved state, and a huge paste there crashes the Activity with
// TransactionTooLargeException when it stops.
private const val OVER_LIMIT_ALLOWANCE = 500

internal sealed interface FormLoad {
  data object Loading : FormLoad

  data class Failed(val code: ReportErrorCode) : FormLoad

  data object Ready : FormLoad
}

internal sealed interface EvidenceState {
  data object Preparing : EvidenceState

  class Ready(val prepared: PreparedEvidence) : EvidenceState

  class Failed(val code: ReportErrorCode) : EvidenceState
}

internal data class EvidenceItem(
  val id: Long,
  val state: EvidenceState = EvidenceState.Preparing,
  val name: String? = null,
  val mimeType: String? = null,
  val thumbnail: ImageBitmap? = null,
)

/** Why Send cannot work at all right now, as opposed to a field that needs fixing. */
internal enum class SendBlock {
  REPORTING_DISABLED,
  DAILY_LIMIT,
}

/** Why the evidence stops a send, on top of the core's field problems. */
internal enum class EvidenceBlock {
  STILL_PREPARING,
  FILE_FAILED,
}

internal data class SendFailure(val code: ReportErrorCode, val retryAfterSeconds: Long? = null)

internal data class SentReport(val reportId: String, val status: ReportStatus)

internal class ReportFormViewModel(
  private val handle: SavedStateHandle,
  private val backend: ReportBackend,
  val identifiers: List<String>,
  initialTitle: String?,
  initialDate: String?,
  private val prefill: List<EvidenceInput>,
  alreadySent: SentReport? = null,
  private val today: () -> LocalDate = ::deviceToday,
) : ViewModel() {

  var load: FormLoad by mutableStateOf(FormLoad.Loading)
    private set

  var requirements: ReportRequirements? by mutableStateOf(null)
    private set

  var title: String by mutableStateOf(handle[KEY_TITLE] ?: initialTitle.orEmpty())
    private set

  var chronology: String by mutableStateOf(handle[KEY_CHRONOLOGY] ?: "")
    private set

  var comment: String by mutableStateOf(handle[KEY_COMMENT] ?: "")
    private set

  var incidentDate: String? by mutableStateOf(handle[KEY_DATE] ?: initialDate)
    private set

  var evidence: List<EvidenceItem> by mutableStateOf(emptyList())
    private set

  /** The user picked more than the form can take, and the rest was left out. */
  var evidenceOverflow: Boolean by mutableStateOf(false)
    private set

  var sending: Boolean by mutableStateOf(false)
    private set

  var progress: Float by mutableFloatStateOf(0f)
    private set

  var failure: SendFailure? by mutableStateOf(null)
    private set

  var sent: SentReport? by mutableStateOf(null)
    private set

  /** The status the server gave after sending. Null hides it: `report.read` is optional. */
  var checkedStatus: ReportStatus? by mutableStateOf(null)
    private set

  var needsConsent: Boolean by mutableStateOf(false)
    private set

  /** Moves each time a send stops at a field, so the screen can scroll to it. */
  var attentionRequests: Int by mutableIntStateOf(0)
    private set

  private var termsStale by mutableStateOf(false)
  private var termsLoading by mutableStateOf(false)
  private var refused: SendBlock? by mutableStateOf(null)
  private var validated by mutableStateOf(false)

  private var draft: ReportDraft? = null
  private var nextEvidenceId = 0L
  private var prefillStarted = false
  private val evidenceJobs = mutableMapOf<Long, Job>()

  val sendBlock: SendBlock? by derivedStateOf {
    val current = requirements
    when {
      refused != null -> refused
      current == null -> null
      !current.enabled -> SendBlock.REPORTING_DISABLED
      current.remainingToday <= 0 -> SendBlock.DAILY_LIMIT
      else -> null
    }
  }

  val canSend: Boolean by derivedStateOf {
    requirements != null && sendBlock == null && !sending && !termsLoading
  }

  val problems: List<ReportFieldProblem> by derivedStateOf {
    val current = requirements
    if (!validated || current == null) emptyList() else problemsIn(candidate(current), current)
  }

  val evidenceBlock: EvidenceBlock? by derivedStateOf {
    if (validated) findEvidenceBlock() else null
  }

  val maxEvidence: Int by derivedStateOf {
    requirements?.let { candidate(it).maxEvidence(it.limits) } ?: 0
  }

  /** How many more files fit. A file that failed takes no place: it can never be sent. */
  val evidenceRoom: Int by derivedStateOf {
    (maxEvidence - evidence.count { it.state !is EvidenceState.Failed }).coerceAtLeast(0)
  }

  init {
    if (alreadySent != null) onSubmitted(alreadySent) else loadRequirements()
  }

  fun retryLoad() {
    load = FormLoad.Loading
    loadRequirements()
  }

  fun onTitleChanged(value: String) {
    title = bounded(value) { it.titleMaxLength }
    handle[KEY_TITLE] = title
  }

  fun onChronologyChanged(value: String) {
    chronology = bounded(value) { it.chronologyMaxLength }
    handle[KEY_CHRONOLOGY] = chronology
  }

  fun onCommentChanged(value: String) {
    comment = bounded(value) { it.commentMaxLength }
    handle[KEY_COMMENT] = comment
  }

  fun onDateChanged(value: String) {
    incidentDate = value
    handle[KEY_DATE] = value
  }

  fun addEvidence(inputs: List<EvidenceInput>) {
    val room = evidenceRoom
    evidenceOverflow = inputs.size > room
    inputs.take(room).forEach(::startEvidence)
  }

  fun removeEvidence(id: Long) {
    evidenceJobs.remove(id)?.cancel()
    evidence = evidence.filterNot { it.id == id }
    evidenceOverflow = false
  }

  fun send() {
    val current = requirements
    if (current == null || !canSend) return
    if (termsStale) {
      refreshTerms()
      return
    }
    validated = true
    val next = candidate(current)
    if (findEvidenceBlock() != null || problemsIn(next, current).isNotEmpty()) {
      attentionRequests++
      return
    }
    // A retry must keep its idempotency key. An edited report cannot: the core sends the first
    // body again for a key it has seen, which would quietly drop the edit.
    val chosen = draft?.takeIf { it.sameReportAs(next) } ?: next
    draft = chosen
    sending = true
    progress = 0f
    failure = null
    viewModelScope.launch {
      try {
        when (val result = backend.submit(chosen) { fraction -> progress = fraction }) {
          is SubmitResult.Submitted -> onSubmitted(SentReport(result.reportId, result.status))
          SubmitResult.NeedsConsent -> needsConsent = true
          is SubmitResult.Failed -> onSendFailed(result.error)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: TraciveReportException) {
        onSendFailed(error)
      } catch (_: Exception) {
        failure = SendFailure(ReportErrorCode.SERVER)
      } finally {
        sending = false
      }
    }
  }

  private fun loadRequirements() {
    viewModelScope.launch {
      try {
        requirements = backend.requirements()
        load = FormLoad.Ready
        startPrefill()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: TraciveReportException) {
        if (error.code == ReportErrorCode.NEEDS_CONSENT) needsConsent = true
        else load = FormLoad.Failed(error.code)
      } catch (_: Exception) {
        load = FormLoad.Failed(ReportErrorCode.SERVER)
      }
    }
  }

  private fun startPrefill() {
    if (prefillStarted) return
    prefillStarted = true
    prefill.forEach(::startEvidence)
  }

  private fun startEvidence(input: EvidenceInput) {
    val id = nextEvidenceId++
    evidence = evidence + EvidenceItem(id)
    evidenceJobs[id] = viewModelScope.launch {
      try {
        val file = input.read()
        update(id) {
          it.copy(name = file.name, mimeType = file.mimeType, thumbnail = file.thumbnail)
        }
        val prepared = backend.prepare(file.bytes, file.mimeType)
        update(id) { it.copy(state = EvidenceState.Ready(prepared)) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: TraciveReportException) {
        update(id) { it.copy(state = EvidenceState.Failed(error.code)) }
      } catch (_: Exception) {
        update(id) { it.copy(state = EvidenceState.Failed(ReportErrorCode.IMAGE_UNREADABLE)) }
      }
    }
  }

  private fun update(id: Long, change: (EvidenceItem) -> EvidenceItem) {
    evidence = evidence.map { if (it.id == id) change(it) else it }
  }

  private fun onSubmitted(report: SentReport) {
    sent = report
    viewModelScope.launch {
      try {
        checkedStatus = backend.status(report.reportId)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // A partner without `report.read` gets NEEDS_CONSENT here. Sending worked either way, so
        // the status line just stays hidden.
      }
    }
  }

  private fun onSendFailed(error: TraciveReportException) {
    failure = SendFailure(error.code, error.retryAfterSeconds)
    when (error.code) {
      ReportErrorCode.TERMS_OUTDATED -> {
        draft = null
        termsStale = true
        refreshTerms()
      }
      ReportErrorCode.DAILY_LIMIT_REACHED -> refused = SendBlock.DAILY_LIMIT
      ReportErrorCode.REPORTING_DISABLED -> refused = SendBlock.REPORTING_DISABLED
      else -> Unit
    }
  }

  private fun refreshTerms() {
    termsLoading = true
    viewModelScope.launch {
      try {
        requirements = backend.requirements()
        termsStale = false
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: TraciveReportException) {
        if (error.code == ReportErrorCode.NEEDS_CONSENT) needsConsent = true
        else failure = SendFailure(error.code)
      } catch (_: Exception) {
        failure = SendFailure(ReportErrorCode.SERVER)
      } finally {
        termsLoading = false
      }
    }
  }

  private fun candidate(current: ReportRequirements) =
    ReportDraft(
      identifiers = identifiers,
      title = title.trim(),
      chronology = chronology.trim(),
      comment = comment.trim().ifEmpty { null },
      incidentDate = incidentDate.orEmpty(),
      evidence = evidence.mapNotNull { (it.state as? EvidenceState.Ready)?.prepared },
      acceptedTerms = current.terms,
    )

  private fun problemsIn(report: ReportDraft, current: ReportRequirements) =
    report.problems(current.limits, today())

  private fun findEvidenceBlock(): EvidenceBlock? =
    when {
      evidence.any { it.state is EvidenceState.Failed } -> EvidenceBlock.FILE_FAILED
      evidence.any { it.state is EvidenceState.Preparing } -> EvidenceBlock.STILL_PREPARING
      else -> null
    }

  private fun bounded(value: String, limit: (ReportLimits) -> Int): String {
    val max = requirements?.limits?.let(limit) ?: return value
    return value.take(max + OVER_LIMIT_ALLOWANCE)
  }
}

private fun ReportDraft.sameReportAs(other: ReportDraft) =
  title == other.title &&
    chronology == other.chronology &&
    comment == other.comment &&
    incidentDate == other.incidentDate &&
    evidence == other.evidence &&
    acceptedTerms == other.acceptedTerms

internal fun deviceToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
