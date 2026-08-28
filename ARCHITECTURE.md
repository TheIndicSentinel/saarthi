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
├── core:core-rag         ← Bm25Retriever (production); deprecated embedding/vector path
└── core:core-common      ← Dispatchers, sqliteWriteWithRetry, CrashReporter
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
| **L**iskov Substitution | `LiteRTInferenceEngine` fully substitutes `InferenceEngine`. `Bm25Retriever` is the production retrieval primitive; legacy `VectorStore` impls are not wired in app code. |
| **I**nterface Segregation | `MemoryRepository` exposes only what callers need. `EmbeddingModel` hides model internals. |
| **D**ependency Inversion | ViewModels depend on repository interfaces, not Room DAOs directly. Hilt DI wires implementations. |

---

## Clean Architecture Layers (per feature)

```
Presentation (Composable + ViewModel)
    ↓ only knows domain models
Domain (Repository interfaces, Domain models)
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
Each Pack = base Gemma model + optional LoRA adapter + **persona / RAG prompt overlays**
(not a separate vector DB). Session-attached documents share one BM25 index per chat
(`rag_chunks` in Room). Packs reuse the same retrieval pipeline with pack-specific
personality strings.
- Packs share user context via **Shared Memory Layer** (Room DB key-value store)
- `MemoryRepository.buildContextSummary()` prepends user profile to every prompt

### 3. Offline RAG (production path)
**Do not add callers to `RagPipeline`, `GemmaEmbeddingModel`, or `SqliteVectorStore`.**
Those are legacy reference code in `core-rag`; the live path is BM25-only.

```
Attach PDF/DOC → extract (PdfBox, OCR fallback) → legal/gazette-aware chunking
              → Room rag_chunks (+ chapter registry, parentChunkIndex graph)
              → optional FTS5 prefilter (large sessions only)

Query → turn-mode routing (plain / GK / doc-grounded / mixed)
      → scope (active doc, this-turn attach, compare, session)
      → query rewrite lexicon + Indic/Hinglish expansion (pre-BM25)
      → structural anchors (chapter span, topic, tabular contract, registry)
      → BM25 (+ FTS5 candidate pool when gated)
      → lightweight feature rerank (no cross-encoder, no embeddings)
      → neighbor + hierarchical section expansion
      → paraphrase retry when scores stay weak
      → prompt assembly (shape, char budget, citation labels)
      → on-device Gemma generation
      → post-gen groundedness audit (amounts, sections, shall)
      → deterministic Sources footer + claim-overlap filter
```

| Layer | Module / type | Notes |
|-------|----------------|-------|
| Index | `RagDocumentRepository.indexIfNeeded` | Idempotent per `(sessionId, docUri)`; legal sections at ~1800c, tables at 600c |
| Retrieve | `RagDocumentRepository.search` | `Bm25Retriever` in `core-rag`; metadata from `chapterId`, `parentChunkIndex` |
| Rerank | `FeatureRerank` | Additive bonuses on BM25 scores; **cross-encoder deferred** (Wave 6 P28) |
| Eval | JVM golden harness (`GoldenSessionHarness`, `DpdpaShipEvalGate`, Phase 6 eval gates) | No Room, no LLM — ship + deferral gates before dense/cross-encoder spikes |
| Citations | `DeterministicSourcesFooter`, `CitationGating`, `PostGenGroundedness` | System-built footer; drop when excerpts do not support claims |

**Not in production:** MiniLM ONNX, `sqlite-vss`, cosine `SqliteVectorStore`, `GemmaEmbeddingModel`,
`RagPipeline`. Revisit dense retrieval only if golden eval still misses after metadata +
feature rerank + lexicon plateau **and** RAM/latency budget allows a second on-device model.

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

| Milestone | What to add | Status |
|-----------|-------------|--------|
| Voice input | Whisper.cpp via JNI, new `core-voice` module | Planned |
| Dense embeddings | MiniLM ONNX or tiny on-device encoder in `core-rag` | **Spike-only** (`DENSE_RETRIEVAL_SPIKE_ENABLED = false`); lexical eval gate must pass first |
| Cross-encoder rerank | Second-stage neural reranker | **Deferred** (`CROSS_ENCODER_RERANK_ENABLED = false`); deferral gate + ship eval green |
| SQLite-VSS | `SqliteVssVectorStore : VectorStore` | **Legacy only** — no Hilt wiring; do not enable without architecture review |
| iOS port | KMP shared `domain` + `data` layers; only `presentation` changes | Planned |
| New Pack | Add `PackType` + persona/RAG overlays in `feature-assistant` | Ongoing |

---

## Conventions

- **One module = one `build.gradle.kts`** using convention plugins
- **`saarthi.android.feature`** plugin auto-adds: Compose, Hilt, Navigation, core-common, core-ui, core-memory, core-i18n
- No feature imports another feature — only through app-level navigation
- All DB entities live in `core-memory`; feature-local DB tables added to `SaarthiDatabase` migrations
