#!/usr/bin/env bash
# Compares two unsigned release APKs built from the same commit (reproducible-build check).
# Signatures are ignored: META-INF signature files and the APK Signing Block aren't part of
# the comparison, only the zip entries' names and contents. Prints a Markdown report (also to
# $GITHUB_STEP_SUMMARY when set) and exits 1 if anything differs.
set -euo pipefail

a=${1:?first APK}
b=${2:?second APK}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

entries() { # apk -> "sha256  name" per entry, signature files excluded
  local dir="$work/$(basename "$1")-$2"
  mkdir -p "$dir"
  unzip -q -o "$1" -d "$dir"
  (cd "$dir" && find . -type f ! -path './META-INF/*.SF' ! -path './META-INF/*.RSA' ! -path './META-INF/*.EC' \
    ! -path './META-INF/*.DSA' ! -path './META-INF/MANIFEST.MF' -print0 | sort -z | xargs -0 sha256sum)
}

entries "$a" a > "$work/a.txt"
entries "$b" b > "$work/b.txt"

report() { echo "$@"; [ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "$@" >> "$GITHUB_STEP_SUMMARY"; true; }

total=$(wc -l < "$work/a.txt")
if diff -q "$work/a.txt" "$work/b.txt" > /dev/null; then
  report "### Reproducible build: ✅ identical"
  report ""
  report "All $total entries of the two unsigned release APKs match (signature files excluded)."
  exit 0
fi

report "### Reproducible build: ❌ differences"
report ""
report "| Entry | Build A | Build B |"
report "|---|---|---|"
join -1 2 -2 2 -a 1 -a 2 -e missing -o 0,1.1,2.1 \
  <(awk '{print $1, $2}' "$work/a.txt" | sort -k2) <(awk '{print $1, $2}' "$work/b.txt" | sort -k2) |
  awk '$2 != $3' | while read -r name ha hb; do
    sa=$( [ -f "$work/$(basename "$a")-a/$name" ] && stat -c %s "$work/$(basename "$a")-a/$name" || echo -)
    sb=$( [ -f "$work/$(basename "$b")-b/$name" ] && stat -c %s "$work/$(basename "$b")-b/$name" || echo -)
    report "| \`${name#./}\` | ${ha:0:12}… ($sa B) | ${hb:0:12}… ($sb B) |"
  done
# For differing native libraries, the first differing strings help find the cause (paths, dates).
join -1 2 -2 2 <(awk '{print $1, $2}' "$work/a.txt" | sort -k2) <(awk '{print $1, $2}' "$work/b.txt" | sort -k2) |
  awk '$2 != $3 && $1 ~ /\.so$/ {print $1}' | head -3 | while read -r name; do
    report ""
    report "<details><summary>strings that differ in ${name#./}</summary>"
    report ""
    report '```'
    { diff <(strings -n 8 "$work/$(basename "$a")-a/$name") <(strings -n 8 "$work/$(basename "$b")-b/$name") || true; } |
      head -20 | while IFS= read -r l; do report "$l"; done
    report '```'
    report "</details>"
  done
exit 1
