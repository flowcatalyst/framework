#!/bin/sh
# Copies the statements, scripts and migrations the Go framework shares with the Java framework
# into the Go packages that embed them (//go:embed cannot reach outside the module). The
# conformance tests (go test ./internal/conformance/) fail when a copy differs from its source.
# Run from go/ (make go-sync-shared). Each line: source (relative to the repository root), copy.
set -eu
root=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$root"
while read -r src dst; do
  [ -z "$src" ] && continue
  case $src in \#*) continue ;; esac
  mkdir -p "$(dirname "go/$dst")"
  cp "$src" "go/$dst"
  echo "copied $src -> go/$dst"
done < go/internal/conformance/shared-files.txt
