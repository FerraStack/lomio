package dev.lomio.engine

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lomio.data.GgufReader
import dev.lomio.data.ModelRepository
import dev.lomio.data.db.LomioDatabase
import dev.lomio.data.db.ModelEntity
import dev.lomio.data.db.ModelSource
import dev.lomio.data.db.ModelState
import dev.lomio.data.download.DownloadScheduler
import dev.lomio.data.settings.SettingsRepository
import dev.lomio.domain.CrashGuard
import dev.lomio.domain.Fit
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelManagerTest {
  @get:Rule val tmp = TemporaryFolder()

  private val gib = 1024L * 1024 * 1024
  private lateinit var db: LomioDatabase
  private val engine = FakeEngine()
  private val memory = FakeMemory(available = 8 * gib, total = 12 * gib)
  private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val prefs = ctx.getSharedPreferences("guard-test", 0)

  @Before
  fun setUp() {
    db = LomioDatabase.inMemory(ctx)
    prefs.edit().clear().commit()
  }

  @After fun tearDown() = db.close()

  private suspend fun model(id: String, size: Long): ModelEntity {
    val f = tmp.newFile("$id.gguf")
    val m =
      ModelEntity(
        id = id, source = ModelSource.LOCAL, displayName = id, filePath = f.absolutePath, sizeBytes = size,
        state = ModelState.READY, nLayers = 28, nEmbd = 3072, nHead = 24, nHeadKv = 8, createdAt = 1,
      )
    db.models().upsert(m)
    return m
  }

  private fun TestScope.manager(): Pair<ModelManager, SettingsRepository> {
    val settings =
      SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") })
    val models = ModelRepository(ctx, db, DownloadScheduler(ctx), GgufReader { null })
    return ModelManager(engine, models, settings, CrashGuard(prefs), memory) to settings
  }

  @Test
  fun fittingModelLoadsAndClearsTheCrashMarker() = runTest {
    val (mm, settings) = manager()
    model("a", 2 * gib)
    assertTrue(mm.load("a") is LoadOutcome.Loaded)
    assertEquals("a", mm.activeModelId.value)
    assertNull(CrashGuard(prefs).consumeCrashedLoad())
    assertEquals("a", settings.current().lastModelId)
  }

  @Test
  fun gpuIsOptInAndFallsBackWithANotice() = runTest {
    val (mm, settings) = manager()
    model("a", 1 * gib)
    assertTrue(mm.load("a") is LoadOutcome.Loaded)
    assertEquals("GPU is off by default", 0, engine.lastLoadParams!!.nGpuLayers)

    settings.update { it.copy(gpuEnabled = true, gpuLayers = 20) }
    assertTrue(mm.load("a") is LoadOutcome.Loaded)
    assertEquals(20, engine.lastLoadParams!!.nGpuLayers)
    assertNull(mm.gpuNotice.value)

    engine.gpuUnavailable = "no supported OpenCL GPU found"
    val r = mm.load("a")
    assertTrue(r is LoadOutcome.Loaded)
    assertEquals("CPU", (r as LoadOutcome.Loaded).info.backend)
    assertTrue(mm.gpuNotice.value!!.contains("no supported OpenCL GPU found"))
    mm.dismissGpuNotice()
    assertNull(mm.gpuNotice.value)
  }

  @Test
  fun crashDuringAGpuLoadTurnsGpuOff() = runTest {
    val (mm, settings) = manager()
    settings.update { it.copy(gpuEnabled = true) }
    // A previous process died inside a GPU load.
    CrashGuard(prefs).beginLoad("a", gpu = true)
    mm.onProcessStart()
    assertEquals("a", mm.crashedModelId.value)
    mm.handleGpuCrash()
    assertEquals(false, settings.current().gpuEnabled)
    assertTrue(mm.gpuNotice.value!!.contains("turned off"))
  }

  @Test
  fun crashDuringACpuLoadLeavesGpuAlone() = runTest {
    val (mm, settings) = manager()
    settings.update { it.copy(gpuEnabled = true) }
    CrashGuard(prefs).beginLoad("a", gpu = false)
    mm.onProcessStart()
    mm.handleGpuCrash()
    assertEquals(true, settings.current().gpuEnabled)
    assertNull(mm.gpuNotice.value)
  }

  @Test
  fun tightModelNeedsConfirmation() = runTest {
    val (mm, _) = manager()
    model("a", 2 * gib)
    memory.available = 3200L * 1024 * 1024 // ~2.9 GiB needed vs 3.1 GiB budget: TIGHT
    val r = mm.load("a")
    assertTrue(r is LoadOutcome.NeedsConfirmation)
    assertEquals(Fit.TIGHT, mm.pendingConfirmation.value!!.fit)
    assertTrue(engine.loads.isEmpty())
    assertTrue(mm.confirmPending() is LoadOutcome.Loaded)
    assertNull(mm.pendingConfirmation.value)
  }

  @Test
  fun memoryOfTheLoadedModelCountsAsAvailableWhenSwitching() = runTest {
    val (mm, _) = manager()
    model("a", 2 * gib)
    model("b", 2 * gib)
    assertTrue(mm.load("a") is LoadOutcome.Loaded)
    // After loading A the OS reports little free RAM; B fits once A is unloaded first.
    memory.available = 1 * gib
    assertTrue(mm.load("b") is LoadOutcome.Loaded)
  }

  @Test
  fun crashDuringLoadBlocksAutoLoadOnNextStart() = runTest {
    val (mm, settings) = manager()
    model("a", 2 * gib)
    settings.update { it.copy(lastModelId = "a") }
    // Simulate the previous process dying inside llama.cpp: the marker is still there.
    CrashGuard(prefs).beginLoad("a")
    mm.onProcessStart()
    mm.onFirstForeground()
    assertEquals("a", mm.crashedModelId.value)
    assertTrue(engine.loads.isEmpty())
  }

  @Test
  fun autoLoadsLastModelOnFirstForegroundOnly() = runTest {
    val (mm, settings) = manager()
    model("a", 2 * gib)
    settings.update { it.copy(lastModelId = "a") }
    mm.onProcessStart()
    assertTrue("process start alone must not load", engine.loads.isEmpty())
    mm.onFirstForeground()
    mm.onFirstForeground()
    assertEquals(1, engine.loads.size)
  }

  @Test
  fun failedLoadIsReported() = runTest {
    val (mm, _) = manager()
    model("a", 2 * gib)
    engine.loadFailure = UnsatisfiedLinkError("no liblomio_jni.so")
    val r = mm.load("a")
    assertTrue(r is LoadOutcome.Failed)
    assertNull(mm.activeModelId.value)
  }
}
