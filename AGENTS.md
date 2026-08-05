# AGENTS.md — pixnpu

## Project Overview

`pixnpu` is an Android app (Kotlin + Jetpack Compose) that runs quantized LLMs
(`.litertlm` files) fully on-device using [LiteRT-LM](https://ai.google.dev/edge/litert)
on Google Tensor SoC accelerators (NPU, GPU, CPU fallback).

Think "Gemini app, but 100% local."

### Architecture

```
ui/                     Compose UI (InferenceScreen, ModelSelectorScreen, MainScreen)
  components/           RuntimeStatusBar, CodeHighlight, ParameterSheet
  theme/                Material You (Theme.kt — dynamic colors)
engine/                 LiteRTLMEngine.kt  — native engine wrapper, NPU-first
model/                  ModelManager.kt    — resumable segmented downloads + SAF import
                        DownloadState.kt   — state machine for download/import
server/                 OpenAiApiServer.kt — OpenAI-compatible HTTP API (Ktor/CIO)
                        ChatCompletionsProcessor.kt — OpenAI request → engine mapping
                        OpenAiModels.kt    — OpenAI JSON DTOs
util/                   Fmt.kt             — human-readable byte/sha/speed formatting
```

### Device target
- Pixel 10 Pro (`56061FDCH008CK`) — Tensor G5, NPU with G5 compiler
- 1080×2410 at 420 dpi; nav bar = 126 px; IME top ≈ y=1656

### Toolchain

| Item           | Version          |
|----------------|------------------|
| AGP            | 8.13.2           |
| Kotlin         | 2.3.21           |
| Compose BOM    | 2025.10.00       |
| Material3      | 1.5.0-alpha14    |
| compileSdk     | 36, minSdk 34    |
| coroutines     | **1.9.0** ⚠     |
| Ktor (server)  | 2.3.13           |
| kotlinx-serialization | 1.7.3     |
| LiteRT-LM      | 0.15.0           |
| OkHttp         | 4.12.0           |
| Coil           | 2.7.0            |

---

## ⚠️ Critical: kotlinx-coroutines 1.9.0 ↔ LiteRT-LM 0.15.0 Incompatibility

`sendMessageAsync` (the async streaming API) **crashes the process** on this build
with an uncatchable `NoSuchMethodError`:

```
java.lang.NoSuchMethodError: No static method close$default(
  Lkotlinx/coroutines/channels/SendChannel;Ljava/lang/Throwable;ILjava/lang/Object;)Z
```

**Root cause:** LiteRT-LM's JNI callback thread invokes
`SendChannel.close$default`, a method whose binary signature changed between
coroutines versions. The app ships coroutines 1.9.0 (pinned — see build config),
but the LiteRT-LM native library was compiled against a different coroutines ABI.

**Why try/catch doesn't help:** the error is thrown on a native JNI callback thread
(`Thread-15`), not on the Kotlin coroutine that called `collect`. By the time the
exception surfaces, the process is already being killed.

**Workaround in code:** `LiteRTLMEngine.generate()` does **not** call
`sendMessageAsync`. It uses the synchronous `conversation.sendMessage(contents)`
on `Dispatchers.Default`, then emits the reply in word-sized chunks with an 8 ms
delay to simulate token streaming for the UI.

**Do NOT "fix" by bumping coroutines.** Version 1.9.0 is intentionally pinned.
Bumping to 1.10+ may resolve the `SendChannel` ABI mismatch, but it has not been
tested and may break other behavior. Document any change here if you do.

---

## Build commands

```bash
./gradlew :app:assembleDebug       # release-style: use assembleRelease
./gradlew :app:lintDebug           # 0 errors required on CI
./gradlew :app:connectedDebugAndroidTest
```

## Deploy + test

```bash
adb -s 56061FDCH008CK install -r app/build/outputs/apk/debug/app-debug.apk
```

### Useful on-device inspection

```bash
# List installed models
adb -s 56061FDCH008CK shell run-as com.pixnpu ls -la files/models/

# Push a model for import testing (from host file)
adb -s 56061FDCH008CK push /tmp/test.litertlm /sdcard/Download/

# Watch engine activity
adb -s 56061FDCH008CK logcat | grep -E "litert|com.pixnpu"
```

### OpenAI-compatible API
- Ktor/CIO server in `server/`, bound to **loopback (`127.0.0.1`) by default**. The bind
  host + port are configurable in the **Settings tab** (persisted in SharedPreferences);
  binding to `0.0.0.0` exposes the server on the network — the UI shows a warning
  (no auth yet). `OpenAiApiServer.start(host, port)`, port clamped 1024..65535;
  changing host/port while running stops the server.
- Endpoints: `GET /health`, `GET /v1/models`, `POST /v1/chat/completions` (JSON or SSE
  `text/event-stream` with `data: [DONE]` terminator). Everything else → OpenAI-style 404.
- **Stateless per request**: the full `messages` array is flattened into one role-prefixed
  prompt (`System:`/`User:`/`Assistant:`) and generated with `trackHistory=false`, so API
  calls never read from or write to the app chat. `temperature`/`max_tokens` map to
  `paramsOverride` in `engine.generate(...)`.
- Content parts: `text`, `image_url` (data: URI or file:// only), `input_audio` (base64,
  must be WAV — miniaudio constraint, same as the app UI).
- One generation at a time (shared engine with the UI): busy → HTTP 429 `code:"busy"`.
- Toggle: **API Server switch in the Settings tab** (needs a loaded model). Server dies
  with the process — no foreground service. Follow-ups: Bearer auth, https image URLs.
- Tests: `ChatCompletionsProcessorTest` (mapping/validation) + `OpenAiApiServerTest`
  (route tests via ktor `testApplication`). **Test JVM must be Java 21+**: LiteRT-LM 0.15.0
  ships Java 21 bytecode (class file 65) — `tasks.withType<Test> { javaLauncher }` in
  app/build.gradle.kts pins it.

## Known limitations

1. **No true native streaming** — works around the coroutines ABI crash with a
   simulated word-by-word emission (8 ms per chunk).
2. **functiongemma-270m-G5.litertlm warmup fails on NPU** on Pixel 10 Pro (this
   build) — `No dispatch library found in .../lib/arm64`. `gemma3-270m-it-q8`
   works on NPU. The load-time warmup in `LiteRTLMEngine` is **non-fatal**: it
   logs `Warmup failed on NPU (continuing without warmup)` and the model stays
   loaded; the first real prompt then fails through the normal generate() path.
   See logcat line references in the design summary.
3. **Single in-flight download/import** — `ModelManager` gates on one
   `operationJob`; pause/cancel reset to Idle.
4. **No segmented retry on NPU dispatch failure** — NPU registration is
   all-or-nothing per model/backend.

## Improvements Implemented

### Stability
- **Thread-safe engine state**: All state access in `LiteRTLMEngine` is now protected by mutex
- **Circuit breakers**: Added for download and import operations to prevent repeated failures
- **Input validation**: Added validation for prompts, URLs, SHA-256 hashes, and file sizes
- **Resource leak prevention**: Bounded conversation history (50 turns) and message history (200 messages)

### Maintainability
- **Interfaces**: Extracted `ModelManagerInterface` and `LiteRTLMEngineInterface` for testability
- **Dependency Injection**: Added `AppContainer` for DI, used in `MainViewModel`
- **Documented constants**: Magic numbers replaced with named constants (maxPromptLength, maxUrlLength, etc.)

### Testing
- **Unit tests**: Added tests for `CircuitBreaker`, input validation, and existing utility functions
- **Test coverage**: Core logic now has test coverage

### Code Quality
- **Error handling**: Consistent error handling with proper error messages
- **Null safety**: Improved null checks and validation
- **Separation of concerns**: Better separation between components

### google-ai-edge/gallery practices (applied)
- **Load-time warmup**: `LiteRTLMEngine.warmup()` runs one short inference
  (`"Hi"`, `maxOutputToken = 1`) right after `initializeBackend()` so the
  one-time NPU dispatch/kernel-compilation latency is absorbed at load, not at
  the first user prompt. Non-fatal on failure (model stays loaded). Duration
  surfaced as `warmupMs` in `InferenceMetrics` and the status bar. Mirrors
  gallery's `generativeModel.warmup()` after model init.
- **NPU uses no SamplerConfig**: On NPU, conversations are created with
  `samplerConfig = null` (model defaults), exactly like gallery's
  `LlmChatModelHelper` (`if (preferredBackend is Backend.NPU) null else
  SamplerConfig(...)`). Custom temp/topK/topP are only applied on GPU/CPU.
- **AutoCloseable `use {}`**: warmup conversation is closed via `use {}`.
- **Graceful init failures**: backend/warmup errors are logged and handled
  without crashing the process.

### Audio (voice notes)
- Mic button in the composer records a voice note via `AudioRecord` as raw
  16-bit PCM mono at 16 kHz (`AudioRecorder`), capped at 30 s. Audio files
  picked from storage are decoded to the same format via MediaExtractor +
  MediaCodec (`AudioFile.kt`, downmix + linear resample, 5 min cap).
- **`Content.AudioBytes` must be WAV-wrapped** (`pcm16ToWav` in `AudioFile.kt`,
  applied at send time in `MainViewModel.send()`): LiteRT-LM's native audio
  preprocessor decodes with miniaudio's `ma_decoder_init_memory`, which sniffs
  for a container header. Raw PCM (or OGG — vorbis not compiled in) fails with
  `Failed to initialize miniaudio decoder, error code: -10`. WAV's decoder is
  always built in.
- `EngineConfig.audioBackend = Backend.CPU()` on load (gallery practice —
  audio modules must run on CPU). Models without audio support automatically
  fall back to loading without an audio backend.
- Requires `RECORD_AUDIO` permission (runtime-requested on first mic tap);
  models without audio capabilities will error through the normal generate()
  path.

## Conventions

- Use `MutableStateFlow`/`StateFlow` for all async state — never `LiveData`.
- Engine calls (`load`/`unload`/`initializeBackend`) must run on `Dispatchers.IO`
  (already wrapped in `withContext` in MainViewModel).
- Never call native LiteRT-LM methods on the main thread.
- `.litertlm` files must preserve raw bytes (`noCompress` is set in build.gradle.kts).
- Compose: Material 3 expressive, `RoundedCornerShape(28.dp)` for input/composer,
  `surfaceContainerHigh` for surfaces, dynamic color tokens only (no custom palette).
