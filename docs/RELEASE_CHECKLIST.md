# Saarthi Release Checklist

Run through this before publishing any build.

**What CI actually covers:** `.github/workflows/ci.yml` runs `testDebugUnitTest` +
`lintDebug` on **every pull request** (any target branch, including
`feature/phase-4-rag`) and on `workflow_dispatch`. Pushes to `main`/`master` run
`.github/workflows/build_apk.yml` (same tests/lint plus a debug APK). Store `.aab`
is `.github/workflows/release_aab.yml` on a `v*` tag.

**What CI does not cover:** instrumented tests, Firebase Test Lab, Play Console
forms, signed-AAB install, or on-device LiteRT inference. A green `test-and-lint`
check is necessary and not sufficient. Emulator / cloud-agent / JVM unit tests
are **not** inference proof (GPU, SIGKILL, OEM killers, 2.5 GB+ downloads).
Complete **Manual smoke** on a physical phone before any Play upload. Do not bump
`versionCode` until you are cutting a store candidate.

The items below are the human gates around that.

## Every build (beta or production)

- [ ] `./gradlew test` passes locally and in CI (green check on the commit).
- [ ] **RAG ship eval gate** (Phase 5.5): DPDPA golden replay for attach→ask, chapter
      VI/VII + special provisions spans, and GK/opt-out citation-off paths. Focused
      command (also included in full `testDebugUnitTest`):

      ```bash
      ./gradlew :feature:feature-assistant:testDebugUnitTest \
        --tests "com.saarthi.feature.assistant.data.DpdpaShipEvalGateTest" \
        --tests "com.saarthi.feature.assistant.data.AttachAskSmokeTest" \
        --no-daemon
      ```

      **Phase 6 deferral gates** (lexical baseline before dense/cross-encoder spikes):

      ```bash
      ./gradlew :feature:feature-assistant:testDebugUnitTest \
        --tests "com.saarthi.feature.assistant.data.DenseRetrievalEvalGateTest" \
        --tests "com.saarthi.feature.assistant.data.CrossEncoderDeferralGateTest" \
        --tests "com.saarthi.feature.assistant.data.Fts5AtScaleGoldenTest" \
        --no-daemon
      ```

      Firebase Test Lab's `attach_demo_document_penalty_question_retrieves_a_hit` and
      `attach_overview_scopes_to_newest_file` instrumented smokes cover the same attach
      path on device (no model load).
- [ ] Lint report reviewed (CI artifact `lint-report`) — no new Error-level findings.
- [ ] `versionCode` / `versionName` bumped in `app/build.gradle.kts`.
      Store tag must be `v` + `versionName` (`release_aab.yml` fails otherwise).
- [ ] Release APK is signed with the release keystore (CI: `KEYSTORE_*` secrets set;
      falls back to debug-signing only when they are absent).
- [ ] Installed and smoke-tested on a real phone (see **Release device coverage** below).
- [ ] Model download + resume + cancel tested on a real device/network.
- [ ] **RAG index schema bumps** (Phase 4.3): after changes to chunk metadata sentinels
      (`parentChunkIndex`, chapter registry row, document-role stamp, truncation notice),
      existing sessions keep stale rows until users **re-attach** affected files (or clear
      chat data). Spot-check one upgraded build: attach a known PDF, ask a section-span
      question, confirm retrieval hits the right doc — not a silent no-op on old chunks.
- [ ] Debug log reviewed for anything sensitive that should not ship
      (Point 9: user-sourced strings must be lengths/counts only — see
      [LogPrivacy]; no document names, memory keys, or reminder text in
      `saarthi_debug.log` / Support attachments).
- [ ] No unused restricted permissions in `AndroidManifest.xml`
      (currently declared, verified 2026-07-15: INTERNET, ACCESS_NETWORK_STATE,
      RECORD_AUDIO, POST_NOTIFICATIONS, WAKE_LOCK, RECEIVE_BOOT_COMPLETED,
      FOREGROUND_SERVICE[_SPECIAL_USE]). READ_SMS / MANAGE_EXTERNAL_STORAGE were removed.
      **READ_MEDIA_IMAGES/VIDEO/AUDIO and READ_EXTERNAL_STORAGE were also removed
      2026-07-15** — Play flagged them as undeclared-use during store submission;
      confirmed zero code references anywhere (file attachments go through
      `ActivityResultContracts.GetMultipleContents()`, a scoped content picker that
      never needed broad media-read permission). Don't re-add these without adding
      the corresponding Play Console "Photo and video permissions" declaration.
      **SCHEDULE_EXACT_ALARM is NOT declared** — the reminder feature that needed it was
      removed; only the daily wisdom card remains, which uses an inexact Doze-friendly
      alarm. Don't add it back without also re-adding the "Alarms & reminders" Play
      Console declaration this checklist previously (incorrectly) told you to fill in.

## Release device coverage (Point 8 — one phone + free tools only)

You are **not** expected to buy a device farm. Coverage = your physical phone +
**free** remote/cloud options + code-level SoC gates already in the app
(Exynos API 34+ CPU-only, RAM/compact GPU limits, crash bans). Do not claim a
SoC class was “validated” unless that row actually passed below.

### Free sources (use these; skip paid device labs)

| Source | Cost | Trust for LiteRT / GPU / SIGKILL | Use for |
|--------|------|----------------------------------|---------|
| **Your physical phone** | Free | **High** — only full trust signal | Full manual smoke every release |
| **Firebase Test Lab** (Spark / free daily quota) | Free tier | **Medium** — good for install + instrumented smoke; not a substitute for chat-on-your-SoC | Remote profiles you don’t own |
| **Android Emulator (arm64 AVD)** | Free | **Low** — UI/nav only; do **not** treat as inference proof | Optional nav sanity |
| **Closed testers / friends** (Play internal/closed) | Free | **Medium–high** if they send Support debug logs on crash | Opportunistic SoC diversity |

Build Test Lab APKs (free, local):

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

- App: `app/build/outputs/apk/debug/app-debug.apk`
- Test: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

Upload both in [Firebase Console → Test Lab](https://console.firebase.google.com/)
(Instrumentation) and pick **virtual or physical devices covered by the free
quota**. `AppPackageInstrumentedTest` covers Play `applicationId`, `SaarthiApp`,
FileProvider, and leftover SMS/storage permissions — it does not launch chat.

Optional CI: Actions → **Firebase Test Lab** → Run workflow. Needs secret
`FIREBASE_SERVICE_ACCOUNT_JSON` (GCP/Firebase service account that can start
Test Lab). Unset secret → the job **skips** (green); this is not a merge gate.
The workflow uses one Spark virtual device and that smoke class only. Room
migration androidTests stay in `core-memory` and are not part of this Lab run.

### Pass / fail matrix (fill per release candidate)

Record `versionName` / `versionCode` and date. Mark each row **Pass / Fail / Skip**
(Skip = not available this cycle; do not invent a Pass).

| # | Target | How (free) | What to verify | P/F/S |
|---|--------|------------|----------------|-------|
| 1 | **Your phone** (whatever you own) | Manual install of release or minified candidate | Full list in **Manual smoke** below | |
| 2 | **Remote mid/high Android** (prefer Snapdragon 8-gen-class if listed) | Firebase Test Lab free quota | `AppPackageInstrumentedTest` green; note device model in Lab results | |
| 3 | **Remote mid-range / other OEM** (MediaTek or similar if listed) | Firebase Test Lab free quota | Same instrumented smoke green | |
| 4 | **Remote low-RAM or older API** (if free catalog has one) | Firebase Test Lab free quota | Same instrumented smoke green | |
| 5 | **Friend / closed tester** (optional) | Play internal testing | They complete onboarding + one chat; crash → Support “Report a problem” | |

**Ship bar (solo + free only):** Row 1 **Pass** is required. Rows 2–4: run whatever
the free Lab catalog allows that day (aim for ≥1 remote Pass beyond your phone).
Row 5 when available. Gaps are accepted risk — mitigated by existing SoC/RAM
policy in code, not by buying hardware.

**Do not:** use x86 emulator results as GPU/inference proof; pay for device clouds;
block ship solely because free Lab lacked an Exynos/Unisoc slot that day.

## Manual smoke (your phone — required every release)

These cover the paths unit tests cannot. Do them on **your** real device before release:

- [ ] Onboarding completes from a clean install.
- [ ] Model download → resume after interruption → cancel.
- [ ] Model load failure path shows a graceful message (not a crash).
- [ ] Chat: send a message, watch it stream, press Stop mid-stream.
- [ ] App restart after a generation — history and the selected model persist.
- [ ] Notification permission denied — daily wisdom is suppressed, no crash.
- [ ] Attachment → OCR/RAG path: attach a PDF, ask about it, get a grounded answer.
- [ ] Voice: mic turn + read a long reply aloud (>4000 chars) — chunked TTS speaks fully.
- [ ] Background ~1–2 min during/after load, return — no freeze; generation still works.
- [ ] App upgrade over a previous version — chat history / memories survive
      (Room migrations; plaintext `saarthi.db` is exported once into SQLCipher
      on first launch after this change. Destructive fallback only from ancient
      dev schemas).

## Production (Play Store) only

- [ ] **Debug log gate**: build with `-Psaarthi.publicLog=false` so `saarthi_debug.log`
      is NOT written to public Downloads (stays app-private). Keep it `true` for beta.
- [x] **Crash reporting: decided — no Firebase Crashlytics** (2026-07-13). Automatic
      crash telemetry would silently contradict the "100% offline, nothing leaves
      the phone" pitch and require Data Safety disclosure for a feature nobody
      opted into — inconsistent with this project's own privacy-by-default
      standing rule. Deliberate substitute instead: the Support screen's "Report a
      problem" now auto-attaches the on-device debug log via
      `DebugLogger.shareableUri()` (FileProvider-wrapped when the log lives in
      app-private storage, which it does by default in production) — opt-in,
      visible to the user before it's sent, and works identically in beta and
      production. Revisit only if crash volume post-launch proves this
      insufficient; don't silently re-add Crashlytics without updating the
      privacy-guardrails review and Data Safety form together.
- [ ] **HuggingFace token**: the APK must not embed `HF_APP_TOKEN_B64` (unit test
      `HuggingFaceTokenNotInBuildConfigTest`). Gated Gemma 3n downloads use a
      **user-pasted** read-only token stored in DataStore (Settings). Rotate any
      token that was previously baked into shipped APKs — those builds remain
      extractable. Treat any leftover dashboard token as download-quota only,
      never write/billing.
- [ ] **SQLCipher**: Room `saarthi.db` is encrypted at rest (user-pasted HF
      token stays in DataStore, not this file). Confirm a debug upgrade over a
      pre-SQLCipher install still shows old chats; a clean install creates an
      encrypted file (no `SQLite format 3` header).
- [ ] **Play policy review** before submission:
  - No "Alarms & reminders" declaration needed — SCHEDULE_EXACT_ALARM isn't declared
    (verified 2026-07-13; see "Every build" section above).
  - Foreground service type `specialUse` declared with a subtype string + Play
    Console FGS declaration (on-device LLM inference / model download).
  - Data Safety form: on-device processing, local storage, optional model downloads,
    no data sold/shared; microphone (voice) and notifications usage disclosed.
  - AI / privacy disclosure: state that the model runs fully on-device and chats stay local.
  - Model-download behavior: large downloads gated on Wi-Fi/validated network and surfaced to the user.

---

## v1 Play submission — step by step (added 2026-07)

**Code/config now in place for launch:**
- [x] **Pro paywall disabled** for v1 via `FeatureFlags.PRO_ENABLED = false` (core-i18n).
      No Play Billing yet → showing a purchase flow would be rejected. Flag makes
      every feature free (`EntitlementManager.isPro` → true) and hides the upsell.
      Flip to `true` ONLY after Google Play Billing is wired into `setProUnlocked`.
- [x] **Signed AAB pipeline**: `.github/workflows/release_aab.yml` builds the signed
      `.aab` on a `v*` tag (Play requires AAB, not APK). Optional auto-publish step
      is commented in.
- [x] **Privacy policy**: `docs/PRIVACY_POLICY.md` — host it, paste URL in Console.
- [x] **ProGuard**: litertlm/inference/Hilt/Room keeps verified (release-only crash guard).

**Do before clicking submit:**
1. [ ] **Build the release bundle** and **install the minified build on a real device
       at least once** (`./gradlew bundleRelease`, or tag `vX.Y.Z`) before inviting
       friends to test — R8 can break things a debug build never exercises, and you
       don't want your first external tester to hit that. Static ProGuard audit done
       (2026-07-13): cross-referenced `proguard-rules.pro` against every
       reflection/JNI/native-callback usage in the codebase — litertlm, Room, Hilt,
       PdfBox were already covered; found and fixed one real gap —
       `PackUpdateWorker`'s `(Context, WorkerParameters)` constructor is
       reflection-instantiated by WorkManager with no visible call site, so R8 could
       strip it as dead code. That failure mode is worth calling out specifically:
       it wouldn't surface at launch, only ~24h later when the pack-update job
       actually runs — the kind of thing a quick smoke test right after install
       would miss even on a real device. Static analysis reduces risk but doesn't
       replace actually running the release build — still needed: install it,
       confirm model download/chat/attachments work, and ideally leave it running
       long enough to see the 24h pack-update job fire cleanly.
2. [x] **App signing**: Play App Signing confirmed enrolled (2026-07-13) — Google holds
       the real signing key, so a lost/compromised upload key is recoverable, not fatal.
       [ ] **Still open**: no signed AAB has ever actually been built (`release_aab.yml`
       has zero runs — confirmed via `gh run list`), so there's no old upload keystore to
       recover, but you still need to generate a fresh one and back it up somewhere
       durable and OFFLINE (a GitHub Actions secret is write-only — once set, its value
       can never be read back by anyone, including you, so it can't serve as your backup).
3. [ ] **Upload the `.aab`** (NOT an APK) to the **Internal testing** track first.
4. [ ] **Data safety form** — no Firebase (confirmed: never adopted, decision made
       2026-07-13 to keep it that way — see "Crash reporting" above). Declare:
       - On-device processing + local-only storage (chats, memories, attachments).
       - Voice/audio data: as of 2026-08-28, on-device transcription is the **default**
         (`SpeechRecognizer.createOnDeviceSpeechRecognizer` when the platform confirms
         a model is installed, API 33+). Cloud / standard speech is blocked unless the
         user turns off **Settings → On-device voice only**. If they do, Android's
         standard speech service may send audio to its provider (typically Google).
         Declare audio data as "collected, shared with a third party (device's speech
         service) for app functionality, not used for any other purpose, user can
         opt out" — the opt-out is the default; turning the setting off is opt-in to
         sharing. Do NOT declare "no data collected" for microphone, even though
         Saarthi itself has no backend to receive it, because the optional path exists.
       - No data sold, no advertising use, no data retained by Saarthi's own
         infrastructure (there isn't any — no servers).
5. [ ] **Foreground service** `specialUse`: three services declared, each with its
       justification string already written in `AndroidManifest.xml` — paste directly
       into the Console FGS declaration:
       - `InferenceService`: "On-device AI model inference (LLM text generation)"
       - `ModelDownloadService`: "AI model file download (user-initiated, resumable via OkHttp)"
       - WorkManager's internal `SystemForegroundService` override: "AI model file
         download (background, resumable via OkHttp)"
6. [x] **Exact alarm — not applicable.** SCHEDULE_EXACT_ALARM isn't declared (verified
       2026-07-13); no "Alarms & reminders" form needed. Don't check this box if that
       permission ever gets re-added without updating this note.
7. [x] **Generative-AI content policy** — confirmed: Support screen's "Report a
       problem" (auto-attaches the on-device debug log as of 2026-07-13) satisfies this.
8. [ ] **Store listing**: title, short/full description, screenshots, feature graphic,
       content rating (IARC), category, contact email, privacy policy URL.
9. [ ] **Closed testing**: new personal dev accounts need ~12 testers × 14 days before
       production — start this early.
10. [ ] Promote Internal → Closed → **Production**.

**Future store updates (hands-off):** bump `versionCode`/`versionName` → `git tag vX.Y.Z`
(tag must match `versionName`; `release_aab.yml` fails on mismatch)
→ push → `release_aab.yml` builds the signed AAB (and auto-publishes to the internal
track once `PLAY_SERVICE_ACCOUNT_JSON` is set and the upload step is uncommented).
