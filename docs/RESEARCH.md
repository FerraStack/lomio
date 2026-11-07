# Lomio — technical research

*Status: research input for the first architecture decision. Written 2026-11-07; all versions,
stars and dates were checked live on that day unless marked otherwise.*

Lomio is a "ChatGPT for local models" app for Android (iOS later): download GGUF models from
Hugging Face or import local files, chat with streaming output, keep conversation history,
tune per-model generation settings and system prompts, and optionally benchmark the device.
The reference product is [PocketPal AI](https://github.com/a-ghorbani/pocketpal-ai) (MIT,
React Native + llama.rn). Lomio deliberately drops PocketPal's personas ("Pals"), marketplace,
voice, tools and paid tier.

## Decision update (2026-11-07): Kotlin + C++ JNI, no Rust

**This supersedes the Rust-core recommendation in the TL;DR, §5, §7 and §8 below.** Those sections
are kept for the record.

The founder approved this stack:

- **Kotlin + Jetpack Compose** for the UI and all app logic.
- **A thin C++ JNI layer over llama.cpp**: llama.cpp is a git submodule pinned to a release tag
  and built by Gradle's `externalNativeBuild` (CMake).
- No UniFFI, no cargo, no `llama-cpp-2`.

Why:

- **Rust adds no inference speed.** llama.cpp is C/C++ either way (§5 already said so).
- **`llama-cpp-2` lags upstream** and does not expose llama.cpp's `common` library, which has:
  - Jinja chat templating and per-format reasoning parsing (`common_chat_*`), the area that
    causes PocketPal's #2 class of bugs
  - `mtmd`
  
  Calling `common_chat_session` directly from our own JNI code gets upstream's template and
  reasoning fixes for free.
- **One FFI hop, two build systems.** The Rust plan meant Kotlin → UniFFI → Rust → C++ (two FFI
  hops) and Gradle + cargo + CMake (three build systems). Now it is Kotlin → JNI → C++ with
  Gradle + CMake.
- **Prior art follows this pattern:** llama.cpp's own `examples/llama.android` (Arm-maintained,
  MIT) and SmolChat-Android.

What changes in the plan:

- `lomio-hub` → OkHttp + WorkManager.
- `lomio-store` → Room, plus DataStore for settings.
- `lomio-gguf` → llama.cpp's `gguf` API called through JNI.
- `lomio-templates` → llama.cpp `common_chat`.
- `lomio-engine` → the `:llama` Android library: `InferenceEngine` / `LlamaEngine`.
- The data model in §8.2, the UI spec in §9 and the milestones in §10 still apply, translated to
  Room entities and Compose screens.
- iOS later would need its own UI plus a Swift wrapper around the same C++ layer. That is
  accepted.
- The CPU strategy from §3.3–3.5 stands: on arm64, `GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS`
  + KleidiAI, with the CPU variant `.so` files loaded from `nativeLibraryDir`.

## TL;DR

*Superseded in part: see the decision update above (no Rust core).*


- **Engine: llama.cpp**, via the `llama-cpp-2` crate (utilityai/llama-cpp-rs, actively
  maintained, ~1–2 weeks behind upstream). Nothing else gives "paste any GGUF from Hugging Face
  and it works" — ~219k GGUF repos on HF, new architectures land upstream within days. Rust-native
  engines (mistral.rs, candle, burn) are CPU-only on Android and slower. LiteRT-LM (Google) is much
  faster at prefill on GPU/NPU but only runs a curated `.litertlm` catalog — a possible *second*
  backend later, not the base.
- **Run CPU-first.** Build llama.cpp with `GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS` (+ KleidiAI)
  so the best ARM variant (dotprod / i8mm / SVE2 / SME) is picked at runtime. Adreno OpenCL is an
  opt-in later (helps prefill, often not decode); Vulkan on phones is not worth it; Hexagon NPU is
  promising but experimental and not exposed by `llama-cpp-2` yet.
- **UI: Kotlin + Jetpack Compose** with the Rust core behind **UniFFI** (the Element X /
  Bitwarden / Firefox pattern). iOS later = SwiftUI over the same Rust core. Full-Rust UI toolkits
  (Slint, Dioxus native, egui, iced, Makepad, Freya) are **not ready** for a consumer chat app in
  late 2026: Android IME (soft keyboard composition/autocorrect) and TalkBack accessibility are
  the blockers.
- **Is Rust worth it?** Honest answer: **not for speed, and not strictly necessary for an
  Android-only app** — Kotlin + a small JNI layer over llama.cpp (like SmolChat-Android or
  llama.cpp's own `llama.android` example) would reach an MVP somewhat faster. Rust *is* worth it
  as a **shared, testable core** (model downloads, GGUF parsing, chat-template rendering,
  reasoning parsing, SQLite storage, settings resolution, benchmark, a future local
  OpenAI-compatible server) that iOS and desktop reuse unchanged, and that can be developed and
  tested on a laptop with a CLI. Keep the UI native; keep Rust out of the UI.
- **Biggest risks:** (1) native build complexity — Gradle → cargo-ndk → CMake → llama.cpp, with
  dynamic CPU-variant backends that `llama-cpp-2` hasn't proven on Android; (2) chat templates and
  reasoning parsing (PocketPal's #2 bug class) — `llama-cpp-2` doesn't expose llama.cpp's Jinja +
  `common_chat` parsers; (3) memory/OOM on phones (PocketPal's #1 bug class); (4) keeping up with
  llama.cpp's pace.

## 1. Scope and method

- Read PocketPal's source at commit `34f561b` (2026-11-06, v1.22.1): navigation (`App.tsx`),
  screens, MobX stores, WatermelonDB schema, default parameters, HF API client, device rules,
  English locale strings (to get exact UI labels).
- Read `llama-cpp-rs` at commit `0364f5d` (2026-11-04): features, `build.rs` Android handling,
  `mtmd`, chat-template API.
- Mined 537 PocketPal issues and 26 releases with `gh`, plus Reddit/review coverage of
  competitors.
- Checked current state of engines (llama.cpp backends, LiteRT-LM, MNN, ExecuTorch, MLC,
  mistral.rs, candle, burn) and UI stacks (UniFFI, Compose/CMP, Slint, Dioxus, egui, iced,
  Makepad, Freya, Tauri, Flutter/FRB, RN) via release pages, repos and docs.
- Nothing was built or benchmarked by us; benchmark numbers are third-party and from mixed
  devices. Milestone M1 below exists to replace them with our own.

## 2. PocketPal AI deep dive

Snapshot (2026-11-07): `a-ghorbani/pocketpal-ai`, MIT, ~9.1k stars, ~1.0k forks, 139 open
issues/PRs, latest release **v1.22.1 (2026-11-05)**, last push 2026-11-06. Very actively
developed; since ~Aug 2026 a large share of PRs are authored by an AI "dev-team" bot, which
explains the feature velocity (and some regressions).

### 2.1 Stack (from `package.json` / README at commit `34f561b`)

| Area | Choice |
|---|---|
| Framework | React Native 0.87.1 (New Architecture), React 19.2, TypeScript 5.9 |
| UI kit | React Native Paper 5 (Material 3-ish), React Navigation 7 (drawer + stack), `@gorhom/bottom-sheet`, Reanimated 4 |
| State | MobX 6 stores (`ModelStore`, `ChatSessionStore`, `HFStore`, `BenchmarkStore`, `PalStore`, `TTSStore`, `ASRStore`, `VoiceModeStore`, `ServerStore`, `UIStore`, …) persisted with `mobx-persist-store` + AsyncStorage |
| DB | WatermelonDB (SQLite) for chats; Keychain for secrets |
| LLM | `llama.rn` 0.13.0-rc.9 (llama.cpp b11960) over JSI, **patched** via `patch-package` |
| Markdown | `marked` + `react-native-render-html` + `react-native-code-highlighter` / `react-syntax-highlighter` |
| Extras | `whisper.rn` (STT), ONNX Runtime (Kokoro/Kitten/Supertonic TTS), "Phonon" TTS (its own **Rust** runtime), vision-camera, IAP, Supabase + Google/Apple sign-in (PalsHub), Firebase App Check |

Notable: PocketPal itself already ships a Rust native component (Phonon TTS) — Rust in a mobile
LLM app is not exotic anymore.

Default load parameters worth copying (from `contextInitParamsVersions.ts`): `n_ctx 2048`,
`n_batch 512`, `n_ubatch 512`, `n_threads 4`, `cache_type_k/v f16`, `n_gpu_layers 99` (only
effective when a GPU device is selected), `kv_unified true`, `image_max_tokens 512`, flash
attention `off` on Android / `auto` on iOS, and on Android **mmap off + weight repacking on**
("mmap OFF + repack ON is optimal on Android" — repacking Q4_0 into ARM-friendly layouts doubles
memory if the weights are also mmapped).

Default sampling (`completionSettingsVersions.ts`): temperature 0.7, top_k 40, top_p 0.95,
min_p 0.05, penalty_repeat 1.0 (off), penalty_last_n 64, XTC/mirostat/typical off,
`n_predict -1` (unlimited), `jinja: true`, `enable_thinking: true`,
`include_thinking_in_context: true`.

### 2.2 Navigation and screens

A **drawer navigator** is the root (`App.tsx`); an onboarding stack (6 screens + splash) is shown
on first launch. Drawer items: **Chat, Pals (experimental), Models, Benchmark, Settings, App
Info** (+ dev-only Dev Tools / Test Completion). The drawer body is the **chat history**
(`SidebarContent`): a `SectionList` grouped as *Pinned, Today, Yesterday, This week, Last week,
2/3/4 weeks ago, Last month, Older*; long-press → *Pin/Unpin, Rename, Export, Delete, Select…*
(bulk export/delete).

**Chat screen**
- Header: ☰, title = chat title with the active model under it, right menu: *Generation
  settings, Model, Duplicate chat history, Make chat temporary, Export (JSON/Markdown), Import,
  Delete*.
- Message list: inverted list (forked from Flyer Chat UI). Assistant output in Markdown with code
  blocks (header + copy), tables, collapsible **Reasoning** block, optional HTML preview bubble.
  Per-message footer shows performance: `ms/token`, `tokens/sec`, `prompt tokens/sec`, cached
  tokens, TTFT, speculative draft acceptance.
- Message actions (long-press): *Copy, Regenerate, Regenerate with (another model), Edit (user
  messages), Report content*. Long-press a paragraph to copy only that paragraph.
- Composer: multi-line input ("Message"), a **chevron button** that opens a bottom sheet to switch
  **Model / Pal** (two tabs), `+` for camera/gallery images (vision models), a **Think** toggle
  (with effort cycling) for reasoning models, mic button (Whisper), voice-mode button, Send ↔ Stop.
- Empty states: "No models available → Download model"; "Activate model to get started";
  "Ready to chat? Load the last used model".
- Context-full banner with *New chat* / *Increase context* (a sheet with a slider and
  fits/tight/won't-fit chips plus RAM estimate).
- Keeps the screen awake during inference; optional memory-usage indicator in the header.

**Models screen**
- `FlatList` of model cards in two accordions: **Ready to Use** and **Available to Download**
  (or grouped by model type). Header menu filters: *Hugging Face models, Downloaded, Group by
  type, Reset models list*.
- Model card: name, author/repo, size, params, capability/vision chips, warnings ("Low storage",
  "close to device memory", "Legacy quantization"), *Download* with progress & speed, then
  *Load/Offload*, *Settings*, *Delete/Remove*; expandable details (context length, architecture,
  "View model card on HF"); projector (mmproj) offer for vision models.
- FAB (`+`) → *Add from Hugging Face*, *Add Local Model* (document picker; copy into app
  storage with "Replace / Keep both"), *Add Remote Model*, *Manage servers*.
- **HF search** (bottom sheet): search box, filters *Author* and *Sort by* (Relevance, Downloads,
  Recently updated, Most liked); infinite scroll (HF `Link` header pagination, `limit=10`);
  detail view lists **"Available GGUF files"** with size, fits-in-storage/memory, gated lock,
  bookmark or download. API calls: `GET /api/models?search=&author=&filter=gguf&sort=&direction=-1`,
  `GET /api/models/{id}/tree/main?recursive=true` (file sizes + LFS sha256),
  `GET /api/models/{id}?expand[]=gguf` (architecture, context length, chat template, BOS/EOS).
- **Model settings** (per model): display name, BOS/EOS toggles + tokens, *Add generation
  prompt*, **System prompt**, chat template picker + raw template editor (Nunjucks; empty = use
  GGUF template), **stop words** chips, and the full sampler set.
- Preset list comes from **device rules** (`pocketpal-device-rules` JSON, bundled + fetched from
  jsDelivr): RAM band × SoC class → tiers *low/mid/high/flagship* with ordered candidates (e.g.
  low: Gemma 3 1B Q4_K_M; mid: SmolLM3 3B Q4_0, LFM2 2.6B; high/flagship: Gemma 3 4B Q4_0,
  Gemma 4 E2B/E4B Q4_0), with sha256, size, `min_ram_gb`, observed TG t/s. Policy note in the
  file: "for ≥3B models Q4_0 is primary (Hexagon 5× PP speedup, lower load mem); sub-1B uses
  Q4_K_M"; leave 25–35% RAM headroom for Android's low-memory killer. *The rules repo has no
  license file — don't copy it; build our own list using the same idea.*

**Settings screen** (global)
- *Model initialization*: device selection (CPU / GPU-OpenCL / Hexagon NPU on Android, Metal on
  iOS), layers on GPU, context size, batch/ubatch, CPU threads, image max tokens, flash attention
  (auto/on/off), K/V cache type, mlock, mmap, weight repacking, speculative decoding (draft model
  or embedded MTP).
- *Model loading*: Auto offload/load in background, auto-navigate to chat on load.
- *App*: language (16+ locales), dark mode, display memory usage, TTS availability.
- *API*: Hugging Face token (+ "Use HF token" toggle), internet-search provider keys, remote
  servers. *Export/Import* chats, clear caches, account/purchases.

**Benchmark screen**: model selector, profiles *Default* (pp512/tg128/pl1/nr3) / *Fast*
(pp128/tg32) / *Custom*, warning that the test can take 2–5 min, device-info card (SoC, cores,
RAM, CPU features FP16/DotProd/SVE/I8MM, GPU, OpenCL, Hexagon), result cards (PP t/s, TG t/s,
total time, peak memory, model settings), *Submit to leaderboard* (pocketpal.dev/leaderboard,
needs Firebase App Check).

**Pals / PalsHub**: personas (Assistant, Roleplay, "Lookie"/video) with model, system prompt,
color, greeting, generation settings and "talents" (tools); a marketplace with paid Pals.

**Voice**: Whisper dictation, hands-free voice mode (Plus), several TTS engines.

**Tools ("Talents")**: calculator, datetime, render_html, web_search, read_url via an
`AgentRunner` tool loop.

**Remote models**: OpenAI-compatible servers and llama-server router, QR pairing.

### 2.3 Keep vs drop for Lomio

| Feature | Lomio | Why |
|---|---|---|
| Chat with streaming, Markdown, code copy, stop, regenerate, edit-and-resend | **Keep (MVP)** | Core |
| Reasoning/thinking block + Think toggle | **Keep (MVP)** | Every 2025–26 model family (Qwen3.x, Gemma 4, GPT-OSS, LFM) reasons; top source of PocketPal bugs |
| Drawer with date-grouped history, pin, rename, export, bulk delete | **Keep (MVP)** | Users expect it |
| Models screen, HF search/download, local import, per-model settings | **Keep (MVP)** | Core |
| Device-aware recommended models | **Keep (MVP, small curated list)** | Biggest onboarding win |
| Per-chat generation settings, system prompt (global → model → chat) | **Keep (MVP)** | Replaces what Pals did for most users |
| Benchmark (local only) | **Keep (M4)** | Cheap with llama.cpp, great for bug reports |
| Context-full handling, memory-fit estimates | **Keep (MVP)** | #1 complaint class is OOM/crash |
| Vision (mmproj) | Later (post-MVP) | Valuable but doubles model-management complexity |
| GPU (OpenCL) / NPU (Hexagon) | Later, opt-in | Big regressions source in PocketPal; CPU first |
| Speculative decoding / MTP | Later | Nice speedup, complex |
| Pals, PalsHub, IAP, accounts, Supabase, Firebase | **Drop** | Explicitly out of scope; also blocks F-Droid |
| Voice (Whisper), TTS, voice mode | Drop (maybe much later) | Large surface, not "clean chat" |
| Tools/web search, HTML render | Drop | Agentic scope |
| Remote servers | Drop for now | Not "local models"; reconsider |
| Onboarding carousel, 16 locales | Minimal / later | English first, i18n-ready strings |

### 2.4 Known problems in PocketPal (what to do better)

From 537 issues + releases (full list in sources):

1. **OOM crashes / process kills** are the biggest theme (~45 issue titles): #89 "crashes
   whenever any model is loaded" (open since 2024), #512 killed in background on Android 16.
   #658: a model that crashes on load put the app into a **crash loop on every launch**.
   → Lomio: pre-load memory check (weights + KV + projector vs. *available* RAM), never auto-load a
   model whose previous load didn't complete ("safe mode"), conservative defaults.
2. **Backend regressions**: SIGABRT on SD 8 Gen 1 Hexagon (#960/#952), crash with default
   99 GPU layers (#964), Metal broken on M5 iPad (#1044). "No accelerators detected" on Adreno
   7xx/8xx mid-range, Exynos, Tensor (#796, #848, #679, #716). → CPU by default, GPU opt-in with
   automatic fallback remembered per device+model.
3. **Chat template / reasoning bugs** (~25 issues): thinking can't be disabled or hides the answer
   (#677 Gemma 4, #610 Qwen3.5), GPT-OSS Harmony output hidden (#735), wrong reasoning parse
   (#950, #484, #723), **previous chat leaking via KV cache** (#464). → Proper Jinja rendering,
   per-format reasoning parsers, clear KV on chat switch.
4. **Context**: "Context is full" regardless of setting (#371), no context shift/compaction
   (#864, #882).
5. **Downloads**: failures on flaky networks/background (#304, #450), corrupt files (#172),
   resumable downloads only arrived Oct 2026 (#954); HF blocked in some regions → mirror endpoint
   requested (#874, #359).
6. **New architectures** wait for llama.rn bumps (#661 Gemma 4, #1032 LFM3). → Make bumping
   llama.cpp a routine one-day task.
7. **Trust/FOSS**: F-Droid is the most-reacted issue (#55, open > 2 years), Firebase on launch
   (#143), backlash against the paid Plus tier (#1035). → Lomio can win here cheaply: no Google
   services, reproducible builds, F-Droid from day one.
8. **UI nits**: keyboard covers output (#777), no auto-scroll during long replies on iOS (#1013),
   code blocks not wrapping (#488), no text-size setting (#854), wrong fit badge (#974).

Most-requested missing features: local OpenAI-compatible API server (#259), F-Droid (#55),
LiteRT/NPU speed (#306), LaTeX (#138), chat with files (#183), edit/continue assistant messages
(#137), mmproj for locally imported models (#841), message search (#603).

Competitors: **Google AI Edge Gallery / LiteRT-LM** is reported ~2× faster with lower memory for
Gemma-class models; **MNN Chat** fast but unreliable and few models; **ChatterUI** better message
editing/formats; **Maid** is on F-Droid; **Off Grid** has more features but crashes. PocketPal wins
on GGUF breadth, HF access and polish.

## 3. Inference engine options

### 3.1 Comparison

| Engine | Android acceleration (Nov 2026) | Model format / coverage | Rust access | Verdict |
|---|---|---|---|---|
| **llama.cpp** | CPU (NEON/dotprod/i8mm/SVE2/SME, KleidiAI, Q4_0 repacking), OpenCL on Adreno 7xx/8xx, Hexagon HTP (experimental), Vulkan (poor on phones) | **GGUF — any HF GGUF**, new archs within days | `llama-cpp-2` (active), or own bindgen sys crate | **Pick** |
| LiteRT-LM (Google) v0.19.1 | CPU, GPU, NPU; Kotlin API stable, **C API 1.0 new in v0.19** | `.litertlm` only, curated (Gemma 4/3n/3, Phi-4-mini, small Qwen) | via C API (Bazel/CMake) | Runner-up as optional 2nd backend |
| MediaPipe LLM Inference | maintenance-only, superseded by LiteRT-LM | `.task` | no | Skip |
| MNN-LLM (Alibaba) 3.6.2 | CPU/OpenCL/Vulkan, Hexagon since 3.6.1 | MNN-converted models | C++ (`mnn` crate 0.3) | Fast but curated; skip |
| ExecuTorch 1.5.1 | XNNPACK, QNN, Vulkan, MediaTek | `.pte` exported per model (per SoC for QNN) | C++ (`executorch` crate) | Too much per-model work |
| MLC-LLM | OpenCL Adreno/Mali | TVM-compiled per model | C++/TVM | Slowing project; skip |
| ONNX Runtime GenAI 0.19 | CPU; QNN mostly desktop-focused | ONNX | C API / `ort` | Skip |
| mistral.rs 0.9.5 | **CPU only** on Android (CUDA/Metal elsewhere), no official Android | GGUF + others | native Rust | Not competitive on phones |
| candle 0.11 | **CPU only** (no Vulkan/wgpu) | GGUF quant kernels, no ARM repack/KleidiAI | native Rust | Skip |
| burn 0.22 | Vulkan/WebGPU via CubeCL in theory | no GGUF LLM runtime | native Rust | Skip |

### 3.2 Why llama.cpp

1. **Coverage.** HF lists ~219k GGUF repos; unsloth/bartowski/ggml-org publish GGUFs of new
   models within hours. Users of a PocketPal-like app expect to paste *any* HF GGUF. Every other
   engine needs a conversion step and a curated catalog.
2. **Proven on phones.** PocketPal (llama.rn), SmolChat, ChatterUI, Arm's own
   `examples/llama.android` all ship it.
3. **Multimodal** via `libmtmd` (image/audio through an `mmproj` GGUF) — exposed by `llama-cpp-2`
   behind the `mtmd` feature.
4. **Benchmarking** is built in (same metrics as `llama-bench`: pp/tg tokens/s).

The honest downside: Google's LiteRT-LM is far ahead on **prefill** using GPU/NPU (e.g. Gemma 4 E2B
on S26 Ultra: CPU 557 pp / 47 tg vs GPU 3808 pp / 52 tg; Reddit reports ~2× end-to-end speed and
half the memory vs llama.cpp on Gemma-class models). Decode speed is similar for same-size models
because decode is memory-bandwidth-bound. If users mostly run Gemma, a LiteRT-LM backend for the
curated catalog is a reasonable post-MVP addition; the engine trait in `lomio-engine` should allow
it.

### 3.3 llama.cpp backends on phones — what to actually ship

| Backend | State | Recommendation |
|---|---|---|
| **CPU** | Best path. Upstream builds Android CPU variants `armv8.0`, `armv8.2 (+dotprod, +fp16)`, `armv8.6 (+i8mm)`, `armv9.0 (+SVE2)`, `armv9.2 (+SME)` with `GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL`, selected at runtime. KleidiAI (`GGML_CPU_KLEIDIAI=ON`) accelerates Q4_0. Q4_0/IQ4_NL weights are repacked at load for dotprod/i8mm: ~2× prompt processing, same decode, but needs anonymous RAM ≈ model size (so disable mmap when repacking — PocketPal's Android default). | **MVP default** |
| **OpenCL (Adreno)** | Qualcomm-maintained; Adreno 750/810/830/840 (8 Gen 3, 7s Gen 3, 8 Elite, 8 Elite Gen 5); Q4_0 is the optimized path. Mixed results: 8 Gen 2 + Qwen2.5-3B Q4_K_M CPU 45 pp/13.8 tg vs OpenCL 37/13.0 (and 25–37% slower under sustained load); SM7750 + 2B Q4_0 OpenCL 211 pp vs CPU 116, but tg 15 vs 18. | Post-MVP, opt-in, per-device auto-fallback |
| **Vulkan** | Wrong output on Adreno, very slow on Mali reported repeatedly (discussions #9464, #17456). | Don't ship |
| **Hexagon HTP** | Contributed by Qualcomm, still "experimental"; needs Hexagon SDK 6.x; HTP libs `libggml-htp-v73…v85.so`. Upstream numbers on 8 Elite, Llama-3.2-1B Q4_0: **169 pp / 51.5 tg**. llama.rn ships it with an SoC allowlist (SM8450/8550/8635/8650/8750/8845/8850). Not exposed by `llama-cpp-2` (issue #956 open). PocketPal shows SIGABRT regressions on 8 Gen 1 (#960/#952). | v2+, needs own sys crate or upstream PR |

Representative CPU numbers (third-party, mixed devices): OnePlus 13 (8 Elite) Llama-3.2-3B Q4_0
≈ 168 pp512 / 17.6 tg128 t/s; SM7750 2B Q4_0 116 pp / 18 tg (Q4_K_M: 70 / 15); OpenMP *reduced*
decode to 8 t/s on that device (keep `GGML_OPENMP=OFF`, which `llama-cpp-2` forces on Android).
Practical model sizes: 1–4B at Q4 on 6–8 GB phones, up to ~8B on 12 GB+ flagships; leave 25–35% of
RAM as headroom for Android's low-memory killer (PocketPal device-rules policy).

**How PocketPal/llama.rn ship it:** six arm64 `.so` builds (`v8`, `v8_2`, `v8_2_dotprod`,
`v8_2_i8mm`, `v8_2_dotprod_i8mm`, `v8_2_dotprod_i8mm_hexagon_opencl`) + x86_64; Java reads
`/proc/cpuinfo` and `System.loadLibrary`s the best match. OpenCL is linked against a stub
`libOpenCL.so` built from the Khronos ICD loader (never shipped; the vendor driver provides it),
declared with `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` (same
for `libcdsprpc.so` for Hexagon). OpenCL is only offered with Adreno + i8mm + dotprod. ABIs
`arm64-v8a, x86_64`, minSdk 24. With `GGML_BACKEND_DL` the same is achieved more cleanly: one
`libllama.so`, CPU variants and `libggml-opencl.so` as loadable modules that simply fail to load
where unsupported.

### 3.4 `llama-cpp-2` in detail

- crates.io `llama-cpp-2` 0.1.161 (2026-11-03; repo at 0.1.162), 8 releases Aug→Nov 2026, ~886k
  recent downloads, 665★. Vendored llama.cpp `8642541` (2026-10-28) vs upstream v0.9.0 /
  b12189 → ~1–2 weeks lag.
- `build.rs` drives llama.cpp CMake via the `cmake` crate, finds the NDK
  (`ANDROID_NDK`/`ANDROID_NDK_ROOT`/`CARGO_NDK_ANDROID_NDK`/`$ANDROID_HOME/ndk`), sets the NDK
  toolchain file/ABI/platform, forces `GGML_OPENMP=OFF` and `GGML_LLAMAFILE=OFF` on Android, and
  forwards any `GGML_*`/`CMAKE_*` env var to CMake.
- Features: `opencl` (since June 2026), `vulkan`, `mtmd`, `dynamic-backends`
  (`GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS` + `load_backends_from_path()`),
  `android-shared-stdcxx`/`android-static-stdcxx`, `common`, `llguidance`, `system-ggml`, …
- **Trap:** without `dynamic-backends`, arm64 gets `-march=armv8-a` → **no dotprod/i8mm**, i.e.
  several times slower Q4 inference. Either use `dynamic-backends` or set
  `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod` as a minimum. Whether `dynamic-backends` works end-to-end on
  Android with this crate is **unverified** — first thing to test in M1.
- Full sampler chain (temp, top-k/p, min-p, typical, top-n-σ, XTC, DRY, penalties, mirostat,
  grammar/lazy grammar, logit bias, llguidance) and MTP speculative decoding wrappers.
- **Gap — chat templates:** `apply_chat_template` wraps only `llama_chat_apply_template` (a
  fixed list of built-in templates, not real Jinja). llama.cpp's `common_chat_*` (Jinja engine +
  per-format reasoning/tool-call parsers used by `llama-server` and llama.rn's
  `getFormattedChat`) is **not** exposed. Options:
  1. **Own C++ shim** (~200 lines) exposing `common_chat_templates_init/apply` and the
     reasoning parser, compiled alongside `libcommon` (which `llama-cpp-sys-2` already builds with
     the `common` feature). Best fidelity: we inherit upstream's fixes for Harmony (GPT-OSS),
     Gemma 4, Qwen3.x, LFM thinking formats — the exact things PocketPal keeps breaking.
  2. **minijinja 3.0** (+ `minijinja-contrib` pycompat) rendering `tokenizer.chat_template` from
     the GGUF in Rust, plus our own reasoning-tag parser. Pure Rust, easy to test, but we own every
     template quirk.
  Recommendation: start with (2) for speed of M1, plan (1) as soon as reasoning models are in scope
  (they are, from day one in 2026) — decide in M1 after testing ~10 popular templates.
- Escape hatch: if the crate fights us (Android variants, Hexagon, template API), write our own
  `lomio-llama-sys` with bindgen over `llama.h` + `mtmd.h` + our C++ shim and upstream CMake. The
  maintainer himself notes the crate "does not add any functionality on top" (issue #852); the C
  API is small.

### 3.5 Build recipe (pick)

```toml
# crates/lomio-engine/Cargo.toml
llama-cpp-2 = { version = "0.1.16x", default-features = false,
                features = ["dynamic-backends", "mtmd", "android-shared-stdcxx"] }
# later: "opencl"
```

1. `cargo ndk -t arm64-v8a -P 28 -o android/app/src/main/jniLibs build --release`
   (`-t x86_64` too for the emulator). Use NDK r28+/r29 (16 KB page alignment by default — Play
   requires 16 KB-compatible native libs for apps targeting Android 15+; upstream `llama.android`
   pins 29.0.13113456).
2. Env for CMake: `GGML_NATIVE=OFF`, `GGML_CPU_KLEIDIAI=ON`, `LLAMA_OPENSSL=OFF` (we download in
   Rust), `GGML_OPENMP=OFF` (forced). Do **not** combine `GGML_CPU_ARM_ARCH` with
   `GGML_CPU_ALL_VARIANTS`.
3. Package `libllama.so`, `libggml*.so`, `libggml-cpu-android_*.so` and `libc++_shared.so` in
   `jniLibs` (cargo-ndk copies only our cdylib — an `xtask` step copies the rest).
   `extractNativeLibs=true` (or `useLegacyPackaging`) so the backend `.so` files exist on disk; pass
   `applicationInfo.nativeLibraryDir` to Rust and call `load_backends_from_path()` before
   `LlamaBackend::init()`. Verify in logs that the i8mm/SME variant is chosen on a modern phone.
4. OpenCL later: build a stub `libOpenCL.so` from Khronos OpenCL-ICD-Loader + headers
   (as llama.rn's `build-opencl.sh`), set `OPENCL_INCLUDE_DIR`/`OPENCL_LIBRARY`, never ship the
   stub, declare `uses-native-library libOpenCL.so required=false`.
5. Release builds: `arm64-v8a` only for Play/F-Droid (32-bit ARM phones can't run useful LLMs
   anyway); `x86_64` in debug for emulators.

## 4. UI options

### 4.1 Scorecard (1–5, 5 = best; "Risk" 5 = lowest risk)

| Option | IME / text input | Markdown + code | Streaming | A11y (TalkBack) | iOS path | APK size | Dev velocity | Risk | Total /40 |
|---|---|---|---|---|---|---|---|---|---|
| **A1 Compose + UniFFI (+ SwiftUI later)** | 5 | 4 | 4 | 5 | 3 | 4 | 4 | 5 | **34** |
| F Kotlin + llama.cpp JNI (no Rust) | 5 | 4 | 4 | 5 | 2 | 5 | 4 | 4 | 33 |
| D Flutter + flutter_rust_bridge | 4 | 4 | 4 | 4 | 5 | 3 | 4 | 4 | 32 |
| C Tauri v2 mobile | 4 | 5 | 4 | 4 | 4 | 4 | 4 | 3 | 32 |
| A2 Compose Multiplatform (shared UI) | 4 | 4 | 4 | 4 | 4 | 4 | 4 | 3 | 31 |
| E React Native + uniffi-bindgen-react-native | 5 | 3 | 3 | 4 | 5 | 3 | 4 | 3 | 30 |
| B Makepad | 3 | 3 | 4 | 1 | 4 | 5 | 2 | 2 | 24 |
| B Slint / Dioxus native (Blitz) / egui | 1–3 | 2–3 | 3–4 | 1–2 | 1–3 | 4 | 2–3 | 1–2 | ~20 |
| B Freya / iced | 1–2 | 2 | 3 | 2 | 1 | 4 | 2 | 1 | ~16 |

APK note: every option carries the same llama.cpp native libs (~5–15 MB arm64 incl. CPU
variants); UI layers add roughly Compose 2–5 MB, Tauri 1–3 MB (system WebView), Flutter 4–6 MB,
RN 6–10 MB (estimates).

### 4.2 Findings per option

**(A) Kotlin + Jetpack Compose + UniFFI — recommended.**
- UniFFI **0.32.2** (2026-09-22): Rust `async fn` → Kotlin `suspend` / Swift `async`; callback
  interfaces implemented in Kotlin/Swift; `async_runtime = "tokio"` exports. Caveats:
  coroutine cancellation does **not** cancel the Rust future (design explicit `stop()`), no native
  `Flow` (wrap a callback in `callbackFlow`), Kotlin bindings use JNA today (an experimental
  JNI-based bindgen is in the unreleased changelog). 0.x → pin versions.
- Production: **Element X** Android (Compose) + iOS (SwiftUI) on `matrix-sdk-ffi`; Bitwarden
  `sdk-internal`; Firefox (application-services).
- Markdown: **mikepenz/multiplatform-markdown-renderer** 0.46 (2026-10) has
  `rememberStreamingMarkdownState()` / `collectAsStreamingMarkdownState(Flow<String>)` that
  re-parses only the unstable tail — exactly the LLM streaming case — GFM tables, code module with
  syntax highlighting (Highlights) and copy, LazyColumn-friendly, multiplatform. Alternatives:
  compose-richtext (alpha, used by Google AI Edge Gallery), Markwon via `AndroidView`
  (unmaintained since 2021 but used by SmolChat and MNN Chat). Text selection in the renderer is
  not documented — wrap in `SelectionContainer` and test. LaTeX needs another library.
- Compose `TextField` is the reference IME implementation (Gboard swipe, composition,
  autocorrect, selection handles); TalkBack is first-class.
- Streaming recipe: keep the in-progress message in its own state; stable keys + `contentType`
  in `LazyColumn`; coalesce tokens every ~30–60 ms.
- Real LLM apps on Compose: Google AI Edge Gallery, SmolChat-Android, MLC Chat, MNN Chat.

**(A2) Compose Multiplatform** 1.12.2 is stable on iOS (since 1.8, May 2025; 1.12 improved
VoiceOver and text input). The catch: UniFFI's Kotlin bindings are JVM-only (JNA), not
Kotlin/Native, so sharing the UI with iOS needs **Gobley** (UniFFI → KMP; 444★, last release
v0.3.7 Oct 2025) or per-platform bridges behind `expect/actual`. Keep the door open (use KMP-friendly
UI libs like the mikepenz renderer), decide when iOS starts.

**(B) Full-Rust UI — not ready for this app.**
- *Slint* 1.18: royalty-free license OK for apps (with attribution); Android IME via a hidden
  `InputConnection` view, composition underline still open (#14291), SwiftKey issues (#9240);
  **no Android accessibility code at all**; markdown via `StyledText` without code blocks. Focus
  is embedded/desktop.
- *Dioxus* 0.7 stable / 0.8 alpha: mobile defaults to a WebView; the native Blitz renderer is
  "very young", IME composition (#1086) and caret issues open, Android onclick bug (#5786).
- *egui* 0.37: Android soft keyboard issues open since 2022 (#5590, #3890) because winit's
  Android IME PRs (#4451/#4459) are unmerged.
- *iced*: no official mobile support (#302 open since 2020). *Freya* 0.5: Android new, iOS in PR.
- *Makepad*: the strongest of these on mobile (Robrix Matrix client, native `UITextView` input on
  iOS) but no accessibility (#196) and a small community.
- Conclusion: a chat app is mostly *text input + long selectable rich text*; that is exactly where
  these toolkits are weakest. Revisit in 2027.

**(C) Tauri v2 mobile** (2.12.5; **v3 alpha already out** → migration within a year). Chromium
WebView gives excellent IME and accessibility and the richest streaming-markdown ecosystem
(vercel/streamdown, shiki). Downsides: keyboard/viewport resize bugs on Android/iOS (#7868,
#10631, #9907), ~214 open Android issues, "web-in-a-shell" feel. Example: LettuceAI (Tauri +
`llama-cpp-2`, Android). Good choice only for a web-first team.

**(D) Flutter + flutter_rust_bridge** 2.13 (stable, Flutter Favorite): `StreamSink<T>` for token
streams, `gpt_markdown` built for streaming LLM output (LaTeX, code). Best single-codebase iOS
story. Text input is Flutter's own bridge (good, occasional Gboard quirks). Solid runner-up if one
UI codebase matters more than native feel. (Note: Maid, often cited as "Flutter + llama.cpp", is
now pure React Native/TypeScript as of v3.0.)

**(E) React Native** works (PocketPal, ChatterUI), but the Rust bridge
(uniffi-bindgen-react-native 0.31, pre-1.0, being renamed) lags UniFFI and RN's markdown story is
the weakest. Only for a React-first team.

**(F) Kotlin + llama.cpp JNI, no Rust** — see section 5.

## 5. Is Rust worth it here at all?

**Straight answer:** Rust buys nothing for inference speed (llama.cpp is C/C++ either way) and is
not required to ship an Android app. If Lomio were Android-only forever, **Kotlin + ~500 lines of
JNI C++ over llama.cpp** (the pattern of SmolChat-Android and llama.cpp's own Arm-maintained
`examples/llama.android`; or the Llamatik KMP SDK) plus OkHttp/WorkManager/Room would be the
fastest route to an MVP — probably 20–30% less effort for v0.1, one fewer FFI layer, one fewer
build system, simpler native crash debugging.

**Rust is worth it if — and because — Lomio wants iOS/desktop later and treats the non-UI logic as
the product.** In a local-LLM chat app the non-UI part is large and subtle:

| Concern | Rust value |
|---|---|
| HF client + resumable downloader (Range, retries, sha256 verify, mirrors, split GGUFs) | `reqwest` + `tokio` + `sha2`; written once for Android/iOS/desktop. PocketPal only got resumable Android downloads in Oct 2026. |
| GGUF header parsing + memory-fit estimation | Pure Rust, unit-testable against real files on a laptop. Prevents PocketPal's #1 bug class (OOM). |
| Chat templating + reasoning parsing | `minijinja` 3.0 (Jinja2-compatible, used by mistral.rs/TGI) or a C++ shim over llama.cpp `common_chat`; either way shared and testable with golden files. |
| Storage | `rusqlite` 0.41 (bundled SQLite, FTS5) + `rusqlite_migration`/`refinery`; one schema on all platforms. |
| Engine thread, KV-cache prefix reuse, cancellation, benchmark | Safe ownership of `llama_context` pointers; RAII for model/context/batch lifetimes; no JNI global-ref leaks. |
| Future local OpenAI-compatible server (PocketPal's most-requested feature, #259) | `axum` in the same core — trivial in Rust, painful in Kotlin+iOS twice. |
| Dev loop | A desktop CLI over `lomio-core` lets you iterate on templates, downloads, DB and sampling without a phone; CI runs real model tests on Linux. |

**Costs to accept:** Kotlin → UniFFI (JNA) → Rust → C++ is two FFI hops; Gradle + cargo-ndk +
CMake toolchain; native crashes need symbolized tombstones from both Rust and C++ (ship
`ndk-stack`-friendly debug symbols); UniFFI is 0.x; `llama-cpp-2` lags upstream by 1–2 weeks (or we
maintain our own sys crate). These are manageable and well-trodden (Element X, Bitwarden,
Firefox).

**Where Rust should NOT go:** the UI. Section 4 shows that full-Rust UI is the one place where
Rust would actively hurt the product in 2026.

## 6. Supporting crates (current versions, 2026-11-07)

| Purpose | Crate | Version |
|---|---|---|
| LLM engine | `llama-cpp-2` / `llama-cpp-sys-2` | 0.1.161 (crates.io) |
| FFI | `uniffi` | 0.32.2 |
| Async runtime | `tokio` | 1.54 |
| HTTP | `reqwest` (rustls, stream) | 0.13.5 |
| HF hub helpers (optional; we mostly need 3 endpoints) | `hf-hub` | 1.0.0 |
| Chat templates | `minijinja` + `minijinja-contrib` (pycompat) | 3.0.5 |
| SQLite | `rusqlite` (`bundled`) + `rusqlite_migration` | 0.41.1 / 2.6.0 |
| GGUF metadata | own ~300-line reader (or `gguf-rs-lib` 0.3.4) | — |
| Markdown (desktop/CLI export only) | `pulldown-cmark` | 0.13.6 |
| Android build | `cargo-ndk` | 4.1.2 |
| Hashing / errors / logging | `sha2`, `thiserror`, `tracing` + `tracing-android` | — |

Hugging Face endpoints actually needed (same as PocketPal):
`GET /api/models?search=&author=&filter=gguf&sort={downloads|likes|lastModified|trendingScore}&direction=-1&limit=N`
(pagination via `Link` header), `GET /api/models/{repo}/tree/main?recursive=true` (file sizes +
LFS sha256), `GET /api/models/{repo}?expand[]=gguf` (architecture, context length, chat
template), downloads from `https://huggingface.co/{repo}/resolve/main/{file}` with optional
`Authorization: Bearer` (only ever sent to `huggingface.co` — PocketPal pins this, so should we)
and a configurable mirror base URL (requested by users where HF is blocked).

## 7. Recommendation

*Superseded: the stack is now Kotlin + C++ JNI (see the decision update at the top). The
product decisions and risks below still apply.*


**Stack**

| Layer | Choice |
|---|---|
| UI (Android) | Kotlin 2.x, Jetpack Compose + Material 3, Navigation 3 (or Navigation-Compose), ViewModel + `StateFlow`, Hilt or manual DI, mikepenz multiplatform-markdown-renderer (streaming state + code module) |
| UI (iOS, later) | SwiftUI over the same UniFFI Swift bindings (re-evaluate Compose Multiplatform + Gobley at that time) |
| Bridge | UniFFI 0.32 proc-macros, a single `lomio-ffi` crate; callback interface for token streaming; explicit `stop()` |
| Core | Rust workspace: `lomio-core`, `lomio-engine`, `lomio-hub`, `lomio-gguf`, `lomio-templates`, `lomio-store` |
| Engine | llama.cpp via `llama-cpp-2` with `dynamic-backends` (CPU variants + KleidiAI); OpenCL opt-in later; own sys crate as fallback |
| Templates | minijinja first; C++ shim over llama.cpp `common_chat` once reasoning-format parity matters (decide in M1) |
| Storage | SQLite (rusqlite bundled, FTS5) in app-private storage; HF token in Android Keystore-backed encrypted prefs (Kotlin side), passed to Rust per request |
| Downloads | Rust downloader; Kotlin owns the lifecycle (user-initiated data transfer job / foreground service + notification) |
| Distribution | GitHub Releases + **F-Droid from v0.1** (no Google services, no Firebase, no analytics) + Play |
| Min SDK / ABIs | minSdk 28 (Android 9), target latest; `arm64-v8a` (+ `x86_64` debug only) |

**Product decisions that follow from PocketPal's issue tracker**

1. Memory-fit check before every load (weights + KV at chosen `n_ctx` + projector vs. currently
   *available* RAM with 25–35% headroom); refuse or suggest a smaller quant/context.
2. Never auto-load a model whose last load didn't complete (crash-loop guard, "safe mode").
3. CPU default; any accelerator is opt-in with automatic fallback remembered per device+model.
4. Reasoning models first-class: Think on/off toggle, collapsible reasoning, correct parsing.
5. Clear/separate KV state per conversation (PocketPal #464 leaked chats via the KV cache).
6. Context meter + graceful "context full" handling; context shift later.
7. Robust downloads: resumable, verified, background, mirror-configurable.
8. Trust: no Google services, reproducible builds, exportable logs instead of telemetry.
9. Make bumping llama.cpp a routine, CI-tested, same-week task.

**Explicit non-goals for v0.x:** personas/Pals, marketplace, accounts, purchases, voice
in/out, tools/web search, remote servers, onboarding carousels.

**Biggest risks and mitigations**

| Risk | Mitigation |
|---|---|
| Android native build (Gradle → cargo-ndk → CMake → llama.cpp dynamic CPU variants) doesn't work cleanly with `llama-cpp-2` | M1 is a go/no-go spike on real phones; fallback = own bindgen sys crate over upstream CMake, or llama.cpp's `examples/llama.android` CMake setup as reference |
| Chat template / reasoning parsing bugs | Golden-file tests per model family in CI on desktop; C++ shim over llama.cpp `common_chat` if minijinja + own parser falls behind |
| OOM / process kills | Memory-fit estimator, conservative defaults, crash-loop guard, auto-unload in background |
| llama.cpp churn (API changes, new archs weekly) | Pin a version, CI job that bumps it weekly and runs smoke tests with 3–4 tiny GGUFs |
| UniFFI 0.x breaking changes | Pin; keep the FFI surface small and coarse-grained |
| GPU backends regress on some SoCs | Opt-in only, per-device+model fallback memory |
| Second UI for iOS | Keep logic in Rust; iOS UI is mostly 4 screens; reconsider CMP + Gobley then |

## 8. Proposed architecture

*Superseded: the Rust workspace layout is replaced by `android/llama` + `android/app` (see the
decision update and README). The data model (§8.2) still applies.*


### 8.1 Repository layout

```
lomio/
├── Cargo.toml                    # Rust workspace
├── rust-toolchain.toml           # pinned stable toolchain + android targets
├── crates/
│   ├── lomio-engine/             # llama.cpp wrapper: load/unload, generate, cancel, bench
│   │                              #   depends on llama-cpp-2 (+ thin C++ shim if needed)
│   ├── lomio-templates/          # chat-template rendering (minijinja + pycompat),
│   │                              #   thinking-tag + stop-string handling, fallbacks
│   ├── lomio-hub/                # Hugging Face API client + resumable downloader
│   │                              #   (reqwest + tokio, Range requests, sha256 verify)
│   ├── lomio-gguf/               # GGUF header reader: arch, ctx len, layers, template,
│   │                              #   memory estimate (no full load needed)
│   ├── lomio-store/              # SQLite via rusqlite (bundled) + migrations
│   ├── lomio-core/               # app services: ModelService, ChatService, Settings,
│   │                              #   orchestrates engine/templates/store; pure Rust, testable
│   └── lomio-ffi/                # UniFFI surface (the ONLY crate that knows about FFI)
├── android/                      # Gradle project (Kotlin + Jetpack Compose)
│   ├── app/                      # UI, ViewModels, navigation, DI
│   └── core-bindings/            # generated Kotlin bindings + jniLibs (built by cargo-ndk)
├── ios/                          # later: SwiftUI app on the same lomio-ffi (generated Swift)
├── cli/                          # `lomio` desktop CLI over lomio-core: dev loop + CI tests
├── docs/
└── xtask/                        # cargo xtask: build-android, bindgen, bench, release
```

Rules of thumb:

- `lomio-core` never imports UniFFI or Android types; `lomio-ffi` is a thin adapter. This keeps
  the core testable on a Linux/macOS dev machine with a CLI (`cli/`) — the single biggest
  dev-velocity win of the Rust approach: you can iterate on prompts, templates, downloads and the
  DB without deploying to a phone.
- One long-lived **engine thread** owns the `LlamaContext` (llama.cpp contexts are not
  thread-safe). Everything else talks to it via a command channel. Generation streams tokens to
  the UI through a UniFFI callback interface (`TokenSink`) or an async `next_event()` poll; batch
  tokens at ~30–60 ms to avoid flooding the JNI boundary and Compose recompositions.
- Cancellation: an `AtomicBool` checked between decode steps; `stop()` returns promptly.
- `lomio-engine` exposes an `Engine` trait (`load`, `generate`, `tokenize`, `bench`) with
  `LlamaCppEngine` as the only implementation for now, so a LiteRT-LM backend can be added later
  without touching the core.
- Crash-loop guard: write a `loading = {model_id, settings}` marker to SQLite before
  `load_model`, clear it after the first successful decode; on startup, if a marker exists, do not
  auto-load and show "Last load of X crashed — try smaller context / CPU only".
- Switching conversations clears the KV cache (`llama_memory_clear`) or uses a separate sequence id;
  the prefix-reuse cache is keyed by conversation id.
- KV-cache reuse across turns: keep the last tokenized prompt; on the next turn only decode the
  diff (prefix match). This is what makes multi-turn chat feel fast on phones.
- Downloads run in Rust (`lomio-hub`) but the **Android lifecycle** around them (a
  user-initiated data-transfer job or a `dataSync` foreground service with a notification) is
  Kotlin. Rust exposes `start/pause/resume/cancel` + progress callbacks; Kotlin keeps the process
  alive. Write to `<file>.part`, resume with HTTP `Range`, verify size + sha256 (HF exposes the LFS
  oid = sha256), then atomic rename.
- Files live in app-specific storage (`filesDir/models/<repo>/<file>.gguf`). Local import uses the
  Storage Access Framework; copy into app storage (simple, robust) with an option to reference
  in place via a persisted URI + `/proc/self/fd` path for huge files (advanced, later).

### 8.2 Data model (SQLite)

```sql
-- Models known to the app (downloaded, downloading, or bookmarked)
CREATE TABLE model (
  id            TEXT PRIMARY KEY,          -- uuid
  source        TEXT NOT NULL,             -- 'hf' | 'local' | 'catalog'
  hf_repo       TEXT,                      -- e.g. 'unsloth/gemma-3-4b-it-GGUF'
  hf_file       TEXT,                      -- e.g. 'gemma-3-4b-it-Q4_0.gguf'
  hf_revision   TEXT,                      -- commit sha pinned at download time
  display_name  TEXT NOT NULL,
  file_path     TEXT,                      -- absolute path once downloaded/imported
  size_bytes    INTEGER NOT NULL,
  sha256        TEXT,
  state         TEXT NOT NULL,             -- 'remote' | 'downloading' | 'paused' | 'ready' | 'error'
  bytes_done    INTEGER NOT NULL DEFAULT 0,
  -- cached GGUF metadata (parsed once from the header)
  arch          TEXT, n_params INTEGER, n_layers INTEGER, n_ctx_train INTEGER,
  quant         TEXT,                      -- 'Q4_0', 'Q4_K_M', ...
  chat_template TEXT,                      -- template embedded in the GGUF
  mmproj_model_id TEXT REFERENCES model(id), -- vision projector (post-MVP)
  created_at    INTEGER NOT NULL,
  last_used_at  INTEGER
);

-- Per-model load + generation defaults (one row per model; JSON keeps it migration-friendly)
CREATE TABLE model_settings (
  model_id      TEXT PRIMARY KEY REFERENCES model(id) ON DELETE CASCADE,
  load_json     TEXT NOT NULL,   -- {n_ctx, n_batch, n_ubatch, n_threads, n_gpu_layers, backend,
                                 --  flash_attn, cache_type_k, cache_type_v, use_mmap, use_mlock}
  gen_json      TEXT NOT NULL,   -- {temperature, top_k, top_p, min_p, repeat_penalty, repeat_last_n,
                                 --  presence_penalty, frequency_penalty, max_tokens, seed, stop[]}
  system_prompt TEXT,            -- per-model default system prompt (nullable = use global)
  template_override TEXT,        -- custom Jinja template (nullable = use GGUF's)
  thinking      TEXT             -- 'auto' | 'on' | 'off'
);

CREATE TABLE conversation (
  id            TEXT PRIMARY KEY,
  title         TEXT NOT NULL,
  model_id      TEXT REFERENCES model(id) ON DELETE SET NULL, -- last model used
  system_prompt TEXT,            -- per-chat override (nullable)
  gen_override_json TEXT,        -- per-chat sampling overrides (nullable)
  pinned        INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
CREATE INDEX conversation_updated ON conversation(pinned DESC, updated_at DESC);

CREATE TABLE message (
  id            TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
  parent_id     TEXT REFERENCES message(id), -- enables edit/regenerate branches later
  role          TEXT NOT NULL,     -- 'system' | 'user' | 'assistant'
  content       TEXT NOT NULL,
  reasoning     TEXT,              -- extracted <think> content, shown collapsed
  status        TEXT NOT NULL,     -- 'complete' | 'streaming' | 'stopped' | 'error'
  model_id      TEXT,              -- which model produced it
  stats_json    TEXT,              -- {prompt_tokens, gen_tokens, ttft_ms, pp_tps, tg_tps, stop_reason}
  created_at    INTEGER NOT NULL
);
CREATE INDEX message_conv ON message(conversation_id, created_at);
CREATE VIRTUAL TABLE message_fts USING fts5(content, content='message', content_rowid='rowid'); -- history search

CREATE TABLE benchmark_run (
  id TEXT PRIMARY KEY, model_id TEXT, device_json TEXT, config_json TEXT,
  pp_tps REAL, tg_tps REAL, peak_mem_bytes INTEGER, created_at INTEGER NOT NULL
);

CREATE TABLE app_setting (key TEXT PRIMARY KEY, value_json TEXT NOT NULL);
-- theme, default_model_id, global_system_prompt, auto_unload_on_background,
-- keep_screen_on, show_stats, hf_token stored in Android Keystore/EncryptedFile, not here
```

Settings resolution order for a generation: **chat override → model settings → global
defaults**. Load settings (context size, threads, GPU layers) are per-model only, because they
require a reload.

### 8.3 FFI surface (sketch)

```rust
#[uniffi::export(async_runtime = "tokio")]
impl Lomio {
    #[uniffi::constructor] fn open(data_dir: String) -> Result<Arc<Self>, LomioError>;
    // models
    async fn hf_search(&self, q: HfQuery) -> Result<HfPage, LomioError>;
    async fn hf_files(&self, repo: String) -> Result<Vec<HfGgufFile>, LomioError>;
    fn download(&self, repo: String, file: String, cb: Box<dyn DownloadListener>) -> Result<DownloadHandle, LomioError>;
    fn import_local(&self, path: String) -> Result<ModelInfo, LomioError>;
    fn list_models(&self) -> Vec<ModelInfo>;
    fn delete_model(&self, id: String) -> Result<(), LomioError>;
    // engine
    async fn load_model(&self, id: String, opts: Option<LoadOptions>) -> Result<LoadedModel, LomioError>;
    fn unload(&self);
    // chat
    fn send(&self, conversation_id: String, text: String, sink: Box<dyn TokenSink>) -> Result<GenerationHandle, LomioError>;
    fn regenerate(&self, message_id: String, sink: Box<dyn TokenSink>) -> Result<GenerationHandle, LomioError>;
    fn conversations(&self) -> Vec<ConversationSummary>;
    fn messages(&self, conversation_id: String) -> Vec<MessageRecord>;
    async fn benchmark(&self, id: String, cfg: BenchConfig) -> Result<BenchResult, LomioError>;
}

#[uniffi::export(with_foreign)]
pub trait TokenSink: Send + Sync {
    fn on_delta(&self, text: String, reasoning: Option<String>);
    fn on_done(&self, stats: GenStats);
    fn on_error(&self, err: LomioError);
}
```

On the Kotlin side, wrap `TokenSink` in `callbackFlow {}` and collect it in a `ViewModel`.

## 9. UI spec (screens)

Design language: Material 3 (dynamic color on Android 12+, light/dark/system), edge-to-edge,
one accent color. The structure mirrors PocketPal (drawer + chat-first), minus Pals, PalsHub,
voice, tools, remote servers and purchases.

### 9.0 Navigation

- **Modal navigation drawer** (hamburger at top-left of Chat, or swipe from the left edge):
  1. `+ New chat` button (full-width, top)
  2. Destinations: **Chat**, **Models**, **Benchmark**, **Settings**, **About**
  3. **Chat history** list below, grouped by date: *Pinned, Today, Yesterday, This week, Last week,
     Older* (PocketPal uses the same buckets). Tap = open; long-press = menu
     (*Pin/Unpin, Rename, Export (Markdown/JSON), Delete*) and *Select…* for bulk delete/export.
  4. Search field at the top of the history list (FTS5) — PocketPal lacks this; cheap to add.
- On tablets / foldables (≥ 840dp): permanent drawer + chat side-by-side (Compose adaptive
  `NavigationSuiteScaffold` / list-detail).
- Start destination: **Chat**. First launch with no models → Chat shows the empty state that
  routes to Models (no multi-page onboarding carousel in MVP).

### 9.1 Chat screen

- **Top app bar**: ☰ menu · title = conversation title (auto-generated from the first user
  message, editable) with subtitle = loaded model name + small status dot (green loaded / amber
  loading / grey none). Tapping the title opens the **model picker sheet**. Right side: ⋮ menu →
  *Generation settings (this chat)*, *System prompt (this chat)*, *Rename*, *Export*,
  *Duplicate*, *Delete*.
- **Message list** (`LazyColumn`, reverse layout, sticky to bottom while streaming unless the user
  scrolled up; a "↓ jump to latest" FAB appears when not at bottom):
  - User messages: right-aligned tonal bubble, plain text (no markdown), max width ~85%.
  - Assistant messages: **full-width, no bubble** (like ChatGPT), rendered Markdown: headings,
    lists, tables (horizontal scroll), inline code, fenced code blocks with a header bar
    (language label + *Copy* button) and syntax highlighting, LaTeX optional later.
  - Reasoning models: a collapsible **"Thinking…" / "Thought for 12 s"** chip above the answer,
    expanded while streaming, collapsed when done (PocketPal's ThinkingBubble/ReasoningBlock).
  - Footer under each assistant message (small, muted): `Copy · Regenerate · ⋯`, and a stats line
    toggleable in Settings: `42 tok · 18.3 tok/s · TTFT 410 ms` (PocketPal shows ms/token,
    tokens/sec, prompt tokens/sec, TTFT).
  - Long-press a user message → *Edit* (re-sends from there, truncating the tail), *Copy*.
  - Long-press an assistant message → *Copy*, *Select text*, *Regenerate*, *Regenerate with…*
    (pick another model).
  - Status markers: "Stopped", "Cut off — context full" with an action *Start new chat* /
    *Increase context* (PocketPal's context-full banner is a good pattern).
- **Composer** (bottom, IME-aware via `imePadding()`):
  - Multi-line text field (grows to ~6 lines, then scrolls), placeholder "Message".
  - Left of the field: a model chip (`▲ Gemma 3 4B`) → opens the model picker sheet (PocketPal's
    chevron/pal button, minus Pals).
  - Above/inside the field for reasoning models: a **Think** toggle chip.
  - Right: **Send** (enabled when text non-empty and a model is loaded or loadable) morphing into
    **Stop** (■) while generating.
  - Post-MVP: `+` attachment button for images when a vision projector is present.
- **Empty states** (centered illustration + one button):
  - No models downloaded → "Download a model to start" → *Browse models*.
  - Models downloaded, none loaded → "Load **{last used model}**" button + "Choose another".
  - Model loading → progress indicator with model name.
- **Model picker sheet** (modal bottom sheet): list of downloaded models (name, quant, size,
  "Loaded" badge), tap = load and switch; footer link *Manage models*.
- **Chat generation settings sheet** (from ⋮): sliders + numeric fields for temperature, top-p,
  top-k, min-p, repeat penalty, max tokens; *Reset to model defaults*; *Save as model default*.
  PocketPal's "Session vs Preset" distinction maps to "this chat" vs "model default".
- Keep screen awake while generating (PocketPal does this); haptic tick on completion (optional).

### 9.2 Models screen

- **Top bar**: title "Models", filter chips row: *Downloaded*, *Recommended*, (*All*); overflow ⋮
  with *Sort by (name/size/last used)*, *Storage usage*.
- **Sections**: "On this device" (ready / downloading / paused) and "Recommended for your phone"
  (curated catalog picked by RAM tier + SoC class — PocketPal's device-rules idea, but our own
  list; their rules repo has no license).
- **Model card** (outlined card):
  - Line 1: display name; line 2: `author/repo` (muted).
  - Chips: params (`3.9B`), quant (`Q4_0`), size (`2.37 GB`), context (`128K`), `Vision` badge.
  - Warnings row when relevant: "May not fit in memory", "Low storage", "Legacy quant".
  - Actions: *Download* (with size) → progress bar with %/speed/ETA + *Pause/Cancel*;
    when downloaded: primary **Load** / **Unload**, secondary ⚙ *Settings*, 🗑 *Delete*.
  - Expandable details: architecture, layers, context length, file path, HF link.
- **FAB** (`+`) expanding into: *Search Hugging Face*, *Import local file* (SAF picker for
  `.gguf`), *Add by URL* (paste an `hf.co/.../resolve/...gguf` link).
- **Hugging Face search** (full-screen sheet):
  - Search field with debounce; filters: author, sort (*Trending, Downloads, Likes, Recently
    updated*); results are `filter=gguf` models: name, author, ↓downloads, ♥likes, updated-ago,
    gated lock icon.
  - **Repo detail**: model-card summary link + list of GGUF files (grouped by quant, split files
    `-00001-of-0000N` collapsed into one entry), each with size, "fits/tight/won't fit" badge from
    the memory estimator, and *Download*. `mmproj-*.gguf` files shown as "Vision projector".
- **Model settings** (full screen from ⚙):
  - *Name* (editable display name)
  - *System prompt* (multi-line; default for chats with this model)
  - *Chat template*: "From model (GGUF)" / preset list / custom Jinja editor (monospace)
  - *Stop sequences* (chip input)
  - *Generation defaults*: temperature, top-k, top-p, min-p, repeat penalty + last-n, max tokens,
    seed; advanced (collapsed): presence/frequency penalty, mirostat, XTC, DRY, typical-p
  - *Load settings* (reload required, banner says so): context size (slider with fit estimate),
    CPU threads, batch/ubatch, backend (CPU / GPU-OpenCL when available) + GPU layers,
    flash attention (auto/on/off), KV cache type K/V, mmap, mlock.
  - *Reset to defaults*.

### 9.3 Settings screen (global)

- **Appearance**: theme (system/light/dark), dynamic color, font size of chat text.
- **Chat**: default system prompt, show generation stats, auto-title chats, keep screen on while
  generating, send on Enter (hardware keyboard).
- **Models & memory**: default model to auto-load on launch; auto-unload when app goes to
  background (PocketPal's "Auto offload/load"); show memory usage in chat.
- **Downloads**: Hugging Face token (stored encrypted; "Test token" button), Wi-Fi only,
  storage location info + total space used.
- **Data**: export all chats (JSON / Markdown zip), import chats, delete all chats.
- **Advanced**: default load parameters for new models, log level, "Copy debug info" (device
  SoC, CPU features dotprod/i8mm/SVE, GPU, Android version — useful for bug reports).

### 9.4 Benchmark screen

- Model selector (downloaded models), profile chips *Fast (pp128/tg32)*, *Default (pp512/tg128)*,
  *Custom* (pp, tg, repetitions) — PocketPal's defaults are `pp512 tg128 pl1 nr3`.
- *Run* button; warning "keep the screen open"; progress.
- Device card: brand/model, SoC, cores, RAM, CPU features (FP16, dotprod, i8mm, SVE), GPU
  (Adreno/Mali) and OpenCL availability.
- Result cards: PP t/s, TG t/s, total time, peak memory, plus the load settings used; *Share as
  text*; history list with delete. (Public leaderboard: out of scope.)

### 9.5 About

Version, llama.cpp build/commit, licenses (Apache-2.0 + third-party notices incl. MIT for
anything derived from PocketPal), links to repo and issue tracker.

## 10. MVP milestones

Each milestone ends with something runnable. Time estimates assume one experienced developer
part-time-ish; scale accordingly.

**M0 — Skeleton & toolchain (≈1 week)**
- Cargo workspace, `xtask build-android` (cargo-ndk → `arm64-v8a` only, plus `x86_64` for the
  emulator), UniFFI bindgen into `android/core-bindings`, Gradle app with Compose + Material 3.
- CI: `cargo test`, `clippy`, Android assemble; 16 KB page-size alignment check on `.so` files.
- Hello-world: Kotlin calls `lomio_version()` returning the llama.cpp build string.

**M1 — Engine spike on real hardware (≈1–2 weeks) — the go/no-go gate**
- `lomio-engine`: load a GGUF, stream tokens, cancel, unload; KV-prefix reuse.
- Desktop CLI runs the same code. On 2–3 phones (one Snapdragon 8 Gen 2/3/Elite, one Tensor,
  one mid-range Mali/MediaTek) measure pp/tg vs PocketPal on the same GGUF. Target: within ±10%
  of PocketPal on CPU. If not, fix build flags (arch variants, repack, OpenMP vs threadpool)
  before writing UI.

**M2 — Models (≈2 weeks)**
- HF search, repo file listing, resumable downloads with progress + background job +
  notification, sha256 verify, delete; local `.gguf` import via SAF; GGUF header parsing and
  memory-fit estimate; small curated "recommended" list.

**M3 — Chat (≈2–3 weeks)**
- Chat screen with streaming Markdown, code blocks with copy, stop, regenerate, edit-and-resend,
  thinking block; chat templates via minijinja with GGUF template; conversation history in SQLite
  with drawer, rename, pin, delete; per-model + per-chat system prompt and sampling settings.

**M4 — Settings, benchmark, polish (≈2 weeks)**
- Global settings, model settings screen, benchmark screen, export/import, auto-unload in
  background, memory/OOM guards (refuse to load what clearly won't fit), TalkBack pass, dark
  mode, tablet layout.

**M5 — Release 0.1 (≈1 week)**
- F-Droid-friendly build (reproducible, no proprietary deps), GitHub Releases APK, Play internal
  testing; crash reporting opt-in only (or none).

**Post-MVP candidates (in priority order)**
1. OpenCL/Adreno GPU backend as an opt-in (Q4_0 models), with automatic fallback.
2. Vision (mmproj via libmtmd): image attach in composer.
3. Message branching UI (data model already has `parent_id`).
4. iOS app (SwiftUI on the same `lomio-ffi`, Metal backend).
5. Desktop (Compose Desktop or a Tauri shell) reusing `lomio-core`.
6. Optional extras explicitly *not* in MVP: voice (whisper.cpp), TTS, tools/web search, remote
   OpenAI-compatible servers, personas.
## 11. Sources

PocketPal
- https://github.com/a-ghorbani/pocketpal-ai (source read at commit `34f561b`, v1.22.1; README, `App.tsx`, `src/database/schema.ts`, `src/utils/types.ts`, `src/utils/completionSettingsVersions.ts`, `src/utils/contextInitParamsVersions.ts`, `src/api/hf.ts`, `src/store/bundledDeviceRules/rules.android.json`, `src/locales/en.json`)
- https://github.com/a-ghorbani/pocketpal-ai/releases · issues #55, #89, #137, #138, #143, #147, #183, #259, #304, #306, #359, #371, #450, #464, #475, #488, #512, #603, #610, #658, #661, #677, #735, #777, #796, #841, #848, #854, #864, #874, #882, #950, #952, #954, #960, #964, #974, #1013, #1035, #1040, #1044
- https://github.com/a-ghorbani/pocketpal-device-rules (no license file) · https://pocketpal.dev/leaderboard · https://huggingface.co/spaces/a-ghorbani/ai-phone-leaderboard
- Competitor sentiment: https://reddit.sentinel-team.org/posts/1rl7n24/snapshots/2026-03-05T08%3A52%3A33.635831Z · https://reddit.sentinel-team.org/posts/1sp06ky/snapshots/2026-04-25T00%3A46%3A56.728262Z · https://reddit.sentinel-team.org/posts/1tdvqpw/snapshots/2026-05-15T23%3A40%3A01.25497Z · https://reddit.sentinel-team.org/posts/1sglu08/snapshots/2026-04-10T16%3A31%3A22.888624Z · https://www.promptquorum.com/power-local-llm/best-local-llm-apps-android-2026 · https://discuss.privacyguides.net/t/update-on-ai-chat-tools/29496 · https://alternativeto.net/software/pocketpal/about

Engines
- https://github.com/utilityai/llama-cpp-rs (read at `0364f5d`; issues #852, #956; PRs #1046, #1084, #1133, #1139, #1140) · https://crates.io/crates/llama-cpp-2
- https://github.com/ggml-org/llama.cpp/releases · https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md · https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md#arm-kleidiai · https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md · https://github.com/ggml-org/llama.cpp/tree/master/docs/backend/snapdragon · https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android · https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md
- https://github.com/ggml-org/llama.cpp/discussions/9464 · https://github.com/ggml-org/llama.cpp/discussions/17456 · https://github.com/ggml-org/llama.cpp/issues/16217 · https://github.com/ggml-org/llama.cpp/discussions/23736
- https://sources.debian.org/src/llama.cpp/7965%2Bdfsg-1/common/jinja/README.md (llama.cpp native Jinja engine, PR #18462)
- https://github.com/mybigday/llama.rn (android CMakeLists, RNLlama.java, scripts/build-opencl.sh)
- https://huggingface.co/datasets/vovaxa44/snapdragon778g-llama-cpp-opencl-bench · https://huggingface.co/bpmatt/Qwen3.8-2B-Distill-Q4_0-ARM-GGUF · https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/performance_llama_cpp_sme2/run_llm/ · https://github.com/JackZeng0208/llama.cpp-android-tutorial
- https://github.com/google-ai-edge/LiteRT-LM · https://developers.google.com/edge/litert-lm/overview · https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference
- https://github.com/EricLBuehler/mistral.rs (issue #1934) · https://github.com/huggingface/candle (issue #2823) · https://github.com/tracel-ai/burn · https://github.com/mlc-ai/mlc-llm · https://github.com/pytorch/executorch · https://github.com/microsoft/onnxruntime-genai · https://github.com/alibaba/MNN · https://arxiv.org/pdf/2506.10443
- https://huggingface.co/models?library=gguf

UI
- https://github.com/mozilla/uniffi-rs · https://mozilla.github.io/uniffi-rs/latest/futures.html · https://github.com/matrix-org/matrix-rust-sdk · https://github.com/element-hq/element-x-android · https://github.com/element-hq/element-x-ios · https://github.com/bitwarden/sdk-internal
- https://github.com/JetBrains/compose-multiplatform/releases · https://github.com/gobley/gobley
- https://github.com/mikepenz/multiplatform-markdown-renderer · https://github.com/halilozercan/compose-richtext · https://github.com/noties/Markwon · https://github.com/SnipMeDev/Highlights
- https://slint.dev/blog/slint-1.18-released · https://github.com/slint-ui/slint/issues/9240 · https://github.com/slint-ui/slint/issues/14291 · https://dioxuslabs.com/blog/release-070/ · https://github.com/DioxusLabs/blitz/issues/1086 · https://github.com/DioxusLabs/dioxus/issues/5786 · https://github.com/emilk/egui/issues/5590 · https://github.com/rust-windowing/winit/pull/4451 · https://github.com/iced-rs/iced/issues/302 · https://github.com/makepad/makepad/issues/196 · https://github.com/project-robius/robrix · https://github.com/marc2332/freya/releases/tag/v0.5.0
- https://github.com/tauri-apps/tauri/releases · https://github.com/tauri-apps/tauri/issues/7868 · https://github.com/tauri-apps/tauri/issues/10631 · https://github.com/LettuceAI/app · https://github.com/vercel/streamdown
- https://pub.dev/packages/flutter_rust_bridge · https://pub.dev/packages/gpt_markdown · https://pub.dev/packages/flutter_markdown
- https://github.com/jhugman/uniffi-bindgen-react-native · https://github.com/Vali-98/ChatterUI · https://github.com/Mobile-Artificial-Intelligence/maid
- https://github.com/shubham0204/SmolChat-Android · https://github.com/ferranpons/Llamatik · https://github.com/google-ai-edge/gallery

Android platform
- https://developer.android.com/develop/background-work/services/fg-service-timeout (dataSync 6 h/24 h limit, Android 15+)
- https://developer.android.com/about/versions/15/behavior-changes-15
- 16 KB page size requirement: https://github.com/expo/fyi/blob/main/android-16kb-page-sizes.md · https://www.freecodecamp.org/news/google-16-kb-page-size-requirement-what-to-do/

Crates (versions from crates.io API, 2026-11-07): uniffi, tokio, reqwest, hf-hub, minijinja, minijinja-contrib, rusqlite, rusqlite_migration, refinery, pulldown-cmark, gguf-rs-lib, cargo-ndk.
