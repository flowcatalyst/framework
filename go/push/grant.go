package push

import (
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// The denial codes of a subscribe answer (push contract 6.1).
const (
	codeTopicForbidden = "TOPIC_FORBIDDEN"
	codeTicketInvalid  = "TICKET_INVALID"

	// codeClientForbidden answers a subscribe that names a client the credentials cannot use.
	codeClientForbidden = "CLIENT_FORBIDDEN"
)

// Denial is a topic a subscribe did not get, with why: TOPIC_FORBIDDEN (no rule allows it and no
// ticket grants it) or TICKET_INVALID (a ticket that names it grants nothing).
type Denial struct {
	Topic string `json:"topic"`
	Code  string `json:"code"`
}

// granter decides a subscribe: rules for the request's Context, tickets for whoever holds them.
type granter struct {
	application string
	rules       []Rule
	keys        *ticketKeys
	maxLifetime time.Duration
	multi       *multiApp // non-nil: many applications, application, rules and keys unused
}

// decision is what a subscribe gets.
type decision struct {
	sub      subscription
	platform bool
	granted  []string
	denied   []Denial
	expires  time.Time
	tickets  []ticket
	apps     []string // the applications of the granted topics (multi-application hosts)
}

// decide applies sections 6.1, 6.3 and 6.4. topics are checked and without duplicates. The errors
// are the answers: 400 for a malformed ticket, 401 for a request without credentials and without a
// valid ticket (it has no client), 403 NO_TOPICS when nothing was granted.
func (g *granter) decide(topics, rawTickets []string, creds Credentials, present bool, now time.Time) (decision, error) {
	return g.decideFor(topics, rawTickets, creds, "", present, now)
}

// decideFor is decide for a subscribe that names the client it wants (the body's client member, ""
// for none; push contract 17.16). With credentials the stream's client is the credentials' and a
// different one named is 403 CLIENT_FORBIDDEN; without, it is the first valid ticket's client that
// is the one named.
func (g *granter) decideFor(topics, rawTickets []string, creds Credentials, want string, present bool, now time.Time) (decision, error) {
	check := ticketCheck{application: g.application, now: now, proofKey: creds.ProofKey, maxLifetime: g.maxLifetime}
	if g.multi != nil {
		check.multi, check.publicKey = true, g.multi.keys.PublicKey
	}
	d := decision{}
	for i, raw := range rawTickets {
		var t ticket
		var err error
		if g.multi != nil {
			t, err = checkTicket(raw, check)
		} else {
			t, err = g.keys.check(raw, check)
		}
		if err != nil {
			return decision{}, httpfw.Validation(fmt.Sprintf("tickets[%d] is malformed: not a JWS compact with a JSON header and claims", i))
		}
		d.tickets = append(d.tickets, t)
	}
	var cx fw.Context
	switch {
	case present:
		cx = creds.Context
		d.sub.client = cx.Client().String()
		if want != "" && want != d.sub.client {
			return decision{}, httpfw.Forbidden(codeClientForbidden, "the credentials cannot subscribe for the client named")
		}
	default:
		for _, t := range d.tickets {
			if t.valid && (want == "" || t.client == want) {
				d.sub.client = t.client
				break
			}
		}
		if d.sub.client == "" {
			return decision{}, httpfw.Unauthenticated("a subscribe without credentials needs a valid ticket")
		}
	}
	for i := range d.tickets {
		if d.tickets[i].valid && d.tickets[i].client != d.sub.client {
			d.tickets[i].valid, d.tickets[i].reason = false, reasonClientMismatch
		}
	}
	d.platform = d.sub.client == fw.PlatformClient.String()
	used := make([]bool, len(d.tickets))
	for _, topic := range topics {
		segs := strings.Split(topic, ":")
		if g.ruleAllows(cx, topic) {
			d.granted = append(d.granted, topic)
			continue
		}
		code := codeTopicForbidden
		granted := false
		for i, t := range d.tickets {
			if !matchesAny(t.pats, segs) {
				continue
			}
			if t.valid {
				granted, used[i] = true, true
				break
			}
			code = codeTicketInvalid
		}
		if granted {
			d.granted = append(d.granted, topic)
		} else {
			d.denied = append(d.denied, Denial{Topic: topic, Code: code})
		}
	}
	if len(d.granted) == 0 {
		return d, httpfw.NewError(http.StatusForbidden, "NO_TOPICS", "no topic was granted")
	}
	d.expires = now.Add(g.maxLifetime)
	if present && !creds.ExpiresAt.IsZero() && creds.ExpiresAt.Before(d.expires) {
		d.expires = creds.ExpiresAt
	}
	for i, t := range d.tickets {
		if used[i] {
			if t.streamExp.Before(d.expires) {
				d.expires = t.streamExp
			}
			if !present && d.sub.principal == "" {
				d.sub.principal = t.sub
			}
		}
	}
	d.expires = time.Unix(d.expires.Unix(), 0).UTC()
	if present {
		if p, ok := cx.Principal(); ok {
			d.sub.principal = p.String()
		}
		d.sub.device, d.sub.session = creds.Device, creds.Session
	}
	d.sub.topics = d.granted
	if g.multi != nil {
		d.apps = applicationsOf(d.granted)
	}
	return d, nil
}

func (g *granter) ruleAllows(cx fw.Context, topic string) bool {
	rules := g.rules
	if g.multi != nil { // only the rules of the topic's own application
		rules = g.multi.rulesOf(applicationOf(topic))
	}
	for _, r := range rules {
		if r.Allows(cx, topic) {
			return true
		}
	}
	return false
}

func matchesAny(pats []pattern, topic []string) bool {
	for _, p := range pats {
		if p.matchAny(topic) {
			return true
		}
	}
	return false
}
