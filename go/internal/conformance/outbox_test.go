package conformance_test

import (
	"encoding/json/jsontext"
	"encoding/json/v2"
	"log/slog"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/internal/dbtx"
	"github.com/flowcatalyst/framework/go/usecase"
)

const outboxVector = "outbox/outbox.json"

type outboxDoc struct {
	Cases []struct {
		Name    string `json:"name"`
		Now     string `json:"now"`
		Context struct {
			Client      string  `json:"client"`
			Principal   *string `json:"principal"`
			Application *string `json:"application"`
			Correlation string  `json:"correlation"`
			Causation   *string `json:"causation"`
			Execution   *string `json:"execution"`
		} `json:"context"`
		Events []struct {
			Kind         string         `json:"kind"`
			EventID      string         `json:"eventId"`
			Type         string         `json:"type"`
			SubjectID    string         `json:"subjectId"`
			MessageGroup *string        `json:"messageGroup"`
			Fields       jsontext.Value `json:"fields"`
		} `json:"events"`
		DispatchJobs []jsontext.Value `json:"dispatchJobs"`
		Audit        struct {
			EntityType string         `json:"entityType"`
			EntityID   string         `json:"entityId"`
			Operation  string         `json:"operation"`
			Data       jsontext.Value `json:"data"`
			Source     string         `json:"source"`
		} `json:"audit"`
		Rows []outboxRowVector `json:"rows"`
	} `json:"cases"`
	InvalidDispatchJobs []jsontext.Value `json:"invalidDispatchJobs"`
}

type outboxRowVector struct {
	ID           string         `json:"id"`
	Type         string         `json:"type"`
	MessageGroup *string        `json:"message_group"`
	Status       int            `json:"status"`
	RetryCount   int            `json:"retry_count"`
	ClientID     *string        `json:"client_id"`
	PayloadSize  int            `json:"payload_size"`
	Headers      *string        `json:"headers"`
	ErrorMessage *string        `json:"error_message"`
	CreatedAt    string         `json:"created_at"`
	UpdatedAt    string         `json:"updated_at"`
	Payload      jsontext.Value `json:"payload"`
	PayloadText  string         `json:"payloadText"`
}

// The vector's event kinds, as Go events: the JSON keys are the Java records' component names.
type line struct {
	SKU      string `json:"sku"`
	Quantity int    `json:"quantity"`
}

type placed struct {
	Meta       event.Metadata `json:"-"`
	OrderID    string         `json:"orderId"`
	Customer   string         `json:"customer"`
	Lines      []line         `json:"lines"`
	TotalCents int64          `json:"totalCents"`
	Note       *string        `json:"note,omitzero"`
	PlacedAt   time.Time      `json:"placedAt"`
}

func (e placed) Metadata() event.Metadata { return e.Meta }

type cancelled struct {
	Meta    event.Metadata `json:"-"`
	OrderID string         `json:"orderId"`
	Reason  string         `json:"reason"`
}

func (e cancelled) Metadata() event.Metadata { return e.Meta }

func TestOutboxInsertIsTheSharedStatement(t *testing.T) {
	t.Parallel()
	conformance.EqualBytes(t, "sql/outbox/insert.sql", conformance.ReadFile(t, "sql/outbox/insert.sql"), []byte(usecase.InsertStatement()))
}

// TestOutboxVector writes every case's events, dispatch jobs and audit entry with the Go outbox
// writer and compares every stored column with the rows the Java writer stored, the payload text
// byte for byte.
func TestOutboxVector(t *testing.T) {
	t.Parallel()
	var doc outboxDoc
	conformance.ReadJSON(t, outboxVector, &doc)
	if len(doc.Cases) == 0 {
		t.Fatal("no cases")
	}
	for _, c := range doc.Cases {
		t.Run(c.Name, func(t *testing.T) {
			t.Parallel()
			ctx := t.Context()
			now, err := time.Parse(time.RFC3339Nano, c.Now)
			if err != nil {
				t.Fatal(err)
			}
			cx, err := fw.NewContext(fw.MustClientID(c.Context.Client), fw.MustCorrelationID(c.Context.Correlation))
			if err != nil {
				t.Fatal(err)
			}
			if p := c.Context.Principal; p != nil {
				cx = cx.WithPrincipal(fw.MustPrincipalID(*p))
			}
			if a := c.Context.Application; a != nil {
				cx = cx.WithApplication(fw.MustApplicationCode(*a))
			}
			if x := c.Context.Causation; x != nil {
				cx = cx.WithCausation(fw.MustCausationID(*x))
			}
			if x := c.Context.Execution; x != nil {
				cx = cx.WithExecution(fw.MustExecutionID(*x))
			}
			var events []event.Event
			for _, e := range c.Events {
				meta := event.New(cx, event.Fields{EventID: e.EventID, Type: event.MustType(e.Type), SubjectID: e.SubjectID, Time: now})
				if e.MessageGroup != nil {
					meta = meta.WithMessageGroup(*e.MessageGroup)
				}
				events = append(events, vectorEvent(t, e.Kind, meta, e.Fields))
			}
			var jobs []usecase.DispatchJob
			for _, raw := range c.DispatchJobs {
				j, err := dispatchJob(t, raw)
				if err != nil {
					t.Fatalf("dispatch job %s: %v", raw, err)
				}
				jobs = append(jobs, j)
			}
			entry := usecase.AuditEntry{
				EntityType: c.Audit.EntityType, EntityID: c.Audit.EntityID, Operation: c.Audit.Operation,
				PerformedAt: now, Source: c.Audit.Source, CorrelationID: cx.Correlation().String(), Client: cx.Client(),
			}
			if len(c.Audit.Data) > 0 {
				entry.Data, entry.HasData = auditFields(t, c.Audit.Data), true
			}
			if p, ok := cx.Principal(); ok {
				entry.PrincipalID = p.String()
			}
			if a, ok := cx.Application(); ok {
				entry.ApplicationCode = a.String()
			}

			db := outboxDatabase(t)
			clock := fwtest.NewClock(now)
			writer := usecase.NewOutboxWriter(fw.NewIds(clock), clock)
			err = pgx.BeginFunc(ctx, db.Pool(), func(tx pgx.Tx) error {
				wtx := dbtx.New(tx)
				if err := writer.WriteEvents(ctx, wtx, cx, events); err != nil {
					return err
				}
				if len(jobs) > 0 {
					if err := writer.WriteDispatchJobs(ctx, wtx, cx, jobs); err != nil {
						return err
					}
				}
				return writer.WriteAudit(ctx, wtx, entry)
			})
			if err != nil {
				t.Fatal(err)
			}
			compareRows(t, c.Name, c.Rows, readOutbox(t, db))
		})
	}
	for i, raw := range doc.InvalidDispatchJobs {
		var want struct {
			Error string `json:"error"`
		}
		if err := json.Unmarshal(raw, &want); err != nil {
			t.Fatal(err)
		}
		_, err := dispatchJob(t, raw)
		where := conformance.Where(outboxVector, "invalidDispatchJobs "+strconv.Itoa(i), "error")
		if err == nil || err.Error() != want.Error {
			t.Errorf("%s: got %v, want %q", where, err, want.Error)
		}
	}
}

func vectorEvent(t *testing.T, kind string, meta event.Metadata, fields jsontext.Value) event.Event {
	t.Helper()
	switch kind {
	case "placed":
		var f struct {
			OrderID    string    `json:"orderId"`
			Customer   string    `json:"customer"`
			Lines      []line    `json:"lines"`
			TotalCents int64     `json:"totalCents"`
			Note       *string   `json:"note"`
			PlacedAt   time.Time `json:"placedAt"`
		}
		if err := json.Unmarshal(fields, &f); err != nil {
			t.Fatal(err)
		}
		return placed{Meta: meta, OrderID: f.OrderID, Customer: f.Customer, Lines: f.Lines, TotalCents: f.TotalCents, Note: f.Note, PlacedAt: f.PlacedAt}
	case "cancelled":
		var f cancelled
		if err := json.Unmarshal(fields, &f); err != nil {
			t.Fatal(err)
		}
		f.Meta = meta
		return f
	}
	t.Fatalf("unknown event kind %q", kind)
	return nil
}

// auditFields keeps the vector's key order; numbers become integers (Java's numberValue).
func auditFields(t *testing.T, raw jsontext.Value) []usecase.AuditField {
	t.Helper()
	dec := jsontext.NewDecoder(strings.NewReader(string(raw)))
	if _, err := dec.ReadToken(); err != nil { // {
		t.Fatal(err)
	}
	var out []usecase.AuditField
	for dec.PeekKind() != '}' {
		tok, err := dec.ReadToken()
		if err != nil {
			t.Fatal(err)
		}
		key := tok.String() // read before the next call voids the token
		v, err := dec.ReadValue()
		if err != nil {
			t.Fatal(err)
		}
		if v.Kind() == '0' {
			n, err := strconv.ParseInt(string(v), 10, 64)
			if err != nil {
				t.Fatal(err)
			}
			out = append(out, usecase.Field(key, n))
			continue
		}
		var s string
		if err := json.Unmarshal(v, &s); err != nil {
			t.Fatal(err)
		}
		out = append(out, usecase.Field(key, s))
	}
	return out
}

func dispatchJob(t *testing.T, raw jsontext.Value) (usecase.DispatchJob, error) {
	t.Helper()
	var j struct {
		ID               *string        `json:"id"`
		Code             string         `json:"code"`
		TargetURL        string         `json:"targetUrl"`
		Kind             *string        `json:"kind"`
		Source           *string        `json:"source"`
		Subject          *string        `json:"subject"`
		JSONPayload      jsontext.Value `json:"jsonPayload"`
		PayloadText      *string        `json:"payloadText"`
		ContentType      *string        `json:"contentType"`
		DataOnly         *bool          `json:"dataOnly"`
		ExternalID       *string        `json:"externalId"`
		EventID          *string        `json:"eventId"`
		PlatformClientID *string        `json:"platformClientId"`
		SubscriptionID   *string        `json:"subscriptionId"`
		ServiceAccountID *string        `json:"serviceAccountId"`
		DispatchPoolID   *string        `json:"dispatchPoolId"`
		MessageGroup     *string        `json:"messageGroup"`
		Mode             *string        `json:"mode"`
		Sequence         *int           `json:"sequence"`
		TimeoutSeconds   *int           `json:"timeoutSeconds"`
		MaxRetries       *int           `json:"maxRetries"`
		Metadata         [][2]string    `json:"metadata"`
		Queue            *string        `json:"queue"`
		Descriptor       *string        `json:"descriptor"`
	}
	if err := json.Unmarshal(raw, &j, json.RejectUnknownMembers(false)); err != nil {
		t.Fatal(err)
	}
	var opts []usecase.JobOption
	str := func(p *string, f func(string) usecase.JobOption) {
		if p != nil {
			opts = append(opts, f(*p))
		}
	}
	str(j.ID, usecase.JobID)
	if j.Kind != nil {
		opts = append(opts, usecase.KindOf(usecase.JobKind(*j.Kind)))
	}
	str(j.Source, usecase.Source)
	str(j.Subject, usecase.Subject)
	if len(j.JSONPayload) > 0 {
		opts = append(opts, usecase.JSONPayload(j.JSONPayload))
	}
	if j.PayloadText != nil {
		ct := ""
		if j.ContentType != nil {
			ct = *j.ContentType
		}
		opts = append(opts, usecase.TextPayload(*j.PayloadText, ct))
	}
	if j.DataOnly != nil {
		opts = append(opts, usecase.DataOnly(*j.DataOnly))
	}
	str(j.ExternalID, usecase.ExternalID)
	str(j.EventID, usecase.EventID)
	str(j.PlatformClientID, usecase.PlatformClientID)
	str(j.SubscriptionID, usecase.SubscriptionID)
	str(j.ServiceAccountID, usecase.ServiceAccountID)
	str(j.DispatchPoolID, usecase.DispatchPoolID)
	str(j.MessageGroup, usecase.MessageGroup)
	if j.Mode != nil {
		opts = append(opts, usecase.Mode(usecase.JobMode(*j.Mode)))
	}
	if j.Sequence != nil {
		opts = append(opts, usecase.Sequence(*j.Sequence))
	}
	if j.TimeoutSeconds != nil {
		opts = append(opts, usecase.TimeoutSeconds(*j.TimeoutSeconds))
	}
	if j.MaxRetries != nil {
		opts = append(opts, usecase.MaxRetries(*j.MaxRetries))
	}
	for _, kv := range j.Metadata {
		opts = append(opts, usecase.Metadata(kv[0], kv[1]))
	}
	if j.Queue != nil {
		opts = append(opts, usecase.Queue(usecase.JobQueue(*j.Queue)))
	}
	str(j.Descriptor, usecase.Descriptor)
	return usecase.NewDispatchJob(j.Code, j.TargetURL, opts...)
}

func outboxDatabase(t *testing.T) *database.DB {
	t.Helper()
	db := openFresh(t)
	if err := database.ApplyMigrations(t.Context(), db, []fw.MigrationSet{{Module: usecase.OutboxModuleID, Files: usecase.OutboxMigrations()}},
		slog.New(slog.DiscardHandler)); err != nil {
		t.Fatal(err)
	}
	return db
}

func readOutbox(t *testing.T, db *database.DB) []outboxRowVector {
	t.Helper()
	utc := func(col string) string {
		return "to_char(" + col + ` AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')`
	}
	rows, err := db.Pool().Query(t.Context(), "SELECT id, type, message_group, status, retry_count, client_id, payload_size, headers::text,"+
		" error_message, "+utc("created_at")+", "+utc("updated_at")+", payload FROM outbox_messages ORDER BY ctid")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []outboxRowVector
	for rows.Next() {
		var r outboxRowVector
		var status, retry int16
		var size int32
		if err := rows.Scan(&r.ID, &r.Type, &r.MessageGroup, &status, &retry, &r.ClientID, &size, &r.Headers,
			&r.ErrorMessage, &r.CreatedAt, &r.UpdatedAt, &r.PayloadText); err != nil {
			t.Fatal(err)
		}
		r.Status, r.RetryCount, r.PayloadSize = int(status), int(retry), int(size)
		out = append(out, r)
	}
	return out
}

func compareRows(t *testing.T, name string, want, got []outboxRowVector) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("%s: %d rows, want %d", conformance.Where(outboxVector, name, "rows"), len(got), len(want))
	}
	str := func(p *string) string {
		if p == nil {
			return "<null>"
		}
		return *p
	}
	for i, w := range want {
		g := got[i]
		where := func(field string) string { return conformance.Where(outboxVector, name+" row "+strconv.Itoa(i), field) }
		if w.ID == "<tsid>" {
			if !fw.CanonicalTSID(g.ID) {
				t.Errorf("%s: %q is not a TSID", where("id"), g.ID)
			}
		} else if g.ID != w.ID {
			t.Errorf("%s: got %q, want %q", where("id"), g.ID, w.ID)
		}
		for _, c := range []struct{ field, got, want string }{
			{"type", g.Type, w.Type},
			{"message_group", str(g.MessageGroup), str(w.MessageGroup)},
			{"status", strconv.Itoa(g.Status), strconv.Itoa(w.Status)},
			{"retry_count", strconv.Itoa(g.RetryCount), strconv.Itoa(w.RetryCount)},
			{"client_id", str(g.ClientID), str(w.ClientID)},
			{"payload_size", strconv.Itoa(g.PayloadSize), strconv.Itoa(w.PayloadSize)},
			{"headers", str(g.Headers), str(w.Headers)},
			{"error_message", str(g.ErrorMessage), str(w.ErrorMessage)},
			{"created_at", g.CreatedAt, w.CreatedAt},
			{"updated_at", g.UpdatedAt, w.UpdatedAt},
		} {
			if c.got != c.want {
				t.Errorf("%s: got %q, want %q", where(c.field), c.got, c.want)
			}
		}
		conformance.EqualBytes(t, where("payloadText"), []byte(w.PayloadText), []byte(g.PayloadText))
		conformance.EqualJSON(t, where("payload"), w.Payload, parsedPayload(t, g.PayloadText))
	}
}

// parsedPayload parses a payload and the JSON documents carried in its data and operationData
// strings, as the vector stores them.
func parsedPayload(t *testing.T, text string) []byte {
	t.Helper()
	var m map[string]jsontext.Value
	if err := json.Unmarshal([]byte(text), &m); err != nil {
		t.Fatalf("payload is not JSON: %v", err)
	}
	for _, k := range []string{"data", "operationData"} {
		if v, ok := m[k]; ok {
			var s string
			if err := json.Unmarshal(v, &s); err != nil {
				t.Fatal(err)
			}
			m[k] = jsontext.Value(s)
		}
	}
	b, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
