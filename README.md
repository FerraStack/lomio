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
- Export chats as Markdown (readable) or JSON (everything, importable) through the system file
  picker, per chat or all at once; import JSON exports.
- Models:
  - Hugging Face search and one-tap starter models.
  - Downloads pinned to a commit, resumable with `If-Range`, checked against the file's sha256,
    and retried on flaky networks; storage is checked first. At most two run at once, the rest
    wait as Queued. On Android 14+ they run as user-initiated data transfer jobs (no 6 h
    dataSync cap); older versions use a WorkManager foreground job.
  - The Hugging Face token is stored encrypted (AES-GCM, key in the Android Keystore) and can be
    checked with *Test token*.
  - Import of `.gguf` files.
  - A memory-fit check before each load that asks before loading a model that is tight on, or
    over, the memory budget.
  - A Storage screen (Models → storage icon): each model's size on disk and when it was last
    used, free space, one-step clean-up of models not loaded in 30 days, leftover files from
    unfinished downloads or imports, and temporary files.
- Engine: llama.cpp v0.9.0 through a small JNI layer.
  - Chat templates come from the GGUF via llama.cpp's own `common_chat`.
  - The KV cache is reused when the prompt starts the same as the last one.
  - Prompt cache: when you switch to another chat, the KV cache of the one you leave is saved
    to disk (`llama_state_seq_*`, in the app's cache directory). Coming back restores it
    instead of re-processing the history. Caches are tied to the model file, the chat template
    and the llama.cpp build. A cache over 512 MB isn't kept, the least recently used caches go
    above 1 GB in total, and deleted chats' caches are removed on start.
  - Long chats keep their newest turns to fit the context.
  - Stop takes effect promptly, including during prompt processing, and keeps the part of the
    prompt that was already processed. Long prompts show a progress bar.
- Settings at three levels: global defaults, per model (name, system prompt, sampler, context
  size, threads, batch size, mmap, and a chat template override from the GGUF / a preset / custom
  Jinja with a live preview), and per chat (sampler overrides, system prompt). The most specific
  level that sets a value wins.
- Vision: models whose Hugging Face repo has an `mmproj` file get a "Vision" badge, and the
  projector downloads with the weights. In chat, attach up to four images with the system Photo
  Picker (no storage permission); llama.cpp's `libmtmd` encodes them. Images are kept in app
  storage, scaled to at most 1024 px. The memory-fit check counts the projector.
- GPU acceleration (experimental, off by default): llama.cpp's OpenCL backend for Snapdragon
  (Adreno) phones, offered only where the phone has an OpenCL driver. It falls back to the CPU
  when the GPU can't be used, and turns itself off after a crash during a GPU load. See
  [docs/GPU.md](docs/GPU.md); untested on real phones so far.
- A local benchmark (pp/tg tokens per second, and where the model runs).
- Local API server (off by default): an OpenAI-compatible endpoint for other apps, using the
  loaded model. See [Local API server](#local-api-server).

Not done yet: split GGUF files, a vision projector for imported (local) models, a tablet layout. Screenshots of every screen are
posted by CI on each pull request; README screenshots are a TODO.

## Local API server

Settings → **Local API server** turns on an OpenAI-compatible HTTP server so other apps can use
the model loaded in Lomio: Termux scripts, coding tools, chat front-ends, anything that lets you
set a base URL.

- **Off by default.** While it runs, a notification shows the address and has a Stop button. A
  foreground service (type `specialUse`) keeps the process and the model in memory.
- **127.0.0.1 only** unless you turn on *Allow devices on your Wi-Fi*. In that default mode any
  app on the phone can use the server without a key; turn on *Require the API key on this phone
  too* if you don't want that. Then it listens on all
  interfaces, and every request needs the API key shown in Settings
  (`Authorization: Bearer <key>`). In loopback mode, requests whose `Host` isn't
  `127.0.0.1`/`localhost` are refused (no DNS rebinding from web pages). JSON bodies must be
  sent as `application/json` (no cross-site form posts).
- **Endpoints:** `GET /v1/models` (the loaded model, named after its GGUF file) and
  `POST /v1/chat/completions`, with or without `"stream": true` (SSE, `data: [DONE]` at the
  end, `stream_options.include_usage` supported). Supported fields: `messages` (text content;
  `developer` counts as `system`), `temperature`, `top_p`, `top_k`, `min_p`, `max_tokens` /
  `max_completion_tokens`, `stop`, `seed`, `presence_penalty`, `frequency_penalty`,
  `repeat_penalty`, `chat_template_kwargs.enable_thinking`. Fields you leave out use Lomio's
  global and per-model settings. Reasoning models return their thoughts in
  `reasoning_content`, like llama.cpp's server. The `model` field is accepted but ignored: the
  server always answers with the loaded model.
- One reply at a time. Further requests wait their turn. While the app itself is writing a
  reply, requests get `503` with `Retry-After`. A client that disconnects stops its
  generation.

```sh
# From Termux on the phone (default port 8080):
curl http://127.0.0.1:8080/v1/models

curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Write a haiku about autumn"}]}'

# Streaming:
curl -N http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Count to five"}],"stream":true}'

# From a laptop on the same Wi-Fi (with Allow devices on your Wi-Fi turned on):
curl http://192.168.1.23:8080/v1/chat/completions \
  -H "Authorization: Bearer lomio-…" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Hello"}]}'
```

With the OpenAI Python SDK: `OpenAI(base_url="http://127.0.0.1:8080/v1", api_key="unused")`
(use the real key in Wi-Fi mode).

## Architecture

The app is Kotlin and Jetpack Compose, with a thin C++ JNI layer over llama.cpp. There is no
Rust: see the decision update at the top of [docs/RESEARCH.md](docs/RESEARCH.md).

```
android/
├── llama/                 Android library: llama.cpp (git submodule, pinned to v0.9.0)
│   ├── llama.cpp/         ← submodule
│   └── src/main/
│       ├── cpp/           CMakeLists.txt + lomio_jni.cpp (load, chat templates, streaming,
│       │                  cancel, KV-prefix reuse, images via libmtmd, GGUF reader, bench)
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
  `nativeLibraryDir`. x86_64 (emulator only) gets one SSE4.2 baseline module. arm64 also gets
  the OpenCL GPU backend as `liblomio-opencl.so`, loaded only when GPU acceleration is on
  ([docs/GPU.md](docs/GPU.md)). All native code is built as Release, with 16 KB page alignment.
- **Settings resolution.** For each setting, a value set on the chat wins, then the model's
  setting, then the global default.
- **Crash-loop guard.** The app writes a marker before each model load and clears it after the
  load's warm-up decode. If the process dies in between, the next launch does not auto-load that
  model and shows a banner instead. Auto-load only happens when the UI comes to the foreground,
  never when WorkManager starts the process. The model is unloaded after a configurable time in
  the background.
- **Storage.** Models live in `noBackupFilesDir`. Backup and device-transfer rules exclude the
  models and the settings; the HF token is stored only as Keystore-encrypted ciphertext.

## Building

Prerequisites:

- JDK 17 or newer. CI uses Temurin 21; JDK 27 also works locally with the pinned Gradle 9.8.2.
- An Android SDK with platform 37, NDK `30.0.16248370` and CMake `3.31.6`. AGP installs the
  platform automatically once the licenses are accepted.
- Python 3: the OpenCL backend embeds its kernels at build time. `-Plomio.opencl=false` leaves
  the GPU backend out.

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
   - runs `PromptCacheTtftTest` with the SmolVLM text model: leave a long chat, come back, and
     compare the time to first token with and without the saved cache (written to the job
     summary)
   - runs `GpuFallbackTest`: GPU acceleration on, where there is no GPU backend; the model
     must load on the CPU and show why, in the chat and on the Benchmark screen
   - runs `VisionChatTest` with SmolVLM-256M Q8_0 and its Q8_0 projector (279 MB, cached):
     attach an image through the attach button (the Photo Picker's intent is answered by
     espresso-intents), send a question and check for a non-empty answer
   - runs `ApiServerEndToEndTest`: switch the local API server on in Settings, then call
     `/v1/models` and `/v1/chat/completions` (streamed and plain) over HTTP against the real
     engine, and check the port closes when it's switched off
   - runs `ShortcutsEndToEndTest`: the latest-chat shortcut opens that chat, and the Quick
     Settings tile (added and clicked with `cmd statusbar`) opens a new chat with the message
     field focused
   - fails on any crash of `dev.lomio` in logcat
3. **`screenshots-comment`** (pull requests): pushes the PNGs to the orphan `ci-screenshots`
   branch (only the latest run per PR is kept; a push that races another PR is rebased and
   retried) and creates or updates a single PR comment with a light/dark table.

[`screenshots-cleanup.yml`](.github/workflows/screenshots-cleanup.yml) deletes a PR's
screenshots when it closes and squashes the branch weekly. All actions are pinned to commit
SHAs (Dependabot proposes updates); workflows default to `contents: read`.

## License

Apache-2.0. llama.cpp is MIT. Some UI behaviour is modelled on PocketPal AI (MIT).
