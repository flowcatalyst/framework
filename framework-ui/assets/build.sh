#!/bin/sh
# Rebuilds the module's static assets from the ui-kit sources:
#   src/main/resources/fw-ui/kit.css            Tailwind build of ui-kit/theme + this module's classes
#   src/main/resources/fw-ui/lucide-sprite.svg  ui-kit/icons/lucide-sprite.svg
# datastar.js (1.0.4) is vendored by hand: see THIRD-PARTY-NOTICES.md.
# Maven never runs this (no Node, no network in the build); run it when a template gains a class or the
# ui-kit theme changes, and commit the result. FixtureAssetsTest fails when kit.css lacks a class a template uses.
set -eu
cd "$(dirname "$0")/.."
OUT=src/main/resources/fw-ui
BIN=../ui-kit/.bin/tailwindcss
[ -x "$BIN" ] || (cd ../ui-kit && ./build.sh >/dev/null)
"$BIN" -i assets/kit.css -o "$OUT/kit.css" --minify
cp ../ui-kit/icons/lucide-sprite.svg "$OUT/lucide-sprite.svg"
echo "wrote $OUT/kit.css ($(wc -c < "$OUT/kit.css") bytes)"
