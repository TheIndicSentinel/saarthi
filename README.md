# Saarthi 🪔

**A 100% offline AI assistant for India — every conversation stays on the phone.**

Saarthi runs Google's Gemma models *on-device* via [Google AI Edge LiteRT-LM](https://ai.google.dev/edge). There is no cloud, no account, and no data leaves the device — the model is downloaded once during onboarding and the app works fully offline forever after. Built for Indian users across 10 Indian languages + English, with knowledge packs (e.g. Kisan/farming) layered on top of the base assistant.

> Android · Kotlin · Jetpack Compose · on-device Gemma (LiteRT-LM) · dark-only

---

## Features

- **Fully offline & private** — inference is 100% on-device; no network call is ever made to answer a question.
- **Multilingual** — 10 Indian languages + English, with per-language prompting and TTS.
- **Knowledge packs** — domain overlays (Kisan farming pack today) that combine a persona with curated, offline RAG content over government sources. Pack chat requires a capable model (Gemma 3n / Gemma 4); the compact 1B is browse-only.
- **Tier-aware model catalog** — recommends the right Gemma model for the device's RAM/SoC, from a 584 MB compact model up to Gemma 4 / 3n.
- **Resumable model downloads** — 2.5 GB+ models download via a foreground service with HTTP Range resume.
- **On-device extras** — reminders (reliable alarm-clock scheduling), voice input, document attachments (BM25 RAG), per-chat memory.

## Tech stack

| Area | Choice |
|------|--------|
| Language / UI | Kotlin · Jetpack Compose (Material3) |
| On-device AI | Google AI Edge LiteRT-LM (`litertlm-android`) — Gemma 4 / 3n / 3; GPU (OpenCL/Vulkan) → CPU fallback |
| RAG | BM25 over Room-persisted chunks |
| DI / Data | Hilt + KSP · Room · DataStore |
| Downloads | Foreground `Service` + OkHttp (resumable) |
| Monitoring | Firebase Crashlytics + Analytics (needs `google-services.json`) |
| Build | AGP 8.7.3 · convention plugins in `build-logic/` · minSdk 28, target/compile 35 |

## Build & run

```bash
# Debug APK
./gradlew assembleDebug

# Install on a connected device
./gradlew :app:installDebug

# Unit tests (all modules / one module)
./gradlew test
./gradlew :core:core-inference:test

# Lint
./gradlew lint
```

No manual model setup is needed — the app downloads the appropriate Gemma model during onboarding and stores it in the app's files directory. Firebase is optional in debug (Crashlytics/Analytics are no-ops without `google-services.json`).

## Project structure

```
saarthi/
├── app/                       Application module, navigation host, receivers
├── feature/
│   ├── feature-onboarding/    Language select + model download/init
│   └── feature-assistant/     Chat, packs, RAG attachments, reminders, TTS, voice
├── core/
│   ├── core-inference/        LiteRT-LM engine, ModelCatalog, DeviceProfiler, prompts
│   ├── core-memory/           Room DB (memories, conversations, sessions, rag_chunks)
│   ├── core-rag/              BM25 retriever
│   ├── core-ui/               Cyber-Vedic design system
│   ├── core-i18n/             Languages, personalities, pack entitlements
│   └── core-common/           Result, UseCase, dispatchers
├── build-logic/               Gradle convention plugins
└── gradle/libs.versions.toml
```

## Key design decisions

- **LiteRT-LM over MediaPipe** — official Google AI Edge runtime, better Gemma support.
- **Foreground Service for downloads** — WorkManager's 10-minute ceiling kills 2.5 GB+ model downloads.
- **BM25 RAG, not vector embeddings** — fully offline, no embedding model, fast on low-end devices.
- **Conversation recycled per turn** — works around a LiteRT-LM crash when reusing a live `Conversation` on some SoCs.
- **Dark-only UI** — target users (offline/rural India), battery/OLED efficiency.

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — module graph and runtime flow
- [CONTRIBUTING.md](CONTRIBUTING.md) — contribution guidelines
- [docs/PRODUCTION.md](docs/PRODUCTION.md) — production notes
- [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) — release checklist