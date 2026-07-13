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
- **Voice input** — Saarthi asks your device to transcribe speech to text and prefers
  on-device transcription where your phone supports it. On devices without an on-device
  speech model, Android's standard speech service is used instead, which may send the
  audio to that service's provider (typically Google) for transcription — this is a
  device/platform behavior, not something Saarthi's own servers ever see (Saarthi has
  no servers). Nothing is collected by us either way.

## Network connections the app makes
Saarthi is offline for AI — there is no server-side chat processing, and Saarthi has
no backend to send your data to. It does connect to the internet for:
1. **Downloading the AI model** (one time) and **Kisan knowledge-pack updates**, fetched
   from public hosts (Hugging Face / GitHub). These are downloads to your device — no
   personal data is sent.
2. **Voice input**, if your device doesn't have an on-device speech model — see above.
3. **Crash & support reports you choose to send.** Saarthi has no automatic crash
   reporting or analytics of any kind — no Firebase, no telemetry. If something goes
   wrong, the on-device debug log (technical: timings, error codes, device model —
   never your message content) is written to your phone only. The Support screen's
   "Report a problem" lets you email it to us, with the log attached automatically so
   you can review exactly what's being sent before you send it — nothing leaves your
   device unless you choose to send that email.

## Permissions and why
- **Microphone** — voice input (see above: on-device where supported, otherwise your
  device's standard speech service).
- **Photos/Media & files** — only files you attach to a chat.
- **Notifications** — the daily wisdom card and download-progress updates.
- **Foreground service** — keeps the large model download and AI responses running reliably.
- **Internet / network state** — model & pack downloads, and voice input when on-device
  transcription isn't available.

We do **not** request contacts, location, or SMS.

## Children
Saarthi is not directed at children under 13.

## Data deletion
All on-device data is removed when you clear chat history or uninstall the app.
Diagnostic data (if enabled) is managed per Google Firebase retention policies.

## Contact
Questions or requests: **<add support email — e.g. inerd1412@gmail.com>**
