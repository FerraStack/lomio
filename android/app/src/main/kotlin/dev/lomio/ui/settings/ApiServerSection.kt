package dev.lomio.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lomio.api.ApiServerStatus
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.ui.res.stringResource
import dev.lomio.R

data class ApiServerUiState(
  val enabled: Boolean = false,
  val status: ApiServerStatus = ApiServerStatus.Stopped,
  val port: Int = 8080,
  val lan: Boolean = false,
  val apiKey: String = "",
  /** Apps on this phone need the key too (otherwise any app here can connect without one). */
  val requireKeyLocal: Boolean = false,
  /** This phone's address on the local network, when LAN access is on. */
  val lanAddress: String? = null,
  /** The loaded model the server answers with. */
  val modelName: String? = null,
  val served: Int = 0,
  val active: Int = 0,
)

class ApiServerActions(
  val onEnabled: (Boolean) -> Unit = {},
  val onPort: (Int) -> Unit = {},
  val onLan: (Boolean) -> Unit = {},
  val onNewKey: () -> Unit = {},
  val onRequireKeyLocal: (Boolean) -> Unit = {},
)

/** Settings → Local API server: the switch, a status chip, the address(es) and the LAN key. */
@Composable
fun ApiServerSection(state: ApiServerUiState, actions: ApiServerActions) {
  HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp))
  Text(
    stringResource(R.string.api_title),
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.semantics { heading() },
  )
  ToggleRow(
    stringResource(R.string.api_switch),
    stringResource(R.string.api_switch_sub),
    state.enabled,
    actions.onEnabled,
  )
  AnimatedVisibility(state.enabled || state.status is ApiServerStatus.Failed) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      StatusCard(state)
      PortField(state.port, actions.onPort)
      ToggleRow(
        stringResource(R.string.api_lan),
        stringResource(if (state.lan) R.string.api_lan_on else R.string.api_lan_off),
        state.lan,
        actions.onLan,
      )
      if (!state.lan) {
        ToggleRow(
          stringResource(R.string.api_key_local),
          stringResource(if (state.requireKeyLocal) R.string.api_key_local_on else R.string.api_key_local_off),
          state.requireKeyLocal,
          actions.onRequireKeyLocal,
        )
      }
      if (state.lan || state.requireKeyLocal) KeyField(state.apiKey, actions.onNewKey)
      Text(
        stringResource(R.string.api_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  Spacer(Modifier.height(24.dp))
}

/** Like [SwitchRow], but the whole row is the touch target and one TalkBack item. */
@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 8.dp),
  ) {
    Column(Modifier.weight(1f).padding(end = 16.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = checked, onCheckedChange = null)
  }
}

@Composable
private fun StatusCard(state: ApiServerUiState) {
  val context = LocalContext.current
  Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        StatusChip(state.status, state.active > 0)
        Box(Modifier.weight(1f))
        if (state.status is ApiServerStatus.Running) {
          Text(
            if (state.served == 1) "1 reply served" else "${state.served} replies served",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      when (val s = state.status) {
        is ApiServerStatus.Running -> {
          AddressRow("http://127.0.0.1:${s.port}/v1", stringResource(R.string.api_on_phone)) { copy(context, it, sensitive = false) }
          if (s.lan) {
            val lan = state.lanAddress
            if (lan != null) AddressRow("http://$lan:${s.port}/v1", stringResource(R.string.api_on_wifi)) { copy(context, it, sensitive = false) }
            else Note(Icons.Outlined.WarningAmber, stringResource(R.string.api_no_lan), MaterialTheme.colorScheme.tertiary)
          }
          if (!s.lan && !state.requireKeyLocal) {
            Note(Icons.Outlined.LockOpen, stringResource(R.string.api_no_key_note), MaterialTheme.colorScheme.onSurfaceVariant)
          }
          if (state.modelName != null) Note(Icons.Outlined.Memory, stringResource(R.string.api_answers_with, state.modelName), MaterialTheme.colorScheme.onSurfaceVariant)
          else Note(Icons.Outlined.WarningAmber, stringResource(R.string.api_no_model), MaterialTheme.colorScheme.tertiary)
        }
        is ApiServerStatus.Failed -> Note(Icons.Outlined.ErrorOutline, s.message, MaterialTheme.colorScheme.error)
        else -> Unit
      }
    }
  }
}

@Composable
private fun StatusChip(status: ApiServerStatus, answering: Boolean) {
  val cs = MaterialTheme.colorScheme
  val (label, container, content, dot) =
    when (status) {
      is ApiServerStatus.Running -> Quad(stringResource(if (answering) R.string.api_status_answering else R.string.api_status_running), cs.primaryContainer, cs.onPrimaryContainer, cs.primary)
      ApiServerStatus.Starting -> Quad(stringResource(R.string.api_status_starting), cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary)
      is ApiServerStatus.Failed -> Quad(stringResource(R.string.api_status_failed), cs.errorContainer, cs.onErrorContainer, cs.error)
      ApiServerStatus.Stopped -> Quad(stringResource(R.string.api_status_stopped), cs.surfaceContainerHighest, cs.onSurfaceVariant, cs.outline)
    }
  val description = stringResource(R.string.api_status_a11y, label)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.background(container, RoundedCornerShape(50))
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .semantics(mergeDescendants = true) {
          contentDescription = description
          liveRegion = LiveRegionMode.Polite
        },
  ) {
    Box(Modifier.size(8.dp).background(dot, CircleShape))
    Text(label, style = MaterialTheme.typography.labelLarge, color = content, modifier = Modifier.padding(start = 8.dp))
  }
}

private data class Quad(val label: String, val container: Color, val content: Color, val dot: Color)

@Composable
private fun AddressRow(url: String, label: String, onCopy: (String) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(url, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    IconButton(onClick = { onCopy(url) }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.api_copy_address, url)) }
  }
}

@Composable
private fun Note(icon: ImageVector, text: String, color: Color) {
  Row(verticalAlignment = Alignment.Top) {
    Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = color)
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(start = 8.dp))
  }
}

/** Applied on Done or when focus leaves, not per keystroke (each change restarts the server). */
@Composable
private fun PortField(port: Int, onPort: (Int) -> Unit) {
  var text by rememberSaveable(port) { mutableStateOf(port.toString()) }
  val value = text.toIntOrNull()
  val valid = value != null && value in 1024..65535
  fun commit() {
    if (valid && value != port) onPort(value!!)
  }
  OutlinedTextField(
    value = text,
    onValueChange = { t -> text = t.filter(Char::isDigit).take(5) },
    label = { Text(stringResource(R.string.api_port)) },
    isError = !valid,
    supportingText = { Text(stringResource(if (valid) R.string.api_port_done else R.string.api_port_invalid)) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { commit() }),
    modifier = Modifier.width(220.dp).onFocusChanged { if (!it.isFocused) commit() },
  )
}

@Composable
private fun KeyField(key: String, onNewKey: () -> Unit) {
  val context = LocalContext.current
  var shown by rememberSaveable { mutableStateOf(false) }
  Column {
    OutlinedTextField(
      value = key,
      onValueChange = {},
      readOnly = true,
      label = { Text(stringResource(R.string.api_key)) },
      singleLine = true,
      textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
      visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
      trailingIcon = {
        Row {
          IconButton(onClick = { shown = !shown }) {
            Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(if (shown) R.string.api_key_hide else R.string.api_key_show))
          }
          IconButton(onClick = { copy(context, key, sensitive = true) }, enabled = key.isNotEmpty()) {
            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.api_key_copy))
          }
        }
      },
      supportingText = { Text(stringResource(R.string.api_key_sub)) },
      modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = onNewKey) { Text(stringResource(R.string.api_key_new)) }
  }
}

private fun copy(context: Context, text: String, sensitive: Boolean) {
  val clip = ClipData.newPlainText("Lomio", text)
  if (sensitive && Build.VERSION.SDK_INT >= 33) {
    // Keeps the key out of the clipboard preview on Android 13+.
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
  }
  context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
