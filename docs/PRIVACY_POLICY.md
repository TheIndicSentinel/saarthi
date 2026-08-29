# Saarthi — Privacy Policy

_Last updated: 2026-08-29 • Applies to the Saarthi Android app (com.indicsentinel.saarthi)_

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
- **Chats & messages** — stored locally in an encrypted on-device database
  (SQLCipher); deletable any time (Settings → Clear chat history).
- **Remembered facts** (name, city, diet, likes, …) — local only, in that same
  encrypted database; used to personalise replies.
- **Attached documents / photos** — processed on-device for your question; not uploaded.
- **Voice input** — Saarthi asks your device to transcribe speech to text using
  on-device transcription. Voice works only when an on-device speech model is
  available; otherwise type. Saarthi does not send your audio to its own servers
  (it has none). Nothing is collected by us.

## Network connections the app makes
Saarthi is offline for AI — there is no server-side chat processing, and Saarthi has
no backend to send your data to. It does connect to the internet for:
1. **Downloading the AI model** (one time) and **Kisan knowledge-pack updates**, fetched
   from public hosts (Hugging Face / GitHub). These are downloads to your device — no
   personal data is sent. Saarthi does **not** ship a Hugging Face token in the app.
   Gemma 4 and Compact are public downloads. Gemma 3n is on a gated Google repo: if you
   choose it, you paste a read-only token when asked, stored only on this phone.
2. **Voice input**, only on phones that already allowed the platform speech
   service in an older app version. New installs stay on-device; if the phone
   has no on-device speech model, type instead.
3. **Crash & support reports you choose to send.** Saarthi has no automatic crash
   reporting or analytics of any kind — no Firebase, no telemetry. If something goes
   wrong, the on-device debug log (technical: timings, error codes, device model —
   lengths/counts for attachments — never your message content or document
   names) is written to your phone only. The Support screen's
   "Report a problem" lets you email it to us, with the log attached automatically so
   you can review exactly what's being sent before you send it — nothing leaves your
   device unless you choose to send that email.

## Permissions and why
- **Microphone** — voice input (on-device speech on this phone).
- **Photos/Media & files** — only files you attach to a chat.
- **Notifications** — the daily wisdom card and download-progress updates.
- **Foreground service** — keeps the large model download and AI responses running reliably.
- **Internet / network state** — model & pack downloads.

We do **not** request contacts, location, or SMS.

## Children
Saarthi is not directed at children under 13.

## Data deletion
All on-device data is removed when you clear chat history or uninstall the app.
There is no automatic diagnostic collection to retain — see "Network connections
the app makes" above: the debug log stays on your device unless you personally
choose to email it to us via the Support screen.

## Contact
Questions or requests: **<add support email — e.g. inerd1412@gmail.com>**
