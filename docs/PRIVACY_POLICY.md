# Saarthi — Privacy Policy

_Last updated: 2026-07-01 • Applies to the Saarthi Android app (com.saarthi.app)_

> **Host this file at a public URL** (e.g. GitHub Pages, your site) and paste that
> URL into Play Console → App content → Privacy policy. Fill in the **Contact**
> line before publishing. Keep it consistent with the **Data safety** form
> answers (see `RELEASE_CHECKLIST.md`).

## The short version
Saarthi is a **100% offline AI assistant**. The AI model runs **on your device**.
Your conversations, attached files, and remembered facts (name, preferences, etc.)
**stay on your phone** and are **never uploaded to us** or any server. There are
**no accounts and no login**.

## What stays on your device (never sent to us)
- **Chats & messages** — stored locally; deletable any time (Settings → Clear chat history).
- **Remembered facts** (name, city, diet, likes, …) — local only; used to personalise replies.
- **Attached documents / photos** — processed on-device for your question; not uploaded.
- **Voice input** — speech is transcribed for your message; audio is not collected by us.

## Network connections the app makes
Saarthi is offline for AI, but it does connect to the internet for two things:
1. **Downloading the AI model** (one time) and **Kisan knowledge-pack updates**, fetched
   from public hosts (Hugging Face / GitHub). These are downloads to your device — no
   personal data is sent.
2. **Crash & basic usage diagnostics** via **Google Firebase Crashlytics and Analytics**
   *(only when enabled in this build)*. This collects standard technical data — app
   version, device model, OS version, crash stack traces, and anonymous usage events —
   to keep the app stable. It does **not** include your chats, files, or remembered facts.
   _If your build ships without `google-services.json`, this collection is disabled._

## Permissions and why
- **Microphone** — voice input (on-device transcription).
- **Photos/Media & files** — only files you attach to a chat.
- **Notifications + exact alarm** — reminders you set.
- **Foreground service** — keeps the large model download running reliably.
- **Internet / network state** — model & pack downloads, diagnostics.

We do **not** request contacts, location, or SMS.

## Children
Saarthi is not directed at children under 13.

## Data deletion
All on-device data is removed when you clear chat history or uninstall the app.
Diagnostic data (if enabled) is managed per Google Firebase retention policies.

## Contact
Questions or requests: **<add support email — e.g. inerd1412@gmail.com>**
