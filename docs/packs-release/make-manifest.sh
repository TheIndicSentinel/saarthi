#!/usr/bin/env bash
#
# Build a SIGNED manifest.json for a Kisan pack release.
# Signs the EXACT bytes of the pack file with your offline ECDSA P-256 key,
# then self-verifies the signature. Run this on your machine only — the
# private key never leaves it and is never committed (.keys/ is gitignored).
#
# Usage:
#   ./make-manifest.sh <kisan_pack.json> <private-key.pem> <pack-download-url> [minAppVersionCode]
#
# Example:
#   ./make-manifest.sh kisan_pack.json ../../.keys/saarthi-pack-private.pem \
#     https://github.com/TheIndicSentinel/saarthi-packs/releases/download/kisan-v8/kisan_pack.json 30
#
set -euo pipefail

PACK="${1:?path to kisan_pack.json}"
KEY="${2:?path to ECDSA P-256 private key PEM (kept offline)}"
URL="${3:?public download URL of the pack asset}"
MIN_APP="${4:-30}"   # versionCode of the first app release containing the v2 parser

command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 1; }

PACK_VERSION=$(python3 -c "import json;print(json.load(open('$PACK'))['packVersion'])")
SHA=$(openssl dgst -sha256 "$PACK" | awk '{print $NF}')
SIG=$(openssl dgst -sha256 -sign "$KEY" "$PACK" | base64 | tr -d '\n')
SIZE=$(wc -c < "$PACK" | tr -d ' ')
PUBAT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
OUT="$(dirname "$PACK")/manifest.json"

cat > "$OUT" <<JSON
{
  "schemaVersion": 2,
  "packId": "kisan",
  "packVersion": $PACK_VERSION,
  "language": "en",
  "title": "Kisan Knowledge",
  "publishedAt": "$PUBAT",
  "minAppVersionCode": $MIN_APP,
  "pack": {
    "url": "$URL",
    "sha256": "$SHA",
    "sizeBytes": $SIZE,
    "signature": "$SIG",
    "signatureAlgo": "ecdsa-p256-sha256",
    "keyId": "saarthi-pack-2026-06"
  }
}
JSON

echo "Wrote $OUT"
echo "  packVersion=$PACK_VERSION  sha256=$SHA  size=$SIZE  minAppVersionCode=$MIN_APP"

# Self-verify the signature using the public key derived from the private key.
PUB=$(mktemp); SIGBIN=$(mktemp)
trap 'rm -f "$PUB" "$SIGBIN"' EXIT
openssl ec -in "$KEY" -pubout 2>/dev/null > "$PUB"
printf '%s' "$SIG" | base64 -d > "$SIGBIN"
if openssl dgst -sha256 -verify "$PUB" -signature "$SIGBIN" "$PACK" >/dev/null 2>&1; then
  echo "  signature self-check: OK"
else
  echo "  signature self-check: FAILED" >&2
  exit 1
fi
