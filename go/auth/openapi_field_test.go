package auth_test

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

type fieldPing struct {
	OK bool `json:"ok"`
}

// document declares auth with the given options and one operation that requires a context.
func document(t *testing.T, opts ...auth.DeclareOption) map[string]any {
	t.Helper()
	app := fw.New("field-doc")
	web := httpfw.Declare(app)
	web.Info("field-doc", "1")
	auth.Declare(app, web, opts...)
	web.Operations(httpfw.Get[fieldPing]("/api/ping").ID("ping").RequiresContext().Build())
	raw, problems := web.OpenAPI()
	if len(problems) > 0 {
		t.Fatalf("the document cannot be written: %v", problems)
	}
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	return doc
}

func schemes(doc map[string]any) map[string]any {
	return doc["components"].(map[string]any)["securitySchemes"].(map[string]any)
}

func security(t *testing.T, doc map[string]any) string {
	t.Helper()
	op := doc["paths"].(map[string]any)["/api/ping"].(map[string]any)["get"].(map[string]any)
	b, err := json.Marshal(op["security"])
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// OpenAPI contract section 7, amended 2026-11-03: with field workers the document lists the dpop
// scheme, exactly this JSON, and operations list session, bearer, dpop.
func TestOpenAPIDPoPScheme(t *testing.T) {
	t.Parallel()
	doc := document(t, auth.DeclareFieldWorkers())
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(schemes(doc)["dpop"]); err != nil {
		t.Fatal(err)
	}
	got := strings.TrimSpace(buf.String())
	const want = `{"bearerFormat":"fc-field+jwt","description":"A FlowCatalyst field access token as ` + "`Authorization: DPoP <token>`" + `, with a ` + "`DPoP`" + ` proof header (RFC 9449, ES256). The application answers ` + "`401`" + ` with a ` + "`DPoP-Nonce`" + ` header when the proof needs its nonce.","scheme":"DPoP","type":"http"}`
	if got != want {
		t.Fatalf("dpop scheme:\n got %s\nwant %s", got, want)
	}
	if sec := security(t, doc); sec != `[{"session":[]},{"bearer":[]},{"dpop":[]}]` {
		t.Fatalf("operation security: %s", sec)
	}
}

// The member order is part of the contract: check the raw bytes, not the decoded map.
func TestOpenAPIDPoPMemberOrder(t *testing.T) {
	t.Parallel()
	app := fw.New("field-doc")
	web := httpfw.Declare(app)
	web.Info("field-doc", "1")
	auth.Declare(app, web, auth.DeclareFieldWorkers())
	raw, problems := web.OpenAPI()
	if len(problems) > 0 {
		t.Fatal(problems)
	}
	s := string(raw)
	i := strings.Index(s, `"dpop"`)
	if i < 0 {
		t.Fatal("no dpop scheme")
	}
	rest := s[i:]
	last := -1
	for _, k := range []string{`"type"`, `"scheme"`, `"bearerFormat"`, `"description"`} {
		at := strings.Index(rest, k)
		if at <= last {
			t.Fatalf("member %s out of order in %s", k, rest[:min(len(rest), 400)])
		}
		last = at
	}
}

// Without field workers the document has no dpop scheme and operations list session and bearer.
func TestOpenAPINoDPoPWithoutFieldWorkers(t *testing.T) {
	t.Parallel()
	doc := document(t)
	if _, has := schemes(doc)["dpop"]; has {
		t.Fatal("dpop listed without field workers")
	}
	if sec := security(t, doc); sec != `[{"session":[]},{"bearer":[]}]` {
		t.Fatalf("operation security: %s", sec)
	}
}
