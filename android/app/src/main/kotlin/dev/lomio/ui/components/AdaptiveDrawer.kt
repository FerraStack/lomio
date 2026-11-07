package dev.lomio.ui.components

import androidx.compose.ui.res.stringResource
import dev.lomio.R
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** True when the drawer is permanently shown beside the content (no menu button needed). */
val LocalPermanentDrawer = staticCompositionLocalOf { false }

/** Material 3 window size class breakpoints. */
object WindowWidths {
  val Expanded = 840.dp
  /** Below this height (landscape phones) a permanent drawer would crowd the content. */
  val MinPermanentHeight = 480.dp
  /** Comfortable reading width for chat and settings on large screens. */
  val Readable = 840.dp
}

fun usePermanentDrawer(width: Dp, height: Dp): Boolean =
  width >= WindowWidths.Expanded && height >= WindowWidths.MinPermanentHeight

/**
 * Modal drawer on phones; on expanded windows (tablets, unfolded foldables, desktop windows)
 * a permanent drawer beside the content, like a list-detail layout (RESEARCH.md §9.0). The
 * content keeps its state when the window crosses the breakpoint (fold / unfold, resize).
 */
@Composable
fun AdaptiveDrawerScaffold(
  drawerState: DrawerState,
  gesturesEnabled: Boolean,
  drawerContent: @Composable () -> Unit,
  content: @Composable () -> Unit,
) {
  val currentContent by rememberUpdatedState(content)
  val movable = remember { movableContentOf { currentContent() } }
  BoxWithConstraints {
    if (usePermanentDrawer(maxWidth, maxHeight)) {
      CompositionLocalProvider(LocalPermanentDrawer provides true) {
        PermanentNavigationDrawer(drawerContent = drawerContent) { movable() }
      }
    } else {
      ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = gesturesEnabled, drawerContent = drawerContent) {
        movable()
      }
    }
  }
}

/** The ☰ button; hidden while the drawer is permanently visible. */
@Composable
fun DrawerMenuButton(onClick: () -> Unit) {
  if (!LocalPermanentDrawer.current) {
    IconButton(onClick = onClick) { Icon(Icons.Outlined.Menu, stringResource(R.string.action_open_menu)) }
  }
}

/** Centers content and caps its width on large screens. */
fun Modifier.readableWidth(max: Dp = WindowWidths.Readable): Modifier =
  wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)
