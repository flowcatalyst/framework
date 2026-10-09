#!/bin/sh
# Build icons/lucide-sprite.svg from the pinned lucide-static release.
#   ./icons/build-sprite.sh        rebuild the sprite from icons/sprite-icons.txt
# The npm tarball is downloaded into .bin/ on first use and checked against its
# sha1 (the registry's `dist.shasum`). No Node, no npm: curl, tar, sed, awk.
# Each icon becomes <symbol id="i-{name}"> with Lucide's viewBox and stroke
# attributes and the icon's child elements joined by single spaces.
set -eu
cd "$(dirname "$0")/.."
LUCIDE_VERSION="1.52.0"
LUCIDE_SHA1="ed1a4f3e64f06e30a50d7898fa1f7a4c33545218"
TGZ=".bin/lucide-static-$LUCIDE_VERSION.tgz"
SRC=".bin/lucide-static-$LUCIDE_VERSION"
LIST="icons/sprite-icons.txt"
OUT="icons/lucide-sprite.svg"

if [ ! -d "$SRC" ]; then
  mkdir -p .bin
  if [ ! -f "$TGZ" ]; then
    echo "downloading lucide-static $LUCIDE_VERSION"
    curl -fsSL -o "$TGZ" "https://registry.npmjs.org/lucide-static/-/lucide-static-$LUCIDE_VERSION.tgz"
  fi
  got=$(shasum -a 1 "$TGZ" | awk '{print $1}')
  if [ "$got" != "$LUCIDE_SHA1" ]; then
    echo "lucide-static tarball sha1 $got, want $LUCIDE_SHA1" >&2
    rm -f "$TGZ"
    exit 1
  fi
  mkdir -p "$SRC"
  tar -xzf "$TGZ" -C "$SRC" --strip-components=1 package/icons package/LICENSE
fi

tmp="$OUT.tmp"
{
  echo '<svg xmlns="http://www.w3.org/2000/svg" style="display:none" aria-hidden="true">'
  # One name per line; blank lines and # comments ignored; sorted, unique.
  sed -e 's/#.*//' -e 's/[[:space:]]//g' "$LIST" | grep -v '^$' | LC_ALL=C sort -u | while read -r name; do
    svg="$SRC/icons/$name.svg"
    if [ ! -f "$svg" ]; then
      echo "unknown Lucide icon: $name" >&2
      exit 1
    fi
    # The children are the lines between the end of the <svg ...> start tag
    # (a line holding only ">") and </svg>, trimmed and joined by one space.
    body=$(awk 'inside && /^<\/svg>/ {exit} inside {sub(/^[ \t]+/, ""); sub(/[ \t]+$/, ""); printf "%s%s", sep, $0; sep=" "} /^>$/ {inside=1}' "$svg")
    if [ -z "$body" ]; then
      echo "could not read the paths of $svg" >&2
      exit 1
    fi
    printf '<symbol id="i-%s" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">%s</symbol>\n' "$name" "$body"
  done
  echo '</svg>'
} > "$tmp"
mv "$tmp" "$OUT"
cp "$SRC/LICENSE" icons/LICENSE-lucide.txt
echo "$OUT: $(grep -c '<symbol ' "$OUT") icons from lucide-static $LUCIDE_VERSION"
