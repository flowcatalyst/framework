#!/bin/sh
# Checks that the guide still matches the code. Run it from anywhere:
#
#   docs/guide/verify.sh
#
# For every page under docs/guide it checks that:
#   1. the page has a "<!-- types: A, B, C -->" comment;
#   2. every type named there is declared (class, interface, record or enum) somewhere under
#      framework-*/src (main and test, because the test-jars publish helpers such as PostgresFixture);
#   3. every relative link to a .md file resolves to a file, and when it carries an #anchor (or is a
#      bare #anchor within the page), the target page has a heading whose GitHub-style slug is that
#      anchor (lowercase, punctuation other than - and _ removed, spaces to hyphens, a repeated
#      heading gets -1, -2 ...; headings inside code fences are ignored);
#   4. the page contains no Liquid markers ("{{" or "{%"), which Jekyll would try to evaluate.
# Site checks: every page under docs/guide is listed in docs/_data/nav.yml, every nav.yml path
# exists, the layout and stylesheet exist, _config.yml has no theme dependency, and the layout and
# includes use only Liquid tags and filters that GitHub Pages' Jekyll accepts (no render, liquid,
# include_relative; filters from an allow-list).
# And that STATUS.md names a "Last verified against commit <hash>" that is an ancestor of HEAD.
#
# POSIX sh; needs only grep, find, sed, cut, tr, awk and git.

set -u

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
cd "$root" || exit 2

failures=0
fail() {
  printf 'FAIL: %s\n' "$1" >&2
  failures=$((failures + 1))
}

src_dirs=$(find . -maxdepth 2 -type d -path './framework-*/src' | sort)
if [ -z "$src_dirs" ]; then
  fail "no framework-*/src directories found under $root"
fi

pages=$(find docs/guide -type f -name '*.md' | sort)
if [ -z "$pages" ]; then
  fail "no pages found under docs/guide"
fi

# Prints the GitHub-style anchor of every heading in $1, one per line.
anchors_of() {
  awk '
    /^```/ { fence = !fence; next }
    fence { next }
    /^#+[ \t]/ {
      h = $0
      sub(/^#+[ \t]+/, "", h)
      sub(/[ \t#]+$/, "", h)
      h = tolower(h)
      gsub(/[^a-z0-9 _-]/, "", h)
      gsub(/ /, "-", h)
      n = seen[h]++
      if (n > 0) h = h "-" n
      print h
    }
  ' "$1"
}

declared() {
  # $1 = type name. True when some Java file declares it.
  # shellcheck disable=SC2086
  grep -rqE --include='*.java' \
    "(class|interface|record|enum)[[:space:]]+$1([^A-Za-z0-9_]|\$)" $src_dirs 2>/dev/null
}

checked_pages=0
checked_types=0

for page in $pages; do
  checked_pages=$((checked_pages + 1))

  line=$(grep -m1 -E '^<!--[[:space:]]*types:' "$page" || true)
  if [ -z "$line" ]; then
    fail "$page: no '<!-- types: ... -->' comment (list the public types the page documents)"
  else
    list=$(printf '%s\n' "$line" | sed -E 's/^<!--[[:space:]]*types:[[:space:]]*//; s/[[:space:]]*-->.*$//')
    if [ -z "$list" ]; then
      fail "$page: the types comment is empty"
    fi
    names=$(printf '%s' "$list" | tr ',' ' ')
    for name in $names; do
      checked_types=$((checked_types + 1))
      case $name in
        *[!A-Za-z0-9_]*)
          fail "$page: '$name' is not a simple type name"
          continue
          ;;
      esac
      if ! declared "$name"; then
        fail "$page: type '$name' is not declared under framework-*/src (renamed or removed? update the page)"
      fi
    done
  fi

  if grep -nE '\{\{|\{%' "$page" >/dev/null 2>&1; then
    fail "$page: contains '{{' or '{%', which Jekyll treats as Liquid; reword or wrap in raw tags"
  fi

  dir=$(dirname "$page")
  links=$(grep -oE '\]\([^)#[:space:]]+\.md(#[^)]*)?\)' "$page" 2>/dev/null \
    | sed -E 's/^\]\(//; s/\)$//; s/#.*$//' | sort -u)
  for link in $links; do
    case $link in
      http://* | https://* | mailto:*) continue ;;
    esac
    if [ ! -f "$dir/$link" ]; then
      fail "$page: link to '$link' does not resolve to a file"
    fi
  done

  # Anchors: "](file.md#anchor)" and "](#anchor)".
  anchor_links=$(grep -oE '\]\(([^)#[:space:]]*\.md)?#[^)[:space:]]+\)' "$page" 2>/dev/null \
    | sed -E 's/^\]\(//; s/\)$//' | sort -u)
  for al in $anchor_links; do
    case $al in
      http://* | https://* | mailto:*) continue ;;
    esac
    target=${al%%#*}
    anchor=${al#*#}
    if [ -z "$target" ]; then
      target_file=$page
    else
      target_file="$dir/$target"
    fi
    [ -f "$target_file" ] || continue # reported above
    if ! anchors_of "$target_file" | grep -qxF -- "$anchor"; then
      fail "$page: link '$al' points at a heading that does not exist in ${target:-this page}"
    fi
  done
done


# ---------------------------------------------------------------- site: navigation and layout
nav=docs/_data/nav.yml
if [ ! -f "$nav" ]; then
  fail "$nav is missing (the sidebar, previous/next and titles come from it)"
else
  nav_paths=$(grep -v '^[[:space:]]*#' "$nav" | grep -oE 'path:[[:space:]]*[^ ,}]+' | sed -E 's/^path:[[:space:]]*//')
  for p in $nav_paths; do
    if [ ! -f "docs/$p" ]; then
      fail "$nav: path '$p' does not exist under docs/"
    fi
  done
  for page in $pages; do
    rel=${page#docs/}
    if ! printf '%s\n' "$nav_paths" | grep -qxF "$rel"; then
      fail "$page: not listed in $nav (add one line: - { title: ..., path: $rel })"
    fi
  done
fi

for f in docs/_layouts/default.html docs/assets/css/docs.css; do
  [ -f "$f" ] || fail "$f is missing"
done
if grep -qE '^[[:space:]]*(theme|remote_theme):' docs/_config.yml; then
  fail "docs/_config.yml still names a theme; the custom layout replaces it"
fi
if ! grep -qE 'layout:[[:space:]]*default' docs/_config.yml; then
  fail "docs/_config.yml: defaults must set 'layout: default' for all pages"
fi

tpl_files=$(find docs/_layouts docs/_includes -type f -name '*.html' 2>/dev/null | sort)
allowed_tags=" assign capture endcapture comment endcomment for endfor if elsif else endif unless endunless include break continue case when endcase raw endraw "
allowed_filters=" relative_url absolute_url append prepend replace escape default strip_html strip split first last slice size join downcase upcase date date_to_xml where sort uniq truncate jsonify markdownify slugify "
for f in $tpl_files; do
  for tag in $(grep -oE '\{%-?[[:space:]]*[a-z_]+' "$f" | sed -E 's/^\{%-?[[:space:]]*//' | sort -u); do
    case "$allowed_tags" in
      *" $tag "*) ;;
      *) fail "$f: Liquid tag '$tag' is not in the GitHub Pages-safe list (render/liquid/include_relative are not supported)" ;;
    esac
  done
  for filt in $(grep -oE '\{[{%][^}]*[}%]\}' "$f" | grep -oE '[^|]\|[[:space:]]*[a-z_]+' | sed -E 's/^.\|[[:space:]]*//' | sort -u); do
    case "$allowed_filters" in
      *" $filt "*) ;;
      *) fail "$f: Liquid filter '$filt' is not in the allow-list in verify.sh (check Jekyll 3.x supports it, then add it)" ;;
    esac
  done
  if grep -nE "href=\"/|src=\"/" "$f" | grep -v relative_url >/dev/null 2>&1; then
    fail "$f: absolute-path href/src without relative_url (breaks on a project-pages URL)"
  fi
done

# STATUS.md: the commit it was last verified against must be an ancestor of HEAD.
status=docs/guide/STATUS.md
if [ ! -f "$status" ]; then
  fail "$status is missing"
else
  hash=$(grep -m1 -oE 'Last verified against commit [0-9a-f]{7,40}' "$status" | grep -oE '[0-9a-f]{7,40}$' || true)
  if [ -z "$hash" ]; then
    fail "$status: no 'Last verified against commit <hash>' line"
  elif ! git rev-parse --verify --quiet "$hash^{commit}" >/dev/null 2>&1; then
    fail "$status: commit $hash does not exist in this repository"
  elif ! git merge-base --is-ancestor "$hash" HEAD 2>/dev/null; then
    fail "$status: commit $hash is not an ancestor of HEAD"
  fi
fi

if [ "$failures" -gt 0 ]; then
  printf '\n%s problem(s) found. Fix the pages (and STATUS.md) in the same commit as the code change.\n' "$failures" >&2
  exit 1
fi

printf 'OK: %s pages, %s type names, STATUS.md commit %s is an ancestor of HEAD\n' \
  "$checked_pages" "$checked_types" "$hash"
