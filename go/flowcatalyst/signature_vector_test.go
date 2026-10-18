package flowcatalyst

import (
	"net/http"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/internal/conformance"
)

type fixedClock struct{ t time.Time }

func (c fixedClock) Now() time.Time { return c.t }

// TestSignatureVectors runs conformance/receivers/signature.json (Java: SignatureConformanceTest):
// the HMAC function alone, then every gate case.
func TestSignatureVectors(t *testing.T) {
	var v struct {
		Now         string `json:"now"`
		HmacVectors []struct {
			Name      string `json:"name"`
			Secret    string `json:"secret"`
			Timestamp string `json:"timestamp"`
			Body      string `json:"body"`
			Hex       string `json:"hex"`
		} `json:"hmacVectors"`
		Cases []struct {
			Name  string `json:"name"`
			Setup struct {
				SigningSecret    *string `json:"signingSecret"`
				BearerToken      *string `json:"bearerToken"`
				RequireSignature bool    `json:"requireSignature"`
			} `json:"setup"`
			Headers [][2]string `json:"headers"`
			Body    string      `json:"body"`
			Expect  struct {
				Accepted bool   `json:"accepted"`
				Status   int    `json:"status"`
				Reason   string `json:"reason"`
			} `json:"expect"`
		} `json:"cases"`
	}
	conformance.ReadJSON(t, "receivers/signature.json", &v)
	now, err := time.Parse(time.RFC3339, v.Now)
	if err != nil {
		t.Fatal(err)
	}
	for _, h := range v.HmacVectors {
		if got := hmacHex(h.Secret, h.Timestamp, []byte(h.Body)); got != h.Hex {
			t.Errorf("hmac %q: got %s want %s", h.Name, got, h.Hex)
		}
	}
	if len(v.Cases) == 0 {
		t.Fatal("no cases")
	}
	for _, c := range v.Cases {
		t.Run(c.Name, func(t *testing.T) {
			g := gate{require: c.Setup.RequireSignature, clock: fixedClock{now}}
			if c.Setup.SigningSecret != nil {
				g.signing, g.hasSigning = config.NewSecret(*c.Setup.SigningSecret), true
			}
			if c.Setup.BearerToken != nil {
				g.bearer, g.hasBearer = config.NewSecret(*c.Setup.BearerToken), true
			}
			h := http.Header{}
			for _, p := range c.Headers {
				h.Add(p[0], p[1])
			}
			f, err := g.verify(h, func() ([]byte, error) { return []byte(c.Body), nil })
			if err != nil {
				t.Fatal(err)
			}
			if accepted := f == ""; accepted != c.Expect.Accepted {
				t.Fatalf("accepted = %v (reason %q), want %v (reason %q)", accepted, f, c.Expect.Accepted, c.Expect.Reason)
			}
			if string(f) != c.Expect.Reason {
				t.Fatalf("reason = %q, want %q", f, c.Expect.Reason)
			}
		})
	}
}
