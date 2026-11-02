package assets

import (
	"crypto/sha256"
	"encoding/hex"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strings"
	"testing"
	"testing/fstest"
)

func load(t *testing.T, opts ...Option) *Assets {
	t.Helper()
	a, err := Load(opts...)
	if err != nil {
		t.Fatal(err)
	}
	return a
}

func sum(b []byte) string {
	s := sha256.Sum256(b)
	return hex.EncodeToString(s[:])
}

// The vendored third-party files are exactly the pinned ones.
func TestPinnedFiles(t *testing.T) {
	a := load(t)
	for name, want := range map[string]string{
		"datastar.js":       DatastarSHA256,
		"mermaid.min.js":    MermaidSHA256,
		"fc-code-editor.js": CodeEditorSHA256,
		"fc-diagram.js":     DiagramSHA256,
	} {
		f, ok := a.files[name]
		if !ok {
			t.Fatalf("%s not loaded", name)
		}
		if got := sum(f.body); got != want {
			t.Errorf("%s sha256 = %s, want %s (ui-kit/islands/README.md)", name, got, want)
		}
	}
	if !strings.HasPrefix(string(a.files["datastar.js"].body), "// Datastar v"+DatastarVersion) {
		t.Errorf("datastar.js header is not v%s", DatastarVersion)
	}
	// The contract names the Datastar pin literally.
	if DatastarSHA256 != "727844adfc825ee651fb93c544a2a739986f9a21820a94524b35f0cac470cf91" {
		t.Error("DatastarSHA256 differs from contract section 4 rule 2")
	}
}

func TestEveryThirdPartyFileTravelsWithItsNotice(t *testing.T) {
	a := load(t)
	have := map[string]bool{}
	for _, n := range a.Names() {
		have[n] = true
	}
	for _, name := range []string{
		"LICENSE-datastar.md", "LICENSE-lucide.txt", "LICENSE-mermaid.md", "LICENSE-codemirror.md", "KIT-THIRD-PARTY-NOTICES.md",
		"kit.css", "datastar.js", "lucide-sprite.svg", "fc-code-editor.js", "fc-diagram.js", "mermaid.min.js",
	} {
		if !have[name] {
			t.Errorf("%s is not served", name)
		}
	}
	if have["fc-webauthn.js"] {
		t.Error("fc-webauthn.js is platform code and must not be in the module")
	}
}

func TestURLScheme(t *testing.T) {
	a := load(t)
	h := sum(a.files["kit.css"].body)[:12]
	if got, want := a.URL("kit.css"), "/fw-ui/kit.css?v="+h; got != want {
		t.Errorf("URL = %q, want %q", got, want)
	}
	if got := a.SpriteURL(); got != "/fw-ui/lucide-sprite.svg" {
		t.Errorf("SpriteURL = %q", got)
	}
	if a.Prefix() != "/fw-ui/" {
		t.Errorf("Prefix = %q", a.Prefix())
	}
	b := load(t, WithPrefix("/ui/assets/"))
	if got := b.URL("datastar.js"); got != "/ui/assets/datastar.js?v="+DatastarSHA256[:12] {
		t.Errorf("URL with prefix = %q", got)
	}
	if got := b.SpriteURL(); got != "/ui/assets/lucide-sprite.svg" {
		t.Errorf("SpriteURL with prefix = %q", got)
	}
}

func TestURLPanicsOnUnknownName(t *testing.T) {
	defer func() {
		if recover() == nil {
			t.Fatal("URL(unknown) did not panic")
		}
	}()
	_ = load(t).URL("missing.css")
}

func TestInvalidPrefix(t *testing.T) {
	for _, p := range []string{"", "/", "fw-ui/", "/fw-ui", "/a b/", "/a?b/"} {
		if _, err := Load(WithPrefix(p)); err == nil {
			t.Errorf("prefix %q accepted", p)
		}
	}
}

func get(t *testing.T, a *Assets, method, target string, hdr map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, target, nil)
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	a.Handler().ServeHTTP(rec, req)
	return rec
}

func TestServing(t *testing.T) {
	a := load(t)
	f := a.files["kit.css"]
	wantTag := `"` + sum(f.body)[:32] + `"`

	rec := get(t, a, http.MethodGet, a.URL("kit.css"), nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	if got := rec.Header().Get("ETag"); got != wantTag {
		t.Errorf("ETag = %q, want %q", got, wantTag)
	}
	if got := rec.Header().Get("Cache-Control"); got != "public, max-age=31536000, immutable" {
		t.Errorf("Cache-Control with ?v = %q", got)
	}
	if got := rec.Header().Get("Content-Type"); got != "text/css; charset=utf-8" {
		t.Errorf("Content-Type = %q", got)
	}
	if rec.Body.String() != string(f.body) {
		t.Error("body differs from the file")
	}

	rec = get(t, a, http.MethodGet, "/fw-ui/kit.css", nil)
	if got := rec.Header().Get("Cache-Control"); got != "public, max-age=3600, must-revalidate" {
		t.Errorf("Cache-Control without ?v = %q", got)
	}

	for name, ctype := range map[string]string{
		"datastar.js": "text/javascript; charset=utf-8", "fc-code-editor.js": "text/javascript; charset=utf-8",
		"lucide-sprite.svg": "image/svg+xml", "LICENSE-datastar.md": "text/plain; charset=utf-8",
	} {
		if got := get(t, a, http.MethodGet, "/fw-ui/"+name, nil).Header().Get("Content-Type"); got != ctype {
			t.Errorf("%s: Content-Type = %q, want %q", name, got, ctype)
		}
	}

	head := get(t, a, http.MethodHead, a.URL("kit.css"), nil)
	if head.Code != http.StatusOK || head.Body.Len() != 0 || head.Header().Get("ETag") != wantTag {
		t.Errorf("HEAD: status %d, %d body bytes, ETag %q", head.Code, head.Body.Len(), head.Header().Get("ETag"))
	}
}

func TestConditionalRequests(t *testing.T) {
	a := load(t)
	tag := a.files["datastar.js"].etag
	for name, header := range map[string]string{
		"exact":       tag,
		"weak":        "W/" + tag,
		"star":        "*",
		"in a list":   `"nope", ` + tag + `, "other"`,
		"weak list":   `"a",W/` + tag,
		"with spaces": "  " + tag + "  ",
	} {
		rec := get(t, a, http.MethodGet, a.URL("datastar.js"), map[string]string{"If-None-Match": header})
		if rec.Code != http.StatusNotModified || rec.Body.Len() != 0 {
			t.Errorf("%s: status %d, %d body bytes", name, rec.Code, rec.Body.Len())
		}
		if rec.Header().Get("ETag") != tag || rec.Header().Get("Cache-Control") != "public, max-age=31536000, immutable" {
			t.Errorf("%s: 304 headers %v", name, rec.Header())
		}
	}
	for name, header := range map[string]string{"other tag": `"0000"`, "prefix of the tag": tag[:10] + `"`, "empty": ""} {
		if rec := get(t, a, http.MethodGet, a.URL("datastar.js"), map[string]string{"If-None-Match": header}); rec.Code != http.StatusOK {
			t.Errorf("%s: status %d, want 200", name, rec.Code)
		}
	}
}

func TestSpriteAlias(t *testing.T) {
	a := load(t)
	rec := get(t, a, http.MethodGet, SpriteAliasPath, nil)
	if rec.Code != http.StatusOK || rec.Header().Get("Content-Type") != "image/svg+xml" || rec.Body.String() != string(a.files[SpriteName].body) {
		t.Fatalf("alias: status %d, type %q", rec.Code, rec.Header().Get("Content-Type"))
	}
	if rec.Header().Get("ETag") != a.files[SpriteName].etag {
		t.Error("alias ETag differs from the sprite's")
	}
	// A prefix other than the default does not move the alias.
	b := load(t, WithPrefix("/ui/assets/"))
	if rec := get(t, b, http.MethodGet, SpriteAliasPath, nil); rec.Code != http.StatusOK {
		t.Errorf("alias with a custom prefix: status %d", rec.Code)
	}
	if rec := get(t, b, http.MethodGet, "/fw-ui/kit.css", nil); rec.Code != http.StatusNotFound {
		t.Errorf("default prefix with a custom one: status %d", rec.Code)
	}
}

func TestUnknownFilesAndMethods(t *testing.T) {
	a := load(t)
	for _, p := range []string{"/fw-ui/nope.css", "/fw-ui/", "/fw-ui/../assets.go", "/fw-ui/files/kit.css", "/fw-ui/sub/kit.css", "/other/kit.css", "/icons/other.svg"} {
		if rec := get(t, a, http.MethodGet, p, nil); rec.Code != http.StatusNotFound {
			t.Errorf("%s: status %d, want 404", p, rec.Code)
		}
	}
	rec := get(t, a, http.MethodPost, a.URL("kit.css"), nil)
	if rec.Code != http.StatusMethodNotAllowed || rec.Header().Get("Allow") != "GET, HEAD" {
		t.Errorf("POST: status %d, Allow %q", rec.Code, rec.Header().Get("Allow"))
	}
}

func TestAddedFiles(t *testing.T) {
	app := fstest.MapFS{"fc-webauthn.js": {Data: []byte("console.log('passkey')")}, "other.css": {Data: []byte("a{}")}}
	a := load(t, WithPrefix("/ui/assets/"), WithFiles(app, "fc-webauthn.js"))
	if got, want := a.URL("fc-webauthn.js"), "/ui/assets/fc-webauthn.js?v="+sum([]byte("console.log('passkey')"))[:12]; got != want {
		t.Errorf("URL = %q, want %q", got, want)
	}
	rec := get(t, a, http.MethodGet, "/ui/assets/fc-webauthn.js", nil)
	if rec.Code != http.StatusOK || rec.Body.String() != "console.log('passkey')" || rec.Header().Get("Content-Type") != "text/javascript; charset=utf-8" {
		t.Errorf("added file: status %d, type %q, body %q", rec.Code, rec.Header().Get("Content-Type"), rec.Body.String())
	}
	if !slices.Contains(a.Names(), "fc-webauthn.js") || slices.Contains(a.Names(), "other.css") {
		t.Errorf("Names = %v", a.Names())
	}

	for name, opt := range map[string]Option{
		"missing file":     WithFiles(app, "absent.js"),
		"duplicate name":   WithFiles(app, "kit.css"),
		"path in the name": WithFiles(fstest.MapFS{"a/b.js": {Data: []byte("x")}}, "a/b.js"),
		"empty name":       WithFiles(app, ""),
	} {
		if _, err := Load(opt); err == nil {
			t.Errorf("%s: Load succeeded", name)
		}
	}
}

func TestNamesAreSortedAndACopy(t *testing.T) {
	a := load(t)
	n := a.Names()
	if !sort.StringsAreSorted(n) || len(n) < 11 {
		t.Fatalf("Names = %v", n)
	}
	n[0] = "changed"
	if a.Names()[0] == "changed" {
		t.Error("Names returns the internal slice")
	}
}

func TestHasIcon(t *testing.T) {
	a := load(t)
	for _, name := range []string{"x", "search", "loader-circle", "triangle-alert"} {
		if !a.HasIcon(name) {
			t.Errorf("sprite lacks %q", name)
		}
	}
	for _, name := range []string{"", "nope", "i-x", "X"} {
		if a.HasIcon(name) {
			t.Errorf("sprite has %q", name)
		}
	}
}

// Every icon a kit component names (Icon: "x", IconProps{Name: "x"}) is in the
// sprite: a missing symbol renders as an empty box, silently (contract
// section 4 rule 9).
func TestEveryIconTheKitNamesIsInTheSprite(t *testing.T) {
	a := load(t)
	uses := regexp.MustCompile(`(?:[Ii]con: |IconProps\{Name: |Name: |orDefault\(p\.Icon, )"([a-z0-9-]+)"`)
	missing := map[string][]string{}
	seen := 0
	err := filepath.WalkDir(filepath.Join("..", "kit"), func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() || strings.HasSuffix(p, "_templ.go") || strings.HasSuffix(p, "_test.go") ||
			(!strings.HasSuffix(p, ".templ") && !strings.HasSuffix(p, ".go")) {
			return err
		}
		src, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		for _, m := range uses.FindAllStringSubmatch(string(src), -1) {
			seen++
			if !a.HasIcon(m[1]) {
				missing[m[1]] = append(missing[m[1]], p)
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if seen < 40 {
		t.Fatalf("found only %d icon references; the scan pattern is broken", seen)
	}
	if len(missing) > 0 {
		var names []string
		for n, files := range missing {
			names = append(names, n+" ("+strings.Join(files, ", ")+")")
		}
		sort.Strings(names)
		t.Fatalf("icons not in the sprite (add them to ui-kit/icons/sprite-icons.txt): %s", strings.Join(names, "; "))
	}
}
