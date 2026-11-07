# GPU acceleration (experimental)

Lomio can offload model layers to the GPU with llama.cpp's OpenCL backend. Qualcomm maintains
that backend for Adreno GPUs. It is off by default and lives at **Settings → Models & memory →
GPU acceleration (experimental)**.

## How it works

- **Build.** arm64 only. `android/llama/src/main/cpp/CMakeLists.txt` builds `ggml-opencl` as
  `liblomio-opencl.so` (`-DLOMIO_OPENCL=OFF` leaves it out).
  - It compiles against the Khronos headers, a submodule at `android/llama/opencl/OpenCL-Headers`.
  - It links against a generated stub `libOpenCL.so` that is never packaged. At runtime the
    phone's own driver, `/vendor/lib64/libOpenCL.so`, is used. The manifest asks for it with
    `<uses-native-library android:name="libOpenCL.so" android:required="false"/>`.
  - The OpenCL kernels are embedded in the library and compiled by the driver on first use. The
    compiled programs are cached in `cacheDir/opencl-kernels`.
- **Lazy loading.** The backend is renamed on purpose: `ggml_backend_load_all_from_path()` only
  loads `libggml-*.so`, so it never picks this one up. `liblomio-opencl.so` is loaded only when
  a model loads with GPU layers. A phone whose OpenCL driver misbehaves is untouched unless the
  user turns the switch on.
- **Detection.** The switch is enabled when the phone is 64-bit ARM, has a Qualcomm SoC
  (`Build.SOC_MANUFACTURER`, or `Build.HARDWARE` before Android 12), and has a
  `libOpenCL.so` in the vendor partition (`GpuSupport`). The real test is the load itself.
- **Fallback.** If the backend can't load, finds no GPU, or the model fails to load or warm up
  on the GPU, the model loads on the CPU. The chat then shows a banner with the reason
  (`ModelInfo.gpuNote`).
- **Crash-loop guard.** Driver initialisation, kernel builds and the warm-up decode all run
  inside the guarded load. The marker also records that the load used the GPU. If the process
  dies there, the next start turns GPU acceleration off and says so.
- **What stays on the CPU.** The vision projector (mmproj) always runs on the CPU, and so does
  everything when the switch is off: no device is offered to llama.cpp at all, not even for
  op offload.
- **Where it runs.** The Benchmark screen shows where the model runs, for example
  `GPU (OpenCL): QUALCOMM Adreno(TM) 750 · 28/28 layers` or `CPU`, and why the GPU isn't used.

## What CI tests

CI has no Adreno GPU. The x86_64 emulator job (`GpuFallbackTest`) runs this check:

1. Turn the switch on and load a model.
2. Check that it loads on the CPU with the reason "this build has no GPU backend for this CPU
   architecture".
3. Check that the banner and the Benchmark screen show that reason, and that the benchmark runs.

Unit tests cover:

- the detection rules
- the GPU layer count passed to the engine
- the fallback notice
- the crash guard turning the switch off after a crash during a GPU load

## Testing on a real phone

You need a Snapdragon 8 Gen 2 / 8 Gen 3 / 8 Elite phone (Adreno 740/750/830), or a 7s Gen 3
(Adreno 810). Older Adreno 6xx phones usually have drivers that are too old.

1. Install a debug build: `./gradlew -Plomio.abis=arm64-v8a installDebug`, or the CI artifact
   `lomio-debug-apk`.
2. Download a **Q4_0** model, the backend's best-optimised format. For example, Llama 3.2 3B
   Q4_0 from the starter list. Q8_0, Q4_K and Q6_K also work, more slowly.
3. Benchmark on the CPU first: Benchmark → Default (pp512/tg128) → Run. Note pp and tg.
4. Settings → GPU acceleration (experimental) → on, layers 99. Load the model again from the
   model sheet. The first GPU load builds the kernels and can take 10–60 s; later loads use
   the cache.
5. Benchmark again. "Runs on" must say `GPU (OpenCL): QUALCOMM Adreno(TM) … · N/N layers`.
   Compare pp and tg with step 3. Expect better prompt processing. Generation can be equal or
   slower; see RESEARCH.md §3.3.
6. Chat for a few turns and check the replies are coherent. Wrong output on the GPU is a bug:
   please report the device, model and quant.
7. Watch the logs while loading:
   `adb logcat -s lomio-llama llama.cpp | grep -iE "opencl|gpu|backend|adreno"`.
   You should see `OpenCL backend loaded` and the device name.
8. Fallback check on a non-Snapdragon phone (Pixel, Exynos, MediaTek). The switch is disabled
   there and explains why. The fallback inside the engine is what CI's `GpuFallbackTest`
   checks on the emulator.
9. Crash-guard check (optional). Start a GPU load and kill the app during it:
   `adb shell am kill dev.lomio` while "Loading…" shows. Reopen the app. It must not
   auto-load, and the banner must say GPU acceleration was turned off.

Please send the numbers from steps 3 and 5 with the device name. They decide whether GPU
acceleration should stay experimental.
