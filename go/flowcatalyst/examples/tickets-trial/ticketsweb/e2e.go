package ticketsweb

import (
	"bytes"
	"context"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authtest"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/tickets"
	"github.com/flowcatalyst/framework/go/flowcatalyst/fakeplatform"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/pgembed"
)

// The browser end-to-end launcher: `tickets-trial e2e <app-port> <control-port> [--dist <dir>] [<public-origin>]`.
//
// It is the Go counterpart of the Java TicketsE2e (examples/tickets-trial, test sources) on the shared
// kit (docs/guide/e2e-kit.md): the real application serving the built single-page application, on an
// embedded PostgreSQL 18 (never 15432), with authtest.Provider standing in for the platform's sign-in,
// a fake platform for the service account and the job-completion calls, and a fake notification
// service; and the kit's control endpoints, plus the service's own: empty the data, move the clock and
// play the platform's part (fire the SLA sweep, deliver the assignment events). Playwright's web server
// starts it and waits for GET /control/ready; it prints READY and runs until SIGTERM or SIGINT.
//
// `--dist <dir>` (after the ports) serves the SPA from a directory (ui/apps/tickets writes its build to
// examples/tickets-trial/web/dist); without it the embedded build is served, which is stale unless the
// binary was compiled after `vite build`.
//
// Control endpoints (any method):
//
//	ready, end-sessions (deletes every session, as an expiry would), provider-down, authorize-error
//	sign-in-as?roles=tickets-trial:agent,...&client=acme&sub=prn_ada&name=...   the next sign-in
//	reset                         deletes every ticket, setting and outbox row, puts the clock back, forgets the
//	                              notifications (sessions stay)
//	advance?minutes=61            moves the application's clock forward (the sign-in provider follows it)
//	sweep                         the platform fires the SLA sweep; answers when it completed, with its result
//	deliver-assignments           the platform delivers every assignment event not delivered yet; answers how many
//	notifications                 what the notification service received
//	notifier-status?value=500     what the notification service answers (empty: 202)

// ConfigDir is the directory of config/application.yaml the launcher and the tests read, relative to
// the working directory (the command runs from examples/tickets-trial).
var ConfigDir = "config"

const (
	e2eClientID   = "tickets-web"
	e2eSecret     = "whsec-tickets-test-only" //nolint:gosec // a fixed value for a local launcher, not a credential
	e2eAcmeKey    = "key-acme-e2e-only"
	e2eGlobexKey  = "key-globex-e2e-only"
	e2eThirtyDays = 30 * 24 * time.Hour
)

// movableClock is the system clock plus an offset the suite moves.
type movableClock struct{ offset atomic.Int64 }

func (c *movableClock) Now() time.Time {
	return fw.SystemClock().Now().Add(time.Duration(c.offset.Load()))
}

// E2EMain runs the launcher and returns the exit code.
func E2EMain(ctx context.Context, args []string) int {
	if err := RunE2E(ctx, args, os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "tickets-trial e2e:", err)
		return 1
	}
	return 0
}

// RunE2E runs the launcher until ctx ends; out receives the READY line.
func RunE2E(ctx context.Context, args []string, out io.Writer) error {
	if len(args) < 2 {
		return errors.New("usage: tickets-trial e2e <app-port> <control-port> [--dist <dir>] [<public-origin>]")
	}
	appPort, err := strconv.Atoi(args[0])
	if err != nil {
		return fmt.Errorf("app port: %w", err)
	}
	controlPort, err := strconv.Atoi(args[1])
	if err != nil {
		return fmt.Errorf("control port: %w", err)
	}
	if !pgembed.AllowedPort(appPort) || !pgembed.AllowedPort(controlPort) {
		return errors.New("ports 8080 and 15432 belong to the developer's platform")
	}
	origin := "http://127.0.0.1:" + args[0]
	var dist fs.FS
	for rest := args[2:]; len(rest) > 0; {
		if rest[0] == "--dist" && len(rest) > 1 {
			dist = os.DirFS(rest[1])
			if _, err := fs.Stat(dist, "index.html"); err != nil {
				return fmt.Errorf("--dist has no index.html: %w", err)
			}
			rest = rest[2:]
			continue
		}
		origin, rest = strings.TrimSuffix(rest[0], "/"), rest[1:]
	}

	clock := &movableClock{}
	provider := authtest.Start(clock)
	defer provider.Close()
	provider.Client(e2eClientID, "", origin+"/auth/callback")
	provider.SignInAs(e2ePerson("tickets-trial:agent", "Ada Lovelace", "acme", "prn_ada"))

	pgURL, stopPG, err := pgembed.Start(ctx)
	if err != nil {
		return err
	}
	defer stopPG()
	platform := StartPlatform()
	defer platform.Close()
	notifier := StartNotifier()
	defer notifier.Close()

	env := map[string]string{
		"APP_ENV":        "test",
		"HTTP_HOST":      "127.0.0.1",
		"HTTP_PORT":      args[0],
		"AUTH_ISSUER":    provider.Issuer(),
		"AUTH_CLIENT_ID": e2eClientID,
		// A blank environment value beats every file: the run does not read the developer's
		// secrets.local.yaml secret, which would make this PUBLIC client confidential.
		"AUTH_CLIENT_SECRET":                        "",
		"AUTH_PUBLIC_BASE_URL":                      origin,
		"DATABASE_INSTANCES_MAIN_URL":               pgURL,
		"DATABASE_INSTANCES_MAIN_USERNAME":          "postgres",
		"DATABASE_INSTANCES_MAIN_PASSWORD":          "postgres",
		"FLOWCATALYST_ENABLED":                      "true",
		"FLOWCATALYST_BASE_URL":                     platform.BaseURL(),
		"FLOWCATALYST_CLIENT_ID":                    fakeplatform.ClientID,
		"FLOWCATALYST_CLIENT_SECRET":                fakeplatform.ClientSecret,
		"FLOWCATALYST_SUBSCRIPTION_TARGET_BASE_URL": "http://127.0.0.1:" + args[0],
		"FLOWCATALYST_RECEIVERS_SIGNING_SECRET":     e2eSecret,
		"HTTP_CLIENTS_INSTANCES_NOTIFIER_BASE_URL":  notifier.BaseURL(),
		"TICKETS_NOTIFY_ACME__NOTIFY_KEY":           e2eAcmeKey,
		"TICKETS_NOTIFY_GLOBEX__NOTIFY_KEY":         e2eGlobexKey,
	}
	var (
		mu    sync.Mutex
		built *Built
	)
	app := NewApp(fw.WithEnvironment(env), fw.WithConfigDir(ConfigDir), fw.WithClock(clock))
	run, err := app.Start(ctx, BuildWeb(Declare(app), Deps{
		SPA: dist,
		// The clock moves in the suite: sessions must outlive the hours it skips, and a test run signs
		// in far more than 20 times a minute from one address.
		AuthOptions: []auth.Option{auth.NoRateLimit(), auth.IdleTimeout(e2eThirtyDays), auth.AbsoluteTimeout(e2eThirtyDays)},
		Served: func(b *Built) {
			mu.Lock()
			built = b
			mu.Unlock()
		},
	}))
	if err != nil {
		return err
	}
	defer func() { _ = run.Shutdown() }()

	execute := func(sql string) error {
		mu.Lock()
		b := built
		mu.Unlock()
		if b == nil {
			return errors.New("the database is not ready")
		}
		_, err := b.DB.Pool().Exec(ctx, sql)
		return err
	}
	var (
		deliveredMu sync.Mutex
		delivered   = map[string]bool{}
		sweeps      int
	)
	baseURL := "http://127.0.0.1:" + args[0]
	control := map[string]func(q map[string]string) (string, error){
		"/control/ready": func(map[string]string) (string, error) { return "ready", nil },
		"/control/sign-in-as": func(q map[string]string) (string, error) {
			provider.SignInAs(e2ePerson(q["roles"], orDefault(q["name"], "Ada Lovelace"), orDefault(q["client"], "acme"), orDefault(q["sub"], "prn_ada")))
			return "ok", nil
		},
		"/control/provider-down": func(q map[string]string) (string, error) {
			provider.Down(q["value"] == "true")
			return "ok", nil
		},
		"/control/authorize-error": func(q map[string]string) (string, error) {
			provider.AuthorizeError(q["value"])
			return "ok", nil
		},
		"/control/end-sessions": func(map[string]string) (string, error) {
			return "ok", execute("DELETE FROM fw_session")
		},
		"/control/reset": func(map[string]string) (string, error) {
			if err := execute("TRUNCATE ticket_comments, tickets, fw_client_settings, outbox_messages CASCADE"); err != nil {
				return "", err
			}
			clock.offset.Store(0)
			notifier.Reset()
			deliveredMu.Lock()
			clear(delivered)
			deliveredMu.Unlock()
			return "ok", nil
		},
		"/control/advance": func(q map[string]string) (string, error) {
			minutes, err := strconv.ParseInt(orDefault(q["minutes"], "0"), 10, 64)
			if err != nil {
				return "", fmt.Errorf("advance: %w", err)
			}
			clock.offset.Add(int64(time.Duration(minutes) * time.Minute))
			return "ok", nil
		},
		"/control/sweep": func(map[string]string) (string, error) {
			deliveredMu.Lock()
			sweeps++
			n := sweeps
			deliveredMu.Unlock()
			now := clock.Now()
			status, err := post(ctx, baseURL, Delivery{Path: "/fc/scheduled-jobs", Body: FiringBody(now, tickets.SLASweepJob, "si_e2e_"+strconv.Itoa(n))}, now)
			if err != nil {
				return "", err
			}
			if status != http.StatusAccepted {
				return "", fmt.Errorf("the service answered %d", status)
			}
			calls, err := platform.AwaitInstanceCalls("complete", n, 15*time.Second)
			if err != nil {
				return "", err
			}
			return calls[n-1].Body, nil
		},
		"/control/deliver-assignments": func(map[string]string) (string, error) {
			rows, err := assignmentEvents(ctx, built, &mu)
			if err != nil {
				return "", err
			}
			now := clock.Now()
			count := 0
			for _, event := range rows {
				jobID := "dj_" + event.DeduplicationID
				deliveredMu.Lock()
				done := delivered[jobID]
				deliveredMu.Unlock()
				if done {
					continue
				}
				status, err := post(ctx, baseURL, Delivery{
					Path: "/fc/dispatch", Body: event.envelope(jobID),
					Headers: map[string]string{"X-Dispatch-Job-Id": jobID, "X-Event-Type": event.Type, "X-FlowCatalyst-Client": "clt_" + event.ClientCode + ":" + event.ClientCode},
				}, now)
				if err != nil {
					return "", err
				}
				if status == http.StatusOK {
					deliveredMu.Lock()
					delivered[jobID] = true
					deliveredMu.Unlock()
					count++
				}
			}
			return strconv.Itoa(count), nil
		},
		"/control/notifications": func(map[string]string) (string, error) {
			type one struct {
				To            string `json:"to"`
				TicketID      string `json:"ticketId"`
				Authorization string `json:"authorization"`
			}
			list := []one{}
			for _, r := range notifier.Received() {
				var body struct{ To, TicketID string }
				if err := json.Unmarshal([]byte(r.Body), &body, json.MatchCaseInsensitiveNames(true)); err != nil {
					return "", err
				}
				list = append(list, one{To: body.To, TicketID: body.TicketID, Authorization: r.Header.Get("Authorization")})
			}
			b, err := json.Marshal(list)
			return string(b), err
		},
		"/control/notifier-status": func(q map[string]string) (string, error) {
			status := http.StatusAccepted
			if v := q["value"]; v != "" {
				n, err := strconv.Atoi(v)
				if err != nil || n < 100 || n > 599 {
					return "", fmt.Errorf("notifier-status: value %q is not an HTTP status", v)
				}
				status = n
			}
			notifier.Answer(status)
			return "ok", nil
		},
	}
	mux := http.NewServeMux()
	for path, handler := range control {
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			q := make(map[string]string)
			for k, v := range r.URL.Query() {
				if len(v) > 0 {
					q[k] = v[0]
				}
			}
			body, err := handler(q)
			status := http.StatusOK
			if err != nil {
				status, body = http.StatusInternalServerError, err.Error()
			}
			w.WriteHeader(status)
			_, _ = io.WriteString(w, body)
		})
	}
	listener, err := (&net.ListenConfig{}).Listen(ctx, "tcp", "127.0.0.1:"+args[1])
	if err != nil {
		return err
	}
	controlServer := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = controlServer.Serve(listener) }()
	defer func() { _ = controlServer.Close() }()
	fmt.Fprintf(out, "READY app=http://127.0.0.1:%d public=%s control=http://127.0.0.1:%d\n", appPort, origin, controlPort)
	<-ctx.Done()
	return nil
}

// post sends a signed delivery and answers its status.
func post(ctx context.Context, baseURL string, d Delivery, now time.Time) (int, error) {
	req, err := d.Request(ctx, baseURL, e2eSecret, now)
	if err != nil {
		return 0, err
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0, err
	}
	defer res.Body.Close()
	_, _ = io.Copy(io.Discard, res.Body)
	return res.StatusCode, nil
}

// assignment is an assignment event as the outbox row holds it.
type assignment struct {
	Type            string `json:"type"`
	Source          string `json:"source"`
	Subject         string `json:"subject"`
	Data            string `json:"data"`
	CorrelationID   string `json:"correlationId"`
	DeduplicationID string `json:"deduplicationId"`
	MessageGroup    string `json:"messageGroup"`
	ClientCode      string `json:"clientCode"`
}

// envelope is what the platform delivers to a subscription for the event: the envelope around the
// row's data.
func (a assignment) envelope(jobID string) string {
	fields := []struct {
		key   string
		value any
	}{
		{"id", jobID},
		{"type", a.Type},
		{"attemptNumber", 1},
		{"source", a.Source},
		{"subject", a.Subject},
		{"correlationId", a.CorrelationID},
		{"messageGroup", a.MessageGroup},
		{"clientId", "clt_" + a.ClientCode},
		{"clientCode", a.ClientCode},
		{"data", jsontext.Value(a.Data)},
	}
	var b bytes.Buffer
	b.WriteByte('{')
	for i, f := range fields {
		if i > 0 {
			b.WriteByte(',')
		}
		k, _ := json.Marshal(f.key)
		v, _ := json.Marshal(f.value)
		b.Write(k)
		b.WriteByte(':')
		b.Write(v)
	}
	b.WriteByte('}')
	return b.String()
}

func assignmentEvents(ctx context.Context, built *Built, mu *sync.Mutex) ([]assignment, error) {
	mu.Lock()
	b := built
	mu.Unlock()
	if b == nil {
		return nil, errors.New("the database is not ready")
	}
	rows, err := b.DB.Pool().Query(ctx, `SELECT payload FROM outbox_messages WHERE payload::jsonb ->> 'type' = $1 ORDER BY created_at, id`, tickets.Assigned.String())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []assignment
	for rows.Next() {
		var payload string
		if err := rows.Scan(&payload); err != nil {
			return nil, err
		}
		var a assignment
		if err := json.Unmarshal([]byte(payload), &a); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func orDefault(v, fallback string) string {
	if v == "" {
		return fallback
	}
	return v
}

// e2ePerson is the Java E2eLauncher.person: a user of one client holding the roles.
func e2ePerson(roles, name, client, sub string) map[string]any {
	list := []any{}
	for r := range strings.SplitSeq(roles, ",") {
		if strings.TrimSpace(r) != "" {
			list = append(list, r)
		}
	}
	return map[string]any{
		"name": name, "roles": list, "sub": sub, "email": sub + "@example.test",
		"type": "USER", "tier": "CLIENT", "clients": []any{"clt_" + client + ":" + client},
	}
}
