#!/bin/sh
# Checks that the guide still matches the code. Run it from anywhere:
#
#   docs/guide/verify.sh
#
# For every page under docs/guide it checks that:
#   1. the page has a "<!-- types: A, B, C -->" comment;
#   2. every type named there is declared (class, interface, record or enum) somewhere under
#      framework-*/src (main and test, because the test-jars publish helpers such as PostgresFixture);
#   3. every relative link to a .md file resolves to a file;
#   4. the page contains no Liquid markers ("{{" or "{%"), which Jekyll would try to evaluate.
# And that STATUS.md names a "Last verified against commit <hash>" that is an ancestor of HEAD.
#
# POSIX sh; needs only grep, find, sed, cut, tr and git.

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
