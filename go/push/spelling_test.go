package push

import (
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/internal/b64url/b64urltest"
)

// The token-spelling contract (docs/contracts/token-spelling.md) for push tickets and stream tokens:
// the vectors Java writes (conformance/push/spelling.json), the values issued before the rule
// (conformance/push/issued-before.json), values issued fresh here, and fuzz targets.

type pushSpelling struct {
	Tickets struct {
		Application        string             `json:"application"`
		Now                int64              `json:"now"`
		MaxLifetimeSeconds int64              `json:"maxLifetimeSeconds"`
		Cases              []b64urltest.Cases `json:"cases"`
	} `json:"tickets"`
	StreamTokens struct {
		StreamSecret string             `json:"streamSecret"`
		Now          int64              `json:"now"`
		Cases        []b64urltest.Cases `json:"cases"`
	} `json:"streamTokens"`
}

// spellingTicketKeys are the TEST KEYS of tickets.json, current then previous.
func spellingTicketKeys(t testing.TB) *ticketKeys {
	t.Helper()
	var v struct {
		Keys map[string]struct {
			Kid  string `json:"kid"`
			Seed string `json:"seed"`
		} `json:"keys"`
	}
	b64urltest.Read(t, "push/tickets.json", &v)
	keys, err := parseTicketKeys(v.Keys["current"].Kid + ":" + v.Keys["current"].Seed + "," + v.Keys["previous"].Kid + ":" + v.Keys["previous"].Seed)
	if err != nil {
		t.Fatal(err)
	}
	return keys
}

// ticketAnswer is "valid", the reason the ticket grants nothing, or "malformed" (400).
func ticketAnswer(keys *ticketKeys, application string, now time.Time, maxLifetime time.Duration) func(string) string {
	return func(raw string) string {
		tk, err := keys.check(raw, ticketCheck{application: application, now: now, maxLifetime: maxLifetime})
		switch {
		case err != nil:
			return "malformed"
		case tk.valid:
			return "valid"
		default:
			return tk.reason
		}
	}
}

func streamAnswer(s streamTokens, now time.Time) func(string) string {
	return func(token string) string {
		if _, code := s.verify(token, now); code != "" {
			return code
		}
		return "valid"
	}
}

func readPushSpelling(t testing.TB) pushSpelling {
	t.Helper()
	var v pushSpelling
	b64urltest.Read(t, "push/spelling.json", &v)
	return v
}

func (v pushSpelling) verifiers(t testing.TB) (tickets, streams func(string) string) {
	tk := v.Tickets
	tickets = ticketAnswer(spellingTicketKeys(t), tk.Application, time.Unix(tk.Now, 0), time.Duration(tk.MaxLifetimeSeconds)*time.Second)
	streams = streamAnswer(streamTokens{secret: []byte(v.StreamTokens.StreamSecret)}, time.Unix(v.StreamTokens.Now, 0))
	return tickets, streams
}

func TestSpellingVectors(t *testing.T) {
	t.Parallel()
	v := readPushSpelling(t)
	tickets, streams := v.verifiers(t)
	ran := 0
	for _, c := range v.Tickets.Cases {
		t.Run("ticket", func(t *testing.T) { ran += c.Run(t, tickets) })
	}
	for _, c := range v.StreamTokens.Cases {
		t.Run("stream token", func(t *testing.T) { ran += c.Run(t, streams) })
	}
	for _, c := range append(slices.Clone(v.Tickets.Cases), v.StreamTokens.Cases...) {
		if len(c.Resigned) < 25 {
			t.Fatalf("only %d re-signed cases for a value: the vector must hold values whose signed parts were re-spelled and signed again", len(c.Resigned))
		}
	}
	if len(v.Tickets.Cases) != 2 || len(v.StreamTokens.Cases) != 2 || ran < 250 {
		t.Fatalf("%d ticket and %d stream-token values, %d checks", len(v.Tickets.Cases), len(v.StreamTokens.Cases), ran)
	}
}

// TestValuesIssuedBeforeTheRuleStillVerify: tickets and stream tokens the Go and Java code issued at
// 2360d29, before the rule (conformance/push/issued-before.json).
func TestValuesIssuedBeforeTheRuleStillVerify(t *testing.T) {
	t.Parallel()
	var before struct {
		Application        string `json:"application"`
		Now                int64  `json:"now"`
		MaxLifetimeSeconds int64  `json:"maxLifetimeSeconds"`
		StreamSecret       string `json:"streamSecret"`
		Tickets            []struct {
			Ticket    string `json:"ticket"`
			Client    string `json:"client"`
			Principal string `json:"principal"`
		} `json:"tickets"`
		StreamTokens []struct {
			Token     string   `json:"token"`
			Client    string   `json:"client"`
			Principal string   `json:"principal"`
			Device    string   `json:"device"`
			Session   string   `json:"session"`
			Topics    []string `json:"topics"`
			Exp       int64    `json:"exp"`
		} `json:"streamTokens"`
	}
	b64urltest.Read(t, "push/issued-before.json", &before)
	keys := spellingTicketKeys(t)
	now := time.Unix(before.Now, 0)
	for _, tk := range before.Tickets {
		got, err := keys.check(tk.Ticket, ticketCheck{application: before.Application, now: now, maxLifetime: time.Duration(before.MaxLifetimeSeconds) * time.Second})
		if err != nil || !got.valid || got.client != tk.Client || got.sub != tk.Principal {
			t.Errorf("ticket %q: %+v, %v", tk.Ticket, got, err)
		}
	}
	st := streamTokens{secret: []byte(before.StreamSecret)}
	for _, s := range before.StreamTokens {
		c, code := st.verify(s.Token, now)
		if code != "" || c.Client != s.Client || c.Principal != s.Principal || c.Device != s.Device || c.Session != s.Session ||
			!slices.Equal(c.Topics, s.Topics) || c.Exp != s.Exp {
			t.Errorf("stream token %q: %+v, %q", s.Token, c, code)
		}
	}
	if len(before.Tickets) != 2 || len(before.StreamTokens) != 2 {
		t.Fatalf("%d tickets, %d stream tokens", len(before.Tickets), len(before.StreamTokens))
	}
}

func TestSpellingOfFreshTicketsAndStreamTokens(t *testing.T) {
	t.Parallel()
	keys := spellingTicketKeys(t)
	now := time.Unix(1794139200, 0)
	tickets := ticketAnswer(keys, "fulfil", now, time.Hour)
	st := streamTokens{secret: []byte("another-test-secret-for-the-fresh-tokens-only")}
	streams := streamAnswer(st, now)
	for i := range 12 {
		// the claims' lengths vary, so the payloads take every residue mod 4
		sub := "prn_" + strings.Repeat("x", i)
		ticket, err := keys.sign(ticketClaims{
			Iss: "fulfil", Aud: TicketAudience, Client: "acme", Topics: []string{"fulfil:order:1"}, Sub: sub,
			Iat: now.Unix() - 10, Exp: now.Unix() + 100,
		})
		if err != nil {
			t.Fatal(err)
		}
		b64urltest.Fresh(t, ticket, 2, "valid", tickets)
		token, err := st.sign(streamClaims{Client: "acme", Principal: sub, Topics: []string{"fulfil:order:" + strings.Repeat("1", i+1)}, Exp: now.Unix() + 600})
		if err != nil {
			t.Fatal(err)
		}
		b64urltest.Fresh(t, token, 1, "valid", streams)
		b64urltest.Fresh(t, token, 0, "valid", streams)
	}
}

// ---- fuzz: whatever a verifier accepts is spelled canonically --------------------------------

func FuzzTicketSpelling(f *testing.F) {
	v := readPushSpelling(f)
	for _, c := range v.Tickets.Cases {
		f.Add(c.Issued)
		for _, s := range append(c.Respelled, c.Tampered...) {
			f.Add(s.Value)
		}
	}
	tickets, _ := v.verifiers(f)
	f.Fuzz(func(t *testing.T, raw string) {
		if tickets(raw) == "valid" && !b64urltest.SameAsReencoded(raw) {
			t.Fatalf("accepted a ticket that is not spelled canonically: %q", raw)
		}
	})
}

func FuzzStreamTokenSpelling(f *testing.F) {
	v := readPushSpelling(f)
	for _, c := range v.StreamTokens.Cases {
		f.Add(c.Issued)
		for _, s := range append(c.Respelled, c.Tampered...) {
			f.Add(s.Value)
		}
	}
	_, streams := v.verifiers(f)
	f.Fuzz(func(t *testing.T, token string) {
		if streams(token) == "valid" && !b64urltest.SameAsReencoded(token) {
			t.Fatalf("accepted a stream token that is not spelled canonically: %q", token)
		}
	})
}
