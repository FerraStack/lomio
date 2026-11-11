package dev.lomio.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lomio.domain.GenerationSettings
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import dev.lomio.R
import androidx.annotation.StringRes

/** The broader settings layer a value comes from when this layer doesn't set it. */
enum class InheritedFrom(@StringRes val from: Int, @StringRes val reset: Int) {
  GLOBAL(R.string.override_from_global, R.string.override_reset_global),
  MODEL(R.string.override_from_model, R.string.override_reset_model),
}

/**
 * A setting that is either inherited from a broader layer ([inheritedFrom]) or
 * overridden here. Overridden rows show their value in the accent color with a reset button;
 * inherited rows say where the value comes from.
 */
@Composable
private fun OverrideHeader(
  label: String,
  valueText: String,
  overridden: Boolean,
  inheritedFrom: InheritedFrom,
  onReset: () -> Unit,
) {
  val from = stringResource(inheritedFrom.from)
  val state = if (overridden) stringResource(R.string.override_custom_state) else from
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics {
        stateDescription = state
      },
  ) {
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodyLarge)
      Text(
        if (overridden) stringResource(R.string.override_custom) else from,
        style = MaterialTheme.typography.labelSmall,
        color = if (overridden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      valueText,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = if (overridden) FontWeight.SemiBold else FontWeight.Normal,
      color = if (overridden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (overridden) {
      IconButton(onClick = onReset, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Outlined.RestartAlt, stringResource(inheritedFrom.reset, label), Modifier.size(20.dp))
      }
    }
  }
}

@Composable
fun OverridableSlider(
  label: String,
  base: Float,
  own: Float?,
  range: ClosedFloatingPointRange<Float>,
  steps: Int,
  inheritedFrom: InheritedFrom,
  format: (Float) -> String = { String.format(Locale.getDefault(), "%.2f", it) },
  onChange: (Float?) -> Unit,
) {
  val value = own ?: base
  Column(Modifier.padding(vertical = 2.dp)) {
    OverrideHeader(label, format(value), own != null, inheritedFrom) { onChange(null) }
    Slider(
      value = value.coerceIn(range),
      onValueChange = { onChange(it) },
      valueRange = range,
      steps = steps,
      // Dense tick marks read as a dotted bar; keep them only for a handful of stops.
      colors =
        if (steps > 10) SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
        else SliderDefaults.colors(),
    )
  }
}

@Composable
fun OverridableSwitch(
  label: String,
  subtitle: String?,
  base: Boolean,
  own: Boolean?,
  inheritedFrom: InheritedFrom,
  onChange: (Boolean?) -> Unit,
) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      OverrideHeader(label, "", own != null, inheritedFrom) { onChange(null) }
      subtitle?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Switch(checked = own ?: base, onCheckedChange = { onChange(it) }, modifier = Modifier.padding(start = 12.dp))
  }
}

/**
 * Sampler controls for one settings layer. [base] is what this layer inherits (fully
 * resolved), [own] holds this layer's overrides; a reset clears the field back to null.
 */
@Composable
fun OverridableGenerationControls(
  base: GenerationSettings,
  own: GenerationSettings,
  inheritedFrom: InheritedFrom,
  onChange: (GenerationSettings) -> Unit,
) {
  val d = GenerationSettings.DEFAULTS
  val unlimited = stringResource(R.string.gen_unlimited)
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    OverridableSlider(stringResource(R.string.gen_temperature), base.temperature ?: d.temperature!!, own.temperature, 0f..2f, 39, inheritedFrom) {
      onChange(own.copy(temperature = it))
    }
    OverridableSlider(stringResource(R.string.gen_top_p), base.topP ?: d.topP!!, own.topP, 0f..1f, 19, inheritedFrom) { onChange(own.copy(topP = it)) }
    OverridableSlider(stringResource(R.string.gen_min_p), base.minP ?: d.minP!!, own.minP, 0f..0.5f, 49, inheritedFrom) { onChange(own.copy(minP = it)) }
    OverridableSlider(
      stringResource(R.string.gen_top_k),
      (base.topK ?: d.topK!!).toFloat(),
      own.topK?.toFloat(),
      0f..100f,
      99,
      inheritedFrom,
      format = { it.roundToInt().toString() },
    ) {
      onChange(own.copy(topK = it?.roundToInt()))
    }
    OverridableSlider(
      stringResource(R.string.gen_repeat_penalty),
      base.repeatPenalty ?: d.repeatPenalty!!,
      own.repeatPenalty,
      1f..1.5f,
      49,
      inheritedFrom,
    ) {
      onChange(own.copy(repeatPenalty = it))
    }
    OverridableSlider(
      stringResource(R.string.gen_max_reply_tokens),
      (base.maxTokens ?: d.maxTokens!!).coerceAtLeast(0).toFloat(),
      own.maxTokens?.coerceAtLeast(0)?.toFloat(),
      0f..4096f,
      31,
      inheritedFrom,
      format = { if (it.roundToInt() == 0) unlimited else it.roundToInt().toString() },
    ) {
      onChange(own.copy(maxTokens = it?.roundToInt()?.let { v -> if (v == 0) -1 else v }))
    }
  }
}
