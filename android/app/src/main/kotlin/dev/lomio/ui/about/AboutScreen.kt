package dev.lomio.ui.about

import dev.lomio.ui.components.DrawerMenuButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.lomio.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(versionName: String, onOpenDrawer: () -> Unit, modifier: Modifier = Modifier) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.nav_about)) },
        navigationIcon = { DrawerMenuButton(onOpenDrawer) },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.about_version, versionName), style = MaterialTheme.typography.headlineSmall)
      Text(stringResource(R.string.about_tagline))
      Text(stringResource(R.string.about_licenses), style = MaterialTheme.typography.bodyMedium)
      Text(stringResource(R.string.about_source), style = MaterialTheme.typography.bodyMedium)
    }
  }
}
