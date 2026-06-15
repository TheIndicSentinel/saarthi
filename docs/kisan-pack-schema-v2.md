# Kisan Pack — Schema v2 & Update Contract (P0)

Status: **DESIGN — frozen contract**. No app code yet. This is the single source of
truth both the **content pipeline** (separate public repo `saarthi-packs`) and the
**app** build against.

## Goals
A **paid** Kisan pack that is robust, reliable, and **accurate**, kept fresh from
official sources, delivered over **free infrastructure**, without weakening
Saarthi's core philosophy.

## Invariants (must always hold)
1. **On-device inference; nothing uploads.** The pack channel is *pull-only* — the
   app downloads a public, signed file. No user data, queries, or identifiers ever
   leave the phone.
2. **Fully usable offline forever** on the bundled seed, even if the network is
   never reached. Updates are opportunistic (Wi-Fi only), never required.
3. **Every downloaded pack is verified** (SHA-256 + Ed25519 signature) before
   install; a failed check keeps the current pack.
4. **No external AI APIs** anywhere in the pipeline (adjustment #1). Summaries are
   human-curated, optionally drafted by a **local** OSS model inside the CI runner.
5. **No paid services.** GitHub repo + Actions + Releases + jsDelivr CDN + free Govt
   APIs + open-source tooling only.

---

## Free stack
| Concern | Free choice |
|---|---|
| Pipeline CI | GitHub Actions (unlimited minutes on a **public** content repo) |
| Hosting | GitHub Releases (assets: `kisan_pack.json` + `manifest.json`) |
| CDN / bandwidth | jsDelivr in front of GitHub (free, global, anonymous) |
| Sources | data.gov.in CKAN API, Agmarknet, ministry open data (free) |
| Scraping | requests + selectolax (static) → Playwright (JS) → pdfplumber (PDF) |
| Summaries | human curation + optional **local** OSS model in runner (no API) |
| Signing | Ed25519 via `minisign`/libsodium (free); private key offline |

---

## Source acquisition hierarchy (adjustment #2)
Each source in the pipeline's checked-in registry declares a preferred `tier`; the
pipeline tries them **in order** and records which one actually produced the data:

```
api        →  official structured endpoint (preferred — accurate, stable)
scrape     →  static HTML (lightweight parser) when no API exists
playwright →  headless browser ONLY for JS-rendered pages (last resort, heavy)
```

The winning tier is stamped onto every `sources[]` entry as provenance.

---

## Pack file — `kisan_pack.json` (schemaVersion 2)

Carries the content. **Does not** carry its own hash/signature (those live in the
manifest — a file can't contain a hash of itself).

```json
{
  "schemaVersion": 2,
  "packId": "kisan",
  "packVersion": 8,
  "language": "en",
  "title": "Kisan Knowledge",
  "publishedAt": "2026-06-15T00:00:00Z",
  "generator": "saarthi-packs@1.3.0",

  "sections": [
    { "id": "central-schemes", "version": 5, "effectiveDate": "2026-06-01",
      "retrievedAt": "2026-06-14T03:00:00Z", "tier": "api" },
    { "id": "msp", "version": 3, "effectiveDate": "2026-06-19",
      "retrievedAt": "2026-06-14T03:05:00Z", "tier": "scrape" }
  ],

  "entries": [
    {
      "id": "central-schemes/pm-kisan",
      "section": "central-schemes",
      "topic": "PM-KISAN Samman Nidhi — direct cash benefit",
      "content": "Farmer-friendly summary …",
      "tags": ["scheme", "income-support", "central"],
      "state": null,
      "effectiveDate": "2026-06-01",
      "retrievedAt": "2026-06-14T03:00:00Z",
      "sources": [
        { "label": "PM-KISAN official portal", "url": "https://pmkisan.gov.in/", "tier": "api" },
        { "label": "Operational guidelines (PDF)", "url": "https://…", "tier": "scrape" }
      ]
    }
  ]
}
```

### Field reference
| Field | Purpose |
|---|---|
| `schemaVersion` | Format version. App refuses formats newer than it understands. |
| `packVersion` | Monotonic int. Drives the app's existing version-gate (`installed >= bundled → skip`). Bumps when **any** section changes. |
| `sections[]` | **Content-specific versioning** (adjustment #3). Each domain has its own `version` + `effectiveDate` + `retrievedAt` + winning `tier`. Enables partial refresh (update MSP without re-curating schemes) and per-topic "as of" in the UI. |
| `entries[].id` | Stable slug (`section/key`). Used for diffing, dedup, and rollback. |
| `entries[].sources[]` | **Multiple** deep-links per topic (the "pages of links" requirement), each with provenance `tier`. Replaces v1's single `sourceUrl`. |
| `effectiveDate` | When the fact is valid as-of (e.g. MSP season). |
| `retrievedAt` | When the pipeline fetched it (freshness/audit). |
| `state` | Non-null only for state-overlay entries (existing Center→State model). |

---

## Manifest — `manifest.json` (schemaVersion 2)

Tiny pointer file the app polls. Stable URL via GitHub:
`https://github.com/<org>/saarthi-packs/releases/latest/download/manifest.json`
(or jsDelivr-fronted equivalent).

```json
{
  "schemaVersion": 2,
  "packId": "kisan",
  "packVersion": 8,
  "language": "en",
  "publishedAt": "2026-06-15T00:00:00Z",
  "minAppVersionCode": 124,

  "pack": {
    "url": "https://github.com/<org>/saarthi-packs/releases/download/kisan-v8/kisan_pack.json",
    "sha256": "<64-hex>",
    "sizeBytes": 184320,
    "signature": "<base64 Ed25519 over the pack bytes>",
    "signatureAlgo": "ed25519",
    "keyId": "saarthi-pack-2026-06"
  },

  "sections": { "central-schemes": 5, "msp": 3, "crops": 2, "pests": 2, "state-overlays": 4 },

  "rollback": {
    "packVersion": 7,
    "url": "https://github.com/<org>/saarthi-packs/releases/download/kisan-v7/kisan_pack.json",
    "sha256": "<64-hex>",
    "signature": "<base64>",
    "keyId": "saarthi-pack-2026-06"
  }
}
```

### Field reference
| Field | Purpose |
|---|---|
| `pack.sha256` + `pack.signature` | Integrity boundary. App verifies **both** against the embedded public key before installing. Data is public, so this is about *authenticity/tamper*, not secrecy. |
| `keyId` | Supports key rotation — app embeds current + next public key. |
| `minAppVersionCode` | Stops an old app from installing a pack whose schema it can't parse. |
| `sections{}` | Per-section versions visible without downloading the full pack (UI + diff). |
| `rollback{}` | **Rollback support** (adjustment #4): the immediately-previous known-good pack, fully verifiable, so the app can recover from a bad current pack. |

---

## Integrity & signing
- **Ed25519.** Private key kept **offline** on the maintainer's machine (sign locally,
  upload to the Release). CI-secret signing is acceptable but weaker — prefer offline.
- Sign the **raw pack bytes**; publish `signature` + `sha256` + `keyId` in the manifest.
- App embeds the public key(s) (asset/BuildConfig). Verify sig **and** sha256 before install.

## Rollback & self-heal (app behavior — spec only)
- filesDir keeps **`kisan_active.json` + `kisan_previous.json`**.
- Install: download → verify sig+sha256 → parse + schema-validate + non-empty →
  back up active→previous → atomic swap. **Any failure → discard, keep active.**
- Runtime self-heal order: active → previous → bundled seed asset. The user is never
  left with no pack.
- Operational rollback: GitHub Releases retains every version, so a bad release is
  undone by publishing a new manifest (higher `packVersion`) pointing `pack.url` at
  the older verified asset.

## Backward compatibility (transition)
- The app's v2 parser must also accept the **v1 seed** (single `sourceUrl`,
  no sections) by mapping `sourceUrl → sources[0]` and synthesizing one default
  section. Lets us upgrade the bundled seed and remote packs independently.

---

## App integration points (for the later, code phase — not now)
| File | Change |
|---|---|
| `app/build.gradle.kts` | Set `KISAN_PACK_MANIFEST_URL` to the `releases/latest` manifest. |
| `PackUpdateWorker.kt` | Verify sha256 + Ed25519; handle `minAppVersionCode`; rollback. |
| `KisanPackInstaller.kt` | Parse v2 (sections, `sources[]`, dates); keep `previous` backup; self-heal; v1 fallback. |
| `KisanPackScreen.kt` | Render `sources[]` (multi-link) + per-entry "as of `effectiveDate`". |
| `kisan_seed.json` | Upgrade bundled seed to v2 so first-run offline already has the richer structure. |
| App assets/config | Embed Ed25519 public key(s). |

## Open decisions (need a call before the code phase)
1. Content repo name + org for `saarthi-packs` (public).
2. Refresh cadence per section (schemes monthly · MSP on announcement · mandi prices excluded or daily delta?).
3. Offline-sign vs CI-secret-sign (recommend offline).
4. Firebase: drop analytics / opt-in Crashlytics / none (see audit).
