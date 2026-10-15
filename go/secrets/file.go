package secrets

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"unicode/utf8"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
)

var fileClientPattern = regexp.MustCompile(`^[A-Za-z0-9_-][A-Za-z0-9._-]*$`)

// FileFactory makes the file store: see [File].
type FileFactory struct {
	directory string
	maxBytes  int
	cache     CachePolicy
}

// File is the store that reads files: directory/<client>/<name>. The directory is an absolute
// path to an existing directory (checked when [Build] runs). The client matches
// [A-Za-z0-9_-][A-Za-z0-9._-]* (no leading dot, so never "." or ".."; else Refused). The path is
// resolved to its real path (symbolic links followed, so Kubernetes' ..data layout works); the
// real path must lie under the directory's real path and end in exactly <client>/<name> (so
// "ACME" cannot read acme's file on a case-insensitive file system); otherwise NotFound, with a
// warning that does not name the link's target. It must be a regular file of at most MaxBytes
// bytes (default 65 536, 1 to 1 048 576), read as strict UTF-8; one trailing "\n" or "\r\n" is
// removed; an empty file is NotFound. A file readable by every local user is logged at WARN once
// per file, not refused (container secret mounts are often mode 0644 inside a private mount
// namespace).
func File(directory string) FileFactory {
	return FileFactory{directory: directory, maxBytes: 65_536, cache: DefaultCache()}
}

// WithMaxBytes returns a copy with another file size limit.
func (f FileFactory) WithMaxBytes(n int) FileFactory { f.maxBytes = n; return f }

// WithCache returns a copy with another cache policy.
func (f FileFactory) WithCache(p CachePolicy) FileFactory { f.cache = p; return f }

// Create implements [StoreFactory].
func (f FileFactory) Create(_ context.Context, name string, b *fw.Boot) (Store, error) {
	if !filepath.IsAbs(f.directory) {
		return nil, fmt.Errorf("directory must be an absolute path, was %s", f.directory)
	}
	if f.maxBytes < 1 || f.maxBytes > 1_048_576 {
		return nil, fmt.Errorf("maxBytes must be 1 to 1048576, was %d", f.maxBytes)
	}
	if err := f.cache.Validate(); err != nil {
		return nil, err
	}
	st, err := os.Stat(f.directory)
	if err != nil || !st.IsDir() {
		return nil, fmt.Errorf("directory %s is not a directory", f.directory)
	}
	root, err := filepath.EvalSymlinks(f.directory)
	if err != nil {
		return nil, errors.New("directory cannot be resolved")
	}
	s := &fileStore{instance: name, root: root, maxBytes: f.maxBytes, log: b.Logger(ModuleID), warned: map[string]bool{}}
	return withCache(s, f.cache, b.Clock()), nil
}

type fileStore struct {
	instance string
	root     string
	maxBytes int
	log      *slog.Logger

	mu     sync.Mutex
	warned map[string]bool
}

func (s *fileStore) Find(_ context.Context, client fw.ClientID, name string) (Result, error) {
	if err := checkName(name); err != nil {
		return nil, fmt.Errorf("%w: %w", ErrInvalidName, err)
	}
	c := client.String()
	if !fileClientPattern.MatchString(c) {
		return Refused{Reason: "secret store '" + s.instance + "' (file) cannot hold a client id starting with a dot"}, nil
	}
	resolved, err := filepath.EvalSymlinks(filepath.Join(s.root, c, name))
	switch {
	case errors.Is(err, fs.ErrNotExist):
		return NotFound{}, nil
	case err != nil:
		return nil, s.failure("cannot resolve", c, name, err)
	}
	if !strings.HasPrefix(resolved, s.root+string(filepath.Separator)) || filepath.Base(resolved) != name || filepath.Base(filepath.Dir(resolved)) != c {
		s.log.Warn(fmt.Sprintf("secret store '%s': secret %s of client %s resolves outside the directory or to another name; ignored", s.instance, name, c))
		return NotFound{}, nil
	}
	if !exactCase(filepath.Dir(filepath.Dir(resolved)), c) || !exactCase(filepath.Dir(resolved), name) {
		s.log.Warn(fmt.Sprintf("secret store '%s': secret %s of client %s differs in case from the file system's entry; ignored", s.instance, name, c))
		return NotFound{}, nil
	}
	f, err := os.Open(resolved)
	if errors.Is(err, fs.ErrNotExist) {
		return NotFound{}, nil
	}
	if err != nil {
		return nil, s.failure("cannot read", c, name, err)
	}
	defer func() { _ = f.Close() }()
	st, err := f.Stat()
	if err != nil {
		return nil, s.failure("cannot read", c, name, err)
	}
	if !st.Mode().IsRegular() {
		return NotFound{}, nil
	}
	s.warnIfReadableByOthers(resolved, st.Mode(), c, name)
	data, err := io.ReadAll(io.LimitReader(f, int64(s.maxBytes)+1))
	if err != nil {
		return nil, s.failure("cannot read", c, name, err)
	}
	if len(data) > s.maxBytes {
		return nil, &StoreError{msg: s.describe(fmt.Sprintf("is larger than maxBytes (%d)", s.maxBytes), c, name)}
	}
	if !utf8.Valid(data) {
		// no cause: its message could describe the content
		return nil, &StoreError{msg: s.describe("is not valid UTF-8", c, name)}
	}
	text := string(data)
	if t, ok := strings.CutSuffix(text, "\r\n"); ok {
		text = t
	} else if t, ok := strings.CutSuffix(text, "\n"); ok {
		text = t
	}
	if text == "" {
		return NotFound{}, nil
	}
	return Found{Secret: config.NewSecret(text)}, nil
}

func (s *fileStore) warnIfReadableByOthers(real string, mode fs.FileMode, client, name string) {
	if mode.Perm()&0o004 == 0 {
		return
	}
	s.mu.Lock()
	first := !s.warned[real]
	s.warned[real] = true
	s.mu.Unlock()
	if first {
		s.log.Warn(fmt.Sprintf("secret store '%s': the file of secret %s of client %s is readable by every local user", s.instance, name, client))
	}
}

// failure is an unreadable file; the I/O error's text names the path (client and name only, both
// validated), never content.
func (s *fileStore) failure(what, client, name string, cause error) error {
	kind := "I/O error"
	if pe, ok := errors.AsType[*fs.PathError](cause); ok {
		kind = pe.Err.Error()
	}
	return &StoreError{msg: s.describe(what+" ("+kind+")", client, name), cause: cause}
}

func (s *fileStore) describe(what, client, name string) string {
	return "secret store '" + s.instance + "' (file): secret " + name + " of client " + client + " " + what
}

// exactCase reports whether base is spelt in dir exactly as the file system spells it. A case
// insensitive file system opens ACME/x for acme/x and Go's EvalSymlinks keeps the case it was
// given, so the real-path rule (the last two elements equal the client and the name exactly)
// needs this: when the case-swapped spelling is the same file, the directory's entries decide.
func exactCase(dir, base string) bool {
	alt := swapCase(base)
	if alt == base {
		return true
	}
	a, errA := os.Stat(filepath.Join(dir, base))
	b, errB := os.Stat(filepath.Join(dir, alt))
	if errA != nil || errB != nil || !os.SameFile(a, b) {
		return true
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return false
	}
	for _, e := range entries {
		if e.Name() == base {
			return true
		}
	}
	return false
}

func swapCase(s string) string {
	return strings.Map(func(r rune) rune {
		switch {
		case r >= 'a' && r <= 'z':
			return r - 'a' + 'A'
		case r >= 'A' && r <= 'Z':
			return r - 'A' + 'a'
		}
		return r
	}, s)
}

func (*fileStore) Evict(fw.ClientID, string) {}
func (*fileStore) EvictAll()                 {}
func (s *fileStore) String() string          { return "FileSecretStore[" + s.instance + "]" }
