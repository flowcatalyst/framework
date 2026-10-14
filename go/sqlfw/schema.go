package sqlfw

import (
	"bytes"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
)

var versionedFile = regexp.MustCompile(`^V(\d+)__(.+)$`)

// schemaName is the name a migration file gets in the schema directory. sqlc reads a directory's
// files in lexical order, so the version is zero-padded to six digits (V10 must come after V2);
// the bytes are the file's, unchanged.
func schemaName(name string) string {
	m := versionedFile.FindStringSubmatch(name)
	if m == nil {
		return name
	}
	n, err := strconv.Atoi(m[1])
	if err != nil {
		return name
	}
	return fmt.Sprintf("V%06d__%s", n, m[2])
}

// expectedSchema returns what the schema directory must hold: <NN>_<module>__<file> for every
// file of every set, NN being the set's two-digit position so lexical order is apply order. The
// directory is flat because sqlc reads only the files directly inside a schema directory.
func (t *tool) expectedSchema() (map[string][]byte, error) {
	out := map[string][]byte{}
	for i, set := range t.cfg.Sets {
		if set.Module == "" || strings.ContainsAny(set.Module, `/\`) || set.Module == "." || set.Module == ".." {
			return nil, fmt.Errorf("migration set %d: module %q is not usable as a directory name", i, set.Module)
		}
		if set.Files == nil {
			return nil, fmt.Errorf("migration set %q has no files", set.Module)
		}
		prefix := fmt.Sprintf("%02d_%s__", i, set.Module)
		err := fs.WalkDir(set.Files, ".", func(p string, d fs.DirEntry, err error) error {
			if err != nil || d.IsDir() {
				return err
			}
			b, err := fs.ReadFile(set.Files, p)
			if err != nil {
				return err
			}
			dir, name := path.Split(p)
			out[prefix+strings.ReplaceAll(dir, "/", "__")+schemaName(name)] = b
			return nil
		})
		if err != nil {
			return nil, fmt.Errorf("migration set %q: %w", set.Module, err)
		}
	}
	return out, nil
}

// writeSchema makes the schema directory hold exactly the expected files.
func (t *tool) writeSchema() error {
	expected, err := t.expectedSchema()
	if err != nil {
		return err
	}
	root := t.path(t.schemaDir)
	have, err := readTree(root)
	if err != nil {
		return err
	}
	for name := range have {
		if _, ok := expected[name]; !ok {
			if err := os.Remove(filepath.Join(root, filepath.FromSlash(name))); err != nil {
				return err
			}
		}
	}
	for name, b := range expected {
		if old, ok := have[name]; ok && bytes.Equal(old, b) {
			continue
		}
		target := filepath.Join(root, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil { //nolint:gosec // committed source files are world-readable
			return err
		}
		if err := os.WriteFile(target, b, 0o644); err != nil { //nolint:gosec // committed source files are world-readable
			return err
		}
	}
	removeEmptyDirs(root)
	fmt.Fprintf(t.out, "schema: %d file(s) in %s\n", len(expected), filepath.ToSlash(t.schemaDir))
	return nil
}

// removeEmptyDirs removes the empty directories below root (not root itself).
func removeEmptyDirs(root string) {
	var dirs []string
	_ = filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err == nil && d.IsDir() && p != root {
			dirs = append(dirs, p)
		}
		return nil
	})
	slices.Reverse(dirs)
	for _, d := range dirs {
		_ = os.Remove(d) // fails, harmlessly, when the directory is not empty
	}
}

// readTree reads every file below dir into a map keyed by slash path; a missing dir is empty.
func readTree(dir string) (map[string][]byte, error) {
	out := map[string][]byte{}
	err := filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) && p == dir {
				return fs.SkipAll
			}
			return err
		}
		if d.IsDir() {
			return nil
		}
		b, err := os.ReadFile(p) //nolint:gosec // the tool reads its own output directories
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(dir, p)
		if err != nil {
			return err
		}
		out[filepath.ToSlash(rel)] = b
		return nil
	})
	return out, err
}

// compareTree lists, one per line, the files of dir that differ from want, that are missing, and
// that should not be there. shown is dir as the user wrote it, for the messages.
func compareTree(want map[string][]byte, dir, shown, what string) []string {
	have, err := readTree(dir)
	if err != nil {
		return []string{fmt.Sprintf("%s: %v", filepath.ToSlash(shown), err)}
	}
	var ps []string
	for _, name := range sortedKeys(want) {
		display := path.Join(filepath.ToSlash(shown), name)
		b, ok := have[name]
		switch {
		case !ok:
			ps = append(ps, fmt.Sprintf("%s: missing (%s)", display, what))
		case !bytes.Equal(b, want[name]):
			ps = append(ps, fmt.Sprintf("%s: differs (%s)", display, what))
		}
	}
	for _, name := range sortedKeys(have) {
		if _, ok := want[name]; !ok {
			ps = append(ps, fmt.Sprintf("%s: unexpected file (%s)", path.Join(filepath.ToSlash(shown), name), what))
		}
	}
	return ps
}

func sortedKeys(m map[string][]byte) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}
