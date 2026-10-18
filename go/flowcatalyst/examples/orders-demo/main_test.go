package main

import (
	"bytes"
	"context"
	"encoding/json/v2"
	"io"
	"net/http"
	"net/http/cookiejar"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/examples/orders-demo/ordersweb"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/internal/pgembed"
)

func TestMain(m *testing.M) {
	ordersweb.ConfigDir = filepath.Join("..", "..", "..", "..", "examples", "orders-demo", "config")
	fwtest.Main(m)
}

// The document of the whole application, written with no database and no configuration, is the
// shared vector the Java demo writes (conformance/openapi/orders-demo-admin.json), byte for byte.
func TestTheAdminDocumentIsTheSharedVector(t *testing.T) {
	t.Parallel()
	app := ordersweb.NewApp(fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir()))
	doc, problems := ordersweb.Declare(app, &extension{}).OpenAPI()
	if len(problems) > 0 {
		t.Fatalf("the document cannot be written: %v", problems)
	}
	vector, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "conformance", "openapi", "orders-demo-admin.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(doc, vector) {
		t.Fatal("the document differs from conformance/openapi/orders-demo-admin.json")
	}
}

type lockedBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *lockedBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedBuffer) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

// The launcher with the platform extension: an administrator signs in through the real round trip,
// the navigation lists every section, the platform sections read the fake, a refusing platform is
// shown as the server's own 502, and /control/reset puts the directory back.
func TestTheLauncherServesTheWholeAdminAreaAgainstItsFakePlatform(t *testing.T) {
	t.Parallel()
	appPort, err := pgembed.FreePort()
	if err != nil {
		t.Fatal(err)
	}
	controlPort, err := pgembed.FreePort()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(t.Context())
	out := &lockedBuffer{}
	done := make(chan error, 1)
	go func() {
		done <- ordersweb.RunE2E(ctx, []string{strconv.Itoa(appPort), strconv.Itoa(controlPort)}, out, newE2EExtension())
	}()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(60 * time.Second):
			t.Error("the launcher did not stop")
		}
	})
	deadline := time.Now().Add(3 * time.Minute)
	for !strings.Contains(out.String(), "READY") {
		select {
		case err := <-done:
			t.Fatalf("the launcher ended before READY: %v", err)
		default:
		}
		if time.Now().After(deadline) {
			t.Fatal("no READY line")
		}
		time.Sleep(100 * time.Millisecond)
	}
	app := "http://127.0.0.1:" + strconv.Itoa(appPort)
	ctl := "http://127.0.0.1:" + strconv.Itoa(controlPort)
	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	client := &http.Client{Jar: jar, Timeout: 30 * time.Second}
	do := func(method, target, body string, headers ...string) (int, string) {
		t.Helper()
		var rd io.Reader
		if body != "" {
			rd = strings.NewReader(body)
		}
		req, err := http.NewRequestWithContext(t.Context(), method, target, rd)
		if err != nil {
			t.Fatal(err)
		}
		for i := 0; i+1 < len(headers); i += 2 {
			req.Header.Set(headers[i], headers[i+1])
		}
		res, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return res.StatusCode, string(b)
	}
	getJSON := func(path string) (int, map[string]any) {
		t.Helper()
		status, body := do("GET", app+path, "", "Accept", "application/json")
		var m map[string]any
		_ = json.Unmarshal([]byte(body), &m)
		return status, m
	}

	do("POST", ctl+"/control/sign-in-as?roles=orders-demo:admin&name=Ada", "")
	do("GET", app+"/auth/login?returnTo=%2Fadmin", "")

	status, nav := getJSON("/api/admin/navigation")
	if status != 200 {
		t.Fatalf("navigation: %d %v", status, nav)
	}
	var ids []string
	for _, s := range nav["sections"].([]any) { //nolint:forcetypeassert // the test's own answer
		ids = append(ids, s.(map[string]any)["id"].(string)) //nolint:forcetypeassert // the test's own answer
	}
	if want := []string{"about", "event-types", "roles", "users", "activity", "config", "orders"}; !slices.Equal(ids, want) {
		t.Fatalf("sections %v, want %v", ids, want)
	}
	for path, key := range map[string]string{"/api/admin/event-types": "items", "/api/admin/roles": "items", "/api/admin/users": "items", "/api/admin/events": "items"} {
		status, body := getJSON(path)
		if items, _ := body[key].([]any); status != 200 || len(items) == 0 {
			t.Errorf("%s: %d %v", path, status, body)
		}
	}
	if _, about := getJSON("/api/admin/about"); about["application"] != "orders-demo" || about["ready"] != true {
		t.Errorf("about: %v", about)
	}

	// a refusing platform is the server's own 502, and the directory comes back with reset
	do("POST", ctl+"/control/platform-reads?value=403", "")
	if status, body := getJSON("/api/admin/users"); status != 502 || body["error"] != "PLATFORM_ERROR" || !strings.Contains(body["message"].(string), "service account") { //nolint:forcetypeassert // the test's own answer
		t.Errorf("a refusing platform: %d %v", status, body)
	}
	do("POST", ctl+"/control/platform-reads?value=", "")
	if status, _ := getJSON("/api/admin/users"); status != 200 {
		t.Errorf("platform-reads reset: %d", status)
	}
	status, body := do("POST", app+"/api/admin/users", `{"email":"new.person@example.test","name":"New Person","clientId":"acme","roles":["orders-demo:clerk"]}`,
		"Content-Type", "application/json", "Sec-Fetch-Site", "same-origin")
	if status != 201 {
		t.Fatalf("invite: %d %s", status, body)
	}
	if _, inv := getJSON("/api/admin/invitations"); len(inv["items"].([]any)) != 3 { //nolint:forcetypeassert // the test's own answer
		t.Errorf("invitations after the invite: %v", inv)
	}
	do("POST", ctl+"/control/reset", "")
	if _, inv := getJSON("/api/admin/invitations"); len(inv["items"].([]any)) != 2 { //nolint:forcetypeassert // the test's own answer
		t.Errorf("invitations after reset: %v", inv)
	}
}
