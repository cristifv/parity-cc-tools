#!/usr/bin/env bash
# Vendors HTMX 2.0.11 (0BSD; 2026-09-22, as parity-frontend) and its SSE extension htmx-ext-sse 2.2.4
# (2025-10-18, the newest at least 14 days old) into src/main/resources/static/vendor/: npm's tarballs,
# each checked against the registry's integrity (sha512); dist/htmx.min.js and sse.js copied with their
# licences and recorded in SHA256SUMS (ci.sh checks it). Pages load them from 'self' (PLAN.md §7).
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT="$ROOT/src/main/resources/static/vendor"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fetch() { # name version
  local want got
  want=$(curl -fsS "https://registry.npmjs.org/$1/$2" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["dist"]["integrity"])')
  curl -fsSo "$tmp/$1.tgz" "https://registry.npmjs.org/$1/-/$1-$2.tgz"
  got="sha512-$(openssl dgst -sha512 -binary "$tmp/$1.tgz" | base64 -w0)"
  [[ "$got" == "$want" ]] || { echo "integrity mismatch for $1 $2" >&2; exit 3; }
  mkdir -p "$tmp/$1"
  tar -xzf "$tmp/$1.tgz" -C "$tmp/$1"
}
fetch htmx.org 2.0.11
fetch htmx-ext-sse 2.2.4
mkdir -p "$OUT/htmx"
cp "$tmp/htmx.org/package/dist/htmx.min.js" "$OUT/htmx/htmx.min.js"
cp "$tmp/htmx.org/package/LICENSE" "$OUT/htmx/LICENSE"
if [[ -f "$tmp/htmx-ext-sse/package/dist/sse.min.js" ]]; then
  cp "$tmp/htmx-ext-sse/package/dist/sse.min.js" "$OUT/htmx/sse.js"
else
  cp "$tmp/htmx-ext-sse/package/sse.js" "$OUT/htmx/sse.js"
fi
if [[ -f "$tmp/htmx-ext-sse/package/LICENSE" ]]; then cp "$tmp/htmx-ext-sse/package/LICENSE" "$OUT/htmx/LICENSE-sse"; fi
(cd "$OUT/htmx" && sha256sum htmx.min.js sse.js > SHA256SUMS)
echo "vendored into $OUT/htmx:"; cat "$OUT/htmx/SHA256SUMS"
