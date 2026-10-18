// Package spa serves one built single-page application next to the JSON API (static-assets
// contract, docs/contracts/static-assets-and-spa.md): an index built once at start-up from an
// fs.FS (usually //go:embed all:dist), served as the router's fallback for a path no route matches,
// with hashed assets immutable, index.html revalidated with an ETag, a JSON 404 for a missing asset
// or an unknown API path, and the security headers and Content-Security-Policy of section 6 and 7.
// The httpfw package mounts it (Routes.SPA); this package knows nothing of routes except the
// descriptions Mount is given.
package spa

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"path"
	"regexp"
	"slices"
	"strings"
)

// Default limits on what is indexed (contract section 3).
const (
	DefaultMaxFiles       = 10_000
	DefaultMaxBytes int64 = 64 << 20
)

// Options configure [New]. The zero value serves the SPA at "/" with Vite's "assets" directory,
// the default limits and the strict Content-Security-Policy.
type Options struct {
	// Prefix is where the SPA is mounted: "/" (default) or "/app". Outside it the fallback is the
	// framework's normal 404.
	Prefix string
	// APIPrefixes are extra API prefixes (one segment each) for paths served by something outside
	// the router. The first segment of every route, "api" and "auth" are API prefixes already.
	APIPrefixes []string
	// AssetsDirectory is the hashed directory (default "assets", Vite's build.assetsDir).
	AssetsDirectory string
	// MaxFiles and MaxBytes bound the index (defaults 10 000 files, 64 MiB); over either is an
	// error.
	MaxFiles int
	MaxBytes int64
	// CSP is the Content-Security-Policy of the SPA's responses. Without one, the default is
	// [Strict] with the script hashes of csp-hashes.json beside index.html when the build wrote
	// it (contract section 7), else [Strict] with none; a policy given here replaces the file.
	CSP CSP
}

type file struct {
	bytes       []byte
	contentType string
	etag        string
}

// SPA is the built index of a single-page application.
type SPA struct {
	source          string
	prefix          []string
	apiPrefixes     []string
	assetsDirectory string
	csp             CSP
	files           map[string]file
}

var segmentPattern = regexp.MustCompile(`^[A-Za-z0-9._~-]+$`)

func segment(s, what string) error {
	if !segmentPattern.MatchString(s) || strings.HasPrefix(s, ".") {
		return fmt.Errorf("%s must be one segment of [A-Za-z0-9._~-] not starting with '.': %q", what, s)
	}
	return nil
}

// New indexes every regular file under the root of fsys. Files and directories whose names start
// with "." (Vite's .vite manifest) are left out, symbolic links are not followed. A missing
// index.html is not an error here: [SPA.HasIndex] says so and Mount decides by environment.
func New(fsys fs.FS, opts Options) (*SPA, error) {
	s := &SPA{files: map[string]file{}, assetsDirectory: opts.AssetsDirectory, csp: opts.CSP, source: "the SPA's file system"}
	if s.assetsDirectory == "" {
		s.assetsDirectory = "assets"
	}
	if err := segment(s.assetsDirectory, "the assets directory"); err != nil {
		return nil, err
	}
	switch p := opts.Prefix; {
	case p == "" || p == "/":
	case !strings.HasPrefix(p, "/"):
		return nil, fmt.Errorf("an SPA prefix starts with '/': %q", p)
	default:
		for part := range strings.SplitSeq(p[1:], "/") {
			if err := segment(part, "an SPA prefix segment"); err != nil {
				return nil, err
			}
			s.prefix = append(s.prefix, part)
		}
	}
	for _, a := range opts.APIPrefixes {
		if err := segment(a, "an API prefix"); err != nil {
			return nil, err
		}
		if !slices.Contains(s.apiPrefixes, a) {
			s.apiPrefixes = append(s.apiPrefixes, a)
		}
	}
	maxFiles, maxBytes := opts.MaxFiles, opts.MaxBytes
	if maxFiles < 0 || maxBytes < 0 {
		return nil, errors.New("MaxFiles and MaxBytes must not be negative")
	}
	if maxFiles == 0 {
		maxFiles = DefaultMaxFiles
	}
	if maxBytes == 0 {
		maxBytes = DefaultMaxBytes
	}
	if err := s.load(fsys, maxFiles, maxBytes); err != nil {
		return nil, err
	}
	// never served, whether or not it is used
	hashes, hasHashes := s.files[hashesFile]
	delete(s.files, hashesFile)
	if s.csp.value == "" {
		s.csp = Strict()
		if hasHashes {
			scriptHashes, err := parseHashes(hashes.bytes)
			if err != nil {
				return nil, fmt.Errorf("cannot use %s (%w); regenerate it with the web build", hashesFile, err)
			}
			s.csp = Strict(scriptHashes...)
		}
	}
	return s, nil
}

// hashesFile is written by the flowcatalystUi() Vite plugin beside index.html: {"scriptHashes":
// ["sha256-..."]}, one per inline script of the build.
const hashesFile = "csp-hashes.json"

func parseHashes(content []byte) ([]string, error) {
	var doc struct {
		ScriptHashes *[]string `json:"scriptHashes"`
	}
	if err := json.Unmarshal(content, &doc); err != nil {
		return nil, fmt.Errorf("it must be {\"scriptHashes\": [\"sha256-...\"]}: %w", err)
	}
	if doc.ScriptHashes == nil {
		return nil, errors.New("it must be {\"scriptHashes\": [\"sha256-...\"]}")
	}
	for _, h := range *doc.ScriptHashes {
		if !hashPattern.MatchString(h) {
			return nil, fmt.Errorf("%q is not a sha256-, sha384- or sha512- hash", h)
		}
	}
	return *doc.ScriptHashes, nil
}

func (s *SPA) load(fsys fs.FS, maxFiles int, maxBytes int64) error {
	var total int64
	err := fs.WalkDir(fsys, ".", func(name string, d fs.DirEntry, err error) error {
		if err != nil {
			if name == "." && errors.Is(err, fs.ErrNotExist) {
				return fs.SkipAll // not built
			}
			return err
		}
		if name == "." {
			return nil
		}
		if strings.HasPrefix(d.Name(), ".") {
			if d.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		if !d.Type().IsRegular() {
			return nil // directories are walked, links and devices are not indexed
		}
		if len(s.files) >= maxFiles {
			return fmt.Errorf("the SPA has more than %d files (Options.MaxFiles)", maxFiles)
		}
		f, err := fsys.Open(name)
		if err != nil {
			return err
		}
		defer func() { _ = f.Close() }()
		room := maxBytes - total
		content, err := io.ReadAll(io.LimitReader(f, room+1))
		if err != nil {
			return err
		}
		if int64(len(content)) > room {
			return fmt.Errorf("the SPA has more than %d bytes (Options.MaxBytes)", maxBytes)
		}
		total += int64(len(content))
		s.files[path.Clean(name)] = file{bytes: content, contentType: contentType(name), etag: etag(content)}
		return nil
	})
	if err != nil {
		return fmt.Errorf("cannot read the SPA: %w", err)
	}
	return nil
}

// etag is the strong ETag: a quote, the first 16 bytes of SHA-256 as lowercase hex, a quote.
func etag(content []byte) string {
	sum := sha256.Sum256(content)
	return `"` + hex.EncodeToString(sum[:16]) + `"`
}

// HasIndex reports whether index.html was found.
func (s *SPA) HasIndex() bool { _, ok := s.files["index.html"]; return ok }

// Files returns the number of indexed files.
func (s *SPA) Files() int { return len(s.files) }

// PrefixText is the mount prefix: "/" or "/app".
func (s *SPA) PrefixText() string { return "/" + strings.Join(s.prefix, "/") }

// contentTypes is the fixed table of section 6.2 (the platforms' MIME tables differ, so it is
// written out).
var contentTypes = map[string]string{
	".html": "text/html; charset=utf-8", ".htm": "text/html; charset=utf-8",
	".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8", ".cjs": "text/javascript; charset=utf-8",
	".css":  "text/css; charset=utf-8",
	".json": "application/json", ".map": "application/json",
	".webmanifest": "application/manifest+json",
	".txt":         "text/plain; charset=utf-8",
	".xml":         "application/xml",
	".wasm":        "application/wasm",
	".svg":         "image/svg+xml",
	".png":         "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp",
	".avif": "image/avif", ".bmp": "image/bmp", ".ico": "image/x-icon",
	".woff": "font/woff", ".woff2": "font/woff2", ".ttf": "font/ttf", ".otf": "font/otf",
	".eot": "application/vnd.ms-fontobject",
}

func extension(name string) string {
	slash, dot := strings.LastIndexByte(name, '/'), strings.LastIndexByte(name, '.')
	if dot <= slash {
		return ""
	}
	return strings.ToLower(name[dot:])
}

func contentType(name string) string {
	if t, ok := contentTypes[extension(name)]; ok {
		return t
	}
	return "application/octet-stream"
}

// isAssetExtension is the allow-list of section 4.2, never "has a dot": client routes carry
// identifiers that may contain dots.
func isAssetExtension(segment string) bool {
	_, ok := contentTypes[extension(segment)]
	return ok
}
