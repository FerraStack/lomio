package dev.lomio.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lomio.R
import dev.lomio.domain.Fit
import androidx.compose.ui.res.stringResource
import dev.lomio.ui.components.Format
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import dev.lomio.ui.models.RecommendedModel

enum class OnboardingStep { WELCOME, PICK, DOWNLOAD }

data class StarterOption(val model: RecommendedModel, val fit: Fit?)

sealed interface StarterDownload {
  data class Running(val bytesDone: Long, val total: Long, val note: String? = null) : StarterDownload

  data object Ready : StarterDownload

  /** [message] null: a generic "download failed" text. */
  data class Failed(val message: String?) : StarterDownload
}

data class OnboardingUiState(
  val step: OnboardingStep = OnboardingStep.WELCOME,
  /** Marketing RAM size in GB, e.g. 8. */
  val ramGb: Int = 0,
  val options: List<StarterOption> = emptyList(),
  val selected: Int = -1,
  val download: StarterDownload? = null,
  val loading: Boolean = false,
)

class OnboardingActions(
  val onNext: () -> Unit = {},
  val onBack: () -> Unit = {},
  val onSelect: (Int) -> Unit = {},
  val onDownload: () -> Unit = {},
  val onRetry: () -> Unit = {},
  val onStartChat: () -> Unit = {},
  /** Leave onboarding without a model (or while it downloads). */
  val onSkip: () -> Unit = {},
)

/** First run (RESEARCH.md §9.0 / item 9): welcome → pick a model sized for this phone → download → chat. */
@Composable
fun OnboardingScreen(state: OnboardingUiState, actions: OnboardingActions, modifier: Modifier = Modifier) {
  Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().wrapContentWidth().widthIn(max = 560.dp).padding(horizontal = 24.dp)) {
      StepDots(state.step)
      AnimatedContent(
        targetState = state.step,
        transitionSpec = {
          val forward = targetState.ordinal > initialState.ordinal
          (slideInHorizontally { w -> if (forward) w / 4 else -w / 4 } + fadeIn()) togetherWith
            (slideOutHorizontally { w -> if (forward) -w / 4 else w / 4 } + fadeOut())
        },
        label = "onboarding-step",
        modifier = Modifier.weight(1f),
      ) { step ->
        Column(Modifier.fillMaxSize()) {
          when (step) {
            OnboardingStep.WELCOME -> Welcome(actions)
            OnboardingStep.PICK -> Pick(state, actions)
            OnboardingStep.DOWNLOAD -> Download(state, actions)
          }
        }
      }
    }
  }
}

@Composable
private fun StepDots(step: OnboardingStep) {
  val stepDescription = stringResource(R.string.onboarding_step, step.ordinal + 1, OnboardingStep.entries.size)
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    modifier =
      Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).semantics {
        contentDescription = stepDescription
      },
  ) {
    OnboardingStep.entries.forEach { s ->
      val width by animateDpAsState(if (s == step) 24.dp else 8.dp, label = "dot")
      Box(
        Modifier.height(8.dp)
          .width(width)
          .clip(CircleShape)
          .background(if (s.ordinal <= step.ordinal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
      )
    }
  }
}

@Composable
private fun ColumnScope.Welcome(actions: OnboardingActions) {
  Column(
    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Mark()
    Spacer(Modifier.height(28.dp))
    Text(
      stringResource(R.string.onboarding_title),
      style = MaterialTheme.typography.headlineMedium,
      textAlign = TextAlign.Center,
      modifier = Modifier.semantics { heading() },
    )
    Text(
      stringResource(R.string.onboarding_subtitle),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(Modifier.height(32.dp))
    Feature(Icons.Outlined.Lock, stringResource(R.string.onboarding_private_title), stringResource(R.string.onboarding_private_body))
    Feature(Icons.Outlined.CloudOff, stringResource(R.string.onboarding_offline_title), stringResource(R.string.onboarding_offline_body))
    Feature(Icons.Outlined.AutoAwesome, stringResource(R.string.onboarding_open_title), stringResource(R.string.onboarding_open_body))
  }
  Button(onClick = actions.onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.onboarding_get_started)) }
  Spacer(Modifier.height(24.dp))
}

@Composable
private fun Mark() {
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      Modifier.size(112.dp)
        .clip(RoundedCornerShape(32.dp))
        .background(MaterialTheme.colorScheme.primaryContainer),
  ) {
    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
  }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
  Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
      Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
    Column(Modifier.padding(start = 16.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun ColumnScope.Pick(state: OnboardingUiState, actions: OnboardingActions) {
  Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.onboarding_pick_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)) {
      Icon(Icons.Outlined.Memory, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
      Text(
        stringResource(R.string.onboarding_pick_ram, stringResource(R.string.onboarding_ram_gb, state.ramGb)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
      )
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      state.options.forEachIndexed { i, o -> OptionCard(o, selected = i == state.selected, recommended = i == state.recommendedIndex()) { actions.onSelect(i) } }
    }
    Text(
      stringResource(R.string.onboarding_pick_note),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 16.dp),
    )
    Spacer(Modifier.height(16.dp))
  }
  val pick = state.options.getOrNull(state.selected)
  Button(
    onClick = actions.onDownload,
    enabled = pick != null,
    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
  ) {
    Text(pick?.let { stringResource(R.string.onboarding_download_size, Format.bytes(it.model.sizeBytes)) } ?: stringResource(R.string.onboarding_download))
  }
  TextButton(onClick = actions.onSkip, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text(stringResource(R.string.onboarding_skip)) }
}

/** The preselected recommendation is shown with a "Recommended" tag even after the user changes the pick. */
private fun OnboardingUiState.recommendedIndex(): Int =
  dev.lomio.domain.Onboarding.recommend(options.map { it.model.sizeBytes }, options.map { it.fit })

@Composable
private fun OptionCard(o: StarterOption, selected: Boolean, recommended: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(16.dp)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .clip(shape)
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
        .border(
          if (selected) 2.dp else 1.dp,
          if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
          shape,
        )
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
  ) {
    RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 4.dp))
    Column(Modifier.weight(1f).padding(start = 4.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(o.model.title, style = MaterialTheme.typography.titleMedium)
        if (recommended) {
          Text(
            stringResource(R.string.onboarding_recommended),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier =
              Modifier.padding(start = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 8.dp, vertical = 2.dp),
          )
        }
      }
      Text(dev.lomio.ui.models.recommendedNote(o.model), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(
          "${Format.bytes(o.model.sizeBytes)} · ${o.model.quant}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FitTag(o.fit)
      }
    }
  }
}

@Composable
private fun FitTag(fit: Fit?) {
  val (text, color, icon) =
    when (fit) {
      Fit.FITS -> Triple(stringResource(R.string.onboarding_fit_fits), MaterialTheme.colorScheme.primary, Icons.Outlined.CheckCircle)
      Fit.TIGHT -> Triple(stringResource(R.string.onboarding_fit_tight), MaterialTheme.colorScheme.tertiary, Icons.Outlined.WarningAmber)
      Fit.WONT_FIT -> Triple(stringResource(R.string.onboarding_fit_too_big), MaterialTheme.colorScheme.error, Icons.Outlined.WarningAmber)
      null -> return
    }
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
    Icon(icon, null, Modifier.size(14.dp), tint = color)
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(start = 4.dp))
  }
}

@Composable
private fun ColumnScope.Download(state: OnboardingUiState, actions: OnboardingActions) {
  val name = state.options.getOrNull(state.selected)?.model?.title
  val d = state.download
  Column(
    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    val progress = (d as? StarterDownload.Running)?.let { if (it.total > 0) it.bytesDone.toFloat() / it.total else 0f } ?: if (d == StarterDownload.Ready) 1f else 0f
    val animated by animateFloatAsState(progress, label = "download")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(168.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
      when (d) {
        StarterDownload.Ready ->
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
          ) {
            Icon(Icons.Outlined.CheckCircle, stringResource(R.string.onboarding_download_complete), Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
          }
        is StarterDownload.Failed ->
          Icon(Icons.Outlined.ErrorOutline, stringResource(R.string.onboarding_download_failed), Modifier.size(72.dp), tint = MaterialTheme.colorScheme.error)
        else -> {
          CircularProgressIndicator(
            progress = { animated },
            strokeWidth = 8.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(160.dp),
          )
          Text(stringResource(R.string.onboarding_percent, (progress * 100).toInt()), style = MaterialTheme.typography.headlineMedium)
        }
      }
    }
    Spacer(Modifier.height(28.dp))
    Text(
      when (d) {
        StarterDownload.Ready ->
          if (name != null) stringResource(R.string.onboarding_ready_title, name) else stringResource(R.string.onboarding_ready_title_generic)
        is StarterDownload.Failed -> stringResource(R.string.onboarding_failed_title)
        else ->
          if (name != null) stringResource(R.string.onboarding_downloading_title, name)
          else stringResource(R.string.onboarding_downloading_title_generic)
      },
      style = MaterialTheme.typography.headlineSmall,
      textAlign = TextAlign.Center,
      modifier = Modifier.semantics { heading() },
    )
    Text(
      when (d) {
        StarterDownload.Ready -> stringResource(R.string.onboarding_ready_body)
        is StarterDownload.Failed -> d.message ?: stringResource(R.string.onboarding_failed_generic)
        is StarterDownload.Running ->
          d.note ?: stringResource(R.string.onboarding_progress, Format.bytes(d.bytesDone), Format.bytes(d.total))
        null -> stringResource(R.string.onboarding_starting)
      },
      style = MaterialTheme.typography.bodyMedium,
      color = if (d is StarterDownload.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
  when (d) {
    StarterDownload.Ready ->
      Button(onClick = actions.onStartChat, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        if (state.loading) {
          CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
          Spacer(Modifier.width(12.dp))
          Text(stringResource(R.string.onboarding_loading))
        } else {
          Text(stringResource(R.string.onboarding_start_chatting))
        }
      }
    is StarterDownload.Failed -> {
      Button(onClick = actions.onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.onboarding_try_again)) }
      OutlinedButton(onClick = actions.onBack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.onboarding_pick_another)) }
    }
    else -> OutlinedButton(onClick = actions.onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.onboarding_continue_background)) }
  }
  Spacer(Modifier.height(24.dp))
}
