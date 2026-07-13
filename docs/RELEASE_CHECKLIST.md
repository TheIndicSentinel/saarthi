# Saarthi Release Checklist

Run through this before publishing any build. CI (`.github/workflows/build_apk.yml`)
already runs unit tests, a non-blocking lint, and a signed `assembleRelease` on
every push to `main`; the items below are the human gates around it.

## Every build (beta or production)

- [ ] `./gradlew test` passes locally and in CI (green check on the commit).
- [ ] Lint report reviewed (CI artifact `lint-report`) — no new Error-level findings.
- [ ] `versionCode` / `versionName` bumped in `app/build.gradle.kts`.
- [ ] Release APK is signed with the release keystore (CI: `KEYSTORE_*` secrets set;
      falls back to debug-signing only when they are absent).
- [ ] Installed and smoke-tested on a real phone (see manual smoke list below).
- [ ] Model download + resume + cancel tested on a real device/network.
- [ ] Debug log reviewed for anything sensitive that should not ship.
- [ ] No unused restricted permissions in `AndroidManifest.xml`
      (currently declared, verified 2026-07-13: INTERNET, ACCESS_NETWORK_STATE,
      RECORD_AUDIO, READ_MEDIA_IMAGES/VIDEO/AUDIO, READ_EXTERNAL_STORAGE,
      POST_NOTIFICATIONS, WAKE_LOCK, RECEIVE_BOOT_COMPLETED,
      FOREGROUND_SERVICE[_SPECIAL_USE]). READ_SMS / MANAGE_EXTERNAL_STORAGE were removed.
      **SCHEDULE_EXACT_ALARM is NOT declared** — the reminder feature that needed it was
      removed; only the daily wisdom card remains, which uses an inexact Doze-friendly
      alarm. Don't add it back without also re-adding the "Alarms & reminders" Play
      Console declaration this checklist previously (incorrectly) told you to fill in.

## Manual smoke test (no emulator/instrumentation in CI yet)

These cover the paths unit tests cannot. Do them on a real device before release:

- [ ] Onboarding completes from a clean install.
- [ ] Model download → resume after interruption → cancel.
- [ ] Model load failure path shows a graceful message (not a crash).
- [ ] Chat: send a message, watch it stream, press Stop mid-stream.
- [ ] App restart after a generation — history and the selected model persist.
- [ ] Notification permission denied — reminders degrade gracefully, no crash.
- [ ] Attachment → OCR/RAG path: attach a PDF, ask about it, get a grounded answer.
- [ ] Voice: read a long reply aloud (>4000 chars) — it speaks fully (chunked TTS).
- [ ] App upgrade over a previous version — chat history / memories survive
      (Room migrations 3→4→5; destructive fallback only from dev v1/v2).

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
- [ ] **HuggingFace token**: confirm the embedded `hf.app.token` is a read-only,
      low-scope token, and rotate it on the HF dashboard if it has been shared.
      It is extractable from the APK — treat it as a download-quota token only,
      never a write/billing token.
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
       - Voice/audio data: as of 2026-07-13, on-device transcription is preferred
         (`SpeechRecognizer.createOnDeviceSpeechRecognizer` when the platform confirms
         a model is installed, API 33+); on devices without one, Android's standard
         speech service is used, which may send audio to its provider (typically
         Google) for transcription. Declare audio data as "collected, shared with a
         third party (device's speech service) for app functionality, not used for
         any other purpose, user cannot opt out" — do NOT declare "no data collected"
         for microphone, even though Saarthi itself has no backend to receive it.
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
→ push → `release_aab.yml` builds the signed AAB (and auto-publishes to the internal
track once `PLAY_SERVICE_ACCOUNT_JSON` is set and the upload step is uncommented).
