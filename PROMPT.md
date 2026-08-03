# OBJECTIVE
Build a lightweight, highly efficient Android application (Kotlin + Jetpack Compose) inspired by `llama.cpp`. The application must execute local Large Language Models in the `.litertlm` format using Google's LiteRT-LM SDK, with direct hardware acceleration offloaded to the Google Tensor NPU (TPU).

---

# TECH STACK & DEPENDENCIES
- **Language**: Kotlin 2.x
- **UI Framework**: Jetpack Compose + Material 3
- **Async Runtime**: Kotlin Coroutines + `StateFlow` / `SharedFlow`
- **Networking**: OkHttp 4.x / WorkManager (for resumable model downloads)
- **Engine**: `com.google.ai.edge.litertlm:litert-lm` (LiteRT GenAI SDK)
- **Target OS**: Android 14+ (API 34 to API 37)

---

# CORE ARCHITECTURE & COMPONENTS

### 1. Model Storage & Download Manager (`ModelManager.kt`)
- Provide a manager that downloads `.litertlm` files from remote HTTP/HTTPS URLs (e.g., Hugging Face) directly into `context.filesDir` or `context.getExternalFilesDir(null)`.
- **Requirements**:
  - Store models strictly in app-specific storage to avoid needing runtime storage permissions.
  - Implement SHA-256 checksum verification before passing files to the engine. Corrupted downloads must be flagged and rejected to prevent native `SIGSEGV` crashes.
  - Expose POSIX absolute string paths (`File.absolutePath`) for direct `mmap()` execution by the native LiteRT engine.
  - Support download pause, resume, and progress tracking via `StateFlow<DownloadState>`.

### 2. Native Inference Engine Wrapper (`LiteRTLMEngine.kt`)
- Wrap the LiteRT-LM runtime API into a thread-safe Kotlin service.
- **Engine Configuration**:
  - Configure `EngineConfig` with `modelPath` set to the verified `.litertlm` local path.
  - Explicitly set `backend = Backend.NPU()` to leverage Google Tensor TPU hardware delegates. Include a fallback switch to `Backend.GPU()` / `Backend.CPU()` if initialization fails.
- **Inference & Sampling Parameters**:
  - Allow runtime tuning of generation parameters:
    - `temperature` (Float, default: 0.7)
    - `top_k` (Int, default: 40)
    - `top_p` (Float, default: 0.95)
    - `max_tokens` (Int, default: 1024)
- **Streaming Execution**:
  - Expose a `generateStream(prompt: String): Flow<String>` function that emits generated tokens in real-time as they are produced by the engine.
  - Track and calculate real-time performance metrics: **Tokens Per Second (t/s)** and **Time-To-First-Token (TTFT)**.

### 3. User Interface (Jetpack Compose)
- Create a terminal-inspired / developer-focused UI reminiscent of `llama.cpp`:
  - **Model Selector Screen**: List locally available models, verify checksums, display file sizes, and provide a download dialog for new `.litertlm` URLs.
  - **Inference Screen**:
    - Interactive prompt input bar with a "Cancel / Stop Generation" action.
    - Streaming response container with syntax-highlighted code block rendering.
    - **Status Bar Overlay**: Show active hardware delegate (`NPU` vs `CPU`/`GPU`), context window usage, memory footprint, and current `t/s` speed.
  - **Parameter Tuning Drawer**: Bottom sheet to tweak Temperature, Top-K, Top-P, and system prompts on the fly.

---

# CRITICAL CONSTRAINTS & BEST PRACTICES
1. **Memory Management**: Do NOT load model files into memory byte arrays in Kotlin/Java. Always pass raw string file paths to native binaries so LiteRT can `mmap` weights directly from UFS storage.
2. **Threading**: Never invoke `engine.initialize()` or `engine.generate()` on `Dispatchers.Main`. Always wrap native calls in `withContext(Dispatchers.IO)`.
3. **Resource Cleanup**: Properly call `engine.close()` or `release()` inside lifecycle events (`ViewModel.onCleared()`) to prevent GPU/NPU memory leaks and native process crashes.
4. **Prompt Templates**: Include predefined chat templates (ChatML, Gemma, Llama-3 formatting) to correctly wrap raw user inputs before feeding them to the tokenizer.

---

# IMPLEMENTATION STEPS
1. Scaffold the Gradle project and configure the required LiteRT dependencies and packaging options (`noCompress += "litertlm"`).
2. Build `ModelManager.kt` with download, hash verification, and file path resolution logic.
3. Build `LiteRTLMEngine.kt` handling native initialization, parameter binding, token streaming, and metrics collection.
4. Create the `MainViewModel.kt` to bind download state, engine lifecycle, and chat streams.
5. Implement the Jetpack Compose UI with model selection, parameter drawer, terminal view, and streaming text support.
