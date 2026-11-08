plugins {
  alias(libs.plugins.android.library)
}

// Restrict ABIs for faster local builds: ./gradlew -Plomio.abis=arm64-v8a assembleDebug
val lomioAbis: List<String> =
  (providers.gradleProperty("lomio.abis").orNull ?: "arm64-v8a,x86_64")
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

android {
  namespace = "dev.lomio.llama"
  compileSdk = libs.versions.compileSdk.get().toInt()
  ndkVersion = libs.versions.ndk.get()

  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()
    consumerProguardFiles("consumer-rules.pro")
    ndk { abiFilters += lomioAbis }
    externalNativeBuild {
      cmake {
        // llama.cpp built in Debug is unusably slow, so always build native code as Release.
        arguments +=
          listOf(
            "-DCMAKE_BUILD_TYPE=Release",
            // Several of our .so files throw/catch C++ exceptions across library boundaries.
            "-DANDROID_STL=c++_shared",
            "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
          )
        // -Plomio.opencl=false leaves the experimental OpenCL GPU backend out (arm64 only anyway).
        if (providers.gradleProperty("lomio.opencl").orNull == "false") arguments += "-DLOMIO_OPENCL=OFF"
        // CI sets LOMIO_CCACHE=1 to cache the llama.cpp build between runs.
        if (providers.environmentVariable("LOMIO_CCACHE").orNull == "1") {
          arguments += listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
        }
        // ggml/llama options are set in src/main/cpp/CMakeLists.txt (per ABI).
      }
    }
  }

  externalNativeBuild {
    cmake {
      path("src/main/cpp/CMakeLists.txt")
      version = libs.versions.cmake.get()
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    // The CPU backends (libggml-cpu-*.so) are dlopen()ed from nativeLibraryDir at runtime,
    // so they must be extracted to disk. Apps using this module must set the same option.
    jniLibs.useLegacyPackaging = true
    // Link-time stub only (see src/main/cpp/CMakeLists.txt): the device provides the real one.
    jniLibs.excludes += "**/libOpenCL.so"
  }
}

dependencies {
  // Flow / StateFlow are part of the public API.
  api(libs.kotlinx.coroutines.android)
}
