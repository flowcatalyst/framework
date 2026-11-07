package usecase_test

import (
	"encoding/json/v2"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/usecase"
)

// A dispatch job carries the client it was created for, and the idempotency key and connection only
// when the use case names them (fulfil-go port, item 16): a job created in a client's context is
// delivered for that client, not for the platform's.

func jobPayload(t *testing.T, cx fw.Context, opts ...usecase.JobOption) map[string]any {
	t.Helper()
	j, err := usecase.NewDispatchJob("test:things:notify", "https://hooks.example.com/n", opts...)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal([]byte(usecase.DispatchJobPayload(cx, "job-1", j)), &m); err != nil {
		t.Fatal(err)
	}
	return m
}

func TestAJobWrittenInAClientsContextNamesThatClient(t *testing.T) {
	t.Parallel()
	p := jobPayload(t, fw.ContextFor(fw.MustClientID("acme")))
	if p["clientCode"] != "acme" {
		t.Errorf("clientCode %v, want acme: %v", p["clientCode"], p)
	}
	if _, has := p["clientId"]; has {
		t.Errorf("clientId written without PlatformClientID: %v", p)
	}
}

func TestAJobOfThePlatformsOwnContextNamesNoClient(t *testing.T) {
	t.Parallel()
	p := jobPayload(t, fw.ContextFor(fw.PlatformClient))
	if _, has := p["clientCode"]; has {
		t.Errorf("a platform-scoped job named a client: %v", p)
	}
}

func TestAnExplicitPlatformClientIDWinsOverTheContextsClient(t *testing.T) {
	t.Parallel()
	p := jobPayload(t, fw.ContextFor(fw.MustClientID("acme")), usecase.PlatformClientID("clt_0ABC"))
	if p["clientId"] != "clt_0ABC" {
		t.Errorf("clientId %v", p["clientId"])
	}
	if _, has := p["clientCode"]; has {
		t.Errorf("clientCode written beside an explicit clientId: %v", p)
	}
}

func TestTheIdempotencyKeyAndConnectionAreWrittenOnlyWhenNamed(t *testing.T) {
	t.Parallel()
	cx := fw.ContextFor(fw.MustClientID("acme"))
	plain := jobPayload(t, cx, usecase.ExternalID("ord_1"))
	for _, k := range []string{"idempotencyKey", "connectionId"} {
		if _, has := plain[k]; has {
			t.Errorf("%s written without being named (never derived from the external id): %v", k, plain)
		}
	}
	named := jobPayload(t, cx, usecase.ExternalID("ord_1"), usecase.IdempotencyKey("create-pick:ord_1"), usecase.ConnectionID("con_0ABCDEFGHJKMN"))
	if named["idempotencyKey"] != "create-pick:ord_1" || named["connectionId"] != "con_0ABCDEFGHJKMN" {
		t.Errorf("payload %v", named)
	}
}

func TestTheNewFieldsAreWrittenInTheirPlace(t *testing.T) {
	t.Parallel()
	j, err := usecase.NewDispatchJob("test:things:notify", "https://hooks.example.com/n",
		usecase.Descriptor("d"), usecase.ConnectionID("con_1"), usecase.IdempotencyKey("k"))
	if err != nil {
		t.Fatal(err)
	}
	got := usecase.DispatchJobPayload(fw.ContextFor(fw.MustClientID("acme")), "job-1", j)
	want := `{"id":"job-1","kind":"TASK","code":"test:things:notify","source":"test","targetUrl":"https://hooks.example.com/n",` +
		`"payload":"{}","payloadContentType":"application/json","dataOnly":false,"correlationId":"` +
		`CORR","clientCode":"acme","descriptor":"d","connectionId":"con_1","idempotencyKey":"k"}`
	i := strings.Index(got, `"correlationId":"`) + len(`"correlationId":"`)
	got = got[:i] + "CORR" + got[i+strings.IndexByte(got[i:], '"'):]
	if got != want {
		t.Errorf("payload\n got %s\nwant %s", got, want)
	}
}

func TestTheNewJobFieldsAreChecked(t *testing.T) {
	t.Parallel()
	for name, c := range map[string]struct {
		opt  usecase.JobOption
		want string
	}{
		"a blank idempotency key":    {usecase.IdempotencyKey("  "), "idempotencyKey must not be blank and at most 100 bytes (UTF-8)"},
		"a long idempotency key":     {usecase.IdempotencyKey(strings.Repeat("k", 101)), "idempotencyKey must not be blank and at most 100 bytes (UTF-8)"},
		"a connection id with a dot": {usecase.ConnectionID("con.1"), "connectionId must match [0-9A-Za-z_-]{1,17}"},
		"a long connection id":       {usecase.ConnectionID(strings.Repeat("c", 18)), "connectionId must match [0-9A-Za-z_-]{1,17}"},
	} {
		_, err := usecase.NewDispatchJob("test:things:notify", "https://hooks.example.com/n", c.opt)
		if err == nil || err.Error() != c.want {
			t.Errorf("%s: %v, want %q", name, err, c.want)
		}
	}
	if _, err := usecase.NewDispatchJob("test:things:notify", "https://hooks.example.com/n", usecase.IdempotencyKey(strings.Repeat("k", 100))); err != nil {
		t.Errorf("a key of 100 bytes: %v", err)
	}
	// bytes, as the platform counts: 34 three-byte characters are 102 bytes
	if _, err := usecase.NewDispatchJob("test:things:notify", "https://hooks.example.com/n", usecase.IdempotencyKey(strings.Repeat("€", 34))); err == nil {
		t.Error("a key of 102 bytes (34 characters) was accepted")
	}
}
