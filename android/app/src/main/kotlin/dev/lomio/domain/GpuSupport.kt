package dev.lomio.domain

import android.os.Build
import java.io.File

/**
 * Whether the experimental GPU backend (llama.cpp's OpenCL backend, written for Qualcomm Adreno)
 * can be offered on this device. This is a cheap check that loads nothing. The real test happens
 * when a model loads with GPU layers: if OpenCL then fails, the model loads on the CPU instead
 * (ModelInfo.gpuNote).
 */
data class GpuSupport(val available: Boolean, val reason: String) {
  companion object {
    /** Shown before detection ran (e.g. in screenshots and tests). */
    val UNKNOWN = GpuSupport(false, "Not checked")

    private val OPENCL_LIBS = listOf("/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so")

    fun detect(): GpuSupport =
      evaluate(
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null,
        hardware = Build.HARDWARE.orEmpty(),
        hasOpenClLibrary = OPENCL_LIBS.any { File(it).exists() },
      )

    /** Pure decision, unit-tested. */
    fun evaluate(abi: String, socManufacturer: String?, hardware: String, hasOpenClLibrary: Boolean): GpuSupport {
      if (abi != "arm64-v8a") return GpuSupport(false, "The GPU backend is only built for 64-bit ARM phones")
      val qualcomm =
        socManufacturer?.let { it.equals("QTI", true) || it.contains("qualcomm", true) }
          ?: (hardware.contains("qcom", true) || hardware.contains("qualcomm", true))
      if (!qualcomm) return GpuSupport(false, "Needs a Snapdragon (Adreno) GPU")
      if (!hasOpenClLibrary) return GpuSupport(false, "This phone has no OpenCL driver")
      return GpuSupport(true, "Adreno GPU with OpenCL")
    }
  }
}
