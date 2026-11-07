# Lomio

Chat with local language models on your Android phone. Lomio is a clean "ChatGPT for local
models": download GGUF models from Hugging Face (or import your own), chat with streaming
replies, keep your history on the device. No account, no analytics, no cloud.

It follows the interface of [PocketPal AI](https://github.com/a-ghorbani/pocketpal-ai), without
personas ("Pals"), the marketplace, voice and other extras.

## Status

**Pre-alpha.** The first code drop (milestones M0–M3 from
[docs/RESEARCH.md](docs/RESEARCH.md#10-mvp-milestones)) is in place. CI runs the real native
engine end to end on an x86_64 emulator: it loads a tiny GGUF, streams a reply and checks the
stats line. arm64 phones are not tested yet:

- Chat: streaming Markdown with highlighted code blocks and a Copy button, a collapsible
  reasoning block for thinking models, Stop, Regenerate, edit-and-resend, and per-chat system
  prompts.
- History: a drawer with date-grouped chats, full-text search, pin, rename and delete.
- Models:
  - Hugging Face search and one-tap starter models.
  - Downloads pinned to a commit, resumable with `If-Range`, checked against the file's sha256,
    and retried on flaky networks; storage is checked first.
  - Import of `.gguf` files.
  - A memory-fit check before each load that asks before loading a model that is tight on, or
    over, the memory budget.
  - A Storage screen (Models → storage icon): each model's size on disk and when it was last
    used, free space, one-step clean-up of models not loaded in 30 days, leftover files from
    unfinished downloads or imports, and temporary files.
- Engine: llama.cpp v0.9.0 through a small JNI layer.
  - Chat templates come from the GGUF via llama.cpp's own `common_chat`.
  - The KV cache is reused when the prompt starts the same as the last one.
  - Long chats keep their newest turns to fit the context.
  - Stop takes effect promptly, including during prompt processing, and keeps the part of the
    prompt that was already processed. Long prompts show a progress bar.
- Settings at three levels: global defaults, per model (name, system prompt, sampler, context
  size, threads, batch size, mmap, and a chat template override from the GGUF / a preset / custom
  Jinja with a live preview), and per chat (sampler overrides, system prompt). The most specific
  level that sets a value wins.
- A local benchmark (pp/tg tokens per second).

Not done yet: chat export/import, the HF token in Keystore storage,
split GGUF files, vision models, GPU backends, a tablet layout. Screenshots of every screen are
posted by CI on each pull request; README screenshots are a TODO.

## Architecture

The app is Kotlin and Jetpack Compose, with a thin C++ JNI layer over llama.cpp. There is no
Rust: see the decision update at the top of [docs/RESEARCH.md](docs/RESEARCH.md).

```
android/
├── llama/                 Android library: llama.cpp (git submodule, pinned to v0.9.0)
│   ├── llama.cpp/         ← submodule
│   └── src/main/
│       ├── cpp/           CMakeLists.txt + lomio_jni.cpp (load, chat templates, streaming,
│       │                  cancel, KV-prefix reuse, GGUF header reader, bench)
│       └── kotlin/        InferenceEngine (interface) + LlamaEngine (one native thread)
└── app/                   Compose UI and app logic (package dev.lomio)
    ├── data/              Room DB (models, chats, messages, FTS), DataStore settings,
    │                      Hugging Face client + resumable downloader (OkHttp, WorkManager)
    ├── domain/            pure logic: memory-fit estimate, settings resolution,
    │                      crash-loop guard, history grouping, chat state reducer
    ├── engine/            ModelManager (load/unload + guards), ChatService (runs turns)
    └── ui/                Chat, Models, Hugging Face search, Settings, Benchmark, About, drawer
```

How the parts work:

- **Native libraries.** On arm64, llama.cpp is built with `GGML_BACKEND_DL` and
  `GGML_CPU_ALL_VARIANTS` plus KleidiAI. Each ARM feature level gets its own CPU backend `.so`
  (armv8.0 up to armv9.2/SME), and the best one is loaded at runtime from
  `nativeLibraryDir`. x86_64 (emulator only) gets one SSE4.2 baseline module. All native code is
  built as Release, with 16 KB page alignment.
- **Settings resolution.** For each setting, a value set on the chat wins, then the model's
  setting, then the global default.
- **Crash-loop guard.** The app writes a marker before each model load and clears it after the
  load's warm-up decode. If the process dies in between, the next launch does not auto-load that
  model and shows a banner instead. Auto-load only happens when the UI comes to the foreground,
  never when WorkManager starts the process. The model is unloaded after a configurable time in
  the background.
- **Storage.** Models live in `noBackupFilesDir`. Backup and device-transfer rules exclude the
  models and the settings, which hold the HF token.

## Building

Prerequisites:

- JDK 17 or newer. CI uses Temurin 21; JDK 27 also works locally with the pinned Gradle 9.8.2.
- An Android SDK with platform 37, NDK `30.0.16248370` and CMake `3.31.6`. AGP installs the
  platform automatically once the licenses are accepted.

```sh
git clone --recurse-submodules https://github.com/FerraStack/lomio
cd lomio/android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # if ANDROID_HOME isn't set
./gradlew testDebugUnitTest assembleDebug             # APK: app/build/outputs/apk/debug/
```

Faster local builds:

- `-Plomio.abis=arm64-v8a` builds llama.cpp for one ABI only.
- On a shared machine, add `--max-workers=4` and `CMAKE_BUILD_PARALLEL_LEVEL=4`.

The first build compiles llama.cpp: about 10–20 minutes per ABI on a laptop. CI caches it with
ccache.

## CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) has three jobs:

1. **`android`**:
   - checks out the repo with submodules and installs the NDK and CMake
   - runs `testDebugUnitTest`: JVM and Robolectric tests, plus Roborazzi screenshots
   - builds `assembleDebug` and uploads the APK and the screenshots as artifacts
2. **`emulator`** (pull requests and manual runs only), on an API 35 x86_64 emulator with the
   Pixel 7 profile:
   - captures the launch screen
   - runs `EndToEndChatTest` with llama2.c stories15M (21 MB, cached): import the GGUF, load
     it from the model sheet, send a message, wait for streamed tokens and the `tok/s` line,
     taking screenshots along the way
   - runs `KvCacheReuseTest` on the same model: stop during prompt processing, then check that
     the next identical prompt reuses the tokens decoded before the stop
   - runs `StorageEndToEndTest`: open Storage through the UI, check the model's size on disk,
     remove a leftover file and temporary files, delete the model and check its file is gone
   - fails on any crash of `dev.lomio` in logcat
3. **`screenshots-comment`** (pull requests): pushes the PNGs to the orphan `ci-screenshots`
   branch and creates or updates a single PR comment with a light/dark table.

## License

Apache-2.0. llama.cpp is MIT. Some UI behaviour is modelled on PocketPal AI (MIT).
