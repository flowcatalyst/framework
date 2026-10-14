package usecase

import (
	"context"
	"embed"
	"encoding/json/jsontext"
	"fmt"
	"io/fs"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/javafmt"
	"github.com/flowcatalyst/framework/go/internal/sqltext"
)

// OutboxModuleID is the module that contributes the outbox_messages migrations (the Java
// module's id, so the history rows are the same).
const OutboxModuleID = "outbox"

//go:embed sql/insert.sql
var insertSQL string

//go:embed migrations/*.sql
var migrationFiles embed.FS

var insertNumbered = sqltext.Number(insertSQL)

// InsertStatement returns the outbox INSERT as embedded (the shared
// conformance/sql/outbox/insert.sql, JDBC placeholders), for the conformance tests.
func InsertStatement() string { return insertSQL }

// OutboxMigrations returns the outbox migration set's files: V0001 is the FlowCatalyst SDK's
// table byte for byte, V0002 widens client_id to 100 characters.
func OutboxMigrations() fs.FS {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		panic(err) // embedded above
	}
	return sub
}

// Declared is the outbox module's declaration: the proof [NewRunner] needs that the
// outbox_messages migrations are part of the application.
type Declared struct{}

// Declare declares the outbox module (id "outbox") and its migration set. An application that
// already created outbox_messages by other means (the SDK's own migration) must not declare it.
func Declare(app *fw.App) *Declared {
	app.Module(OutboxModuleID).Migrations(OutboxMigrations())
	return &Declared{}
}

// AuditField is one entry of a command's audit data.
type AuditField struct {
	Key   string
	Value any
}

// Field returns an audit field; value must marshal to JSON.
func Field(key string, value any) AuditField { return AuditField{Key: key, Value: value} }

// AuditData is implemented by a command that wants data in its audit row; the fields are written
// in order as a JSON object (operationData). Commands that do not implement it record only the
// fact of the operation: keep secrets out.
type AuditData interface {
	AuditData() []AuditField
}

// AuditEntry is one audit row as a use-case run produces it.
type AuditEntry struct {
	EntityType, EntityID, Operation string
	Data                            []AuditField
	HasData                         bool
	PrincipalID                     string // "" when none
	PerformedAt                     time.Time
	Source, CorrelationID           string
	ApplicationCode                 string // "" when none
	Client                          fw.ClientID
}

// OutboxWriter writes events, dispatch jobs and audit rows to outbox_messages inside the
// attempt's transaction (write-path contract sections 7 and 7a): the FlowCatalyst SDK's table and
// payloads, so the platform's outbox processor reads them unchanged, byte for byte what the Java
// framework writes (conformance/outbox/outbox.json).
type OutboxWriter struct {
	ids   *fw.Ids
	clock fw.Clock
}

// NewOutboxWriter returns a writer generating audit and job ids with ids and stamping rows with
// clock.
func NewOutboxWriter(ids *fw.Ids, clock fw.Clock) *OutboxWriter {
	return &OutboxWriter{ids: ids, clock: clock}
}

type outboxRow struct {
	id, typ, group, payload, client string
}

// WriteEvents writes one EVENT row per event, in order.
func (w *OutboxWriter) WriteEvents(ctx context.Context, tx *database.Tx, cx fw.Context, events []event.Event) error {
	rows := make([]outboxRow, 0, len(events))
	for _, ev := range events {
		payload, err := EventPayload(cx, ev)
		if err != nil {
			return err
		}
		m := ev.Metadata()
		rows = append(rows, outboxRow{id: m.EventID(), typ: "EVENT", group: m.MessageGroup(), payload: payload, client: cx.Client().String()})
	}
	return w.insert(ctx, tx, rows)
}

// WriteDispatchJobs writes one DISPATCH_JOB row per job, in order.
func (w *OutboxWriter) WriteDispatchJobs(ctx context.Context, tx *database.Tx, cx fw.Context, jobs []DispatchJob) error {
	rows := make([]outboxRow, 0, len(jobs))
	for _, j := range jobs {
		id := j.id
		if id == "" {
			id = w.ids.Generate()
		}
		rows = append(rows, outboxRow{id: id, typ: "DISPATCH_JOB", group: j.messageGroup, payload: DispatchJobPayload(cx, id, j), client: cx.Client().String()})
	}
	return w.insert(ctx, tx, rows)
}

// WriteAudit writes the audit row.
func (w *OutboxWriter) WriteAudit(ctx context.Context, tx *database.Tx, e AuditEntry) error {
	payload, err := AuditPayload(e)
	if err != nil {
		return err
	}
	return w.insert(ctx, tx, []outboxRow{{id: w.ids.Generate(), typ: "AUDIT_LOG", payload: payload, client: e.Client.String()}})
}

func (w *OutboxWriter) insert(ctx context.Context, tx *database.Tx, rows []outboxRow) error {
	now := w.clock.Now()
	batch := &pgx.Batch{}
	for _, r := range rows {
		var group any
		if r.group != "" {
			group = r.group
		}
		batch.Queue(insertNumbered, r.id, r.typ, group, r.payload, int16(0), now, now, r.client, int32(len(r.payload)), nil) //nolint:gosec // a payload is far below 2 GiB
	}
	return tx.Conn().SendBatch(ctx, batch).Close()
}

// object writes a JSON object with keys in insertion order (the Java writer's LinkedHashMap).
type object struct{ b []byte }

func (o *object) key(k string) {
	if len(o.b) == 0 {
		o.b = append(o.b, '{')
	} else {
		o.b = append(o.b, ',')
	}
	o.b, _ = jsontext.AppendQuote(o.b, k)
	o.b = append(o.b, ':')
}

func (o *object) str(k, v string) { o.key(k); o.b, _ = jsontext.AppendQuote(o.b, v) }

func (o *object) raw(k string, v []byte) { o.key(k); o.b = append(o.b, v...) }

func (o *object) int(k string, v int) { o.key(k); o.b = strconv.AppendInt(o.b, int64(v), 10) }

func (o *object) bool(k string, v bool) { o.key(k); o.b = strconv.AppendBool(o.b, v) }

func (o *object) done() string {
	if len(o.b) == 0 {
		return "{}"
	}
	return string(javafmt.UpperHexEscapes(append(o.b, '}')))
}

func keyValues(pairs [][2]string) []byte {
	var out []byte
	out = append(out, '[')
	for i, p := range pairs {
		if i > 0 {
			out = append(out, ',')
		}
		var o object
		o.str("key", p[0])
		o.str("value", p[1])
		out = append(out, o.done()...)
	}
	return append(out, ']')
}

// EventPayload is the SDK's CreateEventDto for an event: specVersion, type, source, subject,
// data (the event's JSON fields as a string), correlationId, causationId (when present),
// deduplicationId (the event id), messageGroup, clientCode and contextData (principalId and
// executionId, when present).
func EventPayload(cx fw.Context, ev event.Event) (string, error) {
	m := ev.Metadata()
	data, err := javafmt.MarshalJSON(ev)
	if err != nil {
		return "", fmt.Errorf("event %s data: %w", m.Type(), err)
	}
	var o object
	o.str("specVersion", m.SpecVersion())
	o.str("type", m.Type().String())
	o.str("source", m.Source())
	o.str("subject", m.Subject())
	o.str("data", string(data))
	o.str("correlationId", m.CorrelationID())
	if c, ok := m.CausationID(); ok {
		o.str("causationId", c)
	}
	o.str("deduplicationId", m.EventID())
	o.str("messageGroup", m.MessageGroup())
	o.str("clientCode", cx.Client().String())
	var contextData [][2]string
	if p, ok := m.PrincipalID(); ok {
		contextData = append(contextData, [2]string{"principalId", p})
	}
	if e, ok := m.ExecutionID(); ok {
		contextData = append(contextData, [2]string{"executionId", e})
	}
	if len(contextData) > 0 {
		o.raw("contextData", keyValues(contextData))
	}
	return o.done(), nil
}

// DispatchJobPayload is the platform's dispatch-jobs batch item for a job, with only the fields
// the endpoint reads, in the Java writer's order.
func DispatchJobPayload(cx fw.Context, id string, j DispatchJob) string {
	var o object
	o.str("id", id)
	o.str("kind", string(j.kind))
	o.str("code", j.code)
	o.str("source", j.source)
	optional(&o, "subject", j.subject)
	o.str("targetUrl", j.targetURL)
	o.str("payload", j.payload)
	o.str("payloadContentType", j.payloadContentType)
	o.bool("dataOnly", j.dataOnly)
	optional(&o, "externalId", j.externalID)
	optional(&o, "eventId", j.eventID)
	o.str("correlationId", cx.Correlation().String())
	optional(&o, "clientId", j.platformClientID)
	optional(&o, "subscriptionId", j.subscriptionID)
	optional(&o, "serviceAccountId", j.serviceAccountID)
	optional(&o, "dispatchPoolId", j.dispatchPoolID)
	optional(&o, "messageGroup", j.messageGroup)
	optional(&o, "mode", string(j.mode))
	optionalInt(&o, "sequence", j.sequence)
	optionalInt(&o, "timeoutSeconds", j.timeoutSeconds)
	optionalInt(&o, "maxRetries", j.maxRetries)
	if len(j.metadata) > 0 {
		pairs := make([][2]string, len(j.metadata))
		for i, e := range j.metadata {
			pairs[i] = [2]string{e.key, e.value}
		}
		o.raw("metadata", keyValues(pairs))
	}
	optional(&o, "queue", string(j.queue))
	optional(&o, "descriptor", j.descriptor)
	return o.done()
}

func optional(o *object, k, v string) {
	if v != "" {
		o.str(k, v)
	}
}

func optionalInt(o *object, k string, v *int) {
	if v != nil {
		o.int(k, *v)
	}
}

// AuditPayload is the SDK's CreateAuditLogDto: entityType, entityId, operation, operationData (the
// command's audit data as a JSON string, when it has any), principalId (when present),
// performedAt, source, correlationId, applicationCode (when present) and clientCode.
func AuditPayload(e AuditEntry) (string, error) {
	var o object
	o.str("entityType", e.EntityType)
	o.str("entityId", e.EntityID)
	o.str("operation", e.Operation)
	if e.HasData {
		var data object
		for _, f := range e.Data {
			v, err := javafmt.MarshalJSON(f.Value)
			if err != nil {
				return "", fmt.Errorf("audit data %s: %w", f.Key, err)
			}
			data.raw(f.Key, v)
		}
		o.str("operationData", data.done())
	}
	if e.PrincipalID != "" {
		o.str("principalId", e.PrincipalID)
	}
	o.str("performedAt", javafmt.Instant(e.PerformedAt))
	o.str("source", e.Source)
	o.str("correlationId", e.CorrelationID)
	if e.ApplicationCode != "" {
		o.str("applicationCode", e.ApplicationCode)
	}
	o.str("clientCode", e.Client.String())
	return o.done(), nil
}
