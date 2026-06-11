# Saarthi — Architecture Reference

## Overview
Saarthi is a **100% offline** Android AI assistant powered by Gemma 4 / Gemma 3n / Gemma 3 models running on **Google AI Edge LiteRT-LM** (`litertlm-android`). Every user interaction stays on-device.

> Note: the native inference runtime migrated from MediaPipe → LiteRT-LM in
> v1.0.19, and the SMS-based "Money Mentor" pack was removed. Sections below have
> been corrected to match the shipping code; older prose may still lag.

---

## Module Graph

```
app
├── feature:feature-onboarding   ← Language selection + model download/init
├── feature:feature-assistant    ← Chat (streaming), RAG attachments, packs,
│                                   personalities, reminders, TTS
│
├── core:core-ui          ← Design system, Cyber-Vedic theme, components
├── core:core-inference   ← LiteRT-LM (litertlm) Gemma engine (interface + impl)
├── core:core-memory      ← Room: memory, conversations, sessions, rag_chunks
├── core:core-i18n        ← Language manager, SupportedLanguage, personalities
├── core:core-rag         ← Embedding/VectorStore + BM25 retriever
└── core:core-common      ← Result<T>, UseCase, FlowUseCase, Dispatchers
```

> The earlier per-pack feature modules (`feature-money`, `feature-kisan`,
> `feature-knowledge`, `feature-fieldexpert`) were never shipped as separate
> modules. Packs are a persona/RAG overlay inside `feature-assistant`
> (see `PackType` + the pack chat screens), not standalone modules. The
> SMS-based Money Mentor was dropped along with the `READ_SMS` permission.

**Dependency rule:** `app → feature → core`. Features never depend on other features. Core never depends on features.

---

## SOLID Principles Applied

| Principle | Where |
|-----------|-------|
| **S**ingle Responsibility | `InferenceEngine` only generates text. `RagDocumentRepository` only indexes/retrieves chunks. `ReminderManager` only schedules reminders. |
| **O**pen/Closed | New inference backends implement `InferenceEngine` without modifying callers (`InferenceEngineSelector` routes to the active impl). New packs add a `PackType` arm, not new call sites. |
| **L**iskov Substitution | `LiteRTInferenceEngine` fully substitutes `InferenceEngine`. `SqliteVectorStore` substitutes `VectorStore`. |
| **I**nterface Segregation | `MemoryRepository` exposes only what callers need. `EmbeddingModel` hides model internals. |
| **D**ependency Inversion | ViewModels depend on repository interfaces, not Room DAOs directly. Hilt DI wires implementations. |

---

## Clean Architecture Layers (per feature)

```
Presentation (Composable + ViewModel)
    ↓ only knows domain models
Domain (UseCases, Repository interfaces, Domain models)
    ↓ only knows domain
Data (Repository impl, Room DAOs, LiteRT-LM engine, DataStore)
```

---

## Key Architectural Decisions

### 1. On-Device Inference
- **Google AI Edge LiteRT-LM** (`litertlm-android`) — GPU (OpenCL/Vulkan) with
  CPU fallback; NPU is gated off by default (see `DeviceProfiler`).
- Model `.litertlm` stored in app files dir (downloaded at onboarding).
- The `Conversation` is recycled per turn (a second `sendMessageAsync` on a live
  conversation SIGKILLs the process on SM8550/Android 16); continuity comes from
  a prompt-level multi-turn transcript (`ChatRepositoryImpl.buildConversationContext`).
- Streaming via `Conversation.sendMessageAsync` → Kotlin `callbackFlow`.

### 2. Pack System
Each Pack = Base Gemma model + optional LoRA adapter + dedicated RAG vector store.
- Packs share user context via **Shared Memory Layer** (Room DB key-value store)
- `MemoryRepository.buildContextSummary()` prepends user profile to every prompt

### 3. Offline RAG
- Production path is **BM25** over Room-persisted chunks
  (`RagDocumentRepository` + `Bm25Retriever`), with structural sampling,
  neighbour expansion and outline extraction. The `EmbeddingModel` /
  `SqliteVectorStore` cosine path (`RagPipeline`) is legacy/unused
  (`@Deprecated`) and kept only for its test contract.
- Documents chunked sentence/word-aware (~600 chars / 80 overlap).

### 4. Multi-Language
- `SupportedLanguage` enum — 10 Indian languages + English
- `LanguageManager.setLanguage()` applies via `AppCompatDelegate` (no restart needed)
- `LanguageManager.buildLanguageInstruction()` appended to prompts for native responses

### 5. Reminders & Voice
- User-set reminders via `ReminderManager` → `AlarmManager` exact alarms
  (degrades to inexact when `SCHEDULE_EXACT_ALARM` is not granted).
- Text-to-speech read-aloud via `TtsManager` — markdown-stripped, persona voice
  hints, and sentence-aware chunking so replies over the engine's ~4000-char
  input cap are spoken in full.

> The previous SMS-based "Money Mentor" pack (`READ_SMS` + `SmsParser`) was
> removed; the app no longer requests SMS access.

---

## Design System (Cyber-Vedic)

- **Background:** Deep Space `#080B14` + Navy layers
- **Primary accent:** Sacred Gold `#D4A843`
- **Secondary accent:** Cyber Teal `#00D4AA`
- **Glass cards:** `GlassmorphicCard` — 5% white fill + gold/white gradient border
- **Typography:** Nunito family (rounded, accessible)
- **Dark-only** — no light mode by design (offline/rural context)

---

## Scalability Path

| Milestone | What to add |
|-----------|-------------|
| Voice input | Whisper.cpp via JNI, new `core-voice` module |
| Better embeddings | Replace `GemmaEmbeddingModel` with MiniLM ONNX in `core-rag` |
| SQLite-VSS | Drop in `SqliteVssVectorStore : VectorStore` — zero callers change |
| iOS port | KMP shared `domain` + `data` layers; only `presentation` changes |
| New Pack | Add `feature-xyz` module, implement `InferenceEngine` with LoRA path |

---

## Conventions

- **One module = one `build.gradle.kts`** using convention plugins
- **`saarthi.android.feature`** plugin auto-adds: Compose, Hilt, Navigation, core-common, core-ui, core-memory, core-i18n
- No feature imports another feature — only through app-level navigation
- All DB entities live in `core-memory`; feature-local DB tables added to `SaarthiDatabase` migrations
