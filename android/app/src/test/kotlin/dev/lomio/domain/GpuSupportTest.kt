package dev.lomio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuSupportTest {
  @Test
  fun snapdragonWithOpenClQualifies() {
    assertTrue(GpuSupport.evaluate("arm64-v8a", "QTI", "qcom", hasOpenClLibrary = true).available)
    // Before Android 12 there is no SOC_MANUFACTURER: fall back to Build.HARDWARE.
    assertTrue(GpuSupport.evaluate("arm64-v8a", null, "qcom", hasOpenClLibrary = true).available)
  }

  @Test
  fun everythingElseIsExplained() {
    assertEquals(
      "The GPU backend is only built for 64-bit ARM phones",
      GpuSupport.evaluate("x86_64", null, "ranchu", hasOpenClLibrary = false).reason,
    )
    val tensor = GpuSupport.evaluate("arm64-v8a", "Google", "tensor", hasOpenClLibrary = false)
    assertFalse(tensor.available)
    assertEquals("Needs a Snapdragon (Adreno) GPU", tensor.reason)
    val noDriver = GpuSupport.evaluate("arm64-v8a", "QTI", "qcom", hasOpenClLibrary = false)
    assertFalse(noDriver.available)
    assertEquals("This phone has no OpenCL driver", noDriver.reason)
  }
}
