#!/usr/bin/env bash
# Cut a release of the Go FlowCatalyst integration module (go/flowcatalyst).
#
# Usage: scripts/release-go-flowcatalyst.sh <patch|minor|major|X.Y.Z[-suffix]>
#        (or: make -C go release-go-flowcatalyst BUMP=...)
#
# A Go module is versioned by its tag alone: github.com/flowcatalyst/framework/go/flowcatalyst
# at vX.Y.Z is the tag go/flowcatalyst/vX.Y.Z of this repository. There is no VERSION file;
# the bump base is the highest plain go/flowcatalyst/vX.Y.Z tag.
#
# go.mod keeps `replace github.com/flowcatalyst/framework/go => ../` so the module builds
# against the core module in this checkout. A consumer ignores that replace and resolves the
# core module by the version go.mod requires, so this script checks the module the way a
# consumer sees it: the replace stripped (a temporary -modfile), the required core version
# must be a released go/vX.Y.Z tag that is an ancestor of HEAD, and the build, vet and tests
# must pass against it.
#
# Refused when: the tree is dirty, HEAD is not main, main is behind origin/main, go.mod
# declares another module path, requires an unreleased core version (v0.0.0 or untagged), or
# the consumer-view build or tests fail.
#
# RELEASE_DRY_RUN=1 runs every check and prints the tag, without tagging or pushing.
set -euo pipefail

bump="${1:-}"
[ -n "$bump" ] || { echo "usage: scripts/release-go-flowcatalyst.sh <patch|minor|major|X.Y.Z[-suffix]>" >&2; exit 2; }

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

module="github.com/flowcatalyst/framework/go/flowcatalyst"
core="github.com/flowcatalyst/framework/go"
prefix="go/flowcatalyst"
dir="go/flowcatalyst"
gomod="$dir/go.mod"

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
git fetch -q --tags origin
if ! git merge-base --is-ancestor origin/main HEAD; then
	echo "✗ main is behind or has diverged from origin/main. Pull first." >&2
	exit 1
fi

have="$(awk '/^module[[:space:]]/ {print $2; exit}' "$gomod")"
if [ "$have" != "$module" ]; then
	echo "✗ $gomod declares '$have'; it must be '$module'." >&2
	exit 1
fi

core_version="$(awk -v m="$core" '$1 == m && $2 ~ /^v/ {print $2; exit}' "$gomod")"
if [ -z "$core_version" ] || [ "$core_version" = "v0.0.0" ]; then
	echo "✗ $gomod requires $core ${core_version:-(nothing)}; it must require a released version." >&2
	exit 1
fi
core_tag="go/$core_version"
if ! git rev-parse -q --verify "refs/tags/$core_tag" >/dev/null; then
	echo "✗ $gomod requires $core $core_version, but there is no tag $core_tag." >&2
	exit 1
fi
if ! git merge-base --is-ancestor "$core_tag" HEAD; then
	echo "✗ $core_tag is not an ancestor of HEAD." >&2
	exit 1
fi
if ! git diff --quiet "$core_tag" HEAD -- go ':!go/flowcatalyst' ':!go/ui'; then
	echo "✗ the core module changed since $core_tag: release it first (make -C go release-go), then require the new version." >&2
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
if [ -n "$last_tag" ] && git diff --quiet "$last_tag" HEAD -- "$dir"; then
	echo "✗ $dir has not changed since $last_tag." >&2
	exit 1
fi

echo "» checks: build, vet and tests as a consumer sees the module ($core $core_version, no replace)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
grep -Ev '^[[:space:]]*replace[[:space:]]' "$gomod" > "$tmp/consumer.mod"
cp "$dir/go.sum" "$tmp/consumer.sum"
(cd "$dir" && GOWORK=off GOFLAGS=-mod=mod go build -modfile="$tmp/consumer.mod" ./... \
	&& GOWORK=off GOFLAGS=-mod=mod go vet -modfile="$tmp/consumer.mod" ./... \
	&& GOWORK=off GOFLAGS=-mod=mod go test -count=1 -modfile="$tmp/consumer.mod" ./...)

echo
echo "  module:  $module"
echo "  core:    $core $core_version"
echo "  current: ${last_tag:-(none)}"
echo "  new:     $tag at $(git rev-parse --short HEAD)"
if [ -n "$last_tag" ]; then
	echo "  changes since $last_tag:"
	git log --oneline "$last_tag"..HEAD -- "$dir" | sed 's/^/    /'
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

git tag -a "$tag" -m "go/flowcatalyst v$new"
git push origin HEAD:main "$tag"
echo "✓ $tag pushed. Consumers: go get $module@v$new"
