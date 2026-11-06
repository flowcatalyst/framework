package auth_test

import (
	"bytes"
	"context"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authredis"
	"github.com/flowcatalyst/framework/go/auth/authtest"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/redisfw"
)

func TestMain(m *testing.M) { fwtest.Main(m) }

// rawKey is a 32-byte cookie key made of one repeated byte, in the auth.cookie-keys list format.
func rawKey(kid string, b byte) (entry string, raw []byte) {
	raw = bytes.Repeat([]byte{b}, 32)
	return kid + ":" + base64.RawURLEncoding.EncodeToString(raw), raw
}

// testNonce is contract 15.4 written out a second time, independently of the package.
func testNonce(raw []byte, at time.Time) string {
	k, err := hkdf.Key(sha256.New, raw, nil, "fw-field-nonce", 32)
	if err != nil {
		panic(err)
	}
	bucket := make([]byte, 8)
	binary.BigEndian.PutUint64(bucket, uint64(at.Unix()/300))
	m := hmac.New(sha256.New, k)
	m.Write([]byte("fw-field-nonce"))
	m.Write(bucket)
	return base64.RawURLEncoding.EncodeToString(append(bucket, m.Sum(nil)[:16]...))
}

type fieldApp struct {
	*app
	device  *authtest.FieldDevice
	testing *auth.Testing
}

var orders = fw.MustApplicationCode("orders")

func startField(t *testing.T, c config, field ...auth.FieldOption) *fieldApp {
	t.Helper()
	entry, _ := rawKey("k1", 1)
	if c.env == nil {
		c.env = map[string]string{}
	}
	if _, set := c.env["AUTH_COOKIE_KEYS"]; !set {
		c.env["AUTH_COOKIE_KEYS"] = entry
	}
	c.options = append([]auth.Option{auth.Application(orders), auth.FieldWorkers(field...)}, c.options...)
	c.declare = append([]auth.DeclareOption{auth.DeclareFieldWorkers()}, c.declare...)
	a := mustStart(t, c)
	a.provider.FieldAudience(orders.String())
	tt, err := a.module.Testing()
	if err != nil {
		t.Fatal(err)
	}
	return &fieldApp{app: a, device: authtest.NewFieldDevice(authtest.DeviceClock(a.clock)), testing: tt}
}

func (f *fieldApp) headers(method, path string, who auth.FieldPrincipal) []string {
	f.t.Helper()
	h, err := f.testing.FieldRequest(f.provider, f.device, method, path, who)
	if err != nil {
		f.t.Fatal(err)
	}
	return []string{"Authorization", h.Get("Authorization"), "DPoP", h.Get("DPoP")}
}

// call sends a field request built by the test helper.
func (f *fieldApp) call(method, path string, who auth.FieldPrincipal) response {
	f.t.Helper()
	return f.do(method, path, "", f.headers(method, path, who)...)
}

// raw sends a field request with a token and a proof the test made itself.
func (f *fieldApp) raw(method, path, token, proof string) response {
	f.t.Helper()
	h := []string{"Authorization", "DPoP " + token}
	if proof != "" {
		h = append(h, "DPoP", proof)
	}
	return f.do(method, path, "", h...)
}

func (f *fieldApp) url(path string) string { return origin + path }

func (f *fieldApp) nonce() string {
	n, err := f.testing.FieldNonce()
	if err != nil {
		f.t.Fatal(err)
	}
	return n
}

// refused checks a 401 invalid_token answer.
func refused(t *testing.T, name string, r response) {
	t.Helper()
	if r.status != 401 || r.str("error") != "UNAUTHENTICATED" || r.header.Get("WWW-Authenticate") != `DPoP error="invalid_token"` {
		t.Errorf("%s: %d %s %v", name, r.status, r.body, r.header)
	}
	if r.header.Get("DPoP-Nonce") == "" {
		t.Errorf("%s: a DPoP response carries a fresh nonce even when it is an error", name)
	}
}

func TestFieldRequestBuildsTheContext(t *testing.T) {
	f := startField(t, config{}, auth.MapFieldPermissions(auth.FieldPermissionsAll("orders:thing:create")))
	r := f.call("GET", "/api/me", auth.FieldPrincipal{WorkerID: "fwu_7", ClientIdentifier: "acme", WorkerCode: "W7", DeviceMode: "SHARED"})
	if r.status != 200 || r.str("principal") != "fwu_7" || r.str("principalKind") != "FIELD_WORKER" || r.str("client") != "acme" ||
		r.str("application") != "orders" || r.str("source") != "FIELD" {
		t.Fatalf("%d %s", r.status, r.body)
	}
	if got := r.tree()["permissions"].([]any); len(got) != 1 || got[0] != "orders:thing:create" {
		t.Fatalf("permissions %v", got)
	}
	if n := r.header.Get("DPoP-Nonce"); n == "" || len(n) != 32 {
		t.Fatalf("DPoP-Nonce %q", n)
	}
	// the field part of Authenticated; no platform authority
	r = f.call("POST", "/api/field/only", auth.FieldPrincipal{WorkerCode: "W7", DeviceID: "fwd_9", SessionID: "fws_3", ClientID: "clt_5"})
	if r.status != 200 || r.str("worker") != "W7" || r.str("device") != "fwd_9" || r.str("session") != "fws_3" || r.str("clientId") != "clt_5" || r.str("tier") != "" {
		t.Fatalf("%d %s", r.status, r.body)
	}
	if r.tree()["roles"] != float64(0) {
		t.Fatalf("roles %s", r.body)
	}
}

func TestFieldUnenrolledAndActor(t *testing.T) {
	f := startField(t, config{}, auth.MapFieldPermissions(auth.FieldPermissionsByDeviceModes(map[string][]string{
		"UNENROLLED": {"orders:thing:create"},
	})))
	// no device_id, mode UNENROLLED, an act claim: the worker stays the principal, the actor is the operator
	r := f.call("GET", "/api/me", auth.FieldPrincipal{
		WorkerID: "fwu_7", DeviceMode: "UNENROLLED", Test: true,
		Actor: &auth.FieldActor{ID: "prn_ADMIN", Type: "principal", Name: "Admin"},
	})
	if r.status != 200 || r.str("principal") != "fwu_7" || r.str("actor") != "prn_ADMIN|principal|Admin" {
		t.Fatalf("%d %s", r.status, r.body)
	}
	if got := r.tree()["permissions"].([]any); len(got) != 1 {
		t.Fatalf("UNENROLLED permissions %v", got)
	}
	r = f.call("POST", "/api/field/only", auth.FieldPrincipal{DeviceMode: "UNENROLLED"})
	if r.status != 200 || r.str("device") != "" {
		t.Fatalf("%d %s", r.status, r.body)
	}
	// a mode with no entry fails closed
	if r := f.call("POST", "/api/things", auth.FieldPrincipal{DeviceMode: "PERSONAL"}); r.status != 403 {
		t.Fatalf("personal: %d %s", r.status, r.body)
	}
	// no actor without an act claim
	if r := f.call("GET", "/api/me", auth.FieldPrincipal{}); r.str("actor") != "" {
		t.Fatalf("%s", r.body)
	}
}

func TestFieldPermissionMapping(t *testing.T) {
	t.Run("the default grants none", func(t *testing.T) {
		f := startField(t, config{})
		r := f.call("POST", "/api/things", auth.FieldPrincipal{})
		if r.status != 403 || r.str("error") != "FORBIDDEN" {
			t.Fatalf("%d %s", r.status, r.body)
		}
	})
	t.Run("all", func(t *testing.T) {
		f := startField(t, config{}, auth.MapFieldPermissions(auth.FieldPermissionsAll("orders:thing:create")))
		if r := f.call("POST", "/api/things", auth.FieldPrincipal{}); r.status != 201 {
			t.Fatalf("%d %s", r.status, r.body)
		}
	})
	t.Run("by device mode", func(t *testing.T) {
		f := startField(t, config{}, auth.MapFieldPermissions(auth.FieldPermissionsByDeviceMode([]string{"orders:thing:create"}, nil)))
		if r := f.call("POST", "/api/things", auth.FieldPrincipal{DeviceMode: "PERSONAL"}); r.status != 201 {
			t.Fatalf("personal: %d %s", r.status, r.body)
		}
		if r := f.call("POST", "/api/things", auth.FieldPrincipal{DeviceMode: "SHARED"}); r.status != 403 {
			t.Fatalf("shared: %d %s", r.status, r.body)
		}
	})
	t.Run("the session and bearer mapping is not used", func(t *testing.T) {
		f := startField(t, config{options: []auth.Option{auth.Permissions(auth.NoPermissions().And(func(auth.Principal) []string { return []string{"orders:thing:create"} }))}})
		if r := f.call("POST", "/api/things", auth.FieldPrincipal{}); r.status != 403 {
			t.Fatalf("%d %s", r.status, r.body)
		}
	})
}

type tenantKey struct{}

// tenantResolver puts the X-Tenant header into the request context before the module's resolver
// runs, as an application's middleware would.
type tenantResolver struct{ next httpfw.ContextResolver }

func (t tenantResolver) Resolve(r *http.Request) (fw.Context, error) {
	return t.next.Resolve(r.WithContext(context.WithValue(r.Context(), tenantKey{}, "acme-db")))
}

func TestFieldPermissionResolver(t *testing.T) {
	wrap := func(n httpfw.ContextResolver) httpfw.ContextResolver { return tenantResolver{n} }
	who := auth.FieldPrincipal{WorkerCode: "W1"}
	t.Run("the request context reaches the resolver and its permissions apply", func(t *testing.T) {
		var seen string
		f := startField(t, config{wrap: wrap}, auth.FieldPermissionsFrom(func(ctx context.Context, p auth.FieldPrincipal) ([]string, error) {
			seen, _ = ctx.Value(tenantKey{}).(string)
			if p.WorkerCode != "W1" {
				return nil, errors.New("wrong principal")
			}
			return []string{"orders:thing:create"}, nil
		}))
		if r := f.call("POST", "/api/things", who); r.status != 201 {
			t.Fatalf("%d %s", r.status, r.body)
		}
		if seen != "acme-db" {
			t.Fatalf("the resolver saw ctx value %q", seen)
		}
	})
	t.Run("an error answers 503, not no-permissions and not 401 or 403", func(t *testing.T) {
		f := startField(t, config{}, auth.FieldPermissionsFrom(func(context.Context, auth.FieldPrincipal) ([]string, error) {
			return []string{"orders:thing:create"}, errors.New("db is down")
		}))
		r := f.call("POST", "/api/things", who)
		if r.status != 503 || r.str("error") != "UNAVAILABLE" {
			t.Fatalf("%d %s", r.status, r.body)
		}
		if r.header.Get("DPoP-Nonce") == "" {
			t.Error("a DPoP response carries a fresh nonce even when it is an error")
		}
		if r.header.Get("WWW-Authenticate") != "" {
			t.Errorf("a 503 is not a challenge: %v", r.header)
		}
	})
	t.Run("an empty result grants nothing", func(t *testing.T) {
		for name, got := range map[string][]string{"nil": nil, "empty": {}} {
			f := startField(t, config{}, auth.FieldPermissionsFrom(func(context.Context, auth.FieldPrincipal) ([]string, error) { return got, nil }))
			if r := f.call("POST", "/api/things", who); r.status != 403 || r.str("error") != "FORBIDDEN" {
				t.Fatalf("%s: %d %s", name, r.status, r.body)
			}
		}
	})
	t.Run("the mapper still works", func(t *testing.T) {
		f := startField(t, config{}, auth.MapFieldPermissions(auth.FieldPermissionsAll("orders:thing:create")))
		if r := f.call("POST", "/api/things", who); r.status != 201 {
			t.Fatalf("%d %s", r.status, r.body)
		}
	})
	t.Run("the last option given wins", func(t *testing.T) {
		f := startField(t, config{}, auth.FieldPermissionsFrom(func(context.Context, auth.FieldPrincipal) ([]string, error) { return nil, errors.New("x") }),
			auth.MapFieldPermissions(auth.FieldPermissionsAll("orders:thing:create")))
		if r := f.call("POST", "/api/things", who); r.status != 201 {
			t.Fatalf("%d %s", r.status, r.body)
		}
	})
}

func TestFieldClientSelectorIsNotConsulted(t *testing.T) {
	selector := func(auth.Principal, *http.Request) (fw.ClientID, error) { return fw.MustClientID("other"), nil }
	f := startField(t, config{options: []auth.Option{auth.Clients(selector)}})
	r := f.call("GET", "/api/me", auth.FieldPrincipal{ClientIdentifier: "acme"})
	if r.status != 200 || r.str("client") != "acme" {
		t.Fatalf("%d %s", r.status, r.body)
	}
}

func TestRequireSource(t *testing.T) {
	f := startField(t, config{})
	if r := f.call("POST", "/api/field/only", auth.FieldPrincipal{}); r.status != 200 {
		t.Fatalf("field on a field-only route: %d %s", r.status, r.body)
	}
	if r := f.call("POST", "/api/field/never", auth.FieldPrincipal{}); r.status != 403 || r.str("error") != "FORBIDDEN" {
		t.Fatalf("field on a never-field route: %d %s", r.status, r.body)
	}
	bearer := f.do("POST", "/api/field/only", "", "Authorization", "Bearer "+f.provider.AccessToken(nil))
	if bearer.status != 403 {
		t.Fatalf("bearer on a field-only route: %d %s", bearer.status, bearer.body)
	}
	if r := f.do("POST", "/api/field/never", "", "Authorization", "Bearer "+f.provider.AccessToken(nil)); r.status != 200 {
		t.Fatalf("bearer on a never-field route: %d %s", r.status, r.body)
	}
}

func TestFieldRefusals(t *testing.T) {
	f := startField(t, config{})
	const path = "/api/echo"
	token := func(changes map[string]any) string {
		return f.provider.FieldTokenFor(f.device, changes)
	}
	proof := func(token string, opts ...authtest.ProofOption) string {
		return f.device.Proof("POST", f.url(path), f.nonce(), token, opts...)
	}
	good := token(nil)

	t.Run("a field token as Bearer", func(t *testing.T) {
		r := f.do("POST", path, "", "Authorization", "Bearer "+good)
		if r.status != 401 || r.str("error") != "UNAUTHENTICATED" {
			t.Fatalf("%d %s", r.status, r.body)
		}
	})
	t.Run("an API token as DPoP", func(t *testing.T) {
		api := f.provider.AccessToken(nil)
		refused(t, "api token", f.raw("POST", path, api, proof(api)))
	})
	t.Run("a missing proof", func(t *testing.T) {
		refused(t, "no proof", f.raw("POST", path, good, ""))
	})
	t.Run("two proofs", func(t *testing.T) {
		refused(t, "two", f.do("POST", path, "", "Authorization", "DPoP "+good, "DPoP", proof(good), "DPoP", proof(good)))
	})
	t.Run("a bad token", func(t *testing.T) {
		refused(t, "garbage", f.raw("POST", path, "not.a.jwt", proof(good)))
		refused(t, "tampered", f.raw("POST", path, good[:len(good)-4]+"AAAA", proof(good)))
	})
	for name, changes := range map[string]map[string]any{
		"wrong audience":   {"aud": "billing"},
		"audience a list":  {"aud": []any{"orders", "orders"}},
		"wrong issuer":     {"iss": "https://elsewhere.example"},
		"expired":          {"exp": f.clock.Now().Add(-5 * time.Minute).Unix()},
		"not yet valid":    {"nbf": f.clock.Now().Add(5 * time.Minute).Unix()},
		"issued later":     {"iat": f.clock.Now().Add(5 * time.Minute).Unix()},
		"token_use api":    {"token_use": "api"},
		"token_use absent": {"token_use": nil},
		"sub not fwu":      {"sub": "prn_1"},
		"no client_id":     {"client_id": nil},
		"no identifier":    {"client_identifier": nil},
		"odd identifier":   {"client_identifier": "a b"},
		"no sid":           {"sid": nil},
		"no cnf":           {"cnf": nil},
	} {
		t.Run(name, func(t *testing.T) {
			tk := token(changes)
			refused(t, name, f.raw("POST", path, tk, proof(tk)))
		})
	}
	t.Run("typ is not fc-field+jwt", func(t *testing.T) {
		tk := f.provider.Sign(map[string]any{
			"iss": f.provider.Issuer(), "aud": "orders", "sub": "fwu_1", "token_use": "field", "client_id": "c", "client_identifier": "c",
			"device_id": "d", "sid": "s", "cnf": map[string]any{"jkt": f.device.JKT()}, "exp": f.clock.Now().Add(time.Minute).Unix(),
		})
		refused(t, "typ", f.raw("POST", path, tk, proof(tk)))
	})
	t.Run("proof rules", func(t *testing.T) {
		other := authtest.NewFieldDevice(authtest.DeviceClock(f.clock))
		for name, opt := range map[string]authtest.ProofOption{
			"signed by another key": authtest.ProofSignedBy(other),
			"wrong method":          authtest.ProofHTM("GET"),
			"wrong url":             authtest.ProofHTU(f.url("/api/other")),
			"url with a query":      authtest.ProofHTU(f.url(path) + "?a=1"),
			"another host":          authtest.ProofHTU("https://evil.example" + path),
			"wrong ath":             authtest.ProofATH("AAAA"),
			"stale":                 authtest.ProofIssuedAt(f.clock.Now().Add(-25 * time.Hour)),
			"from the future":       authtest.ProofIssuedAt(f.clock.Now().Add(25 * time.Hour)),
			"typ":                   authtest.ProofHeader("typ", "JWT"),
			"alg":                   authtest.ProofHeader("alg", "RS256"),
			"alg none":              authtest.ProofHeader("alg", "none"),
			"private jwk member":    authtest.ProofJWKMember("d", "AAAA"),
			"no jti":                authtest.ProofWithoutJTI(),
			"bad signature":         authtest.ProofTamperedSignature(),
		} {
			refused(t, name, f.raw("POST", path, good, proof(good, opt)))
		}
	})
	t.Run("an old but allowed proof is accepted", func(t *testing.T) {
		if r := f.raw("POST", path, good, proof(good, authtest.ProofIssuedAt(f.clock.Now().Add(-23*time.Hour)))); r.status != 200 {
			t.Fatalf("%d %s", r.status, r.body)
		}
	})
	t.Run("a query on the request is ignored", func(t *testing.T) {
		if r := f.do("POST", path+"?page=2", "", f.headers("POST", path, auth.FieldPrincipal{})...); r.status != 200 {
			t.Fatalf("%d %s", r.status, r.body)
		}
	})
	t.Run("a PIN-change token", func(t *testing.T) {
		tk := token(map[string]any{"pcr": true})
		r := f.raw("POST", path, tk, proof(tk))
		if r.status != 403 || r.str("error") != "PIN_CHANGE_REQUIRED" || r.header.Get("DPoP-Nonce") == "" {
			t.Fatalf("%d %s", r.status, r.body)
		}
		tk = token(map[string]any{"pcr": false})
		if r := f.raw("POST", path, tk, proof(tk)); r.status != 200 {
			t.Fatalf("pcr false: %d %s", r.status, r.body)
		}
	})
	t.Run("no fall back to a session cookie", func(t *testing.T) {
		b := f.browser()
		if b.signIn("/").status != 302 {
			t.Fatal("sign in")
		}
		r := f.do("POST", path, "", "Cookie", b.cookieHeader(), "Authorization", "DPoP "+good, "Sec-Fetch-Site", "same-origin")
		refused(t, "cookie plus bad dpop", r)
	})
	t.Run("a 401 is not a redirect", func(t *testing.T) {
		r := f.do("GET", "/api/me", "", "Authorization", "DPoP junk", "Accept", "text/html", "Sec-Fetch-Mode", "navigate")
		if r.status != 401 {
			t.Fatalf("%d", r.status)
		}
	})
}

func TestFieldReplayMemory(t *testing.T) {
	f := startField(t, config{})
	h := f.headers("POST", "/api/echo", auth.FieldPrincipal{})
	if r := f.do("POST", "/api/echo", "", h...); r.status != 200 {
		t.Fatalf("first: %d %s", r.status, r.body)
	}
	refused(t, "replayed", f.do("POST", "/api/echo", "", h...))
}

func TestFieldReplayRedis(t *testing.T) {
	r := fwtest.NewRedis(t)
	c, err := redisfw.Open(t.Context(), "default", r.URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	cache := authredis.NewReplayCache(c.Redis()).WithPrefix(r.Prefix + authredis.ReplayPrefix)
	one := startField(t, config{}, auth.FieldReplay(cache))
	h := one.headers("POST", "/api/echo", auth.FieldPrincipal{})
	if got := one.do("POST", "/api/echo", "", h...); got.status != 200 {
		t.Fatalf("first: %d %s", got.status, got.body)
	}
	// the key is fw:dpop:{jkt}:{jti} and holds for 11 minutes
	refused(t, "replayed", one.do("POST", "/api/echo", "", h...))
	keys := c.Redis().Keys(t.Context(), r.Prefix+authredis.ReplayPrefix+one.device.JKT()+":*").Val()
	if len(keys) != 1 {
		t.Fatalf("keys %v", keys)
	}
	if ttl := c.Redis().PTTL(t.Context(), keys[0]).Val(); ttl <= 10*time.Minute || ttl > 11*time.Minute {
		t.Fatalf("ttl %v: 11 minutes", ttl)
	}
	first, err := cache.Remember(t.Context(), "jkt", "jti", time.Minute)
	if err != nil || !first {
		t.Fatalf("first %v %v", first, err)
	}
	if first, err = cache.Remember(t.Context(), "jkt", "jti", time.Minute); err != nil || first {
		t.Fatalf("second %v %v", first, err)
	}
	if first, _ = cache.Remember(t.Context(), "other", "jti", time.Minute); !first {
		t.Fatal("another device key is another entry")
	}
}

func TestFieldReplayCacheDown(t *testing.T) {
	f := startField(t, config{}, auth.FieldReplay(brokenCache{}))
	r := f.call("POST", "/api/echo", auth.FieldPrincipal{})
	if r.status != 503 || r.str("error") != "UNAVAILABLE" {
		t.Fatalf("%d %s", r.status, r.body)
	}
}

type brokenCache struct{}

func (brokenCache) Remember(context.Context, string, string, time.Duration) (bool, error) {
	return false, errors.New("redis is down")
}

func TestFieldNonces(t *testing.T) {
	newEntry, newRaw := rawKey("new", 2)
	oldEntry, oldRaw := rawKey("old", 3)
	_, goneRaw := rawKey("gone", 4)
	f := startField(t, config{env: map[string]string{"AUTH_COOKIE_KEYS": newEntry + "," + oldEntry}})
	const path = "/api/echo"
	token := f.provider.FieldTokenFor(f.device, nil)
	withNonce := func(nonce string) response {
		return f.raw("POST", path, token, f.device.Proof("POST", f.url(path), nonce, token))
	}
	useNonce := func(name string, r response) {
		t.Helper()
		if r.status != 401 || r.str("error") != "USE_DPOP_NONCE" || r.header.Get("WWW-Authenticate") != `DPoP error="use_dpop_nonce"` {
			t.Errorf("%s: %d %s %v", name, r.status, r.body, r.header)
		}
		if n := r.header.Get("DPoP-Nonce"); len(n) != 32 {
			t.Errorf("%s: DPoP-Nonce %q", name, n)
		}
	}
	now := f.clock.Now()

	t.Run("the algorithm of 15.4", func(t *testing.T) {
		if got, want := f.nonce(), testNonce(newRaw, now); got != want {
			t.Fatalf("issued %q, want %q", got, want)
		}
	})
	t.Run("a missing nonce, then the retry", func(t *testing.T) {
		r := withNonce("")
		useNonce("missing", r)
		n, ok := authtest.NonceFrom(r.header)
		if !ok {
			t.Fatal("no nonce")
		}
		if r := withNonce(n); r.status != 200 {
			t.Fatalf("retry: %d %s", r.status, r.body)
		}
	})
	t.Run("the current key, the old key and the previous bucket are accepted", func(t *testing.T) {
		if r := withNonce(testNonce(newRaw, now)); r.status != 200 {
			t.Fatalf("current: %d %s", r.status, r.body)
		}
		if r := withNonce(testNonce(oldRaw, now)); r.status != 200 {
			t.Fatalf("a configured earlier key: %d %s", r.status, r.body)
		}
		if r := withNonce(testNonce(newRaw, now.Add(-5*time.Minute))); r.status != 200 {
			t.Fatalf("previous bucket: %d %s", r.status, r.body)
		}
	})
	t.Run("refused", func(t *testing.T) {
		useNonce("a key that is rotated out", withNonce(testNonce(goneRaw, now)))
		useNonce("two buckets ago", withNonce(testNonce(newRaw, now.Add(-10*time.Minute))))
		useNonce("the next bucket", withNonce(testNonce(newRaw, now.Add(5*time.Minute))))
		useNonce("garbage", withNonce("AAAA"))
		useNonce("not base64", withNonce("!!!!"))
		good := testNonce(newRaw, now)
		bad := []byte(good)
		bad[len(bad)-2] ^= 1
		useNonce("one character changed", withNonce(string(bad)))
	})
	t.Run("a nonce from two buckets ago once the clock has moved", func(t *testing.T) {
		old := f.nonce()
		f.clock.Set(now.Add(10 * time.Minute))
		tk := f.provider.FieldTokenFor(f.device, nil)
		r := f.raw("POST", path, tk, f.device.Proof("POST", f.url(path), old, tk))
		useNonce("expired by the clock", r)
		f.clock.Set(now)
	})
}

func TestFieldNotEnabled(t *testing.T) {
	a := mustStart(t, config{})
	device := authtest.NewFieldDevice(authtest.DeviceClock(a.clock))
	token := a.provider.FieldTokenFor(device, map[string]any{"aud": "orders"})
	r := a.do("POST", "/api/echo", "", "Authorization", "DPoP "+token, "DPoP", device.Proof("POST", origin+"/api/echo", "", token))
	if r.status != 401 || r.str("error") != "UNAUTHENTICATED" || r.header.Get("DPoP-Nonce") != "" {
		t.Fatalf("%d %s %v", r.status, r.body, r.header)
	}
	tt, err := a.module.Testing()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := tt.FieldRequest(a.provider, device, "GET", "/api/me", auth.FieldPrincipal{}); err == nil {
		t.Fatal("FieldRequest without FieldWorkers")
	}
	if a.module.FieldTokens() != nil {
		t.Fatal("no verifier")
	}
}

func TestFieldKeysUnreadable(t *testing.T) {
	f := startField(t, config{})
	h := f.headers("POST", "/api/echo", auth.FieldPrincipal{})
	f.provider.Down(true)
	r := f.do("POST", "/api/echo", "", h...)
	if r.status != 503 || r.str("error") != "UNAVAILABLE" {
		t.Fatalf("%d %s", r.status, r.body)
	}
}

func TestFieldStartup(t *testing.T) {
	entry, _ := rawKey("k1", 1)
	t.Run("needs the application", func(t *testing.T) {
		_, err := startApp(t, config{env: map[string]string{"AUTH_COOKIE_KEYS": entry}, options: []auth.Option{auth.FieldWorkers()}, declare: []auth.DeclareOption{auth.DeclareFieldWorkers()}})
		if err == nil || !strings.Contains(err.Error(), "auth.Application") {
			t.Fatalf("%v", err)
		}
	})
	t.Run("needs cookie keys", func(t *testing.T) {
		_, err := startApp(t, config{options: []auth.Option{auth.Application(orders), auth.FieldWorkers()}, declare: []auth.DeclareOption{auth.DeclareFieldWorkers()}})
		if err == nil || !strings.Contains(err.Error(), "auth.cookie-keys") {
			t.Fatalf("%v", err)
		}
	})
	t.Run("the memory replay cache warns once", func(t *testing.T) {
		var logs bytes.Buffer
		startField(t, config{logger: slog.New(slog.NewTextHandler(&logs, nil))})
		if n := strings.Count(logs.String(), "replay cache is in memory"); n != 1 || !strings.Contains(logs.String(), "level=WARN") {
			t.Fatalf("%d warnings:\n%s", n, logs.String())
		}
	})
	t.Run("a shared replay cache does not warn", func(t *testing.T) {
		var logs bytes.Buffer
		startField(t, config{logger: slog.New(slog.NewTextHandler(&logs, nil))}, auth.FieldReplay(auth.NewMemoryFieldReplayCache(fwtest.NewClock(begin))))
		if strings.Contains(logs.String(), "replay cache is in memory") {
			t.Fatalf("warned:\n%s", logs.String())
		}
	})
}
