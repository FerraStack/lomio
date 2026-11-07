package dev.lomio.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(versionName: String, onOpenDrawer: () -> Unit, modifier: Modifier = Modifier) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text("About") },
        navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, "Open menu") } },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Lomio $versionName", style = MaterialTheme.typography.headlineSmall)
      Text("Chat with local language models. Everything runs on your phone: no account, no analytics, no cloud.")
      Text("Inference: llama.cpp v0.9.0 (MIT). App: Apache-2.0.", style = MaterialTheme.typography.bodyMedium)
      Text("Source and issues: github.com/FerraStack/lomio", style = MaterialTheme.typography.bodyMedium)
    }
  }
}
