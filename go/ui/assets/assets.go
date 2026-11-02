// Package assets serves the server-rendered UI's static files from an
// instance: the kit stylesheet (the Tailwind build of ui-kit/theme), the
// pinned Datastar bundle, the Lucide icon sprite, the islands (the code editor,
// the diagram and Mermaid) and the licence notices they travel with. The
// files are the Java module's (framework-ui/src/main/resources/fw-ui), copied
// into this module by go/internal/conformance/sync-shared.sh; nothing is loaded
// from a CDN. Contract: docs/contracts/server-rendered-ui.md section 4.
//
//	a, err := assets.Load()                          // prefix "/fw-ui/"
//	a, err := assets.Load(assets.WithPrefix("/ui/assets/"),
//		assets.WithFiles(os.DirFS("static"), "fc-webauthn.js")) // an application's own file
//
//	a.URL("kit.css")     // "/fw-ui/kit.css?v=0123456789ab" for <link>/<script>
//	a.SpriteURL()        // "/fw-ui/lucide-sprite.svg" for <use href>
//	mux.Handle(a.Prefix(), a.Handler())
//	mux.Handle(assets.SpriteAliasPath, a.Handler())
//
// Every file is read once, by Load, which fails when one is missing.
package assets

import (
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"mime"
	"net/http"
	"path"
	"regexp"
	"slices"
	"strings"
)

// DefaultPrefix is the URL path the files are served under unless
// [WithPrefix] says otherwise.
const DefaultPrefix = "/fw-ui/"

// SpriteAliasPath is the path the sprite also answers at: the one the specs
// and fixtures use in `<use href>`.
const SpriteAliasPath = "/icons/lucide-sprite.svg"

// SpriteName is the logical name of the icon sprite.
const SpriteName = "lucide-sprite.svg"

// DatastarVersion is the vendored Datastar release.
const DatastarVersion = "1.0.4"

// DatastarSHA256 is the sha256 of datastar.js as published at
// https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.4/bundles/datastar.js.
// A test fails if the embedded file differs.
const DatastarSHA256 = "727844adfc825ee651fb93c544a2a739986f9a21820a94524b35f0cac470cf91"

// MermaidVersion is the vendored Mermaid release: dist/mermaid.min.js of the
// npm package mermaid@MermaidVersion, unmodified.
const MermaidVersion = "12.1.0"

// MermaidSHA256 is the sha256 of mermaid.min.js. A test fails if the embedded
// file differs.
const MermaidSHA256 = "6484afc32872a3aa16cac9a76ba1816a1ed4cc870a6593cc2e17757750f518b2"

// CodeEditorSHA256 is the sha256 of fc-code-editor.js as built by
// ui-kit/islands/codemirror (reproducible for the pinned versions). A test
// fails on a hand edit or an unrecorded rebuild.
const CodeEditorSHA256 = "74b59f1db8170b490d4a76140dd2c8318d62581bb95362c1563da295f99ca49f"

// DiagramSHA256 is the sha256 of fc-diagram.js (hand-written, no build).
const DiagramSHA256 = "49aecf56c42fe89a9d28d1b9f4d727be09643611568d7f4978a5cc0b03be94d8"

//go:embed files
var embedded embed.FS

const embeddedDir = "files"

type file struct {
	body    []byte
	ctype   string
	etag    string // quoted, the first 32 hex of the sha256
	version string // the first 12 hex
}

// Assets is one set of served files. It is immutable after [Load] and safe for
// concurrent use.
type Assets struct {
	prefix string
	files  map[string]file
	names  []string
	icons  map[string]bool
}

// Option adjusts [Load].
type Option func(*config) error

type extra struct {
	fsys  fs.FS
	names []string
}

type config struct {
	prefix string
	extras []extra
}

// WithPrefix sets the URL path the files are served under. It must start and
// end with "/" (default [DefaultPrefix]); an application mounted elsewhere
// (the platform: "/ui/assets/") keeps its paths with it.
func WithPrefix(prefix string) Option {
	return func(c *config) error {
		if len(prefix) < 2 || !strings.HasPrefix(prefix, "/") || !strings.HasSuffix(prefix, "/") || strings.ContainsAny(prefix, "?#\\ ") {
			return fmt.Errorf("assets: prefix %q must start and end with / (for example /fw-ui/)", prefix)
		}
		c.prefix = prefix
		return nil
	}
}

// WithFiles adds application files: each name is read from the root of fsys,
// served and hashed like the built-in files, and listed by [Assets.Names]. A
// missing file, a name with a path separator and a name that a built-in file
// already has are errors of [Load].
func WithFiles(fsys fs.FS, names ...string) Option {
	return func(c *config) error {
		c.extras = append(c.extras, extra{fsys: fsys, names: names})
		return nil
	}
}

// Load reads and hashes every file. It fails when an embedded or an added file
// is missing, and for an invalid option.
func Load(opts ...Option) (*Assets, error) {
	c := config{prefix: DefaultPrefix}
	for _, o := range opts {
		if err := o(&c); err != nil {
			return nil, err
		}
	}
	a := &Assets{prefix: c.prefix, files: map[string]file{}, icons: map[string]bool{}}

	entries, err := fs.ReadDir(embedded, embeddedDir)
	if err != nil {
		return nil, fmt.Errorf("assets: read embedded files: %w", err)
	}
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		body, err := fs.ReadFile(embedded, path.Join(embeddedDir, e.Name()))
		if err != nil {
			return nil, fmt.Errorf("assets: read %s: %w", e.Name(), err)
		}
		a.add(e.Name(), body)
	}
	for _, name := range required {
		if _, ok := a.files[name]; !ok {
			return nil, fmt.Errorf("assets: file missing from the module: %s", name)
		}
	}

	for _, x := range c.extras {
		for _, name := range x.names {
			if name == "" || strings.ContainsAny(name, "/\\") || name == "." || name == ".." {
				return nil, fmt.Errorf("assets: added file name %q must be a plain file name", name)
			}
			if _, dup := a.files[name]; dup {
				return nil, fmt.Errorf("assets: added file %s has the name of a file that is already served", name)
			}
			body, err := fs.ReadFile(x.fsys, name)
			if err != nil {
				return nil, fmt.Errorf("assets: added file %s: %w", name, err)
			}
			a.add(name, body)
		}
	}

	for name := range a.files {
		a.names = append(a.names, name)
	}
	slices.Sort(a.names)
	for _, m := range symbolID.FindAllSubmatch(a.files[SpriteName].body, -1) {
		a.icons[string(m[1])] = true
	}
	if len(a.icons) == 0 {
		return nil, errors.New("assets: the icon sprite has no symbols")
	}
	return a, nil
}

// required are the files the module cannot work without.
var required = []string{"kit.css", "datastar.js", SpriteName, "fc-code-editor.js", "fc-diagram.js", "mermaid.min.js"}

var symbolID = regexp.MustCompile(`<symbol id="i-([a-z0-9-]+)"`)

func (a *Assets) add(name string, body []byte) {
	sum := sha256.Sum256(body)
	h := hex.EncodeToString(sum[:])
	a.files[name] = file{body: body, ctype: contentType(path.Ext(name)), etag: `"` + h[:32] + `"`, version: h[:12]}
}

func contentType(ext string) string {
	switch ext {
	case ".css":
		return "text/css; charset=utf-8"
	case ".js":
		return "text/javascript; charset=utf-8"
	case ".svg":
		return "image/svg+xml"
	case ".md", ".txt":
		return "text/plain; charset=utf-8"
	}
	if t := mime.TypeByExtension(ext); t != "" {
		return t
	}
	return "application/octet-stream"
}

// Prefix is the URL path the files are served under (with both slashes).
func (a *Assets) Prefix() string { return a.prefix }

// Names lists the served file names, sorted.
func (a *Assets) Names() []string { return slices.Clone(a.names) }

// URL is the URL of a file for a `<link>` or `<script>`: the prefix, the name
// and `?v=` with the first 12 hex digits of the file's sha256, so a changed
// file has a new URL and the old one can be cached for a year. An unknown name
// is a programming error and panics, so a typo fails the first render.
func (a *Assets) URL(name string) string {
	f, ok := a.files[name]
	if !ok {
		panic("assets: unknown asset " + name)
	}
	return a.prefix + name + "?v=" + f.version
}

// SpriteURL is the sprite URL icons reference (`<use href="URL#i-name">`):
// the prefix and the file name, without `?v=` (the fragment must follow the
// path). Put it in the kit's context with kit.WithSpriteURL.
func (a *Assets) SpriteURL() string { return a.prefix + SpriteName }

// HasIcon reports whether the sprite has the Lucide icon name (the symbol
// "i-"+name). Tests use it to check every icon a page names.
func (a *Assets) HasIcon(name string) bool { return a.icons[name] }

// Handler serves GET and HEAD of prefix+name and of [SpriteAliasPath]; any
// other path is 404, any other method 405. Mount it at the prefix and at
// the alias path (or at "/"). Answers carry the file's `ETag` (a quoted
// 32-digit hex prefix of its sha256); `If-None-Match` matching `*`, the tag or
// `W/` + the tag (in a comma-separated list) gets 304 with the `ETag` and
// `Cache-Control` and no body. `Cache-Control` is `public, max-age=31536000,
// immutable` when the request has a `v` query parameter and
// `public, max-age=3600, must-revalidate` without it.
func (a *Assets) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		name, ok := a.nameOf(r.URL.Path)
		f, found := a.files[name]
		if !ok || !found {
			http.NotFound(w, r)
			return
		}
		cache := "public, max-age=3600, must-revalidate"
		if r.URL.Query().Has("v") {
			cache = "public, max-age=31536000, immutable"
		}
		h := w.Header()
		h.Set("ETag", f.etag)
		h.Set("Cache-Control", cache)
		if matches(r.Header.Get("If-None-Match"), f.etag) {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		h.Set("Content-Type", f.ctype)
		h.Set("Content-Length", fmt.Sprint(len(f.body)))
		if r.Method == http.MethodHead {
			return
		}
		_, _ = w.Write(f.body)
	})
}

// nameOf maps a request path to a file name: prefix+name, or the sprite alias.
func (a *Assets) nameOf(p string) (string, bool) {
	if p == SpriteAliasPath {
		return SpriteName, true
	}
	name, ok := strings.CutPrefix(p, a.prefix)
	if !ok || name == "" || strings.Contains(name, "/") {
		return "", false
	}
	return name, true
}

// matches reports whether an If-None-Match header names the etag: `*`, the
// tag, or `W/` + the tag, in a comma-separated list.
func matches(header, etag string) bool {
	for part := range strings.SplitSeq(header, ",") {
		t := strings.TrimSpace(part)
		if t == "*" || t == etag || t == "W/"+etag {
			return true
		}
	}
	return false
}
