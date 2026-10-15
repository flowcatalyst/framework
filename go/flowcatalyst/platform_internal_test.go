package flowcatalyst

import (
	"encoding/base64"
	"errors"
	"strings"
	"testing"
)

// The service principal of a token (flowcatalyst contract section 4b), as the Java module's
// ServiceTokensTest has it.

func tokenWith(payload string) string {
	enc := base64.RawURLEncoding
	return enc.EncodeToString([]byte(`{"alg":"none"}`)) + "." + enc.EncodeToString([]byte(payload)) + "." + enc.EncodeToString([]byte("sig"))
}

func TestATokenThatIsNotAJWTIsRefused(t *testing.T) {
	for _, tok := range []string{"", "opaque", "a.b", "a.b.c.d", "a..c", ".b.c", "a.b.", tokenWith(`{"sub":"x","type":"SERVICE"}`) + ".extra"} {
		if _, err := servicePrincipalOf(tok); !errors.Is(err, ErrNoServicePrincipal) {
			t.Errorf("%q: %v", tok, err)
		}
	}
}

func TestAPayloadThatIsNotBase64URLJSONObjectIsRefused(t *testing.T) {
	enc := base64.RawURLEncoding
	for name, tok := range map[string]string{
		"not base64url":     "a.***.c",
		"not json":          "a." + enc.EncodeToString([]byte("nope")) + ".c",
		"a list":            tokenWith(`["sub"]`),
		"a string":          tokenWith(`"sub"`),
		"duplicate members": tokenWith(`{"sub":"prn_1","type":"SERVICE","sub":"prn_2"}`),
		"standard base64":   "a." + base64.StdEncoding.EncodeToString([]byte(`{"sub":"prn_1","type":"SERVICE","pad":"???>>>"}`)) + ".c",
	} {
		if _, err := servicePrincipalOf(tok); !errors.Is(err, ErrNoServicePrincipal) {
			t.Errorf("%s: %v", name, err)
		}
	}
}

func TestAMissingOrMalformedSubIsRefused(t *testing.T) {
	for _, payload := range []string{
		`{"type":"SERVICE"}`, `{"sub":null,"type":"SERVICE"}`, `{"sub":5,"type":"SERVICE"}`, `{"sub":"","type":"SERVICE"}`,
		`{"sub":"a b","type":"SERVICE"}`, `{"sub":"a/b","type":"SERVICE"}`, `{"sub":"` + strings.Repeat("a", 129) + `","type":"SERVICE"}`, `{"sub":"a\n","type":"SERVICE"}`,
	} {
		if _, err := servicePrincipalOf(tokenWith(payload)); !errors.Is(err, ErrNoServicePrincipal) {
			t.Errorf("%s: %v", payload, err)
		}
	}
}

func TestATokenForAnythingButAServiceAccountIsRefused(t *testing.T) {
	for _, payload := range []string{`{"sub":"prn_1"}`, `{"sub":"prn_1","type":"USER"}`, `{"sub":"prn_1","type":"service"}`, `{"sub":"prn_1","type":"SERVICES"}`, `{"sub":"prn_1","type":5}`, `{"sub":"prn_1","type":null}`} {
		_, err := servicePrincipalOf(tokenWith(payload))
		if !errors.Is(err, ErrNoServicePrincipal) || !strings.Contains(err.Error(), "SERVICE") {
			t.Errorf("%s: %v", payload, err)
		}
	}
}

func TestTheSubOfAServiceTokenIsTheServicePrincipalAndTheEdgesAreAccepted(t *testing.T) {
	for _, sub := range []string{"prn_0HZXEQ5Y8JY5Z", "a", strings.Repeat("a", 128), "A.b_c:d-e"} {
		p, err := servicePrincipalOf(tokenWith(`{"sub":"` + sub + `","type":"SERVICE","other":{"x":1}}`))
		if err != nil || p.String() != sub {
			t.Errorf("%q: %v %v", sub, p, err)
		}
	}
}

func TestAPaddedPayloadIsAccepted(t *testing.T) {
	payload := base64.URLEncoding.EncodeToString([]byte(`{"sub":"prn_1","type":"SERVICE","x":"???"}`)) // with = padding
	if !strings.HasSuffix(payload, "=") {
		payload = base64.URLEncoding.EncodeToString([]byte(`{"sub":"prn_1","type":"SERVICE","x":"?"}`))
	}
	if !strings.HasSuffix(payload, "=") {
		t.Fatalf("the test payload %q has no padding", payload)
	}
	p, err := servicePrincipalOf("a." + payload + ".c")
	if err != nil || p.String() != "prn_1" {
		t.Fatalf("%v %v", p, err)
	}
}

func TestTheRefusalNeverShowsTheToken(t *testing.T) {
	tok := tokenWith(`{"sub":"secret-subject-value","type":"USER"}`)
	_, err := servicePrincipalOf(tok)
	if err == nil || strings.Contains(err.Error(), tok) || strings.Contains(err.Error(), "secret-subject-value") {
		t.Fatalf("%v", err)
	}
}
