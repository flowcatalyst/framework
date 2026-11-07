package flowcatalyst_test

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"maps"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/flowcatalyst/fakeplatform"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/secrets"
)

const (
	secretValue = "pl4tf0rm-s3cr3t-0xC0FFEE"
	revealPath  = "/api/config/orders/secrets/ftp-password/secret"
)

var (
	acmeID   = fw.MustClientID("clt_0ACME")
	acmeCode = fw.MustClientID("acme")
	ftpRef   = secrets.MustParseRef("ftp-password")
)

type lockedBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *lockedBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedBuffer) String() string { l.mu.Lock(); defer l.mu.Unlock(); return l.b.String() }

type secretsRig struct {
	t        *testing.T
	platform *fakeplatform.Platform
	resolver *secrets.Resolver
	clock    *fwtest.Clock
	logs     *lockedBuffer
}

// startSecrets runs an application whose secrets resolver has the platform store made by store.
func startSecrets(t *testing.T, env map[string]string, store func(*flowcatalyst.PlatformClient) secrets.StoreFactory) (*secretsRig, error) {
	t.Helper()
	p := fakeplatform.Start()
	t.Cleanup(p.Close)
	e := map[string]string{
		"APP_ENV": "test", "FLOWCATALYST_BASE_URL": p.BaseURL(), "FLOWCATALYST_APPLICATION": "orders",
		"FLOWCATALYST_CLIENT_ID": fakeplatform.ClientID, "FLOWCATALYST_CLIENT_SECRET": fakeplatform.ClientSecret,
	}
	maps.Copy(e, env)
	r := &secretsRig{t: t, platform: p, clock: fwtest.NewClock(time.Date(2026, 11, 7, 12, 0, 0, 0, time.UTC)), logs: &lockedBuffer{}}
	app := fw.New("orders", fw.WithEnvironment(e), fw.WithConfigDir(t.TempDir()), fw.WithOutput(io.Discard, io.Discard), fw.WithClock(r.clock),
		fw.WithLogger(slog.New(slog.NewTextHandler(r.logs, &slog.HandlerOptions{Level: slog.LevelDebug}))))
	fcd := flowcatalyst.Declare(app)
	sd := secrets.Declare(app)
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		fc, err := flowcatalyst.Build(ctx, b, fcd)
		if err != nil {
			return nil, err
		}
		r.resolver, err = secrets.Build(ctx, b, sd, secrets.WithStore("platform", store(fc.Client())))
		return nil, err
	})
	if err != nil {
		return nil, err
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	return r, nil
}

func mustSecrets(t *testing.T, store func(*flowcatalyst.PlatformClient) secrets.StoreFactory) *secretsRig {
	t.Helper()
	r, err := startSecrets(t, nil, store)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func defaultStore(c *flowcatalyst.PlatformClient) secrets.StoreFactory {
	return flowcatalyst.PlatformSecrets(c)
}

// answer sets the platform's answer to the reveal of ftp-password in section secrets.
func (r *secretsRig) answer(status int, body string) { r.platform.RespondGet(revealPath, status, body) }

// revealOf answers like the platform: the stored client id whatever the request named.
func revealOf(value string) string {
	return fmt.Sprintf(`{"section":"secrets","property":"ftp-password","clientId":"clt_0ACME","value":%q,"updatedAt":"2026-11-07T10:00:00Z"}`, value)
}

func (r *secretsRig) reveals() []fakeplatform.Recorded {
	var out []fakeplatform.Recorded
	for _, rec := range r.platform.Requests() {
		if strings.HasSuffix(rec.Path, "/secret") {
			out = append(out, rec)
		}
	}
	return out
}

func (r *secretsRig) require(c fw.ClientID) (string, error) {
	s, err := r.resolver.Require(r.t.Context(), c, ftpRef)
	if err != nil {
		return "", err
	}
	return s.Reveal(), nil
}

func TestTheDriverRevealsTheSecretAtTheClientsScopeOfTheSection(t *testing.T) {
	t.Parallel()
	r := mustSecrets(t, defaultStore)
	r.answer(200, revealOf(secretValue))
	if got, err := r.require(acmeID); err != nil || got != secretValue {
		t.Fatalf("%q %v", got, err)
	}
	calls := r.reveals()
	if len(calls) != 1 || calls[0].Path != revealPath || calls[0].Query != "clientId=clt_0ACME" ||
		!strings.HasPrefix(calls[0].Header.Get("Authorization"), "Bearer ") || calls[0].Header.Get("X-On-Behalf-Of") != "" {
		t.Fatalf("calls %+v", calls)
	}
	// a client identifier is passed through for the platform to resolve
	if _, err := r.require(fw.MustClientID("bevco")); err != nil {
		t.Fatal(err)
	}
	if calls := r.reveals(); len(calls) != 2 || calls[1].Query != "clientId=bevco" {
		t.Errorf("identifier: %+v", calls)
	}
}

func TestTheSectionOption(t *testing.T) {
	t.Parallel()
	r := mustSecrets(t, func(c *flowcatalyst.PlatformClient) secrets.StoreFactory {
		return flowcatalyst.PlatformSecrets(c).Section("ftp")
	})
	r.platform.RespondGet("/api/config/orders/ftp/ftp-password/secret", 200, revealOf("other"))
	if got, err := r.require(acmeID); err != nil || got != "other" {
		t.Fatalf("%q %v", got, err)
	}
	if calls := r.reveals(); len(calls) != 1 || calls[0].Path != "/api/config/orders/ftp/ftp-password/secret" {
		t.Errorf("calls %+v", calls)
	}
	_, err := startSecrets(t, nil, func(c *flowcatalyst.PlatformClient) secrets.StoreFactory {
		return flowcatalyst.PlatformSecrets(c).Section("Bad Section")
	})
	if err == nil || !strings.Contains(err.Error(), "section") {
		t.Errorf("a bad section: %v", err)
	}
}

func TestOnlyThePlatformsNotFoundCodesMeanNotFoundAndNotFoundIsNotCached(t *testing.T) {
	t.Parallel()
	r := mustSecrets(t, defaultStore)
	for _, code := range []string{"Config_NOT_FOUND", "Client_NOT_FOUND"} {
		r.answer(404, `{"error":"`+code+`","message":"no"}`)
		res, err := r.resolver.Resolve(t.Context(), acmeID, ftpRef)
		if err != nil || res != (secrets.NotFound{}) {
			t.Fatalf("%s: %v %v", code, res, err)
		}
	}
	r.answer(200, revealOf(secretValue))
	if got, err := r.require(acmeID); err != nil || got != secretValue {
		t.Fatalf("a secret added later is seen at once: %q %v", got, err)
	}
	r2 := mustSecrets(t, defaultStore)
	for _, body := range []string{``, `<html>gateway</html>`, `{"error":"NOT_FOUND"}`} {
		r2.answer(404, body)
		_, err := r2.resolver.Resolve(t.Context(), acmeID, ftpRef)
		if !errors.As(err, new(*secrets.StoreError)) || !strings.Contains(err.Error(), "404") || strings.Contains(err.Error(), secretValue) {
			t.Errorf("404 %q: %v", body, err)
		}
	}
}

func TestAPlainPropertyIsRefusedAndABadClientIsRefusedWithoutACall(t *testing.T) {
	t.Parallel()
	r := mustSecrets(t, defaultStore)
	r.answer(400, `{"error":"NOT_A_SECRET","message":"plain"}`)
	res, err := r.resolver.Resolve(t.Context(), acmeID, ftpRef)
	if err != nil || res != (secrets.Refused{Reason: "the platform property is not a secret"}) {
		t.Fatalf("%v %v", res, err)
	}
	r.platform.Clear()
	for _, c := range []string{"-acme", "acme-", "a.b", "clt_"} {
		res, err := r.resolver.Resolve(t.Context(), fw.MustClientID(c), ftpRef)
		if _, refused := res.(secrets.Refused); err != nil || !refused {
			t.Errorf("%s: %v %v", c, res, err)
		}
	}
	if n := len(r.reveals()); n != 0 {
		t.Errorf("%d calls for clients the platform cannot name", n)
	}
}

func TestFailuresAreStoreErrorsThatNameNoValue(t *testing.T) {
	t.Parallel()
	r := mustSecrets(t, defaultStore)
	r.answer(403, `{"error":"FORBIDDEN","message":"no"}`)
	_, err := r.resolver.Resolve(t.Context(), acmeID, ftpRef)
	if !errors.As(err, new(*secrets.StoreError)) || !strings.Contains(err.Error(), "orders") || !strings.Contains(err.Error(), "not authorised to reveal") {
		t.Errorf("403: %v", err)
	}
	for _, c := range []struct {
		status int
		body   string
	}{{500, `{"error":"INTERNAL"}`}, {200, `not json ` + secretValue}, {200, `{"value":""}`}, {409, `{"error":"CLIENT_NOT_AVAILABLE"}`}} {
		r.answer(c.status, c.body)
		_, err := r.resolver.Resolve(t.Context(), acmeID, ftpRef)
		if !errors.As(err, new(*secrets.StoreError)) || strings.Contains(err.Error(), secretValue) {
			t.Errorf("%d %s: %v", c.status, c.body, err)
		}
		var se *secrets.StoreError
		if errors.As(err, &se) && se.Unwrap() != nil {
			t.Errorf("%d: the cause is kept (a decoder's message can quote what it read)", c.status)
		}
	}
	r.platform.Close() // the platform is down
	if _, err := r.resolver.Resolve(t.Context(), fw.MustClientID("globex"), ftpRef); !errors.As(err, new(*secrets.StoreError)) {
		t.Errorf("down: %v", err)
	}
	if strings.Contains(r.logs.String(), secretValue) {
		t.Error("a log line holds the value")
	}
}

func TestAFoundValueIsCachedUnderBothNamesOfAClientUntilEvicted(t *testing.T) {
	t.Parallel()
	r := mustSecrets(t, defaultStore)
	r.answer(200, revealOf(secretValue))
	for _, c := range []fw.ClientID{acmeCode, acmeID, acmeCode} {
		if got, err := r.require(c); err != nil || got != secretValue {
			t.Fatalf("%s: %q %v", c, got, err)
		}
	}
	if calls := r.reveals(); len(calls) != 1 || calls[0].Query != "clientId=acme" {
		t.Fatalf("calls %+v (the identifier and the stored id share one entry)", calls)
	}
	r.answer(200, revealOf("rotated"))
	if err := r.resolver.Evict(acmeID, ftpRef); err != nil {
		t.Fatal(err)
	}
	if got, _ := r.require(acmeCode); got != "rotated" {
		t.Errorf("after evicting under the stored id: %q", got)
	}
	// stale while the platform fails, after the TTL
	r.clock.Advance(6 * time.Minute)
	r.answer(503, `{"error":"UNAVAILABLE"}`)
	if got, err := r.require(acmeCode); err != nil || got != "rotated" {
		t.Errorf("stale while the platform fails: %q %v", got, err)
	}
	if !strings.Contains(r.logs.String(), "serving the last value") || strings.Contains(r.logs.String(), "rotated") {
		t.Error("the stale warning, without the value")
	}
}

func TestNoCacheAsksThePlatformEveryTime(t *testing.T) {
	t.Parallel()
	r := mustSecrets(t, func(c *flowcatalyst.PlatformClient) secrets.StoreFactory {
		return flowcatalyst.PlatformSecrets(c).WithCache(secrets.NoCache())
	})
	r.answer(200, revealOf(secretValue))
	_, _ = r.require(acmeID)
	_, _ = r.require(acmeID)
	if n := len(r.reveals()); n != 2 {
		t.Errorf("%d calls", n)
	}
}

func TestADisabledPlatformFailsStartUpAndTheCorePlaceholderPointsHere(t *testing.T) {
	t.Parallel()
	_, err := startSecrets(t, map[string]string{"FLOWCATALYST_ENABLED": "false"}, defaultStore)
	if err == nil || !strings.Contains(err.Error(), "flowcatalyst.enabled") {
		t.Errorf("disabled: %v", err)
	}
	_, err = startSecrets(t, nil, func(*flowcatalyst.PlatformClient) secrets.StoreFactory { return secrets.Platform() })
	if err == nil || !strings.Contains(err.Error(), "flowcatalyst.PlatformSecrets") {
		t.Errorf("placeholder: %v", err)
	}
}
