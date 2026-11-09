// Package pushrow checks a push to clients and writes it to fw_push_outbox (push contract sections 1
// and 2). It is shared by the use-case runner (usecase.Push) and package push (Publish), so the two
// write the same row with the same checks.
package pushrow

import (
	"context"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/flowcatalyst/framework/go/fw"
)

// Limits of section 1.
const (
	MaxTopicLength      = 255
	MaxTypeLength       = 100
	MaxDataBytes        = 65536
	DefaultMaxDataBytes = 16384
)

// The reasons a push is refused (the codes of conformance/push/topics.json).
const (
	TopicInvalid  = "TOPIC_INVALID"
	TypeInvalid   = "TYPE_INVALID"
	TypeReserved  = "TYPE_RESERVED"
	DataInvalid   = "DATA_INVALID"
	DataNotObject = "DATA_NOT_OBJECT"
	DataTooLarge  = "DATA_TOO_LARGE"
)

// The reasons only the platform driver's outbox row has (the codes of the platform's ingest).
const (
	IDInvalid         = "PUSH_ID_INVALID"
	ClientInvalid     = "CLIENT_INVALID"
	OptionUnsupported = "OPTION_UNSUPPORTED"
)

// MaxGroupLength is the longest outbox message group.
const MaxGroupLength = 255

// CheckID checks a push id (the outbox row id and the payload's pushId): [0-9A-Za-z_-]{1,26}.
func CheckID(id string) error {
	ok := id != "" && len(id) <= 26
	for i := 0; ok && i < len(id); i++ {
		c := id[i]
		ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
	}
	if !ok {
		return refuse(IDInvalid, "id must match [0-9A-Za-z_-]{1,26}")
	}
	return nil
}

// CheckClientCode checks the client a push names: 1 to 100 characters, not blank.
func CheckClientCode(code string) error {
	if len(code) > 100 || strings.TrimSpace(code) == "" {
		return refuse(ClientInvalid, "clientCode must be 1 to 100 characters")
	}
	return nil
}

// CheckGroup checks an outbox message group: at most 255 characters.
func CheckGroup(group string) error {
	if len(group) > MaxGroupLength {
		return refuse(TopicInvalid, "messageGroup is at most %d characters", MaxGroupLength)
	}
	return nil
}

// ErrInvalid is what every refused push matches with errors.Is.
var ErrInvalid = errors.New("invalid push")

// Error is a refused push: the reason code and a message for the log.
type Error struct {
	Reason  string
	Message string
}

func (e *Error) Error() string { return "invalid push: " + e.Reason + ": " + e.Message }

// Is makes errors.Is(err, ErrInvalid) true.
func (e *Error) Is(target error) bool { return target == ErrInvalid }

func refuse(reason, format string, args ...any) error {
	return &Error{Reason: reason, Message: fmt.Sprintf(format, args...)}
}

// CheckTopic checks a topic: {application}:{segment}(:{segment})*, the application [a-z][a-z0-9-]*,
// each further segment [0-9A-Za-z_.-]+, at least two segments, at most 255 characters.
func CheckTopic(topic string) error {
	if len(topic) > MaxTopicLength {
		return refuse(TopicInvalid, "a topic is at most %d characters", MaxTopicLength)
	}
	segs := strings.Split(topic, ":")
	if len(segs) < 2 || !IsApplication(segs[0]) {
		return refuse(TopicInvalid, "a topic is {application}:{segment}(:{segment})*: %q", topic)
	}
	for _, s := range segs[1:] {
		if !IsSegment(s) {
			return refuse(TopicInvalid, "a topic segment is [0-9A-Za-z_.-]+: %q", topic)
		}
	}
	return nil
}

// IsApplication reports whether s is an application code: [a-z][a-z0-9-]*.
func IsApplication(s string) bool {
	if s == "" || s[0] < 'a' || s[0] > 'z' {
		return false
	}
	for i := 1; i < len(s); i++ {
		c := s[i]
		if (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-' {
			return false
		}
	}
	return true
}

// IsSegment reports whether s is a topic segment after the first: [0-9A-Za-z_.-]+.
func IsSegment(s string) bool {
	if s == "" {
		return false
	}
	for i := range len(s) {
		c := s[i]
		ok := (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-'
		if !ok {
			return false
		}
	}
	return true
}

// CheckType checks a type: [a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*, at most 100 characters, not reset,
// auth-expired or subscribed and not starting fw.
func CheckType(typ string) error {
	if len(typ) > MaxTypeLength {
		return refuse(TypeInvalid, "a type is at most %d characters", MaxTypeLength)
	}
	for part := range strings.SplitSeq(typ, ".") {
		if !IsApplication(part) {
			return refuse(TypeInvalid, "a type is [a-z][a-z0-9-]*(.[a-z][a-z0-9-]*)*: %q", typ)
		}
	}
	if typ == "reset" || typ == "auth-expired" || typ == "subscribed" || strings.HasPrefix(typ, "fw.") {
		return refuse(TypeReserved, "the type %q is reserved", typ)
	}
	return nil
}

// Encode returns data as the compact JSON text of an object, at most limit bytes of UTF-8. A
// jsontext.Value or []byte is taken as JSON text (as is a value with a MarshalJSON method, such as
// json.RawMessage); anything else is marshalled, map keys sorted.
func Encode(data any, limit int) (string, error) {
	var raw []byte
	switch v := data.(type) {
	case jsontext.Value:
		raw = []byte(v)
	case []byte:
		raw = v
	default:
		b, err := json.Marshal(v, json.Deterministic(true))
		if err != nil {
			return "", refuse(DataInvalid, "the data does not marshal to JSON: %v", err)
		}
		raw = b
	}
	value := jsontext.Value(append([]byte(nil), raw...))
	if err := value.Compact(); err != nil {
		return "", refuse(DataInvalid, "the data is not valid JSON")
	}
	if value.Kind() != '{' {
		return "", refuse(DataNotObject, "the data is not a JSON object")
	}
	if len(value) > limit {
		return "", refuse(DataTooLarge, "the data is %d bytes, more than %d", len(value), limit)
	}
	return string(value), nil
}

// Execer runs a statement in the caller's transaction (pgx.Tx).
type Execer interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
}

// insert writes the row and notifies fw_push_outbox, in one statement: both exist only if the
// transaction commits, and the notifications of one transaction are delivered once.
const insert = `WITH woken AS (SELECT pg_notify('fw_push_outbox', ''))
INSERT INTO fw_push_outbox (topic, type, client, data, created_at)
SELECT $1, $2, $3, $4, $5 FROM woken`

// Insert writes one checked push in the caller's transaction. The platform client is written as
// NULL (push contract section 5).
func Insert(ctx context.Context, tx Execer, topic, typ string, client fw.ClientID, data string, at time.Time) error {
	if client.IsZero() {
		return fw.ErrZeroContext
	}
	var c *string
	if client != fw.PlatformClient {
		s := client.String()
		c = &s
	}
	if _, err := tx.Exec(ctx, insert, topic, typ, c, data, at); err != nil {
		return fmt.Errorf("push: write to fw_push_outbox: %w", err)
	}
	return nil
}

// Row is one checked push to write: its client is already known to be set.
type Row struct {
	Topic, Type string
	Client      fw.ClientID
	Data        string
}

// insertBatch writes the rows in the order given (the ordinality column) and notifies
// fw_push_outbox once, in one statement: all of them exist only if the transaction commits.
const insertBatch = `WITH woken AS (SELECT pg_notify('fw_push_outbox', ''))
INSERT INTO fw_push_outbox (topic, type, client, data, created_at)
SELECT u.topic, u.type, u.client, u.data, $5
FROM unnest($1::text[], $2::text[], $3::text[], $4::text[]) WITH ORDINALITY AS u(topic, type, client, data, n), woken
ORDER BY u.n`

// InsertBatch writes checked pushes in the caller's transaction with one statement. The platform
// client is written as NULL (push contract section 5).
func InsertBatch(ctx context.Context, tx Execer, rows []Row, at time.Time) error {
	if len(rows) == 0 {
		return nil
	}
	topics, types, clients, data := make([]string, len(rows)), make([]string, len(rows)), make([]*string, len(rows)), make([]string, len(rows))
	for i, r := range rows {
		if r.Client.IsZero() {
			return fw.ErrZeroContext
		}
		topics[i], types[i], data[i] = r.Topic, r.Type, r.Data
		if r.Client != fw.PlatformClient {
			s := r.Client.String()
			clients[i] = &s
		}
	}
	if _, err := tx.Exec(ctx, insertBatch, topics, types, clients, data, at); err != nil {
		return fmt.Errorf("push: write to fw_push_outbox: %w", err)
	}
	return nil
}
