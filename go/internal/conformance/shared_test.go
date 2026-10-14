package conformance_test

import (
	"bufio"
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/internal/sqltext"
	"github.com/flowcatalyst/framework/go/locks/dblock"
	"github.com/flowcatalyst/framework/go/locks/redislock"
)

// Every copy listed in shared-files.txt equals its source byte for byte (design decision 11).
func TestSharedCopiesEqualTheirSources(t *testing.T) {
	t.Parallel()
	module := conformance.ModuleDir(t)
	root := filepath.Dir(module)
	list, err := os.ReadFile(filepath.Join(module, "internal", "conformance", "shared-files.txt"))
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	sc := bufio.NewScanner(bytes.NewReader(list))
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		src, dst, ok := strings.Cut(line, " ")
		if !ok {
			t.Fatalf("shared-files.txt: bad line %q", line)
		}
		want, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(src)))
		if err != nil {
			t.Fatalf("source %s: %v", src, err)
		}
		got, err := os.ReadFile(filepath.Join(module, filepath.FromSlash(strings.TrimSpace(dst))))
		if err != nil {
			t.Fatalf("copy %s: %v (run make -C go go-sync-shared)", dst, err)
		}
		conformance.EqualBytes(t, src+" -> go/"+dst+" (run make -C go go-sync-shared)", want, got)
		n++
	}
	if n == 0 {
		t.Fatal("shared-files.txt lists no files")
	}
}

// The statements and scripts the drivers embed are the shared files, and the statements are
// sent with numbered placeholders.
func TestLockStatementsAndScriptsAreTheSharedFiles(t *testing.T) {
	t.Parallel()
	acquire, refresh, release := dblock.Statements()
	for name, got := range map[string]string{"acquire": acquire, "refresh": refresh, "release": release} {
		conformance.EqualBytes(t, "sql/locks/"+name+".sql", conformance.ReadFile(t, "sql/locks/"+name+".sql"), []byte(got))
		if strings.Contains(sqltext.Number(got), "?") {
			t.Errorf("sql/locks/%s.sql: a placeholder was not numbered", name)
		}
	}
	la, lr, ll := redislock.Scripts()
	for name, got := range map[string]string{"acquire": la, "refresh": lr, "release": ll} {
		conformance.EqualBytes(t, "lua/locks/"+name+".lua", conformance.ReadFile(t, "lua/locks/"+name+".lua"), []byte(got))
	}
}
