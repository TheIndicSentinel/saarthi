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
      (currently declared: INTERNET, ACCESS_NETWORK_STATE, RECORD_AUDIO, media-read,
      POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM, WAKE_LOCK, RECEIVE_BOOT_COMPLETED,
      FOREGROUND_SERVICE[_SPECIAL_USE]). READ_SMS / MANAGE_EXTERNAL_STORAGE were removed.

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
- [ ] **Crash reporting**: drop a real `app/google-services.json` in to enable
      Firebase Crashlytics for release (currently a deliberate offline-beta policy —
      Crashlytics is wired but inert without the config file). Without it, you have
      no crash visibility beyond users sharing the debug log.
- [ ] **HuggingFace token**: confirm the embedded `hf.app.token` is a read-only,
      low-scope token, and rotate it on the HF dashboard if it has been shared.
      It is extractable from the APK — treat it as a download-quota token only,
      never a write/billing token.
- [ ] **Play policy review** before submission:
  - Restricted permissions: justify SCHEDULE_EXACT_ALARM in the "Alarms & reminders"
    declaration form (scheduled user reminders).
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
1. [ ] **Build the release bundle** and **test the minified build on a real device**
       (`./gradlew bundleRelease`, or tag `vX.Y.Z`). R8 can break LiteRT/Room only in
       release — smoke-test model download, chat, reminders, TTS, attachments.
2. [ ] **App signing**: confirm Play App Signing uses a **Google-generated key**; keep
       your **upload keystore backed up offline** (losing it ≠ fatal; losing a
       self-managed app-signing key = can never update).
3. [ ] **Upload the `.aab`** (NOT an APK) to the **Internal testing** track first.
4. [ ] **Data safety form**: declare on-device processing + local storage **AND**
       Firebase Crashlytics/Analytics collection if `google-services.json` is shipped
       (do NOT answer "no data collected" while Firebase is active).
5. [ ] **Foreground service** `specialUse`: complete the Console declaration + justification.
6. [ ] **Exact alarm**: complete the Alarms & reminders declaration.
7. [ ] **Generative-AI content policy**: confirm an in-app way to report offensive AI
       output exists (Support → "Report an issue" e-mail satisfies the minimum).
8. [ ] **Store listing**: title, short/full description, screenshots, feature graphic,
       content rating (IARC), category, contact email, privacy policy URL.
9. [ ] **Closed testing**: new personal dev accounts need ~12 testers × 14 days before
       production — start this early.
10. [ ] Promote Internal → Closed → **Production**.

**Future store updates (hands-off):** bump `versionCode`/`versionName` → `git tag vX.Y.Z`
→ push → `release_aab.yml` builds the signed AAB (and auto-publishes to the internal
track once `PLAY_SERVICE_ACCOUNT_JSON` is set and the upload step is uncommented).
