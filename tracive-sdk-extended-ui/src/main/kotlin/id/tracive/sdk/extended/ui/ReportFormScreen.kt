package id.tracive.sdk.extended.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import id.tracive.sdk.extended.ReportErrorCode
import id.tracive.sdk.extended.ReportFieldProblem
import id.tracive.sdk.extended.ReportLimits
import id.tracive.sdk.extended.ReportStatus
import id.tracive.sdk.extended.RequiredTerm
import java.util.Locale
import kotlinx.datetime.LocalDate

// The picker throws on a limit above the platform's, and nobody picks more than this at once.
private const val MAX_PICK_AT_ONCE = 20

private val DATE_PROBLEMS =
  setOf(ReportFieldProblem.INCIDENT_DATE_INVALID, ReportFieldProblem.INCIDENT_DATE_IN_FUTURE)
private val TITLE_PROBLEMS =
  setOf(ReportFieldProblem.TITLE_MISSING, ReportFieldProblem.TITLE_TOO_LONG)
private val CHRONOLOGY_PROBLEMS =
  setOf(ReportFieldProblem.CHRONOLOGY_TOO_SHORT, ReportFieldProblem.CHRONOLOGY_TOO_LONG)
private val COMMENT_PROBLEMS = setOf(ReportFieldProblem.COMMENT_TOO_LONG)
private val EVIDENCE_PROBLEMS =
  setOf(ReportFieldProblem.NO_EVIDENCE, ReportFieldProblem.TOO_MANY_EVIDENCE)
private val MEDIA_PROBLEMS =
  setOf(ReportFieldProblem.NO_IDENTIFIER, ReportFieldProblem.TOO_MANY_IDENTIFIERS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportFormScreen(
  model: ReportFormViewModel,
  onClose: () -> Unit,
  onDone: () -> Unit,
  onViewStatus: (String) -> Unit,
) {
  Scaffold(
    containerColor = MaterialTheme.colorScheme.surface,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.tracive_report_form_title)) },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(
              painterResource(R.drawable.tracive_report_ic_close),
              stringResource(R.string.tracive_report_close),
            )
          }
        },
      )
    },
  ) { padding ->
    val sent = model.sent
    val limits = model.requirements?.limits
    when {
      model.needsConsent -> Unit
      sent != null ->
        SentContent(
          model.checkedStatus,
          padding,
          onDone = onDone,
          onViewStatus = { onViewStatus(sent.reportId) },
        )
      model.load == FormLoad.Loading -> LoadingContent(padding)
      model.load is FormLoad.Failed ->
        LoadFailedContent((model.load as FormLoad.Failed).code, padding, model::retryLoad)
      limits != null -> FormContent(model, limits, padding)
    }
  }
}

@Composable
private fun FormContent(model: ReportFormViewModel, limits: ReportLimits, padding: PaddingValues) {
  val scroll = rememberScrollState()
  val focus = LocalFocusManager.current
  val date = remember { BringIntoViewRequester() }
  val title = remember { BringIntoViewRequester() }
  val chronology = remember { BringIntoViewRequester() }
  val comment = remember { BringIntoViewRequester() }
  val evidence = remember { BringIntoViewRequester() }
  val problems = model.problems
  val enabled = !model.sending

  LaunchedEffect(model.attentionRequests) {
    if (model.attentionRequests == 0) return@LaunchedEffect
    focus.clearFocus()
    when {
      problems.any { it in DATE_PROBLEMS } -> date.bringIntoView()
      problems.any { it in TITLE_PROBLEMS } -> title.bringIntoView()
      problems.any { it in CHRONOLOGY_PROBLEMS } -> chronology.bringIntoView()
      problems.any { it in COMMENT_PROBLEMS } -> comment.bringIntoView()
      else -> evidence.bringIntoView()
    }
  }

  // The progress bar appears above the button and would push it off the screen.
  LaunchedEffect(model.sending) {
    if (model.sending) {
      withFrameNanos {}
      scroll.animateScrollTo(scroll.maxValue)
    }
  }

  Column(
    Modifier.padding(padding)
      .consumeWindowInsets(padding)
      .imePadding()
      .verticalScroll(scroll)
      .padding(horizontal = 16.dp)
      .padding(top = 8.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    model.sendBlock?.let { SendBlockNotice(it) }
    IdentifiersSection(model.identifiers, problems, limits, model.maxEvidence)
    DateField(
      model.incidentDate,
      problems,
      limits,
      enabled,
      onChange = model::onDateChanged,
      modifier = Modifier.bringIntoViewRequester(date),
    )
    OutlinedTextField(
      value = model.title,
      onValueChange = model::onTitleChanged,
      enabled = enabled,
      singleLine = true,
      label = { Text(stringResource(R.string.tracive_report_title_label)) },
      placeholder = { Text(stringResource(R.string.tracive_report_title_placeholder)) },
      keyboardOptions = sentences(),
      isError = problems.any { it in TITLE_PROBLEMS },
      supportingText = {
        CountedHint(
          problems.firstOrNull { it in TITLE_PROBLEMS }?.let { problemText(it, limits, 0) },
          count = model.title.trim().length,
          max = limits.titleMaxLength,
        )
      },
      modifier = Modifier.fillMaxWidth().bringIntoViewRequester(title),
    )
    Column(Modifier.bringIntoViewRequester(chronology)) {
      SectionHeader(
        stringResource(R.string.tracive_report_chronology_label),
        stringResource(R.string.tracive_report_chronology_description, limits.chronologyMinLength),
      )
      OutlinedTextField(
        value = model.chronology,
        onValueChange = model::onChronologyChanged,
        enabled = enabled,
        minLines = 5,
        placeholder = { Text(stringResource(R.string.tracive_report_chronology_placeholder)) },
        keyboardOptions = sentences(),
        isError = problems.any { it in CHRONOLOGY_PROBLEMS },
        supportingText = {
          CountedHint(
            problems.firstOrNull { it in CHRONOLOGY_PROBLEMS }?.let { problemText(it, limits, 0) },
            count = model.chronology.trim().length,
            max = limits.chronologyMaxLength,
          )
        },
        modifier = Modifier.fillMaxWidth(),
      )
    }
    Column(Modifier.bringIntoViewRequester(comment)) {
      SectionHeader(
        stringResource(R.string.tracive_report_comment_label),
        stringResource(R.string.tracive_report_comment_description),
      )
      OutlinedTextField(
        value = model.comment,
        onValueChange = model::onCommentChanged,
        enabled = enabled,
        minLines = 2,
        keyboardOptions = sentences(),
        isError = problems.any { it in COMMENT_PROBLEMS },
        supportingText = {
          CountedHint(
            problems.firstOrNull { it in COMMENT_PROBLEMS }?.let { problemText(it, limits, 0) },
            count = model.comment.trim().length,
            max = limits.commentMaxLength,
          )
        },
        modifier = Modifier.fillMaxWidth(),
      )
    }
    EvidenceSection(model, limits, enabled, Modifier.bringIntoViewRequester(evidence))
    SendSection(model)
  }
}

private fun sentences() = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

@Composable
private fun SendBlockNotice(block: SendBlock) {
  Notice(
    stringResource(
      when (block) {
        SendBlock.REPORTING_DISABLED -> R.string.tracive_report_error_reporting_disabled
        SendBlock.DAILY_LIMIT -> R.string.tracive_report_error_daily_limit
      }
    )
  )
}

@Composable
private fun Notice(text: String, action: (@Composable () -> Unit)? = null) {
  Surface(
    color = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
      action?.invoke()
    }
  }
}

@Composable
private fun SectionHeader(title: String, description: String? = null) {
  Text(
    title,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onSurface,
  )
  if (description != null) {
    Text(
      description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp),
    )
  }
  Spacer(Modifier.height(8.dp))
}

@Composable
private fun CountedHint(problem: String?, count: Int, max: Int) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(problem.orEmpty(), modifier = Modifier.weight(1f))
    Text(stringResource(R.string.tracive_report_count, count, max))
  }
}

@Composable
private fun FieldProblem(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.error,
    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
  )
}

@Composable
private fun IdentifiersSection(
  identifiers: List<String>,
  problems: List<ReportFieldProblem>,
  limits: ReportLimits,
  maxEvidence: Int,
) {
  Column {
    SectionHeader(stringResource(R.string.tracive_report_media_title))
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      shape = MaterialTheme.shapes.medium,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        identifiers.forEach { key ->
          val identifier = readableIdentifier(key)
          Column {
            Text(
              stringResource(mediaLabel(identifier.kind)),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              identifier.value,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
    }
    problems
      .firstOrNull { it in MEDIA_PROBLEMS }
      ?.let { FieldProblem(problemText(it, limits, maxEvidence)) }
  }
}

private fun mediaLabel(kind: MediaKind): Int =
  when (kind) {
    MediaKind.PHONE -> R.string.tracive_report_media_phone
    MediaKind.EMAIL -> R.string.tracive_report_media_email
    MediaKind.BANK -> R.string.tracive_report_media_bank
    MediaKind.CRYPTO -> R.string.tracive_report_media_crypto
    MediaKind.SOCIAL_MEDIA -> R.string.tracive_report_media_social
    MediaKind.OTHER -> R.string.tracive_report_media_other
  }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
  value: String?,
  problems: List<ReportFieldProblem>,
  limits: ReportLimits,
  enabled: Boolean,
  onChange: (String) -> Unit,
  modifier: Modifier,
) {
  var picking by rememberSaveable { mutableStateOf(false) }
  val date = parsedDate(value)
  val problem = problems.firstOrNull { it in DATE_PROBLEMS }
  val label = stringResource(R.string.tracive_report_date_label)

  Box(modifier) {
    OutlinedTextField(
      value = date?.let { displayedDate(it, Locale.getDefault()) }.orEmpty(),
      onValueChange = {},
      readOnly = true,
      enabled = enabled,
      singleLine = true,
      label = { Text(label) },
      placeholder = { Text(stringResource(R.string.tracive_report_date_placeholder)) },
      trailingIcon = {
        Icon(painterResource(R.drawable.tracive_report_ic_calendar), contentDescription = null)
      },
      isError = problem != null,
      supportingText = problem?.let { { Text(problemText(it, limits, 0)) } },
      modifier = Modifier.fillMaxWidth(),
    )
    // A read-only field still takes focus and swallows the tap, so the tap target sits on top.
    Box(
      Modifier.matchParentSize()
        .clickable(enabled = enabled, role = Role.Button) { picking = true }
        .semantics { contentDescription = label }
    )
  }

  if (picking) {
    IncidentDatePicker(
      selected = date,
      today = deviceToday(),
      onPicked = {
        picking = false
        onChange(it.toString())
      },
      onDismiss = { picking = false },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IncidentDatePicker(
  selected: LocalDate?,
  today: LocalDate,
  onPicked: (LocalDate) -> Unit,
  onDismiss: () -> Unit,
) {
  val latest = remember(today) { pickerMillis(today) }
  val state =
    rememberDatePickerState(
      initialSelectedDateMillis = selected?.let(::pickerMillis)?.coerceAtMost(latest),
      selectableDates =
        object : SelectableDates {
          override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latest

          override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
  DatePickerDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(
        enabled = state.selectedDateMillis != null,
        onClick = { state.selectedDateMillis?.let { onPicked(pickedDate(it)) } },
      ) {
        Text(stringResource(R.string.tracive_report_ok))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.tracive_report_cancel)) }
    },
  ) {
    DatePicker(state)
  }
}

@Composable
private fun EvidenceSection(
  model: ReportFormViewModel,
  limits: ReportLimits,
  enabled: Boolean,
  modifier: Modifier,
) {
  val context = LocalContext.current
  val room = model.evidenceRoom
  val add = { uri: Uri, mimeType: String -> UriEvidence(context, uri, mimeType) }
  val pickOne =
    rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
      uri?.let { model.addEvidence(listOf(add(it, "image/*"))) }
    }
  val manyContract = remember(room) { PickMultipleVisualMedia(room.coerceIn(2, MAX_PICK_AT_ONCE)) }
  val pickMany =
    rememberLauncherForActivityResult(manyContract) { uris ->
      if (uris.isNotEmpty()) model.addEvidence(uris.map { add(it, "image/*") })
    }
  val pickPdf =
    rememberLauncherForActivityResult(OpenDocument()) { uri ->
      uri?.let { model.addEvidence(listOf(add(it, MIME_PDF))) }
    }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Column {
      SectionHeader(
        stringResource(R.string.tracive_report_evidence_label),
        stringResource(R.string.tracive_report_evidence_description, model.maxEvidence),
      )
    }
    model.evidence.forEach { item ->
      EvidenceRow(item, enabled, onRemove = { model.removeEvidence(item.id) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
        enabled = enabled && room > 0,
        shape = MaterialTheme.shapes.medium,
        onClick = {
          val request = PickVisualMediaRequest(PickVisualMedia.ImageOnly)
          if (room == 1) pickOne.launch(request) else pickMany.launch(request)
        },
      ) {
        Text(stringResource(R.string.tracive_report_add_photos))
      }
      OutlinedButton(
        enabled = enabled && room > 0,
        shape = MaterialTheme.shapes.medium,
        onClick = { pickPdf.launch(arrayOf(MIME_PDF)) },
      ) {
        Text(stringResource(R.string.tracive_report_add_pdf))
      }
    }
    if (model.evidenceOverflow) {
      FieldProblem(
        stringResource(R.string.tracive_report_problem_too_many_evidence, model.maxEvidence)
      )
    }
    when (model.evidenceBlock) {
      EvidenceBlock.STILL_PREPARING ->
        FieldProblem(stringResource(R.string.tracive_report_problem_evidence_preparing))
      EvidenceBlock.FILE_FAILED ->
        FieldProblem(stringResource(R.string.tracive_report_problem_evidence_failed))
      null ->
        model.problems
          .firstOrNull { it in EVIDENCE_PROBLEMS }
          ?.let { FieldProblem(problemText(it, limits, model.maxEvidence)) }
    }
  }
}

@Composable
private fun EvidenceRow(item: EvidenceItem, enabled: Boolean, onRemove: () -> Unit) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainer,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
      EvidenceThumbnail(item)
      Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
        Text(
          item.name ?: stringResource(R.string.tracive_report_evidence_file),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        when (val state = item.state) {
          EvidenceState.Preparing ->
            Text(
              stringResource(R.string.tracive_report_evidence_preparing),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          is EvidenceState.Ready ->
            Text(
              stringResource(R.string.tracive_report_evidence_ready),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          is EvidenceState.Failed ->
            Text(
              stringResource(errorText(state.code)),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
        }
      }
      IconButton(onClick = onRemove, enabled = enabled) {
        Icon(
          painterResource(R.drawable.tracive_report_ic_close),
          stringResource(R.string.tracive_report_remove),
        )
      }
    }
  }
}

@Composable
private fun EvidenceThumbnail(item: EvidenceItem) {
  val colors = MaterialTheme.colorScheme
  Box(
    Modifier.size(56.dp)
      .clip(MaterialTheme.shapes.small)
      .background(colors.surfaceContainerHighest),
    contentAlignment = Alignment.Center,
  ) {
    val thumbnail = item.thumbnail
    if (thumbnail != null) {
      Image(
        thumbnail,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Icon(
        painterResource(
          if (item.mimeType == MIME_PDF) R.drawable.tracive_report_ic_pdf
          else R.drawable.tracive_report_ic_image
        ),
        contentDescription = null,
        tint = colors.onSurfaceVariant,
      )
    }
    if (item.state == EvidenceState.Preparing) {
      Box(
        Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
          color = colors.surfaceContainerLowest,
          strokeWidth = 2.dp,
          modifier = Modifier.size(24.dp),
        )
      }
    }
  }
}

@Composable
private fun SendSection(model: ReportFormViewModel) {
  val context = LocalContext.current
  var browserMissing by rememberSaveable { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    model.failure?.let { failure ->
      Notice(failureText(failure)) {
        if (isRetryable(failure.code)) {
          TextButton(onClick = model::send, enabled = model.canSend) {
            Text(stringResource(R.string.tracive_report_retry))
          }
        }
      }
    }
    if (model.sending) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          stringResource(R.string.tracive_report_sending, (model.progress * 100).toInt()),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
      }
    }
    TermsLine(
      model.requirements?.terms.orEmpty(),
      onOpen = { browserMissing = !openTerm(context, it) },
    )
    if (browserMissing) FieldProblem(stringResource(R.string.tracive_report_no_browser))
    Button(
      onClick = model::send,
      enabled = model.canSend,
      shape = MaterialTheme.shapes.medium,
      modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
      Text(stringResource(R.string.tracive_report_send))
    }
  }
}

/** Colours come from `onSurface` on purpose: a partner's theme never reaches this line. */
@Composable
private fun TermsLine(terms: List<RequiredTerm>, onOpen: (RequiredTerm) -> Unit) {
  if (terms.isEmpty()) return
  val color = MaterialTheme.colorScheme.onSurface
  val sentence = stringResource(R.string.tracive_report_terms_sentence)
  val and = stringResource(R.string.tracive_report_terms_and)
  val linkStyle =
    TextLinkStyles(
      SpanStyle(
        color = color,
        fontWeight = FontWeight.SemiBold,
        textDecoration = TextDecoration.Underline,
      )
    )
  val names = terms.map { termName(it.id) }
  val text = buildAnnotatedString {
    append(sentence)
    append(' ')
    terms.forEachIndexed { index, term ->
      if (index > 0) append(if (index == terms.lastIndex) " $and " else ", ")
      withLink(LinkAnnotation.Clickable(term.id, linkStyle) { onOpen(term) }) {
        append(names[index])
      }
    }
    append('.')
  }
  Text(text, color = color, style = MaterialTheme.typography.bodySmall)
}

private fun openTerm(context: Context, term: RequiredTerm): Boolean {
  val uri = Uri.parse(term.url)
  if (uri.scheme != "https" && uri.scheme != "http") return false
  return try {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    true
  } catch (_: ActivityNotFoundException) {
    false
  }
}

@Composable
internal fun LoadingContent(padding: PaddingValues) {
  Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
  }
}

@Composable
internal fun MessageContent(
  title: String,
  line: String?,
  padding: PaddingValues,
  icon: Int,
  actions: @Composable () -> Unit,
) {
  Column(
    Modifier.fillMaxSize().padding(padding).padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Surface(
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      shape = MaterialTheme.shapes.extraLarge,
    ) {
      Icon(painterResource(icon), contentDescription = null, Modifier.padding(16.dp).size(32.dp))
    }
    Spacer(Modifier.height(20.dp))
    Text(
      title,
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    if (line != null) {
      Text(
        line,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
    Spacer(Modifier.height(24.dp))
    actions()
  }
}

@Composable
private fun LoadFailedContent(code: ReportErrorCode, padding: PaddingValues, onRetry: () -> Unit) {
  MessageContent(
    title = stringResource(R.string.tracive_report_load_failed),
    line = stringResource(errorText(code)),
    padding = padding,
    icon = R.drawable.tracive_report_ic_alert,
  ) {
    Button(onClick = onRetry, shape = MaterialTheme.shapes.medium) {
      Text(stringResource(R.string.tracive_report_retry))
    }
  }
}

@Composable
private fun SentContent(
  checkedStatus: ReportStatus?,
  padding: PaddingValues,
  onDone: () -> Unit,
  onViewStatus: () -> Unit,
) {
  MessageContent(
    title = stringResource(R.string.tracive_report_sent_title),
    line = stringResource(R.string.tracive_report_sent_message),
    padding = padding,
    icon = R.drawable.tracive_report_ic_check,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (checkedStatus != null) {
        // SUBMITTED would only repeat the title above it.
        if (checkedStatus != ReportStatus.SUBMITTED) {
          Text(
            stringResource(
              R.string.tracive_report_sent_status,
              stringResource(statusTitle(checkedStatus)),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        OutlinedButton(
          onClick = onViewStatus,
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.tracive_report_view_status))
        }
      }
      Button(
        onClick = onDone,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.tracive_report_done))
      }
    }
  }
}
