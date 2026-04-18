# Saarthi — Architecture Reference

## Overview
Saarthi is a **100% offline** Android AI assistant powered by Gemma 2B (MediaPipe) with a **Pack-based** feature architecture. Every user interaction stays on-device.

---

## Module Graph

```
app
├── feature:feature-onboarding   ← Language selection + model init
├── feature:feature-assistant    ← Base chat (streaming)
├── feature:feature-money        ← Money Mentor (SMS → AI categorise)
├── feature:feature-kisan        ← Kisan Saathi (agriculture RAG)
├── feature:feature-knowledge    ← Knowledge Pack (NCERT RAG)
├── feature:feature-fieldexpert  ← Field Expert (tech manual RAG)
│
├── core:core-ui          ← Design system, Cyber-Vedic theme, components
├── core:core-inference   ← MediaPipe Gemma engine (interface + impl)
├── core:core-memory      ← Shared memory layer (Room, cross-pack context)
├── core:core-i18n        ← Language manager, SupportedLanguage enum
├── core:core-rag         ← EmbeddingModel, VectorStore, RagPipeline
└── core:core-common      ← Result<T>, UseCase, FlowUseCase, Dispatchers
```

**Dependency rule:** `app → feature → core`. Features never depend on other features. Core never depends on features.

---

## SOLID Principles Applied

| Principle | Where |
|-----------|-------|
| **S**ingle Responsibility | `InferenceEngine` only generates text. `RagPipeline` only augments prompts. `SmsParser` only parses SMS. |
| **O**pen/Closed | New inference backends (llama.cpp, ONNX) implement `InferenceEngine` without modifying callers. New packs add a module, not modify existing ones. |
| **L**iskov Substitution | `MediaPipeInferenceEngine` fully substitutes `InferenceEngine`. `SqliteVectorStore` substitutes `VectorStore`. |
| **I**nterface Segregation | `MemoryRepository` exposes only what callers need. `EmbeddingModel` hides model internals. |
| **D**ependency Inversion | ViewModels depend on repository interfaces, not Room DAOs directly. Hilt DI wires implementations. |

---

## Clean Architecture Layers (per feature)

```
Presentation (Composable + ViewModel)
    ↓ only knows domain models
Domain (UseCases, Repository interfaces, Domain models)
    ↓ only knows domain
Data (Repository impl, Room DAOs, MediaPipe, DataStore)
```

---

## Key Architectural Decisions

### 1. On-Device Inference
- **MediaPipe LLM Inference API** — GPU/NPU acceleration via `tasks-genai`
- Model stored in app files dir (downloaded once at onboarding)
- LoRA adapters loaded per Pack (`PackType.loraFileName`)
- Streaming via `generateResponseAsync` → Kotlin `callbackFlow`

### 2. Pack System
Each Pack = Base Gemma model + optional LoRA adapter + dedicated RAG vector store.
- Packs share user context via **Shared Memory Layer** (Room DB key-value store)
- `MemoryRepository.buildContextSummary()` prepends user profile to every prompt

### 3. Offline RAG
- Query → `EmbeddingModel.embed()` → `VectorStore.search(topK=3)` → augmented prompt
- Vector store: SQLite with cosine similarity (replace with SQLite-VSS `.so` for prod)
- Documents chunked at 512 tokens / 64 overlap

### 4. Multi-Language
- `SupportedLanguage` enum — 10 Indian languages + English
- `LanguageManager.setLanguage()` applies via `AppCompatDelegate` (no restart needed)
- `LanguageManager.buildLanguageInstruction()` appended to prompts for native responses

### 5. Money Mentor Privacy
- `READ_SMS` permission → `SmsParser` (regex + Gemma classification)
- No cloud API, no bank SDK — fully offline
- Transactions stored in local encrypted Room DB

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
