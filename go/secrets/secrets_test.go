package secrets_test

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"maps"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/secrets"
)

const value = "s3cr3t-0xBEEF"

var (
	acme   = fw.MustClientID("acme")
	globex = fw.MustClientID("globex")
)

type rig struct {
	t       *testing.T
	root    string
	outside string
	clock   *fwtest.Clock
}

type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}
func (s *syncBuffer) String() string { s.mu.Lock(); defer s.mu.Unlock(); return s.b.String() }

// build starts an application and builds the resolver with the stores given; the logger records.
func build(t *testing.T, env map[string]string, clock fw.Clock, yaml string, opts ...secrets.BuildOption) (*secrets.Resolver, *syncBuffer, error) {
	t.Helper()
	dir := t.TempDir()
	if yaml != "" {
		if err := os.WriteFile(filepath.Join(dir, "application.yaml"), []byte(yaml), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	e := map[string]string{"APP_ENV": "test"}
	maps.Copy(e, env)
	logs := &syncBuffer{}
	fopts := []fw.Option{
		fw.WithEnvironment(e), fw.WithConfigDir(dir), fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}),
		fw.WithLogger(slog.New(slog.NewTextHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}))),
	}
	if clock != nil {
		fopts = append(fopts, fw.WithClock(clock))
	}
	app := fw.New("secrets-test", fopts...)
	d := secrets.Declare(app)
	var out *secrets.Resolver
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		var err error
		out, err = secrets.Build(ctx, b, d, opts...)
		return nil, err
	})
	if err != nil {
		return nil, logs, err
	}
	if out == nil {
		t.Fatal("Build returned no resolver and no error")
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	return out, logs, nil
}

func newRig(t *testing.T, tweak func(secrets.FileFactory) secrets.FileFactory) *rig {
	t.Helper()
	base := t.TempDir()
	r := &rig{t: t, root: filepath.Join(base, "secrets"), outside: filepath.Join(base, "outside"), clock: fwtest.NewClock(time.Date(2026, 10, 15, 12, 0, 0, 0, time.UTC))}
	for _, d := range []string{r.root, r.outside} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	return r
}

func (r *rig) write(client, name, content string) {
	r.t.Helper()
	if err := os.MkdirAll(filepath.Join(r.root, client), 0o700); err != nil {
		r.t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(r.root, client, name), []byte(content), 0o600); err != nil {
		r.t.Fatal(err)
	}
}

func (r *rig) start(f secrets.FileFactory, extra ...secrets.BuildOption) (*secrets.Resolver, *syncBuffer) {
	r.t.Helper()
	res, logs, err := build(r.t, nil, r.clock, "", append([]secrets.BuildOption{secrets.WithStore("files", f)}, extra...)...)
	if err != nil {
		r.t.Fatal(err)
	}
	return res, logs
}

func reveal(t *testing.T, res secrets.Result, err error) string {
	t.Helper()
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	f, ok := res.(secrets.Found)
	if !ok {
		t.Fatalf("result %#v, want Found", res)
	}
	return f.Secret.Reveal()
}

func isA[T secrets.Result](t *testing.T, res secrets.Result, err error, what string) {
	t.Helper()
	if err != nil {
		t.Fatalf("%s: %v", what, err)
	}
	if _, ok := res.(T); !ok {
		t.Fatalf("%s: result %#v", what, res)
	}
}

func TestReferencesNeverCarryAPathOrAClient(t *testing.T) {
	t.Parallel()
	if r, err := secrets.ParseRef("sftp-password"); err != nil || r.String() != "sftp-password" {
		t.Fatalf("%v %v", r, err)
	}
	r, err := secrets.ParseRef("files:sftp-password")
	store, ok := r.Store()
	if err != nil || !ok || store != "files" || r.Name() != "sftp-password" || r.String() != "files:sftp-password" {
		t.Fatalf("%v %v", r, err)
	}
	for _, bad := range []string{
		"", "../etc/passwd", "a/b", ".hidden", "..", "Sftp", "sftp_password", "sftp--password", "-x", "x-", "files:", ":x",
		"files:a:b", "files:../x", "acme/sftp", strings.Repeat("x", 101), "files:sftp password", "files:sftp\npassword",
	} {
		if _, err := secrets.ParseRef(bad); err == nil {
			t.Errorf("reference %q accepted", bad)
		}
	}
	_, err = secrets.ParseRef(strings.Repeat("x", 500) + "/")
	if err == nil || !strings.Contains(err.Error(), "(501 characters)") || strings.Contains(err.Error(), "xxxxxxxxxx") {
		t.Fatalf("long reference message: %v", err)
	}
	if _, err := secrets.NewRef("Bad", "x"); err == nil {
		t.Error("store name with a capital accepted")
	}
	defer func() {
		if recover() == nil {
			t.Error("MustParseRef did not panic")
		}
	}()
	secrets.MustParseRef("../x")
}

func TestAFileIsReadForTheCallersClientOnlyWithOneTrailingNewlineRemoved(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	r.write("acme", "sftp-password", value+"\n")
	r.write("globex", "sftp-password", "globex-value\r\n")
	r.write("acme", "two-newlines", "v\n\n")
	res, _ := r.start(secrets.File(r.root))
	ctx := t.Context()
	got, err := res.Resolve(ctx, acme, secrets.MustParseRef("files:sftp-password"))
	if v := reveal(t, got, err); v != value {
		t.Fatalf("acme %q", v)
	}
	got, err = res.Resolve(ctx, globex, secrets.MustParseRef("sftp-password"))
	if v := reveal(t, got, err); v != "globex-value" {
		t.Fatalf("globex %q", v)
	}
	got, err = res.Resolve(ctx, acme, secrets.MustParseRef("two-newlines"))
	if v := reveal(t, got, err); v != "v\n" {
		t.Fatalf("two newlines: %q (only one is removed)", v)
	}
	got, err = res.Resolve(ctx, fw.MustClientID("initech"), secrets.MustParseRef("sftp-password"))
	isA[secrets.NotFound](t, got, err, "initech")
	s, err := res.Require(ctx, acme, secrets.MustParseRef("sftp-password"))
	if err != nil || s.Reveal() != value {
		t.Fatalf("Require: %v", err)
	}
}

func TestClientIDsThatAreNotPlainDirectoryNamesAreRefused(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	r.write("acme", "x", value)
	res, _ := r.start(secrets.File(r.root))
	for _, c := range []string{"..", ".", ".acme", "..acme"} {
		got, err := res.Resolve(t.Context(), fw.MustClientID(c), secrets.MustParseRef("x"))
		isA[secrets.Refused](t, got, err, c)
	}
}

func TestALinkThatLeadsOutOfTheDirectoryIsNotFollowedAndNothingIsShown(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	target := filepath.Join(r.outside, "passwd")
	if err := os.WriteFile(target, []byte(value), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(r.root, "acme"), 0o700); err != nil {
		t.Fatal(err)
	}
	must(t, os.Symlink(target, filepath.Join(r.root, "acme", "stolen")))
	must(t, os.Symlink(r.outside, filepath.Join(r.root, "evil")))
	must(t, os.WriteFile(filepath.Join(r.outside, "token"), []byte(value), 0o600))
	res, logs := r.start(secrets.File(r.root))
	got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("stolen"))
	isA[secrets.NotFound](t, got, err, "link to a file outside")
	got, err = res.Resolve(t.Context(), fw.MustClientID("evil"), secrets.MustParseRef("token"))
	isA[secrets.NotFound](t, got, err, "a client directory that is a link outside")
	out := logs.String()
	if !strings.Contains(out, "resolves outside the directory") {
		t.Fatalf("no warning: %s", out)
	}
	if strings.Contains(out, value) || strings.Contains(out, r.outside) {
		t.Fatalf("the log shows a value or the link's target:\n%s", out)
	}
}

func TestALinkInsideTheDirectoryIsFollowedTheKubernetesLayout(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	// /secrets/..2026_10_10/acme/sftp-password, /secrets/..data -> ..2026_10_10, /secrets/acme -> ..data/acme
	dated := filepath.Join(r.root, "..2026_10_10", "acme")
	must(t, os.MkdirAll(dated, 0o700))
	must(t, os.WriteFile(filepath.Join(dated, "sftp-password"), []byte(value), 0o600))
	must(t, os.Symlink("..2026_10_10", filepath.Join(r.root, "..data")))
	must(t, os.Symlink(filepath.Join("..data", "acme"), filepath.Join(r.root, "acme")))
	res, _ := r.start(secrets.File(r.root))
	got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("sftp-password"))
	if v := reveal(t, got, err); v != value {
		t.Fatalf("%q", v)
	}
}

func TestADirectoryGivenAsALinkIsResolvedOnce(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	r.write("acme", "k", value)
	link := filepath.Join(filepath.Dir(r.root), "link")
	must(t, os.Symlink(r.root, link))
	res, _ := r.start(secrets.File(link))
	got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("k"))
	if v := reveal(t, got, err); v != value {
		t.Fatalf("%q", v)
	}
}

func TestALinkedClientDirectoryOutsideTheRootIsNotFollowedEvenWithTheSameNames(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	must(t, os.MkdirAll(filepath.Join(r.outside, "acme"), 0o700))
	must(t, os.WriteFile(filepath.Join(r.outside, "acme", "token"), []byte(value), 0o600))
	must(t, os.Symlink(filepath.Join(r.outside, "acme"), filepath.Join(r.root, "acme")))
	res, _ := r.start(secrets.File(r.root))
	// the real path ends in acme/token, exactly as asked, but lies outside the directory
	got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("token"))
	isA[secrets.NotFound](t, got, err, "a real path outside the directory")
}

func TestALinkToAnotherClientsSecretIsNotFollowed(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	r.write("globex", "api-key", value)
	must(t, os.MkdirAll(filepath.Join(r.root, "acme"), 0o700))
	must(t, os.Symlink(filepath.Join(r.root, "globex", "api-key"), filepath.Join(r.root, "acme", "api-key")))
	res, _ := r.start(secrets.File(r.root))
	got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("api-key"))
	isA[secrets.NotFound](t, got, err, "link to another client's secret")
	// a link to another name of the same client is refused too: the real path must end in the name
	r.write("acme", "real-name", value)
	must(t, os.Symlink(filepath.Join(r.root, "acme", "real-name"), filepath.Join(r.root, "acme", "alias")))
	got, err = res.Resolve(t.Context(), acme, secrets.MustParseRef("alias"))
	isA[secrets.NotFound](t, got, err, "link to another name")
}

func TestAClientDifferingOnlyByCaseNeverReadsAnotherClientsFile(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	r.write("acme", "api-key", value)
	res, _ := r.start(secrets.File(r.root))
	// on a case-insensitive file system ACME/api-key opens acme/api-key; the real-path check refuses it
	got, err := res.Resolve(t.Context(), fw.MustClientID("ACME"), secrets.MustParseRef("api-key"))
	isA[secrets.NotFound](t, got, err, "ACME")
	// a file spelt with capitals is not the file of the lower-case name
	r.write("acme", "Mixed-Name", value)
	got, err = res.Resolve(t.Context(), acme, secrets.MustParseRef("mixed-name"))
	isA[secrets.NotFound](t, got, err, "mixed-name against Mixed-Name")
	// and the other way round: the directory is spelt Initech, the caller's client is initech
	r.write("Initech", "other-key", value)
	got, err = res.Resolve(t.Context(), fw.MustClientID("initech"), secrets.MustParseRef("other-key"))
	isA[secrets.NotFound](t, got, err, "initech against an Initech directory")
}

func TestTooLargeOrInvalidFilesFailWithoutShowingTheirContent(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	r.write("acme", "big", value+strings.Repeat("x", 100))
	r.write("acme", "binary", "s3\xc3\x28")
	r.write("acme", "exact", strings.Repeat("y", 50))
	res, _ := r.start(secrets.File(r.root).WithMaxBytes(50))
	_, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("big"))
	var se *secrets.StoreError
	if !errors.As(err, &se) || !strings.Contains(err.Error(), "maxBytes") || strings.Contains(err.Error(), value) {
		t.Fatalf("big: %v", err)
	}
	_, err = res.Resolve(t.Context(), acme, secrets.MustParseRef("binary"))
	if !errors.As(err, &se) || !strings.Contains(err.Error(), "UTF-8") || errors.Unwrap(err) != nil {
		t.Fatalf("binary: %v (cause %v)", err, errors.Unwrap(err))
	}
	got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("exact"))
	if v := reveal(t, got, err); len(v) != 50 {
		t.Fatalf("a file of exactly maxBytes: %d bytes", len(v))
	}
}

func TestEmptyFilesAndDirectoriesAreNotFound(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	r.write("acme", "empty", "\n")
	r.write("acme", "crlf-only", "\r\n")
	must(t, os.MkdirAll(filepath.Join(r.root, "acme", "a-dir"), 0o700))
	res, _ := r.start(secrets.File(r.root))
	for _, n := range []string{"empty", "crlf-only", "a-dir", "missing"} {
		got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef(n))
		isA[secrets.NotFound](t, got, err, n)
	}
}

func TestAWorldReadableFileIsWarnedAboutOncePerFileAndStillRead(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	r.write("acme", "open", value)
	r.write("acme", "closed", value)
	must(t, os.Chmod(filepath.Join(r.root, "acme", "open"), 0o644))
	res, logs := r.start(secrets.File(r.root).WithCache(secrets.NoCache()))
	for range 3 {
		got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("open"))
		reveal(t, got, err)
	}
	got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("closed"))
	reveal(t, got, err)
	if n := strings.Count(logs.String(), "readable by every local user"); n != 1 || !strings.Contains(logs.String(), "the file of secret open of client acme") {
		t.Fatalf("%d warnings, want 1 for the world-readable file open:\n%s", n, logs.String())
	}
	if strings.Contains(logs.String(), value) {
		t.Fatal("the log shows a value")
	}
}

func TestTheDirectoryMustBeAbsoluteAndExistWhenBuilt(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	for name, dir := range map[string]string{"relative": "relative/path", "missing": filepath.Join(r.outside, "missing")} {
		_, _, err := build(t, nil, nil, "", secrets.WithStore("files", secrets.File(dir)))
		if err == nil || !strings.Contains(err.Error(), "directory") || !strings.Contains(err.Error(), "secret store 'files'") {
			t.Errorf("%s: %v", name, err)
		}
	}
	// a file is not a directory
	f := filepath.Join(r.outside, "file")
	must(t, os.WriteFile(f, []byte("x"), 0o600))
	if _, _, err := build(t, nil, nil, "", secrets.WithStore("files", secrets.File(f))); err == nil {
		t.Error("a file as the directory")
	}
	for _, n := range []int{0, 1_048_577} {
		if _, _, err := build(t, nil, nil, "", secrets.WithStore("files", secrets.File(r.root).WithMaxBytes(n))); err == nil {
			t.Errorf("maxBytes %d accepted", n)
		}
	}
	if _, _, err := build(t, nil, nil, "", secrets.WithStore("files", secrets.File(r.root).WithMaxBytes(1_048_576))); err != nil {
		t.Errorf("maxBytes at the limit refused: %v", err)
	}
}

// --- env store ---------------------------------------------------------------------------------

func TestEnvVariablesAreNamedInjectivelyAndOnlyUnderThePrefix(t *testing.T) {
	t.Parallel()
	env := map[string]string{
		"CLIENT_SECRET_ACME_CORP__SFTP_PASSWORD": value,
		"CLIENT_SECRET_ACME__CORP_SFTP_PASSWORD": "other",
		"DATABASE_INSTANCES_MAIN_PASSWORD":       "db-password",
		"CLIENT_SECRET_ACME__EMPTY":              "",
	}
	res, _, err := build(t, env, nil, "", secrets.WithStore("env", secrets.Env("CLIENT_SECRET_")))
	if err != nil {
		t.Fatal(err)
	}
	ctx := t.Context()
	got, err := res.Resolve(ctx, fw.MustClientID("acme-corp"), secrets.MustParseRef("sftp-password"))
	if v := reveal(t, got, err); v != value {
		t.Fatalf("%q", v)
	}
	// client "acme", name "corp-sftp-password" maps to CLIENT_SECRET_ACME__CORP_SFTP_PASSWORD, a
	// different variable: the double underscore separates client and name unambiguously
	got, err = res.Resolve(ctx, acme, secrets.MustParseRef("corp-sftp-password"))
	if v := reveal(t, got, err); v != "other" {
		t.Fatalf("%q", v)
	}
	got, err = res.Resolve(ctx, fw.MustClientID("database"), secrets.MustParseRef("instances-main-password"))
	isA[secrets.NotFound](t, got, err, "a framework variable")
	got, err = res.Resolve(ctx, acme, secrets.MustParseRef("empty"))
	isA[secrets.NotFound](t, got, err, "an empty variable")
	for _, c := range []string{"acme_corp", "Acme", "acme.corp", "_platform", "acme--corp"} {
		got, err := res.Resolve(ctx, fw.MustClientID(c), secrets.MustParseRef("sftp-password"))
		isA[secrets.Refused](t, got, err, c)
	}
	_, err = res.Require(ctx, fw.MustClientID("acme_corp"), secrets.MustParseRef("sftp-password"))
	if !errors.Is(err, secrets.ErrNotFound) || !strings.Contains(err.Error(), "refused") {
		t.Fatalf("Require of a refused client: %v", err)
	}
}

func TestEnvPrefixesThatReachFrameworkVariablesAreRefusedWhenBuilt(t *testing.T) {
	t.Parallel()
	for _, prefix := range []string{"DATABASE_", "APP_X_", "FLOWCATALYST_", "HTTP_X_", "REDIS_X_", "LOCKS_X_", "SECRETS_X_", "X_", "lower_", "NO_UNDERSCORE", "AB_", ""} {
		_, _, err := build(t, nil, nil, "", secrets.WithStore("env", secrets.Env(prefix)))
		if err == nil || !strings.Contains(err.Error(), "prefix") {
			t.Errorf("prefix %q: %v", prefix, err)
		}
	}
	for _, ok := range []string{"CLIENT_SECRET_", "ABCD_", "ABC_", "A1B_"} {
		if _, _, err := build(t, nil, nil, "", secrets.WithStore("env", secrets.Env(ok))); err != nil {
			t.Errorf("prefix %q refused: %v", ok, err)
		}
	}
}

func TestAnInvalidNameGivenStraightToAStoreIsAnError(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	res, _ := r.start(secrets.File(r.root))
	_ = res
	// the Resolver only takes validated Refs; a store called directly checks the name too
	app := fw.New("x", fw.WithEnvironment(map[string]string{"APP_ENV": "test"}), fw.WithConfigDir(t.TempDir()), fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	var store secrets.Store
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		var err error
		store, err = secrets.File(r.root).WithCache(secrets.NoCache()).Create(ctx, "files", b)
		return nil, err
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = run.Shutdown() }()
	if store == nil {
		t.Fatal("no store")
	}
	for _, bad := range []string{"../x", "A", "a/b", ""} {
		if _, err := store.Find(t.Context(), acme, bad); !errors.Is(err, secrets.ErrInvalidName) {
			t.Errorf("name %q: %v", bad, err)
		}
	}
}

// --- caching -----------------------------------------------------------------------------------

type countingStore struct {
	mu     sync.Mutex
	values map[string]string
	reads  atomic.Int32
}

func (s *countingStore) Find(_ context.Context, client fw.ClientID, name string) (secrets.Result, error) {
	s.reads.Add(1)
	s.mu.Lock()
	defer s.mu.Unlock()
	if v, ok := s.values[client.String()+"/"+name]; ok {
		return secrets.Found{Secret: config.NewSecret(v)}, nil
	}
	return secrets.NotFound{}, nil
}
func (s *countingStore) Evict(fw.ClientID, string) {}
func (s *countingStore) EvictAll()                 {}

type factoryFunc func(ctx context.Context, name string, b *fw.Boot) (secrets.Store, error)

func (f factoryFunc) Create(ctx context.Context, name string, b *fw.Boot) (secrets.Store, error) {
	return f(ctx, name, b)
}

// cachedOver wraps a counting store with the cache of a File factory pointed at a directory whose
// files the test rewrites: the cache is the code under test, so it is reached through File.
func TestFoundValuesAreCachedForTheTTLPerClientAndNotFoundIsNeverCached(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	r.write("acme", "k", "one")
	r.write("globex", "k", "g")
	res, _ := r.start(secrets.File(r.root).WithCache(mustPolicy(t, 10*time.Second, 100)))
	read := func(c fw.ClientID) string {
		got, err := res.Resolve(t.Context(), c, secrets.MustParseRef("k"))
		return reveal(t, got, err)
	}
	if read(acme) != "one" || read(globex) != "g" {
		t.Fatal("first reads")
	}
	r.write("acme", "k", "two") // rotated on disk
	if read(acme) != "one" {
		t.Fatal("the cached value was not served within the TTL")
	}
	r.clock.Advance(9*time.Second + 999*time.Millisecond)
	if read(acme) != "one" {
		t.Fatal("expired just before the TTL")
	}
	r.clock.Advance(time.Millisecond)
	if read(acme) != "two" {
		t.Fatal("not re-read at the TTL")
	}
	// not found is never cached
	got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("later"))
	isA[secrets.NotFound](t, got, err, "later")
	r.write("acme", "later", "now-here")
	got, err = res.Resolve(t.Context(), acme, secrets.MustParseRef("later"))
	if v := reveal(t, got, err); v != "now-here" {
		t.Fatalf("a not-found was cached: %q", v)
	}
}

func TestTheResolverEvictsThroughToTheStore(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	r.write("acme", "k", "one")
	r.write("acme", "j", "j1")
	res, _ := r.start(secrets.File(r.root))
	read := func(n string) string {
		got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef(n))
		return reveal(t, got, err)
	}
	read("k")
	read("j")
	r.write("acme", "k", "two")
	r.write("acme", "j", "j2")
	if err := res.Evict(acme, secrets.MustParseRef("k")); err != nil {
		t.Fatal(err)
	}
	if read("k") != "two" || read("j") != "j1" {
		t.Fatal("Evict dropped more or less than one entry")
	}
	res.EvictAll()
	if read("j") != "j2" {
		t.Fatal("EvictAll")
	}
	if err := res.Evict(acme, secrets.MustParseRef("nope:k")); !errors.Is(err, secrets.ErrUnknownStore) {
		t.Fatalf("Evict in an unknown store: %v", err)
	}
}

func TestTheCacheKeepsAtMostMaxEntriesAndDropsTheLeastRecentlyUsed(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	for _, n := range []string{"a", "b", "c"} {
		r.write("acme", n, n+"1")
	}
	res, _ := r.start(secrets.File(r.root).WithCache(mustPolicy(t, time.Hour, 2)))
	read := func(n string) string {
		got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef(n))
		return reveal(t, got, err)
	}
	read("a")
	read("b")
	read("a") // a is the most recently used
	read("c") // evicts b
	for _, n := range []string{"a", "b", "c"} {
		r.write("acme", n, n+"2")
	}
	if read("a") != "a1" || read("c") != "c1" {
		t.Fatal("a recently used entry was dropped")
	}
	if read("b") != "b2" {
		t.Fatal("the least recently used entry was not dropped")
	}
}

func TestCachePolicyDefaultsAndRanges(t *testing.T) {
	t.Parallel()
	if d := secrets.DefaultCache(); d.TTL != 5*time.Minute || d.MaxEntries != 10_000 {
		t.Fatalf("%+v", d)
	}
	if n := secrets.NoCache(); n.TTL != 0 || n.Validate() != nil {
		t.Fatalf("%+v", n)
	}
	for _, c := range [][2]int64{{0, 1}, {int64(24 * time.Hour), 1_000_000}, {int64(time.Second), 1}} {
		if _, err := secrets.NewCachePolicy(time.Duration(c[0]), int(c[1])); err != nil {
			t.Errorf("%v refused: %v", c, err)
		}
	}
	for _, c := range [][2]int64{{-1, 1}, {int64(24*time.Hour) + 1, 1}, {int64(time.Second), 0}, {int64(time.Second), 1_000_001}} {
		if _, err := secrets.NewCachePolicy(time.Duration(c[0]), int(c[1])); err == nil {
			t.Errorf("%v accepted", c)
		}
	}
}

func TestWithoutACacheARotatedFileIsReadAtOnce(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	r.write("acme", "k", "one")
	res, _ := r.start(secrets.File(r.root).WithCache(secrets.NoCache()))
	read := func() string {
		got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("k"))
		return reveal(t, got, err)
	}
	if read() != "one" {
		t.Fatal("first")
	}
	r.write("acme", "k", "two")
	if read() != "two" {
		t.Fatal("a rotated file was served from a cache that is off")
	}
}

// --- the resolver and the build ------------------------------------------------------------------

func TestThePlatformDriverIsBlockedAndSaysSo(t *testing.T) {
	t.Parallel()
	_, _, err := build(t, nil, nil, "", secrets.WithStore("platform", secrets.Platform()))
	if err == nil || !strings.Contains(err.Error(), "blocked on the platform") || !strings.Contains(err.Error(), "secret store 'platform'") {
		t.Fatalf("%v", err)
	}
}

func TestWithoutStoresOrWithAnUnknownStoreResolvingFails(t *testing.T) {
	t.Parallel()
	res, _, err := build(t, nil, nil, "")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("x")); !errors.Is(err, secrets.ErrNoStore) {
		t.Fatalf("no stores: %v", err)
	}
	r := newRig(t, nil)
	res2, _ := r.start(secrets.File(r.root))
	_, err = res2.Resolve(t.Context(), acme, secrets.MustParseRef("nope:x"))
	if !errors.Is(err, secrets.ErrUnknownStore) || !strings.Contains(err.Error(), "files") {
		t.Fatalf("unknown store: %v", err)
	}
	if _, err := res2.Resolve(t.Context(), fw.ClientID{}, secrets.MustParseRef("x")); err == nil {
		t.Fatal("the zero client resolved")
	}
	if _, err := res2.Resolve(t.Context(), acme, secrets.Ref{}); err == nil {
		t.Fatal("the zero Ref resolved")
	}
}

func TestRequireNamesTheSecretButNeverShowsAValue(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	r.write("acme", "k", value)
	res, _ := r.start(secrets.File(r.root))
	_, err := res.Require(t.Context(), globex, secrets.MustParseRef("k"))
	if !errors.Is(err, secrets.ErrNotFound) {
		t.Fatalf("%v", err)
	}
	for _, want := range []string{"k", "globex", "files"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("message %q lacks %q", err, want)
		}
	}
	if strings.Contains(err.Error(), value) {
		t.Fatal("message shows a value")
	}
	s, _ := res.Require(t.Context(), acme, secrets.MustParseRef("k"))
	if strings.Contains(fmt.Sprintf("%v %+v %#v %s", s, s, s, s), value) {
		t.Fatal("a Secret prints its value")
	}
}

func TestTheDefaultStoreRulesAreCheckedWhenBuilt(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	f := secrets.File(r.root)
	e := secrets.Env("CLIENT_SECRET_")
	_, _, err := build(t, nil, nil, "", secrets.WithStore("a", f), secrets.WithStore("b", e))
	if err == nil || !strings.Contains(err.Error(), "secrets.DefaultStore") {
		t.Errorf("several stores, no default: %v", err)
	}
	_, _, err = build(t, nil, nil, "", secrets.WithStore("a", f), secrets.DefaultStore("zz"))
	if err == nil || !strings.Contains(err.Error(), "defaultStore 'zz' is not a store") {
		t.Errorf("unknown default: %v", err)
	}
	_, _, err = build(t, nil, nil, "", secrets.WithStore("a", f), secrets.WithStore("a", e))
	if err == nil || !strings.Contains(err.Error(), "'a' is already given") {
		t.Errorf("a name twice: %v", err)
	}
	for _, bad := range []string{"", "A", "1a", "a_b"} {
		if _, _, err := build(t, nil, nil, "", secrets.WithStore(bad, f)); err == nil || !strings.Contains(err.Error(), "secret store name must match") {
			t.Errorf("name %q: %v", bad, err)
		}
	}
	res, _, err := build(t, nil, nil, "", secrets.WithStore("a", f), secrets.WithStore("b", e), secrets.DefaultStore("b"))
	if err != nil {
		t.Fatal(err)
	}
	if got := res.Names(); len(got) != 2 || got[0] != "a" || got[1] != "b" {
		t.Errorf("names %v", got)
	}
	// every store's problem is reported at once
	_, _, err = build(t, nil, nil, "", secrets.WithStore("a", secrets.File("relative")), secrets.WithStore("b", secrets.Env("x")), secrets.DefaultStore("a"))
	var se *fw.StartupError
	if !errors.As(err, &se) || len(se.Problems) != 2 {
		t.Errorf("want 2 problems at once: %v", err)
	}
}

func TestACustomStoreFactoryIsUsedAsGiven(t *testing.T) {
	t.Parallel()
	store := &countingStore{values: map[string]string{"acme/k": "custom"}}
	res, _, err := build(t, nil, nil, "", secrets.WithStore("custom", factoryFunc(func(context.Context, string, *fw.Boot) (secrets.Store, error) { return store, nil })))
	if err != nil {
		t.Fatal(err)
	}
	got, err := res.Resolve(t.Context(), acme, secrets.MustParseRef("k"))
	if v := reveal(t, got, err); v != "custom" || store.reads.Load() != 1 {
		t.Fatalf("%q reads=%d", v, store.reads.Load())
	}
}

func TestALeftoverSecretsSectionFailsStartUpNamingTheCode(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	yaml := "secrets:\n  default: files\n  instances:\n    files: { driver: file, directory: /run/secrets }\n"
	_, _, err := build(t, nil, nil, yaml, secrets.WithStore("files", secrets.File(r.root)))
	if err == nil {
		t.Fatal("a leftover secrets section was accepted")
	}
	for _, want := range []string{"secrets.default", "moved to code", "secrets.WithStore", "secrets.instances.files.driver"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("startup error lacks %q:\n%v", want, err)
		}
	}
	// the environment too
	_, _, err = build(t, map[string]string{"SECRETS_DEFAULT": "files"}, nil, "", secrets.WithStore("files", secrets.File(r.root)))
	if err == nil || !strings.Contains(err.Error(), "SECRETS_DEFAULT") {
		t.Errorf("environment: %v", err)
	}
}

func TestAMisspeltSecretsKeyFails(t *testing.T) {
	t.Parallel()
	r := newRig(t, nil)
	_, _, err := build(t, nil, nil, "secrets:\n  verbose: true\n", secrets.WithStore("files", secrets.File(r.root)))
	if err == nil || !strings.Contains(err.Error(), "secrets.verbose") {
		t.Fatalf("%v", err)
	}
}

func mustPolicy(t *testing.T, ttl time.Duration, n int) secrets.CachePolicy {
	t.Helper()
	p, err := secrets.NewCachePolicy(ttl, n)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}
