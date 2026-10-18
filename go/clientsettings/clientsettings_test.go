package clientsettings_test

import (
	"bytes"
	"context"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/clientsettings"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/usecase"
)

func TestMain(m *testing.M) {
	fwtest.Main(m, fwtest.Template(func(ctx context.Context, url string) error {
		db, err := database.Open(ctx, "main", url)
		if err != nil {
			return err
		}
		defer db.Close()
		return database.ApplyMigrations(ctx, db, []fw.MigrationSet{
			{Module: usecase.OutboxModuleID, Files: usecase.OutboxMigrations()},
			{Module: clientsettings.ModuleID, Files: clientsettings.Migrations()},
		}, slog.New(slog.DiscardHandler))
	}))
}

// ---- the catalog of the vector: the settings its scripts use ----------------------------------------

type Mode string

func (m *Mode) UnmarshalJSON(b []byte) error {
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return err
	}
	switch Mode(s) {
	case "MANUAL", "AUTO":
		*m = Mode(s)
		return nil
	}
	return fmt.Errorf("no such mode %q", s)
}

type Sla struct {
	Low    int `json:"low"`
	Normal int `json:"normal"`
	High   int `json:"high"`
	Urgent int `json:"urgent"`
}

func slaRule(v Sla) string {
	for _, m := range []int{v.Low, v.Normal, v.High, v.Urgent} {
		if m < 1 || m > 43200 {
			return "Each time is 1 to 43200 minutes."
		}
	}
	if v.Urgent > v.High || v.High > v.Normal || v.Normal > v.Low {
		return "A more urgent ticket cannot be given more time."
	}
	return ""
}

// adapter lets a script address a typed setting by name with raw JSON values.
type adapter struct {
	key   string
	read  func(ctx context.Context, s *clientsettings.Store, c fw.ClientID) (jsontext.Value, error)
	with  func(s clientsettings.Settings, raw jsontext.Value) (clientsettings.Settings, error)
	wout  func(s clientsettings.Settings) clientsettings.Settings
	check func(raw jsontext.Value) (string, error)
}

func adapt[T any](set clientsettings.Setting[T]) adapter {
	decode := func(raw jsontext.Value) (T, error) {
		var v T
		err := json.Unmarshal(raw, &v)
		return v, err
	}
	return adapter{
		key: set.Key(),
		read: func(ctx context.Context, s *clientsettings.Store, c fw.ClientID) (jsontext.Value, error) {
			v, err := clientsettings.Read(ctx, s, c, set)
			if err != nil {
				return nil, err
			}
			return json.Marshal(v)
		},
		with: func(s clientsettings.Settings, raw jsontext.Value) (clientsettings.Settings, error) {
			v, err := decode(raw)
			if err != nil {
				return s, err
			}
			return clientsettings.With(s, set, v)
		},
		wout: func(s clientsettings.Settings) clientsettings.Settings { return clientsettings.Without(s, set) },
		check: func(raw jsontext.Value) (string, error) {
			v, err := decode(raw)
			return set.Check(v), err
		},
	}
}

func catalog(t *testing.T) map[string]adapter {
	t.Helper()
	sla, err := clientsettings.NewSetting("tickets.sla", Sla{2880, 1440, 480, 60})
	if err != nil {
		t.Fatal(err)
	}
	if sla, err = sla.WithRule(slaRule); err != nil {
		t.Fatal(err)
	}
	return map[string]adapter{
		"big":    adapt(clientsettings.MustSetting("counter.big", int64(0))),
		"flag":   adapt(clientsettings.MustSetting("ui.beta", false)),
		"limit":  adapt(clientsettings.MustSetting("api.limit", int32(100))),
		"mode":   adapt(clientsettings.MustSetting("billing.mode", Mode("MANUAL"))),
		"prefix": adapt(clientsettings.MustSetting("invoice.prefix", "INV-")),
		"ratio":  adapt(clientsettings.MustSetting("tax.ratio", 0.2)),
		"sla":    adapt(sla),
		"tags":   adapt(clientsettings.MustSetting("ticket.tags", []string{})),
	}
}

// ---- the application's save use case ------------------------------------------------------------------

type saveCmd struct {
	current clientsettings.Current
	changed clientsettings.Settings
}

type settingsChanged struct {
	Meta event.Metadata `json:"-"`
	ID   string         `json:"id"`
}

func (e settingsChanged) Metadata() event.Metadata { return e.Meta }

type saveUseCase struct{ events *event.Factory }

func (saveUseCase) Name() usecase.Name { return usecase.MustName("save-client-settings") }

func (saveUseCase) Validate(context.Context, fw.Context, saveCmd) error { return nil }

func (saveUseCase) Authorize(context.Context, fw.Context, usecase.Valid[saveCmd]) (usecase.Grant, error) {
	return usecase.Public(), nil
}

func (u saveUseCase) Execute(_ context.Context, cx fw.Context, a usecase.Authorized[saveCmd], _ *database.DB) (*usecase.Plan[settingsChanged], error) {
	c := a.Command()
	ev := settingsChanged{Meta: u.events.Metadata(cx, event.MustType("settings:client:settings:changed"), c.changed.ID()), ID: c.changed.ID()}
	return usecase.NewPlan(ev, clientsettings.Repository{}.Step(cx, c.current, c.changed)), nil
}

type rig struct {
	db    *database.DB
	store *clientsettings.Store
	repo  clientsettings.Repository
	save  *usecase.Defined[saveCmd, settingsChanged]
	clock *fwtest.Clock
}

func newRig(t *testing.T) *rig {
	t.Helper()
	db, err := database.Open(t.Context(), "main", fwtest.NewDatabase(t).URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	clock := fwtest.NewClock(time.Date(2026, 10, 18, 10, 0, 0, 0, time.UTC))
	app := fw.New("settings-test", fw.WithEnvironment(map[string]string{"APP_ENV": "test"}), fw.WithConfigDir(t.TempDir()),
		fw.WithClock(clock), fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	outbox := usecase.Declare(app)
	r := &rig{db: db, store: clientsettings.NewStore(db), clock: clock}
	run, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		runner, err := usecase.NewRunner(b, outbox, db)
		if err != nil {
			return nil, err
		}
		r.save, err = usecase.Define(runner, saveUseCase{events: event.NewFactory(b.Clock(), b.Ids())})
		return nil, err
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	return r
}

func cxFor(client string) fw.Context {
	return fw.ContextFor(fw.MustClientID(client)).WithPrincipal(fw.MustPrincipalID("usr_1"))
}

func (r *rig) saveSettings(ctx context.Context, cx fw.Context, cur clientsettings.Current, changed clientsettings.Settings) error {
	_, err := r.save.Run(ctx, cx, saveCmd{current: cur, changed: changed})
	return err
}

// ---- unit tests ---------------------------------------------------------------------------------------

func TestADefaultComesFromTheDefinitionAndAWriteIsReadBackIsolatedPerClient(t *testing.T) {
	r := newRig(t)
	ctx := t.Context()
	prefix := clientsettings.MustSetting("invoice.prefix", "INV-")
	acme, globex := fw.MustClientID("acme"), fw.MustClientID("globex")
	if v, err := clientsettings.Read(ctx, r.store, acme, prefix); err != nil || v != "INV-" {
		t.Fatalf("default: %q %v", v, err)
	}
	cur, err := r.repo.ForClient(ctx, r.db, acme)
	if err != nil {
		t.Fatal(err)
	}
	changed, err := clientsettings.With(cur.Settings(), prefix, "ACME-")
	if err != nil {
		t.Fatal(err)
	}
	if err := r.saveSettings(ctx, cxFor("acme"), cur, changed); err != nil {
		t.Fatal(err)
	}
	if v, _ := clientsettings.Read(ctx, r.store, acme, prefix); v != "ACME-" {
		t.Fatalf("read back %q", v)
	}
	if v, _ := clientsettings.Read(ctx, r.store, globex, prefix); v != "INV-" {
		t.Fatalf("another client sees %q", v)
	}
	// the run wrote the event and the audit row in the same transaction
	var n int
	if err := r.db.Pool().QueryRow(ctx, "SELECT count(*) FROM outbox_messages").Scan(&n); err != nil || n != 2 {
		t.Fatalf("outbox rows %d %v", n, err)
	}
}

func TestKeyRules(t *testing.T) {
	t.Parallel()
	for key, ok := range map[string]bool{
		"a": true, "tickets.sla": true, "a.b_c-d": true, strings.Repeat("x", 100): true,
		"": false, "Tickets": false, "1a": false, strings.Repeat("x", 101): false, "a:b": false, "a b": false, "a\n": false, "_a": false, "é": false,
	} {
		_, err := clientsettings.NewSetting(key, 0)
		if (err == nil) != ok {
			t.Errorf("key %q: accepted = %v, want %v", key, err == nil, ok)
		}
	}
}

func TestADefaultThatBreaksItsOwnRuleIsRefused(t *testing.T) {
	t.Parallel()
	s := clientsettings.MustSetting("a", 5)
	if _, err := s.WithRule(func(v int) string {
		if v > 3 {
			return "too big"
		}
		return ""
	}); err == nil {
		t.Fatal("a default that breaks the rule was accepted")
	}
}

// ---- the vectors --------------------------------------------------------------------------------------

type vector struct {
	Keys []struct {
		Key      string `json:"key"`
		Accepted bool   `json:"accepted"`
	} `json:"keys"`
	Scripts []struct {
		Name string `json:"name"`
		Ops  []struct {
			Op      string         `json:"op"`
			As      string         `json:"as"`
			ID      string         `json:"id"`
			Client  string         `json:"client"`
			Setting string         `json:"setting"`
			Version int64          `json:"version"`
			Value   jsontext.Value `json:"value"`
			Raw     jsontext.Value `json:"settings"`
			Note    string         `json:"note"`
		} `json:"ops"`
		Expect []struct {
			OK      bool           `json:"ok"`
			Value   jsontext.Value `json:"value"`
			Result  string         `json:"result"`
			Code    string         `json:"code"`
			Kind    string         `json:"kind"`
			Found   *bool          `json:"found"`
			Version *int64         `json:"version"`
			ID      string         `json:"id"`
			Values  jsontext.Value `json:"values"`
			Problem *string        `json:"problem"`
			Error   *struct {
				Exception   string `json:"exception"`
				NamesKey    bool   `json:"namesKey"`
				NamesClient bool   `json:"namesClient"`
			} `json:"error"`
		} `json:"expect"`
		Rows []struct {
			ClientID string         `json:"client_id"`
			Version  int64          `json:"version"`
			Settings jsontext.Value `json:"settings"`
		} `json:"rows"`
	} `json:"scripts"`
}

func TestClientSettingsVectors(t *testing.T) {
	var v vector
	conformance.ReadJSON(t, "settings/client-settings.json", &v)
	if len(v.Scripts) == 0 || len(v.Keys) == 0 {
		t.Fatal("empty vector")
	}
	for _, k := range v.Keys {
		if _, err := clientsettings.NewSetting(k.Key, 0); (err == nil) != k.Accepted {
			t.Errorf("key %q: accepted = %v, want %v", k.Key, err == nil, k.Accepted)
		}
	}
	for _, sc := range v.Scripts {
		t.Run(sc.Name, func(t *testing.T) {
			t.Parallel()
			r := newRig(t)
			cat := catalog(t)
			ctx := t.Context()
			type named struct {
				cur clientsettings.Current
				set clientsettings.Settings
			}
			held := map[string]*named{}
			for i, op := range sc.Ops {
				e := sc.Expect[i]
				where := fmt.Sprintf("op %d (%s %s)", i, op.Op, op.Setting)
				ad := cat[op.Setting]
				var gotErr error
				var gotResult *usecase.Error
				var gotValue jsontext.Value
				var extra string
				client := func() fw.ClientID { return fw.MustClientID(op.Client) }
				switch op.Op {
				case "get":
					gotValue, gotErr = ad.read(ctx, r.store, client())
				case "set", "unset":
					cur, err := r.repo.ForClient(ctx, r.db, client())
					if err != nil {
						t.Fatal(err)
					}
					var changed clientsettings.Settings
					if op.Op == "set" {
						changed, gotErr = ad.with(cur.Settings(), op.Value)
					} else {
						changed = ad.wout(cur.Settings())
					}
					if gotErr == nil {
						gotResult = asUseCaseError(r.saveSettings(ctx, cxFor(op.Client), cur, changed))
					}
				case "rawSet":
					if _, err := r.db.Pool().Exec(ctx, "INSERT INTO fw_client_settings (client_id, version, settings) VALUES ($1, $2, $3::jsonb) ON CONFLICT (client_id) DO UPDATE SET version = EXCLUDED.version, settings = EXCLUDED.settings",
						op.Client, op.Version, string(op.Raw)); err != nil {
						t.Fatal(err)
					}
				case "load":
					cur, err := r.repo.ForClient(ctx, r.db, client())
					if err != nil {
						t.Fatal(err)
					}
					held[op.As] = &named{cur: cur, set: cur.Settings()}
					vals, _ := json.Marshal(cur.Settings().Values())
					if e.Version != nil && *e.Version != cur.Version() || e.ID != cur.Settings().ID() {
						t.Errorf("%s: version %d id %s, want %v %s", where, cur.Version(), cur.Settings().ID(), *e.Version, e.ID)
					}
					conformance.EqualJSON(t, where+" values", e.Values, vals)
				case "with":
					n := held[op.As]
					n.set, gotErr = ad.with(n.set, op.Value)
				case "without":
					n := held[op.As]
					n.set = ad.wout(n.set)
				case "save", "saveAs":
					n := held[op.As]
					cx := cxFor(n.set.ID())
					if op.Op == "saveAs" {
						cx = cxFor(op.Client)
					}
					gotResult = asUseCaseError(r.saveSettings(ctx, cx, n.cur, n.set))
				case "find":
					_, found, err := r.repo.Find(ctx, r.db, client(), op.ID)
					if err != nil {
						t.Fatal(err)
					}
					if e.Found == nil || found != *e.Found {
						t.Errorf("%s: found %v, want %v", where, found, e.Found)
					}
				case "check":
					extra, gotErr = ad.check(op.Value)
					if (e.Problem == nil) != (extra == "") || (e.Problem != nil && *e.Problem != extra) {
						t.Errorf("%s: problem %q, want %v", where, extra, e.Problem)
					}
				default:
					t.Fatalf("%s: unknown op", where)
				}
				compareOp(t, where, op.Note, e.OK, e.Error, e.Value, e.Result, e.Code, e.Kind, op.Op, gotErr, gotResult, gotValue, op.Client, ad.key)
			}
			compareRows(t, r, sc.Rows)
		})
	}
}

func asUseCaseError(err error) *usecase.Error {
	if err == nil {
		return nil
	}
	if ue, ok := usecase.AsError(err); ok {
		return ue
	}
	return usecase.Internal(usecase.MustErrorCode("UNEXPECTED"))
}

// compareOp checks an op's outcome against its expect. Two Java leniencies Go does not copy are
// recorded differences, found by their note: a string "12" for an integer setting (Jackson coerces,
// Go's JSON refuses) and nothing else.
func compareOp(t *testing.T, where, note string, ok bool, wantErr *struct {
	Exception   string `json:"exception"`
	NamesKey    bool   `json:"namesKey"`
	NamesClient bool   `json:"namesClient"`
}, wantValue jsontext.Value, wantResult, wantCode, wantKind, op string, gotErr error, gotResult *usecase.Error, gotValue jsontext.Value, client, key string,
) {
	t.Helper()
	switch op {
	case "load", "find", "check", "rawSet", "without":
		return
	}
	if strings.Contains(note, "coerces the string") {
		if gotErr == nil {
			t.Errorf("%s: Go accepted a string for an integer setting", where)
		} else {
			t.Logf("%s: recorded difference: Java coerces \"12\" to 12, Go refuses (%v)", where, gotErr)
		}
		return
	}
	switch {
	case wantResult == "failed":
		if gotResult == nil || gotResult.Code() != wantCode || gotResult.Kind().String() != wantKind {
			t.Errorf("%s: result %v, want failed %s %s", where, gotResult, wantCode, wantKind)
		}
	case wantErr != nil:
		if gotErr == nil {
			t.Errorf("%s: no error, want %s", where, wantErr.Exception)
			return
		}
		msg := gotErr.Error()
		if wantErr.NamesKey && !strings.Contains(msg, key) {
			t.Errorf("%s: error %q does not name the key %s", where, msg, key)
		}
		if wantErr.NamesClient && !strings.Contains(msg, client) {
			t.Errorf("%s: error %q does not name the client %s", where, msg, client)
		}
		if wantErr.Exception == "IllegalStateException" && !errors.Is(gotErr, clientsettings.ErrDecode) {
			t.Errorf("%s: error %v is not a decode error", where, gotErr)
		}
	default:
		if gotErr != nil {
			t.Errorf("%s: error %v", where, gotErr)
		}
		if gotResult != nil {
			t.Errorf("%s: failed %v", where, gotResult)
		}
		if len(wantValue) > 0 && op == "get" {
			conformance.EqualJSON(t, where+" value", wantValue, gotValue)
		}
		if wantResult == "committed" && gotResult != nil {
			t.Errorf("%s: want committed", where)
		}
	}
}

func compareRows(t *testing.T, r *rig, want []struct {
	ClientID string         `json:"client_id"`
	Version  int64          `json:"version"`
	Settings jsontext.Value `json:"settings"`
},
) {
	t.Helper()
	rows, err := r.db.Pool().Query(t.Context(), "SELECT client_id, version, settings::text FROM fw_client_settings ORDER BY client_id")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	i := 0
	for rows.Next() {
		var id, text string
		var version int64
		if err := rows.Scan(&id, &version, &text); err != nil {
			t.Fatal(err)
		}
		if i >= len(want) {
			t.Errorf("unexpected row %s", id)
			i++
			continue
		}
		w := want[i]
		if id != w.ClientID || version != w.Version {
			t.Errorf("row %d: %s v%d, want %s v%d", i, id, version, w.ClientID, w.Version)
		}
		// equal as jsonb: Java's text differs in number spelling (1.0), which jsonb equality ignores
		conformance.EqualJSON(t, fmt.Sprintf("row %s settings", id), w.Settings, []byte(text))
		i++
	}
	if i != len(want) {
		t.Errorf("%d rows, want %d", i, len(want))
	}
}
