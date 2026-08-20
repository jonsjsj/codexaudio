#!/bin/sh
# Generate the OTA manifest (audex-latest.json) from the two git-tracked sources
# of truth: the version comes from app/build.gradle.kts, the notes come from the
# top-matching section of CHANGELOG.md. Nothing is hand-written — run this in the
# release flow and publish its output, so the download page, version badge and
# "what's new" all derive from git.
#
# Usage: tools/gen-manifest.sh [output-path]   (default: ./audex-latest.json)
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="$ROOT/app/build.gradle.kts"
CHANGELOG="$ROOT/CHANGELOG.md"
OUT="${1:-$ROOT/audex-latest.json}"

VCODE=$(grep -oE 'versionCode = [0-9]+' "$GRADLE" | head -1 | grep -oE '[0-9]+')
VNAME=$(grep -oE 'versionName = "[^"]+"' "$GRADLE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
[ -n "$VCODE" ] && [ -n "$VNAME" ] || { echo "gen-manifest: could not read version from $GRADLE" >&2; exit 1; }

# Pull the CHANGELOG section for VNAME (lines after "## VNAME" up to the next
# "## "), fold each bullet (a "- " line plus its wrapped continuation lines) into
# one "• "-prefixed entry, and JSON-escape. Entries are joined with a literal \n.
NOTES=$(awk -v v="## $VNAME" '
  $0 == v { grab = 1; next }
  grab && /^## / { exit }
  grab {
    if ($0 ~ /^- /) { if (cur != "") out[++n] = cur; cur = substr($0, 3) }
    else if ($0 ~ /^[[:space:]]+[^[:space:]]/ && cur != "") { sub(/^[[:space:]]+/, ""); cur = cur " " $0 }
  }
  END {
    if (cur != "") out[++n] = cur
    s = ""
    for (i = 1; i <= n; i++) {
      gsub(/\\/, "\\\\", out[i]); gsub(/"/, "\\\"", out[i])
      s = s (i == 1 ? "" : "\\n") "• " out[i]
    }
    print s
  }
' "$CHANGELOG")
[ -n "$NOTES" ] || { echo "gen-manifest: no CHANGELOG section for $VNAME" >&2; exit 1; }

printf '{"versionCode":%s,"versionName":"%s","url":"/audex-%s.apk?v=%s","notes":"%s"}\n' \
  "$VCODE" "$VNAME" "$VNAME" "$VCODE" "$NOTES" > "$OUT"
echo "gen-manifest: wrote $OUT (v$VNAME / vc$VCODE)"
