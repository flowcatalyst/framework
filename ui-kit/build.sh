#!/bin/sh
# Build dist/kit.css with the Tailwind standalone CLI (no Node, no npm).
#   ./build.sh          one-off minified build
#   ./build.sh --watch  rebuild on change
# The binary is downloaded into .bin/ on first use (macOS/Linux, x64/arm64).
set -eu
cd "$(dirname "$0")"
TAILWIND_VERSION="${TAILWIND_VERSION:-v4.3.2}"   # same line fc-web builds with
BIN=".bin/tailwindcss"
if [ ! -x "$BIN" ]; then
  case "$(uname -s)-$(uname -m)" in
    Darwin-arm64)  asset=tailwindcss-macos-arm64 ;;
    Darwin-x86_64) asset=tailwindcss-macos-x64 ;;
    Linux-aarch64|Linux-arm64) asset=tailwindcss-linux-arm64 ;;
    Linux-x86_64)  asset=tailwindcss-linux-x64 ;;
    *) echo "unsupported platform $(uname -s)-$(uname -m)" >&2; exit 1 ;;
  esac
  mkdir -p .bin
  echo "downloading tailwindcss $TAILWIND_VERSION ($asset)"
  curl -fsSL -o "$BIN" "https://github.com/tailwindlabs/tailwindcss/releases/download/$TAILWIND_VERSION/$asset"
  chmod +x "$BIN"
fi
mkdir -p dist
if [ "${1:-}" = "--watch" ]; then
  exec "$BIN" -i theme/kit.css -o dist/kit.css --watch
fi
exec "$BIN" -i theme/kit.css -o dist/kit.css --minify
