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
| coroutines     | **1.11.0** ⚠     |
| Ktor (server)  | 2.3.13           |
| kotlinx-serialization | 1.7.3     |
| LiteRT-LM      | 0.15.0           |
| OkHttp         | 4.12.0           |
| Coil           | 2.7.0            |

---

## ⚠️ Critical: coroutines version ↔ LiteRT-LM 0.15.0 ABI (RESOLVED in 1.11.0)

`sendMessageAsync` (the async streaming API) **crashed the process** on this build
with an uncatchable `NoSuchMethodError`:

```
java.lang.NoSuchMethodError: No static method close$default(
  Lkotlinx/coroutines/channels/SendChannel;Ljava/lang/Throwable;ILjava/lang/Object;)Z
```

**Root cause (verified against the AAR):** LiteRT-LM 0.15.0's
`Conversation$sendMessageAsync$1$1` bytecode calls
`SendChannel.close$default(...)` as a static method **on the `SendChannel`
interface itself**. That static only exists on the interface in coroutines
**1.11.0** (verified with `javap`: 1.6.4/1.9.0/1.10.2 put it on
`SendChannel$DefaultImpls` instead, and a JVM `invokestatic` on the interface
does not resolve to `DefaultImpls`). With coroutines < 1.11.0 the resolution
failed on the JNI callback thread and killed the process — try/catch on the
collecting coroutine never saw it.

**Fix:** coroutines is pinned to **1.11.0** (see build config). Native streaming
in `LiteRTLMEngine.generateInternal` uses `conversation.sendMessageAsync(...)`
and emits real per-token deltas (`Content.Text`). A synchronous
`sendMessage` fallback (word-sized chunks + 8 ms delay) remains in a catch
block — do not remove it without on-device verification.

**Why try/catch doesn't help (historical):** with the old 1.9.0 pin the error
was thrown on a native JNI callback thread (`Thread-15`), not on the Kotlin
coroutine that called `collect` — by the time it surfaced the process was dead.

**Do NOT downgrade coroutines below 1.11.0.** Anything lower re-introduces the
crash. If a future LiteRT-LM bump changes the AAR, re-verify with `javap` before
trusting a new version.

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
  binding to `0.0.0.0` exposes the server on the network — the UI shows a warning.
  `OpenAiApiServer.start(host, port, tokenProvider)`, port clamped 1024..65535;
  changing host/port/token while running stops the server.
- **Bearer auth (optional)**: an **API Token** set in Settings requires
  `Authorization: Bearer <token>` on `/v1/*` (wrong/missing → 401
  `{"error":{...,"code":"invalid_api_key"}}`); blank token = open server.
  `/health` and `/` stay unauthenticated. `tokenProvider` is read per request,
  so the token can change without restarting.
- Endpoints: `GET /health`, `GET /v1/models`, `POST /v1/chat/completions` (JSON or SSE
  `text/event-stream` with `data: [DONE]` terminator, `Cache-Control: no-cache`).
  **The chat handler is mounted at BOTH `/v1/chat/completions` and `/chat/completions`**:
  Pi's openai-completions provider sets `baseURL` to the raw server URL (no `/v1`),
  so the SDK POSTs to `/chat/completions` — with only the `/v1` mount that was a
  body-less 404. Everything else → OpenAI-style 404.
- **Stateless per request**: the full `messages` array is flattened into one role-prefixed
  prompt (`System:`/`User:`/`Assistant:`) and generated with `trackHistory=false`, so API
  calls never read from or write to the app chat. `temperature`, `top_p`,
  `max_tokens`/`max_completion_tokens` map to `paramsOverride` in `engine.generate(...)`.
  `n` is accepted only as 1 (else 400, `param:"n"`); unknown params are ignored
  (vLLM/llama.cpp convention); `max_tokens` + `max_completion_tokens` together → 400.
- **Thinking (Pi sends `thinking_budget_tokens` + `chat_template_kwargs`, NOT
  `reasoning_effort`)**: a non-negative `thinking_budget_tokens` enables thinking with
  that budget; `chat_template_kwargs.enable_thinking == "false"` forces it off (Pi's
  "thinking off" level); negative budget → 400. Maps to `GenerationParams.thinking*` →
  `ThinkingConfig(enableThinking, thinkingTokenBudget)` passed to
  `sendMessageAsync`/`sendMessage` (verified in the 0.15.0 AAR). Reasoning text arrives
  in `Message.channels["thought"]` (gallery's `LlmChatModelHelper` reads the same key);
  it is **counted** (engine `metrics.thinkingTokens`, chars/4 estimate) but never emitted
  as reply text, and history stripping (`stripReasoning`) keeps prior turns clean.
  `usage.completion_tokens_details.reasoning_tokens` reports the count (OpenAI/Gemini
  style).
- Streaming: real per-token deltas from `sendMessageAsync` (coroutines 1.11 ABI
  fix — see the critical section above). `stream_options.include_usage` still
  emits a final usage chunk with empty `choices` before `[DONE]`; usage is
  estimated from character counts (engine has no tokenizer).
- Content parts: `text`, `image_url` (data: URI or file:// only), `input_audio` (base64,
  must be WAV — miniaudio constraint, same as the app UI).
- One generation at a time (shared engine with the UI): busy → HTTP 429 `code:"busy"`.
  Unknown model id → **404** `code:"model_not_found"` (matches OpenAI, not 400).
- Toggle: **API Server switch in the Settings tab**. The server (and any UI
  generation) keeps running when the app loses focus or the screen turns off:
  `PixNpuForegroundService` (dataSync FGS) holds a partial wake lock and is
  reference-counted by MainViewModel (generation + server are the two clients).
  Follow-ups: https image URLs.
- **Branding**: the API advertises itself as llama.cpp — `GET /` returns
  `ServiceInfo` with `service: "llama.cpp"`, `impl: "pixnpu"` and `mode`
  (`"router"`/`"single-model"`); `/health` is llama.cpp-style
  `{"status":"ok"}` / `{"status":"error","slots_idle":0}`; `/v1/models` entries
  have `owned_by: "llamacpp"`. `/props` reports the loaded model's Jinja
  `chat_template` + `bos_token`/`eos_token` (family detected from the model id
  via `server/ChatTemplates.kt`; unknown models → null — LiteRT-LM formats
  internally, so templates are informational for clients).
- **llama.cpp server-compatible API** (`server/LlamaApi.kt` + `LlamaModels.kt`, mounted
  in the same Ktor module, same optional Bearer auth, same shared busy gate):
  - `POST /completion` — raw prompt (string only; token-id arrays → 400, no tokenizer),
    `stream` (SSE frames `data: {...}` with `"stop":false`/`"stop":true` — **no
    `[DONE]`**, faithful to llama.cpp), `n_predict` (`-1` = engine default; `< -1` → 400),
    `temperature`/`top_k`/`top_p` → `paramsOverride` (`top_k: 0` = engine default),
    `echo` prepends the prompt, `slot_id` must be -1 or 0. `min_p`/`repeat_penalty`/
    `stop`/`seed`/`n_keep`/`cache_prompt`/`samplers` accepted and ignored (engine has no
    equivalents). Response echoes `generation_settings` + `timings` (chars/4 estimates);
    `truncated` = predicted_n >= n_predict. Busy → **503 `{"error":"Slot busy"}`**;
    no model → 503.
  - `GET /props` — `default_generation_settings` (engine defaults, `n_ctx` from metrics),
    `total_slots: 1`, `model_path`, `chat_template` + `bos_token`/`eos_token` from
    `ChatTemplates` (null for unknown families), `modalities` (vision/video/audio from
    engine metrics), `is_sleeping: false` (mobile engine never sleeps). **Router mode**:
    no `?model=` → `role: "router"`, `model_alias: "llama-server"`, `model_path: "none"`
    when nothing is loaded, `max_instances: 1`, `models_autoload: true`; `?model=<id>` →
    **llama.cpp status semantics** (Pi's extension polls this for load state): 404 unknown
    id, **503 `{"error":{"code":503,"message":"model is loading"}}`** while that model is
    being loaded (via `loadingModelIdProvider`, wired to MainViewModel's
    `_isLoadingModel`), **400 `{"error":{"code":400,"message":"model is not loaded"}}`**
    for any installed-but-not-loaded model, 200 + props only when resident.
    **Single-model mode honors `?model=` the same way** (400 not loaded / 503
    loading / 404 unknown) — without that, Pi's `SingleModel` polling would take
    the loaded model's 200 and think a non-resident model is loaded.
  - `GET /slots` — one slot, `state` "idle"/"processing" from `metrics.status`.
- **Model listing + management are UNCONDITIONAL (both modes)** — `GET /v1/models`
  and `GET /models` always list every installed model (never 404, never empty
  while models exist): Pi's `detectServerMode` reads `data[0]` and its provider
  registration crashes on an empty/404 list into a bogus **"Llama.cpp
  unreachable at <url>"** + empty registry (**"Cannot find model X in pi
  registry"**). `POST /models/load`/`/models/unload` likewise work in both modes
  (Pi's single-model UI taps Load/Unload on listed models). The **Settings
  "Router mode" toggle** therefore only controls on-demand chat loads:
  `POST /v1/chat/completions` (and `/chat/completions`) with an unloaded model
  id auto-loads it (via `MainViewModel.loadModelForApi`, which reuses the UI
  load path so guards/status/chat-clear stay consistent); the busy gate is held
  across load + generation; single-model mode 404s requests for a non-resident
  model.
- **`status.args` MUST be a string array** (`["--ctx-size", "8192", ...]`, llama.cpp
  format; engine default context). pi-llama-cpp's `RouterModel.extractFrom` calls
  `args.indexOf("--ctx-size")` while a model is unloaded — a JSON object here
  throws inside `registerProvider`, which is caught as a server failure and
  surfaces as the bogus "Llama.cpp unreachable" + "Cannot find model in pi
  registry" pair. `aliases` = `[bareId, filename]` (llama.cpp path-derived
  convention; Pi displays `aliases[0]`).
- **`GET /v1/models` and `GET /models`** list every installed model with
  `status: {"value": "loaded"|"unloaded", "failed": bool, "args": [...]}`
  (**llama.cpp contract — NOT "not loaded"**: Pi's `/llama` UI
  shows `Warning: X is not loaded` and hides the "load model" action for any
  other value; the pi-llama-cpp extension's statusMapper likewise expects
  "unloaded"), `owned_by: "llamacpp"` and **`meta.n_ctx` only on the loaded
  model** (Pi reads it for the context window; absent = client fallback).
  `architecture.input_modalities` (text/image/video/audio from engine metrics)
  is reported **only on the loaded model** — clients (Pi, extensions) use it to
  decide whether images can be sent.
  - **`POST /models/load` is ASYNC — responds `{"success": true}` immediately**
    and loads in the background (llama.cpp contract): Pi's `loadAndWait` POSTs
    then polls `GET /models` every 250 ms for status `"loaded"`, so a
    synchronous load deadlocked that poll and its failures surfaced as
    **503 `model_load_failed` → "llama.cpp returned HTTP 503" in the Pi UI**.
    Loads do **not** hold the busy gate (a background load can't block chat);
    a load while another model is loading → **503 `Slot busy`**. Load failures
    are reported via **`status.failed: true`** on `GET /models` / `GET
    /v1/models` (wired from MainViewModel's `_modelLoadFailure`) so Pi stops
    polling and shows the error. `POST /models/unload` stays synchronous
    (`{"success": true}`; unknown → 404; wrong state → 400; busy → 503).
  - `POST /tokenize` and `POST /detokenize` — **always 501**: LiteRT-LM 0.15.0 exposes
    only `Conversation.getTokenCount()` (count, no ids, no detokenizer); 501 beats
    fabricated token ids.
  - Model id/path synced from the UI via `OpenAiApiServer.setCurrentModel(id, path)`;
    `currentModelId` is read-only public. `start()`/`stop()` are synchronized (atomic
    lifecycle). Chat route maps engine `IllegalArgumentException` → 400; stream
    client-disconnect is a `CancellationException` (info log, rethrow — not a warning).
- Tests: `ChatCompletionsProcessorTest` (mapping/validation) + `OpenAiApiServerTest` +
  `LlamaApiTest` (llama.cpp routes via ktor `testApplication`). **Test JVM must be Java
  21+**: LiteRT-LM 0.15.0 ships Java 21 bytecode (class file 65) —
  `tasks.withType<Test> { javaLauncher }` in app/build.gradle.kts pins it.

## Known limitations

1. **Usage is estimated, not counted** — the engine has no tokenizer, so
   `usage` in OpenAI responses is derived from character counts. Streaming is
   now native (real per-token deltas via `sendMessageAsync` on coroutines
   1.11.0); a synchronous word-chunk fallback remains in a catch block until
   verified on-device.
2. **functiongemma-270m-G5.litertlm fails on NPU** on Pixel 10 Pro (this
   build) — `No dispatch library found in .../lib/arm64`. `gemma3-270m-it-q8`
   works on NPU. The load-time warmup in `LiteRTLMEngine` is **non-fatal**: it
   logs `Warmup failed on NPU (continuing without warmup)` and the model stays
   loaded; the first real prompt then fails through the normal generate() path,
   which now **degrades automatically**: `isBackendFailure(e)` (dispatch/
   backend/delegate/npu/litert keywords in the message chain; template errors
   are explicitly excluded) → `degradeToGpu()` reloads the engine on GPU and
   retries the generation once. `degradedBackend` (AtomicBoolean) then skips
   NPU on every subsequent load until an explicit UI reload resets it.
   See logcat line references in the design summary.
3. **Single in-flight download/import** — `ModelManager` gates on one
   `operationJob`; pause/cancel reset to Idle.
4. **NPU dispatch is a runtime concern, not just load-time** — we don't ship
   Google Tensor `dispatch_api_so`, so NPU init succeeds but every inference
   fails for some models; the degradation above absorbs that on the first
   failed prompt instead of crashing or erroring.
5. **No speculative decoding** — LiteRT-LM 0.15.0 has no enablement API
   (`Capabilities.hasSpeculativeDecodingSupport()` is report-only), so there is
   no SD toggle; only the API surfaces are documented. Verified via `javap` on
   the AAR: `ConversationConfig` has no draft-model field.

## Improvements Implemented

### Chat UI
- **Message queueing**: sending while a generation runs no longer drops the
  message — it is queued FIFO (cap 5) and runs when the current reply
  finishes (`MainViewModel.send` enqueues, `sendInternal` fires the next
  queued send from its `finally`). The payload (text + attachments) is
  captured at enqueue time and the pending chips clear immediately; the chat
  shows a "N messages queued" chip above the composer; the composer keeps the
  Send button (labeled "Queue message") next to Stop while generating; Stop
  also clears the queue (explicit halt intent). `queuedCount` StateFlow drives
  the chip. The API server is unaffected (busy = 429 stays).

### Chat UI
- **Markdown rendering on by default** (`Markdown.kt`): headings, bold/italic,
  inline code, links, bullet/ordered lists, blockquotes, horizontal rules,
  **GFM tables** (header + `---` separator with alignment colons, leading/
  trailing pipes optional, single-dash cells like `|:-:|`; column widths are
  proportional to the longest cell text) — rendered on top of the existing
  fenced-code highlighter (`CodeHighlight.kt`).
  **"Render Markdown" toggle in Settings** (prefs `markdown_enabled`, default
  true) falls back to plain text when off. `StreamingText` gained a
  `markdownEnabled` param; parser (`parseBlocks`/`parseInline`) is internal and
  unit-tested (`MarkdownParserTest`).
- **Tables: GFM alignment colons now honored** — `:---`/`---:`/`:---:` per-column
  separators map to left/center/right `TextAlign` (`MdBlock.Table.alignments`,
  `TableAlign` enum). Column widths are **fit-content**: measured with a
  `TextMeasurer` (remembered per table, header measured SemiBold). When the
  intrinsic total exceeds the bubble width the table switches to fixed-width
  columns inside `horizontalScroll` (header + body rows share one scroll state)
  with a thin always-visible scrollbar strip (thumb = viewport/content ratio,
  position from `ScrollState.maxValue`) — **foundation 1.9.3 has no Scrollbar
  composable** (moved to the KMP split artifacts), hence the hand-drawn bar with
  theme tokens. `Dp.value` is internal in 1.9.x — ratios come from
  `Dp / Dp → Float` and `ScrollState.maxValue`, never `.value`.
- **Chat list no longer yanks the viewport during generation**: auto-scroll is
  split into (a) new-message jumps (`messages.size`) and (b) bottom-pinning on
  stream growth only when the user is already at the bottom (`scrollToItem(
  lastIndex, scrollOffset = Int.MAX_VALUE)` anchors the growing message's bottom
  edge instead of its top).
- **Multi-turn history**: the engine streams `sendMessageAsync(fullPrompt)` where
  `fullPrompt` = cleaned history + new user content (returned by
  `buildConversationWithCleanedHistory`); the sync fallback uses the same prompt.

### Settings
- **Thinking / Reasoning toggle** (prefs `thinking_enabled`, `thinking_budget`,
  default off / unbounded): sets `GenerationParams.thinkingEnabled` +
  `thinkingTokenBudget`, applied through the normal params path (reconfig
  watcher → `createNewConversation` → `ThinkingConfig`, same code the API uses
  for `thinking_budget_tokens`). Budget chips: Auto (-1, unbounded), 64, 128,
  256, 512. Only meaningful on thinking-capable models (Gemma 3+).
- **Inference Backend selector** (prefs `backend_preference`, default
  `Auto`): `BackendPreference` enum (Auto / CPU / GPU / NPU) persisted in
  MainViewModel and passed to `engine.load(..., backendPreference)`. **Auto**
  keeps the NPU→GPU→CPU candidate order + automatic degradation on dispatch
  failures; a **pinned** backend loads exactly that backend and disables
  degradation (`degradeToGpu` returns false when the preference is not Auto),
  so failures surface instead of silently switching accelerators. Applies on
  the next model load — the settings screen says so, and a toast reminds the
  user when a model is already loaded.

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

### Video (frames + audio)
- Video attach in the composer is offered only when the loaded model supports
  vision AND audio (`InferenceMetrics.supportsVideo`, i.e. `Modality.Video`).
- `VideoFile.kt`: `readVideoClip` (name/duration via MediaMetadataRetriever,
  cheap) + `extractVideoFrames` (up to 8 evenly-spaced frames via
  `getScaledFrameAtTime(OPTION_CLOSEST_SYNC)`, ≤640px, JPEG to cache dir,
  1 GB source cap). At send time `MainViewModel.send()` feeds the model
  `Content.ImageFile(framePaths)` first, then `Content.AudioBytes(pcm16ToWav(
  decodeAudioFileToPcm(uri)))` — the same WAV-wrapped audio path as voice
  notes; frame-extraction failure aborts to an error toast, audio failure is
  non-fatal (text+frames still sent).
- A video replaces any other pending attachment (`setPendingVideo` clears
  image/audio/textFile) so a single turn stays under the engine's 10
  content-item cap.
- Video clips are shown as chips in `InputBar` and `MessageBubble`
  (`Fmt.duration` for mm:ss / h:mm:ss).

### App Log (Settings)
- `util/AppLog.kt`: ring buffer (2000 entries) of logcat for the app's own
  process, streamed via `logcat -v threadtime --pid=<pid>` on an IO dispatcher.
  Captures native LiteRT-LM/TFLite/miniaudio output, not just android.util.Log.
  Started lazily (`AppLog.start()`, idempotent) from the Settings screen.
- Settings shows a 320 dp scrolling `LazyColumn` with severity filter chips
  (All / Warnings+ / Errors — levels use numeric priority, alphabetical Char
  comparison is wrong) and a Clear button. Auto-scroll only while pinned at the
  bottom (`snapshotFlow` on the last visible index — same pattern as the chat
  list).
- `AppLog.parseLine` is internal and unit-tested (`AppLogTest`).
- **App Log rows are tap-to-copy**: tapping a log line copies its full logcat
  line (timestamp, priority, tag, message) to the clipboard
  (`LocalClipboardManager`) and shows a "Copied log line" toast; a **"Copy all"**
  button next to Clear copies every visible (filtered) entry as one text block
  (full date timestamps), also with a toast.

### Input bar key handling
- `BasicTextField` in `InputBar` uses `Modifier.onPreviewKeyEvent`: on KeyUp of
  backspace **or** shift+backspace (`Key.Backspace` / `Key.Delete` — compose-ui
  1.9 renamed the old `Key.Del`/`Key.ForwardDel`) with empty input, the pending
  attachment (image → audio → file → video) is removed instead of nothing
  happening.

### Background generation
- **`PixNpuForegroundService`** (dataSync FGS + partial wake lock) keeps the
  process alive and the CPU awake when the app loses focus or the screen turns
  off, so a running generation or the API server is not killed mid-reply.
  Reference-counted by MainViewModel (`backgroundClients`): started when a
  generation begins or the API server is turned on, stopped (service + wake
  lock released) when both are idle. Requires `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC` and `WAKE_LOCK` permissions; service declared
  with `android:foregroundServiceType="dataSync"`, `START_NOT_STICKY` (a
  restarted empty service would only hold a pointless wake lock).

### Reliability / hardening (audit remediation)
- **Cancellation-safe engine metrics**: in `LiteRTLMEngine.generateInternal` the
  `finally` uses non-suspending `mutex.tryLock()` — after cancellation every suspend
  call (incl. `mutex.withLock`) throws immediately, which previously left `_metrics`
  stuck at `Generating` and the API server's 429 busy-gate locked forever. A
  `CancellationException` rethrow sits before the sync-fallback catch, so "stop"
  never triggers the blocking `sendMessage` fallback; the fallback and
  `getTokenCount()` run on `Dispatchers.IO` (never Main).
- **Fallback builds a fresh conversation**: when native streaming fails, the sync
  `sendMessage` fallback rebuilds the conversation from scratch instead of
  reusing the one the failed async attempt already polluted — reusing it pushed
  the user message twice and gemma3's template died with "Conversation roles
  must alternate user/assistant/..." (user at odd index).
- **NPU dispatch degradation**: `generateInternal` is wrapped in a one-shot
  retry — `isBackendFailure(e)` (dispatch/backend/delegate/npu/litert in the
  message chain, template errors excluded) triggers `degradeToGpu()`: the
  engine reloads on GPU and the generation retries once. `degradedBackend`
  (AtomicBoolean) skips NPU on all subsequent loads until an explicit UI
  reload. Absorbs "No dispatch library found" failures at the first prompt
  instead of erroring every time (see Known limitations).
- **Async router loads**: `POST /models/load` answers 200 and loads in the
  background (llama.cpp contract — Pi polls `GET /models`), failures surface
  as `status.failed: true` via MainViewModel `_modelLoadFailure` (cleared on
  every new load attempt). Loads never hold the busy gate.
- **DI cleanup**: `AppContainer` no longer exposes separate `rawModelManager`/
  `rawEngine` instances (they were never wired to UI cancel — model verification
  checked `isCancelled()` on a *different* manager instance, so cancel never
  stopped a verify). Verification now uses the same `manager` the cancel button
  targets.
- **PFD leak fixed**: `ModelManager.import()` closes its `openAssetFileDescriptor`
  via `use {}`.
- **Server hardening**: `OpenAiApiServer.currentModelId` is now a read-only getter
  mutated via `setCurrentModel(id, path)`; `start()`/`stop()` are synchronized
  (no double-start race); base64 payloads (`input_audio`, `data:` URIs) capped at
  64 MiB *before* decoding; engine `IllegalArgumentException` → 400.
- **A11y**: bottom nav buttons are `Role.Tab` + `selectable` (icon `contentDescription
  = null` — the visible label is the name); message bubbles copy on tap or long-press
  (`onClickLabel`/`onLongClickLabel`) instead of an empty `onClick = {}` (foundation
  1.9.3 has **no** nullable-`onClick` combinedClickable overload — verified via javap);
  ModelCard status colors are theme tokens (no hardcoded RGB); recorder buttons are
  48 dp; `AudioRecorder.isRecording` is now a `StateFlow` collected with
  `collectAsStateWithLifecycle()`.
- **Perf**: `AppLog` list filter via `derivedStateOf` (not a per-recomposition
  filter of 2000 entries); `highlightCode` and table-column weights are
  `remember`ed; `@Preview`s added for `RuntimeStatusBar`, `StreamingText`,
  `MarkdownBody`.

## Conventions

- Use `MutableStateFlow`/`StateFlow` for all async state — never `LiveData`.
- Engine calls (`load`/`unload`/`initializeBackend`) must run on `Dispatchers.IO`
  (already wrapped in `withContext` in MainViewModel).
- Never call native LiteRT-LM methods on the main thread.
- `.litertlm` files must preserve raw bytes (`noCompress` is set in build.gradle.kts).
- Compose: Material 3 expressive, `RoundedCornerShape(28.dp)` for input/composer,
  `surfaceContainerHigh` for surfaces, dynamic color tokens only (no custom palette).
