#!/usr/bin/env bash
# Prints the CHANGELOG.md section for the given version (release notes).
set -euo pipefail

VERSION="${1:?usage: release-notes.sh <version>}"

# Matches the section heading in either form: `## X.Y.Z - 2026-06-12` or the
# Keep-a-Changelog bracketed `## [X.Y.Z] - 2026-06-12` (and the bare heading).
awk -v version="$VERSION" '
  $0 ~ "^## \\[?" version "\\]?( |$)" { found = 1; next }
  /^## / && found { exit }
  found { print }
' CHANGELOG.md | sed -e '1{/^$/d;}'
