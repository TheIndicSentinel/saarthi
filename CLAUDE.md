# Saarthi — Project Context

## What this product is
Saarthi is a **100% offline** Android AI assistant for Indian users, powered by Gemma 4/3n/3 models running on-device via Google AI Edge LiteRT-LM. Every interaction stays on-device — no cloud, no data leaving the phone.

## Project status
- [x] Stage 2: Building the MVP (shipping; iterating on stability and features)

## Type of work in this session
- [ ] New feature on existing codebase
- [ ] Bug fix / debugging
- [ ] Refactor / performance improvement

---

## Stack
- **Language:** Kotlin 2.3.21
- **UI:** Jetpack Compose (BOM 2024.09.00), Material3, Compose Navigation
- **DI:** Hilt 2.58 + KSP
- **Database:** Room 2.7.2 (memories, conversations, sessions, rag_chunks)
- **Preferences:** DataStore 1.1.1
- **On-device AI:** Google AI Edge LiteRT-LM (`litertlm-android` 0.11.0) — Gemma inference; GPU (OpenCL/Vulkan) with CPU fallback
- **RAG:** BM25 over Room-persisted chunks (`RagDocumentRepository` + `Bm25Retriever`); embedding/vector path is deprecated
- **Downloads:** WorkManager 2.9.1 + OkHttp 4.12.0 (Range-header resumable downloads for 2.5 GB+ models); downloads run as a foreground Service (`ModelDownloadService`), NOT WorkManager — WorkManager's 10-min limit kills large downloads
- **Monitoring:** Firebase Crashlytics + Analytics (google-services.json required to activate)
- **Logging:** Timber
- **Build:** AGP 8.7.3, convention plugins in `build-logic/`
- **CI/CD:** Not configured in this repo (no CI yaml found)
- **Testing:** JUnit 4, MockK, Turbine (Flow testing)

## Project structure
```
gemma4/
├── saarthi/               ← Android project root (the app)
│   ├── app/               ← Application module, navigation host
│   ├── feature/
│   │   ├── feature-onboarding/   ← Language selection + model download/init
│   │   └── feature-assistant/    ← Chat, RAG attachments, packs, TTS, voice
│   ├── core/
│   │   ├── core-inference/   ← LiteRT-LM engine, ModelDownloadManager, DeviceProfiler
│   │   ├── core-memory/      ← Room DB: memories, conversations, sessions, rag_chunks
│   │   ├── core-rag/         ← BM25 retriever + (deprecated) embedding vector store
│   │   ├── core-ui/          ← Cyber-Vedic design system, GlassmorphicCard, components
│   │   ├── core-i18n/        ← SupportedLanguage (11 langs), LanguageManager, personalities
│   │   └── core-common/      ← Result<T>, UseCase, FlowUseCase, Dispatchers
│   ├── build-logic/          ← Convention plugins (saarthi.android.feature, etc.)
│   └── gradle/libs.versions.toml
├── Modelfile              ← Ollama Modelfile (local dev reference)
├── pratham.onnx           ← ONNX model artifact (local)
└── GEMMA4_MOBILE_DEPLOYMENT_STRATEGY.md
```

## Key commands
```bash
# Run from: /Users/bytenomad./Documents/Projects/saarthi

# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run a specific module's tests
./gradlew :core:core-inference:test

# Lint
./gradlew lint

# Clean build
./gradlew clean assembleDebug
```

---

## What's in scope
**Current focus areas:**
- [ ] On-device inference stability (crash logs present in root: `fresh_crash_log.txt`, `native_crash_debug.txt`)
- [ ] Model download reliability via foreground service
- [ ] Pack system (Kisan, general assistant) — RAG + personality overlays

**Explicitly OUT of scope:**
- Cloud/server-side inference — this is and stays 100% offline
- SMS/READ_SMS features — removed, do not re-add
- Light mode — dark-only by design

---

## Architecture decisions

| Decision | Why | What we rejected |
|----------|-----|-----------------|
| LiteRT-LM over MediaPipe | Migrated in v1.0.19 — better Gemma support, official Google AI Edge runtime | MediaPipe Tasks (legacy) |
| Foreground Service for downloads | WorkManager 10-min limit kills 2.5 GB+ model downloads | WorkManager (kills large downloads) |
| BM25 RAG over vector embeddings | Fully offline, no embedding model needed, fast on low-end devices | SqliteVectorStore cosine path (deprecated, still in code) |
| Recycled Conversation per turn | LiteRT-LM bug: second `sendMessageAsync` on a live Conversation SIGKILLs SM8550/Android 16 | Persistent conversation object |
| Prompt-level multi-turn history | Workaround for recycled Conversation — `buildConversationContext()` reconstructs context | Native conversation memory in LiteRT |
| Dark-only UI | Target users: offline/rural India, battery/OLED display efficiency | Light mode |

## Non-obvious gotchas
- **Conversation must be recycled per turn** — calling `sendMessageAsync` twice on the same LiteRT `Conversation` instance crashes (SIGKILL) on SM8550/Android 16. Always create a new `Conversation` per turn.
- **Downloads use `ModelDownloadService` (foreground Service), NOT WorkManager** — the 10-min WorkManager ceiling kills large model downloads. Do not migrate this to WorkManager.
- **EmbeddingModel / SqliteVectorStore / RagPipeline are `@Deprecated`** — the production RAG path is BM25 only. Don't add callers.
- **NPU is gated off by default** — `DeviceProfiler` deliberately excludes NPU; GPU (OpenCL/Vulkan) → CPU fallback.
- **`SCHEDULE_EXACT_ALARM` graceful degrade** — reminders use `AlarmManager` exact alarms; degrades to inexact when permission not granted. Don't assume exact timing.
- **Firebase only activates with `google-services.json`** — Crashlytics/Analytics are no-ops without it. Don't assume crash data is flowing in debug builds.

---

## AI features in this project
**LLM provider:** Google AI Edge LiteRT-LM (on-device, Gemma 4/3n/3)
**Primary model:** `.litertlm` file stored in app files dir, downloaded at onboarding
**Where inference lives:** `core-inference` — `InferenceEngine` interface + `LiteRTInferenceEngine` impl
**Context assembly:** `ChatRepositoryImpl.buildConversationContext()` — prompt-level multi-turn history
**User context injection:** `MemoryRepository.buildContextSummary()` prepended to every prompt
**Language instruction:** `LanguageManager.buildLanguageInstruction()` appended to prompts
**Caching:** N/A — on-device, no API caching needed
**Usage logging:** ⚠️ Partial — Timber logging exists; no structured per-inference latency/token metrics

AI rules for this project:
- All prompt construction goes through `ChatRepositoryImpl.buildConversationContext()` — never inline prompt strings in ViewModels
- Inference only via `InferenceEngine` interface — never call LiteRT-LM directly from features
- BM25 RAG path only — do not add callers to deprecated `RagPipeline` / `SqliteVectorStore`

---

## Domain rules
- 100% offline — no network calls for inference, ever
- 10 Indian languages + English — always test language switching after prompt changes
- Pack system (Kisan etc.) uses personality + RAG overlays, not separate models
- No SMS/telephony features — permission was removed, do not re-introduce `READ_SMS`

---

## Useful references
- Architecture: [ARCHITECTURE.md](ARCHITECTURE.md)
- Build & run: [README.md](README.md)
- Release checklist: [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)
- Production notes: [docs/PRODUCTION.md](docs/PRODUCTION.md)
- Use the `security-audit` skill before any production launch
- Use the `stage-gates` skill for exit criteria per stage
