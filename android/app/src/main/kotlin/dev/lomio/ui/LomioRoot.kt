package dev.lomio.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import dev.lomio.AppContainer
import dev.lomio.BuildConfig
import dev.lomio.domain.Fit
import dev.lomio.engine.PendingLoad
import dev.lomio.ui.components.Format
import dev.lomio.ui.about.AboutScreen
import dev.lomio.ui.bench.BenchmarkScreen
import dev.lomio.ui.chat.ChatActions
import dev.lomio.ui.chat.ChatScreen
import dev.lomio.ui.chat.ChatViewModel
import dev.lomio.ui.drawer.Destination
import dev.lomio.ui.drawer.DrawerActions
import dev.lomio.ui.drawer.DrawerContent
import dev.lomio.ui.models.HfActions
import dev.lomio.ui.models.HfSearchScreen
import dev.lomio.ui.models.ModelsActions
import dev.lomio.ui.models.ModelSettingsActions
import dev.lomio.ui.models.ModelSettingsScreen
import dev.lomio.ui.models.ModelSettingsViewModel
import dev.lomio.ui.models.ModelsScreen
import dev.lomio.ui.models.ModelsViewModel
import dev.lomio.ui.settings.SettingsScreen
import dev.lomio.ui.search.SearchActions
import dev.lomio.ui.search.SearchScreen
import dev.lomio.ui.search.SearchViewModel
import dev.lomio.ui.storage.StorageActions
import dev.lomio.ui.storage.StorageScreen
import dev.lomio.ui.storage.StorageViewModel
import kotlinx.coroutines.launch

/** Screens on the back stack. The chat screen shows whichever conversation ChatViewModel has open. */
enum class Screen { CHAT, SEARCH, MODELS, HF_SEARCH, MODEL_SETTINGS, SETTINGS, BENCHMARK, ABOUT, STORAGE }

private fun Screen.destination() =
  when (this) {
    Screen.CHAT, Screen.SEARCH -> Destination.CHAT
    Screen.MODELS, Screen.HF_SEARCH, Screen.MODEL_SETTINGS -> Destination.MODELS
    Screen.SETTINGS -> Destination.SETTINGS
    Screen.BENCHMARK -> Destination.BENCHMARK
    Screen.ABOUT -> Destination.ABOUT
    Screen.STORAGE -> Destination.MODELS
  }

@Composable
fun LomioRoot(container: AppContainer, settingsVm: SettingsViewModel) {
  val chatVm: ChatViewModel = viewModel { ChatViewModel(container) }
  val modelsVm: ModelsViewModel = viewModel { ModelsViewModel(container) }
  val drawerVm: DrawerViewModel = viewModel { DrawerViewModel(container) }
  val benchVm: BenchViewModel = viewModel { BenchViewModel(container) }
  val searchVm: SearchViewModel = viewModel { SearchViewModel(container) }

  val backStack = remember { mutableStateListOf(Screen.CHAT) }
  var settingsModelId by rememberSaveable { mutableStateOf<String?>(null) }
  val screen = backStack.last()
  val drawer = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  fun navigate(to: Screen) {
    if (to == Screen.CHAT) {
      backStack.clear()
      backStack.add(Screen.CHAT)
    } else {
      backStack.removeAll { it == to }
      backStack.add(to)
    }
  }
  fun openDrawer() = scope.launch { drawer.open() }
  fun openModelSettings(id: String) {
    settingsModelId = id
    navigate(Screen.MODEL_SETTINGS)
  }

  BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
  BackHandler(enabled = !drawer.isOpen && backStack.size > 1) {
    if (screen == Screen.HF_SEARCH && modelsVm.hfBack()) return@BackHandler
    backStack.removeAt(backStack.lastIndex)
  }

  val chatState by chatVm.state.collectAsStateWithLifecycle()
  val pendingLoad by container.modelManager.pendingConfirmation.collectAsStateWithLifecycle()
  pendingLoad?.let { p ->
    MemoryWarningDialog(
      p,
      onConfirm = { scope.launch { container.modelManager.confirmPending() } },
      onDismiss = container.modelManager::dismissPending,
    )
  }
  val settings by settingsVm.settings.collectAsStateWithLifecycle()

  // Keep the screen awake while generating (RESEARCH.md §9.1).
  val view = LocalView.current
  val keepOn = chatState.isGenerating && settings?.keepScreenOn != false
  DisposableEffect(keepOn) {
    view.keepScreenOn = keepOn
    onDispose { view.keepScreenOn = false }
  }

  val importLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let { modelsVm.import(it) }
    }
  val notificationPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

  ModalNavigationDrawer(
    drawerState = drawer,
    gesturesEnabled = screen != Screen.HF_SEARCH,
    drawerContent = {
      val query by drawerVm.query.collectAsStateWithLifecycle()
      val history by drawerVm.history.collectAsStateWithLifecycle()
      DrawerContent(
        current = screen.destination(),
        currentChatId = chatState.conversationId,
        query = query,
        history = history,
        actions =
          DrawerActions(
            onNewChat = {
              chatVm.open(null)
              navigate(Screen.CHAT)
              scope.launch { drawer.close() }
            },
            onNavigate = { d ->
              navigate(
                when (d) {
                  Destination.CHAT -> Screen.CHAT
                  Destination.MODELS -> Screen.MODELS
                  Destination.BENCHMARK -> Screen.BENCHMARK
                  Destination.SETTINGS -> Screen.SETTINGS
                  Destination.ABOUT -> Screen.ABOUT
                }
              )
              scope.launch { drawer.close() }
            },
            onOpenChat = { id ->
              chatVm.open(id)
              navigate(Screen.CHAT)
              scope.launch { drawer.close() }
            },
            onSearch = drawerVm::search,
            onPin = { id, p -> drawerVm.pin(id, p) },
            onRename = { id, t -> drawerVm.rename(id, t) },
            onDelete = { id ->
              if (id == chatState.conversationId) chatVm.open(null)
              drawerVm.delete(id)
            },
            onOpenSearch = { q ->
              searchVm.setQuery(q.trim())
              navigate(Screen.SEARCH)
              scope.launch { drawer.close() }
            },
          ),
      )
    },
  ) {
    when (screen) {
      Screen.CHAT ->
        ChatScreen(
          state = chatState,
          actions =
            ChatActions(
              onOpenDrawer = { openDrawer() },
              onSend = { chatVm.send(it) },
              onStop = chatVm::stop,
              onLoadModel = { chatVm.loadModel(it) },
              onManageModels = { navigate(Screen.MODELS) },
              onRegenerate = { chatVm.regenerate(it) },
              onShare = { share(context, it) },
              onDeleteMessage = chatVm::deleteMessage,
              onStepVersion = chatVm::stepVersion,
              onCopy = { copy(context, it) },
              onEdit = chatVm::edit,
              onToggleThinking = chatVm::toggleThinking,
              onRename = { chatVm.rename(it) },
              onSetSystemPrompt = { chatVm.setSystemPrompt(it) },
              onSetGeneration = { chatVm.setGenerationOverride(it) },
              onSaveGenerationAsModelDefault = chatVm::saveGenerationAsModelDefault,
              onOpenModelSettings = { container.modelManager.activeModelId.value?.let(::openModelSettings) },
              onNewChat = { chatVm.open(null) },
              onDelete = { chatVm.delete {} },
              onDismissCrash = chatVm::dismissCrash,
              onDismissLoadError = chatVm::dismissLoadError,
              onErrorShown = chatVm::errorShown,
              onScrolledToMessage = chatVm::scrolledToMessage,
            ),
        )
      Screen.SEARCH -> {
        val state by searchVm.state.collectAsStateWithLifecycle()
        SearchScreen(
          state = state,
          actions =
            SearchActions(
              onBack = { backStack.removeAt(backStack.lastIndex) },
              onQuery = searchVm::setQuery,
              onOpen = { r ->
                chatVm.openAt(r.conversationId, r.messageId)
                navigate(Screen.CHAT)
              },
            ),
        )
      }
      Screen.MODELS -> {
        val state by modelsVm.state.collectAsStateWithLifecycle()
        ModelsScreen(
          state = state,
          actions =
            ModelsActions(
              onOpenDrawer = { openDrawer() },
              onSearchHf = {
                modelsVm.hfBack()
                modelsVm.startSearch()
                navigate(Screen.HF_SEARCH)
              },
              onOpenRepo = { repo ->
                modelsVm.openRepo(repo)
                navigate(Screen.HF_SEARCH)
              },
              onDownloadRecommended = {
                if (Build.VERSION.SDK_INT >= 33) {
                  notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                modelsVm.downloadRecommended(it)
              },
              onImport = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
              onLoad = { modelsVm.load(it) },
              onUnload = { modelsVm.unload() },
              onDelete = { modelsVm.delete(it) },
              onPause = { modelsVm.pause(it) },
              onResume = { modelsVm.resume(it) },
              onSettings = ::openModelSettings,
              onMessageShown = modelsVm::messageShown,
              onStorage = { navigate(Screen.STORAGE) },
            ),
        )
      }
      Screen.HF_SEARCH -> {
        val state by modelsVm.hf.collectAsStateWithLifecycle()
        HfSearchScreen(
          state = state,
          actions =
            HfActions(
              onBack = { if (!modelsVm.hfBack()) backStack.removeAt(backStack.lastIndex) },
              onQuery = modelsVm::setQuery,
              onSort = modelsVm::setSort,
              onOpenRepo = modelsVm::openRepo,
              onDownload = {
                if (Build.VERSION.SDK_INT >= 33) {
                  notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                modelsVm.download(it)
              },
            ),
        )
      }
      Screen.MODEL_SETTINGS ->
        settingsModelId?.let { id ->
          val vm: ModelSettingsViewModel = viewModel(key = "model-settings-$id") { ModelSettingsViewModel(container, id) }
          val state by vm.state.collectAsStateWithLifecycle()
          ModelSettingsScreen(
            state = state,
            actions =
              ModelSettingsActions(
                onBack = { backStack.removeAt(backStack.lastIndex) },
                onRename = vm::rename,
                onSystemPrompt = { vm.setSystemPrompt(it) },
                onGen = { vm.setGeneration(it) },
                onLoad = { vm.setLoad(it) },
                onTemplateMode = vm::setTemplateMode,
                onPreset = vm::setPreset,
                onCustomTemplate = vm::setCustomTemplate,
                onReload = { vm.reload() },
                onReset = { vm.reset() },
              ),
          )
        }
      Screen.SETTINGS ->
        settings?.let { s ->
          SettingsScreen(settings = s, onChange = { settingsVm.update(it) }, onOpenDrawer = { openDrawer() })
        }
      Screen.BENCHMARK -> {
        val state by benchVm.state.collectAsStateWithLifecycle()
        BenchmarkScreen(state, onOpenDrawer = { openDrawer() }, onProfile = benchVm::setProfile, onRun = { benchVm.run() })
      }
      Screen.ABOUT -> AboutScreen(BuildConfig.VERSION_NAME, onOpenDrawer = { openDrawer() })
      Screen.STORAGE -> {
        val storageVm: StorageViewModel = viewModel { StorageViewModel(container) }
        val state by storageVm.state.collectAsStateWithLifecycle()
        StorageScreen(
          state = state,
          actions =
            StorageActions(
              onBack = { backStack.removeAt(backStack.lastIndex) },
              onDelete = storageVm::delete,
              onClearCache = { storageVm.clearCache() },
              onRemoveLeftovers = { storageVm.removeLeftovers() },
              onMessageShown = storageVm::messageShown,
            ),
        )
      }
    }
  }
}

/** Confirmation before loading a model that is tight on, or over, the memory budget. */
@Composable
fun MemoryWarningDialog(p: PendingLoad, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  val need = Format.bytes(p.estimate.totalBytes)
  val avail = Format.bytes(p.memory.availableBytes)
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (p.fit == Fit.WONT_FIT) "Probably won't fit in memory" else "Tight on memory") },
    text = {
      Text(
        "${p.modelName} needs about $need; about $avail is available. " +
          if (p.fit == Fit.WONT_FIT) "Android will likely close the app while loading. Try a smaller model or context size."
          else "Other apps may be closed, or Lomio may be stopped, while it runs."
      )
    },
    confirmButton = { TextButton(onClick = onConfirm) { Text("Load anyway") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

private fun share(context: Context, text: String) {
  val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
  context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun copy(context: Context, text: String) {
  context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Lomio", text))
}
