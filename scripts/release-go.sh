#!/usr/bin/env bash
# Cut a release of the Go framework's core module (go/), github.com/flowcatalyst/framework/go.
#
# Usage: scripts/release-go.sh <patch|minor|major|X.Y.Z[-suffix]>
#        (or: make -C go release-go BUMP=...)
#
# A Go module is versioned by its tag alone: github.com/flowcatalyst/framework/go at vX.Y.Z is
# the tag go/vX.Y.Z of this repository (the tag prefix is the module's directory). There is no
# VERSION file; the bump base is the highest plain go/vX.Y.Z tag, and with none the base is 0.0.0
# (the first release is cut with an explicit version: BUMP=0.1.0). The repository's own vX.Y.Z
# tags are the Java release and do not count. On confirm this tags HEAD and pushes main and the
# tag; consumers (the platform's push gateway) then
# `go get github.com/flowcatalyst/framework/go@vX.Y.Z`.
#
# The nested modules (go/ui, go/ui/fwui, go/flowcatalyst, go/tools) are released on their own and
# are not part of this tag; go/ui has its own script (release-go-ui.sh).
#
# Refused when: the tree is dirty, HEAD is not main, main is behind origin/main, go/go.mod
# declares another module path or has a replace directive (consumers ignore replace, so the
# published module would not build), go/go.mod requires one of the nested modules (the core must
# stand alone), or the module does not build or its tests fail.
#
# RELEASE_DRY_RUN=1 runs every check and prints the tag, without tagging or pushing.
set -euo pipefail

bump="${1:-}"
[ -n "$bump" ] || { echo "usage: scripts/release-go.sh <patch|minor|major|X.Y.Z[-suffix]>" >&2; exit 2; }

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

module="github.com/flowcatalyst/framework/go"
prefix="go"
gomod="go/go.mod"

if [ -n "$(git status --porcelain)" ]; then
	echo "✗ Working tree is dirty. Commit first." >&2
	git status --short
	exit 1
fi

branch="$(git rev-parse --abbrev-ref HEAD)"
if [ "$branch" != "main" ]; then
	echo "✗ On '$branch'; releases are cut from main." >&2
	exit 1
fi

git fetch -q origin main
if ! git merge-base --is-ancestor origin/main HEAD; then
	echo "✗ main is behind or has diverged from origin/main. Pull first." >&2
	exit 1
fi

have="$(awk '/^module[[:space:]]/ {print $2; exit}' "$gomod")"
if [ "$have" != "$module" ]; then
	echo "✗ $gomod declares '$have'; it must be '$module'." >&2
	exit 1
fi
if grep -Eq '^[[:space:]]*replace([[:space:]]|\()' "$gomod"; then
	echo "✗ $gomod has a replace directive; a consumer ignores it, so the published module would not build." >&2
	exit 1
fi
if grep -Eq 'github\.com/flowcatalyst/framework/go/' <(grep -v '^module' "$gomod"); then
	echo "✗ $gomod requires a nested framework module (go/ui, go/flowcatalyst, ...); the core must stand alone." >&2
	exit 1
fi

clean_re='^[0-9]+\.[0-9]+\.[0-9]+$'
current="$(git tag --list "$prefix/v*" \
	| sed "s|^$prefix/v||" \
	| awk -F. '/^[0-9]+\.[0-9]+\.[0-9]+$/ { printf "%010d%010d%010d %s\n", $1, $2, $3, $0 }' \
	| sort -r | awk 'NR==1 {print $2}')"
current="${current:-0.0.0}"

case "$bump" in
	patch) new="$(echo "$current" | awk -F. -v OFS=. '{$3++; print}')" ;;
	minor) new="$(echo "$current" | awk -F. -v OFS=. '{$2++; $3=0; print}')" ;;
	major) new="$(echo "$current" | awk -F. -v OFS=. '{$1++; $2=0; $3=0; print}')" ;;
	*)
		if [[ "$bump" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
			new="$bump"
		else
			echo "✗ '$bump' is not patch|minor|major|X.Y.Z[-suffix]" >&2
			exit 1
		fi
		;;
esac
[[ "$current" =~ $clean_re ]] || { echo "✗ cannot bump from '$current'" >&2; exit 1; }

tag="$prefix/v$new"
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
	echo "✗ tag $tag already exists." >&2
	exit 1
fi

last_tag=""
[ "$current" != "0.0.0" ] && last_tag="$prefix/v$current"
if [ -n "$last_tag" ] && git diff --quiet "$last_tag" HEAD -- go ':!go/ui' ':!go/flowcatalyst' ':!go/tools'; then
	echo "✗ the core module has not changed since $last_tag." >&2
	exit 1
fi

echo "» checks: the module builds and its tests pass"
(cd go && go build ./... && go test -count=1 ./...)

echo
echo "  module:  $module"
echo "  current: ${last_tag:-(none)}"
echo "  new:     $tag at $(git rev-parse --short HEAD)"
if [ -n "$last_tag" ]; then
	echo "  changes since $last_tag:"
	git log --oneline "$last_tag"..HEAD -- go ':!go/ui' ':!go/flowcatalyst' ':!go/tools' | sed 's/^/    /'
fi

if [ "${RELEASE_DRY_RUN:-}" = "1" ]; then
	echo "✓ dry run: nothing tagged or pushed."
	exit 0
fi

read -r -p "Tag $tag and push main and the tag? [y/N] " confirm || confirm="n"
case "$confirm" in
	y|Y|yes) ;;
	*) echo "aborted."; exit 1 ;;
esac

git tag -a "$tag" -m "go v$new"
git push origin HEAD:main "$tag"
echo "✓ $tag pushed. Consumers: go get $module@v$new"
