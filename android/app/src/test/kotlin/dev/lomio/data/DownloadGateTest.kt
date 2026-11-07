package dev.lomio.data

import dev.lomio.data.download.DownloadGate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadGateTest {
  @Test
  fun atMostTwoTransfersRunAtOnceAndTheRestQueueInOrder() = runTest {
    val gate = DownloadGate()
    val running = AtomicInteger()
    var peak = 0
    val started = mutableListOf<Int>()
    val release = List(5) { CompletableDeferred<Unit>() }
    repeat(5) { i ->
      launch {
        gate.run {
          started += i
          peak = maxOf(peak, running.incrementAndGet())
          release[i].await()
          running.decrementAndGet()
        }
      }
    }
    runCurrent()
    assertEquals(listOf(0, 1), started)
    release[0].complete(Unit)
    runCurrent()
    assertEquals(listOf(0, 1, 2), started)
    release.forEach { it.complete(Unit) }
    runCurrent()
    assertEquals(listOf(0, 1, 2, 3, 4), started)
    assertEquals(DownloadGate.MAX, peak)
  }
}
