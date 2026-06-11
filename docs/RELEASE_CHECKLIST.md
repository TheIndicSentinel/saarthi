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
