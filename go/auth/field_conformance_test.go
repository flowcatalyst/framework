package auth

import (
	"encoding/json/v2"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fwtest"
)

// TestFieldConformance runs conformance/field/cases.json (contract section 15.9): the platform's
// vectors, with the application-nonce check off and the proof window at the vectors' five minutes.
func TestFieldConformance(t *testing.T) {
	t.Parallel()
	var jwks struct {
		Keys []map[string]any `json:"keys"`
	}
	var fixture struct {
		Issuer  string `json:"issuer"`
		AppCode string `json:"appCode"`
		Cases   []struct {
			Name        string `json:"name"`
			AccessToken string `json:"accessToken"`
			DPoPProof   string `json:"dpopProof"`
			Method      string `json:"method"`
			URL         string `json:"url"`
			Now         string `json:"now"`
			Expect      string `json:"expect"`
			Principal   *struct {
				DeviceID          string   `json:"deviceId"`
				DeviceMode        string   `json:"deviceMode"`
				AMR               []string `json:"amr"`
				Test              bool     `json:"test"`
				LinkedPrincipalID string   `json:"linkedPrincipalId"`
				Actor             *struct {
					ID   string `json:"id"`
					Type string `json:"type"`
					Name string `json:"name"`
				} `json:"actor"`
			} `json:"principal"`
		} `json:"cases"`
	}
	for file, into := range map[string]any{"jwks.json": &jwks, "cases.json": &fixture} {
		raw, err := os.ReadFile("../../conformance/field/" + file)
		if err != nil {
			t.Fatal(err)
		}
		if err := json.Unmarshal(raw, into); err != nil {
			t.Fatalf("%s: %v", file, err)
		}
	}
	if len(fixture.Cases) != 22 {
		t.Fatalf("only %d cases: is the file the platform's?", len(fixture.Cases))
	}
	for _, c := range fixture.Cases {
		t.Run(c.Name, func(t *testing.T) {
			t.Parallel()
			now, err := time.Parse(time.RFC3339, c.Now)
			if err != nil {
				t.Fatal(err)
			}
			clock := fwtest.NewClock(now)
			p, err := newPlatform(fixture.Issuer, time.Second, clock, nil, false)
			if err != nil {
				t.Fatal(err)
			}
			for _, k := range jwks.Keys { // the keys are "already read": nothing is fetched
				kid, _ := k["kid"].(string)
				p.keys[kid] = p.rsaKey(k)
			}
			p.fetchedAt = &now
			tokens := &FieldTokens{
				platform: p, clock: clock, appCode: fixture.AppCode, baseURL: "https://app.example.test",
				replay: NewMemoryFieldReplayCache(clock), skipNonce: true, window: 5 * time.Minute,
			}
			htu, _, _ := strings.Cut(c.URL, "?")
			got := "valid"
			switch r := tokens.verify(t.Context(), c.AccessToken, c.DPoPProof, c.Method, htu).(type) {
			case FieldValid:
				want := "PERSONAL"
				if c.Principal != nil { // the case says what the principal carries (15.9)
					want = c.Principal.DeviceMode
					got := r.Principal
					if got.DeviceID != c.Principal.DeviceID || got.Test != c.Principal.Test || got.LinkedPrincipalID != c.Principal.LinkedPrincipalID || !slices.Equal(got.AMR, c.Principal.AMR) {
						t.Fatalf("principal %+v, want %+v", got, *c.Principal)
					}
					switch a := c.Principal.Actor; {
					case a == nil && got.Actor != nil, a != nil && (got.Actor == nil || *got.Actor != FieldActor{ID: a.ID, Type: a.Type, Name: a.Name}):
						t.Fatalf("actor %+v, want %+v", got.Actor, a)
					}
				}
				if c.Principal == nil && r.Principal.LinkedPrincipalID != "" {
					t.Fatalf("linkedPrincipalId %q on a case that says none", r.Principal.LinkedPrincipalID)
				}
				if r.Principal.WorkerID != "fwu_0FIXTUREWORKER" || r.Principal.ClientIdentifier != "fixture-client" || r.Principal.DeviceMode != want {
					t.Fatalf("principal %+v", r.Principal)
				}
			case FieldInvalid:
				got = r.Reason
				if slices.Contains([]string{"malformed", "alg", "crit", "kid", "unknown_kid", "signature"}, got) {
					got = "invalid_token"
				}
				// 5.5 rule 8 names a missing exp "expired"; the vectors call it invalid_token (15.9).
				if c.Name == "missing_exp" && got == "expired" {
					got = "invalid_token"
				}
			case FieldNonceRequired:
				got = "nonce"
			case FieldUnavailable:
				got = "unavailable: " + r.Reason
			}
			if got != c.Expect {
				t.Fatalf("got %q, want %q", got, c.Expect)
			}
		})
	}
}
