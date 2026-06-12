#!/usr/bin/env bash
# Prints the CHANGELOG.md section for the given version (release notes).
set -euo pipefail

VERSION="${1:?usage: release-notes.sh <version>}"

awk -v version="$VERSION" '
  $0 ~ "^## " version " " || $0 == "## " version { found = 1; next }
  /^## / && found { exit }
  found { print }
' CHANGELOG.md | sed -e '1{/^$/d;}'
