#!/usr/bin/env bash
# Proves that an application can render pages with its own copy of the component kit (go/ui/kit).
#
# Usage: scripts/check-ui-kit-copy.sh        (or: make -C go ui-copy-check)
#
# In a temporary module example.org/ownlook (removed at the end) it
#   1. requires github.com/flowcatalyst/framework/go/ui with a replace to this checkout's go/ui, and
#      takes go/ui's requirements and go.sum as they are, so a warm module cache is enough (the check
#      runs offline; CI has network);
#   2. copies every non-test .go file of go/ui/kit (the generated _templ.go files included) to kit/,
#      then changes the copied look's stylesheet from kit.css to own.css and the copied Main wrapper's
#      class to own-main (it fails when either replacement matches nothing);
#   3. writes a test that renders documents with views.WithLook(ownkit.Look{}) and a shell component;
#   4. checks that none of the temporary module's own files import go/ui/kit; and
#   5. runs go vet and go test there.
#
# The module is not added to go.work or to the repository.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
ui="$repo_root/go/ui"
module="github.com/flowcatalyst/framework/go/ui"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail() { echo "ui-copy-check FAILED: $*" >&2; exit 1; }

# 1. the module. go/ui's go.mod gives the required versions; its own module line and tool directive go.
mkdir "$tmp/kit"
{
	echo "module example.org/ownlook"
	awk '/^module[[:space:]]/ {next} /^tool[[:space:]]/ {next} {print}' "$ui/go.mod"
	echo
	echo "require $module v0.0.0"
	echo "replace $module => $ui"
} > "$tmp/go.mod"
cp "$ui/go.sum" "$tmp/go.sum"

# 2. the copy of the kit.
for f in "$ui"/kit/*.go; do
	case "$f" in *_test.go) continue ;; esac
	cp "$f" "$tmp/kit/"
done
[ -f "$tmp/kit/look.go" ] && [ -f "$tmp/kit/look_templ.go" ] || fail "kit/look.go or kit/look_templ.go is missing from go/ui/kit"
grep -q 'return "kit.css"' "$tmp/kit/look.go" || fail "the copied look.go has no stylesheet name \"kit.css\" to change"
sed -i.bak 's/return "kit.css"/return "own.css"/' "$tmp/kit/look.go"
grep -q 'return "own.css"' "$tmp/kit/look.go" || fail "the stylesheet name was not changed"
grep -q 'mx-auto max-w-xl p-\[24px\]' "$tmp/kit/look_templ.go" || fail "the copied Main wrapper has no class string to change"
sed -i.bak 's/mx-auto max-w-xl p-\[24px\]/own-main/' "$tmp/kit/look_templ.go"
grep -q 'own-main' "$tmp/kit/look_templ.go" || fail "the Main wrapper's class was not changed"
rm -f "$tmp"/kit/*.bak

# 3. the test: an application that uses only its own kit.
cat > "$tmp/own_test.go" <<'GO'
package ownlook_test

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"

	"github.com/a-h/templ"

	"github.com/flowcatalyst/framework/go/ui/assets"
	"github.com/flowcatalyst/framework/go/ui/ds"
	"github.com/flowcatalyst/framework/go/ui/look"
	"github.com/flowcatalyst/framework/go/ui/views"

	ownkit "example.org/ownlook/kit"
)

func ownAssets(t *testing.T) *assets.Assets {
	t.Helper()
	a, err := assets.Load(assets.WithFiles(fstest.MapFS{"own.css": {Data: []byte("/* own */")}}, "own.css"))
	if err != nil {
		t.Fatal(err)
	}
	return a
}

// ownShell wraps the flash stack and the page in a shell of the application's own.
func ownShell(*http.Request) templ.Component {
	return templ.ComponentFunc(func(ctx context.Context, w io.Writer) error {
		if _, err := io.WriteString(w, `<div class="own-shell">`); err != nil {
			return err
		}
		if err := templ.GetChildren(ctx).Render(ctx, w); err != nil {
			return err
		}
		_, err := io.WriteString(w, `</div>`)
		return err
	})
}

func newViews(t *testing.T, opts ...views.Option) *views.Views {
	t.Helper()
	base := []views.Option{
		views.WithLook(ownkit.Look{}),
		views.WithFlashes(map[string]look.Flash{"saved": {Kind: look.FlashSuccess, Summary: "Saved."}}),
		views.WithPlainHTTPCookies(),
	}
	v, err := views.New(ownAssets(t), append(base, opts...)...)
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func page(t *testing.T, v *views.Views) string {
	t.Helper()
	req := httptest.NewRequest("GET", "/", http.NoBody)
	req.AddCookie(&http.Cookie{Name: views.FlashCookie, Value: "saved"})
	rec := httptest.NewRecorder()
	if err := v.Page(rec, req, "Home", ownkit.Raw("<i>page</i>")); err != nil {
		t.Fatal(err)
	}
	return rec.Body.String()
}

func TestTheDocumentIsTheCopys(t *testing.T) {
	body := page(t, newViews(t))
	if !strings.Contains(body, `href="/fw-ui/own.css?v=`) || strings.Contains(body, "kit.css") {
		t.Errorf("the document does not link own.css alone:\n%s", body)
	}
	if !strings.Contains(body, `<main class="own-main">`) || strings.Contains(body, "mx-auto") {
		t.Errorf("the bare document does not use the copy's main:\n%s", body)
	}
	if !strings.Contains(body, `class="fc-flash fc-flash-success"`) || !strings.Contains(body, "Saved.") {
		t.Errorf("the flash from the cookie is not rendered:\n%s", body)
	}
}

func TestAFlashPatchIsTheCopys(t *testing.T) {
	v := newViews(t)
	req := httptest.NewRequest("GET", "/", http.NoBody)
	req.Header.Set(ds.RequestHeader, "true")
	rec := httptest.NewRecorder()
	if err := v.Stream(rec, req, func(p *views.Patches) error {
		return p.Flash(look.FlashError, "Failed.", "")
	}); err != nil {
		t.Fatal(err)
	}
	if body := rec.Body.String(); !strings.Contains(body, "Failed.") || !strings.Contains(body, "fc-flash-error") {
		t.Errorf("the flash patch is not rendered:\n%s", body)
	}
}

func TestThePageIsInsideTheShellComponent(t *testing.T) {
	body := page(t, newViews(t, views.WithShellComponent(ownShell)))
	if !strings.Contains(body, `<div class="own-shell"><div id="flash-stack"`) || !strings.Contains(body, `<div id="fw-page"><i>page</i></div></div>`) {
		t.Errorf("the page is not inside the shell component:\n%s", body)
	}
	if strings.Contains(body, "own-main") {
		t.Error("a document with a shell has the bare main")
	}
}
GO
# The test renders the page content with ownkit.Raw, so the copy must have one.
grep -q '^func Raw(' "$tmp"/kit/*.go || fail "the copied kit has no Raw"

# 4. the temporary module's own files import the module's kit nowhere. A source check is enough: the
# views package imports the module's kit for its default look (that is the dependency's own business),
# so go list -deps would always name it; what an application controls is its own imports.
if grep -rn --include='*.go' "\"$module/kit\"" "$tmp"; then
	fail "a file of the temporary module imports $module/kit"
fi

# 5. vet and test, offline-capable: nothing is fetched when the module cache is warm.
export GOFLAGS=-mod=mod
export GOWORK=off
(cd "$tmp" && go vet ./... ) || fail "go vet failed in the temporary module"
(cd "$tmp" && go test -count=1 ./... ) || fail "go test failed in the temporary module"
echo "ui-copy-check: a copy of the kit under another import path renders pages (own.css, own-main, flash, shell)"
