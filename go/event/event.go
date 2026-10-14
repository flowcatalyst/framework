// Package event holds what every domain event carries (write-path contract section 6): its type
// {application}:{domain}:{aggregate}:{action}, and the metadata the framework fills from the
// Context and the application's clock and ids. The event's own fields are its data: a struct
// with json tags (camelCase, the Java record's component names), marshalled into the outbox row
// by the use-case runner.
//
//	var Placed = event.MustType("orders:sales:order:placed")
//
//	type OrderPlaced struct {
//		Meta     event.Metadata `json:"-"`
//		OrderID  string         `json:"orderId"`
//		Customer string         `json:"customer"`
//	}
//
//	func (e OrderPlaced) Metadata() event.Metadata { return e.Meta }
package event

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// Type is an event type: four segments, each matching [a-z][a-z0-9-]*.
type Type struct {
	application, domain, aggregate, action string
}

// ErrInvalidType wraps every malformed event type.
var ErrInvalidType = errors.New("invalid event type")

// ParseType checks an event type.
func ParseType(s string) (Type, error) {
	parts := strings.Split(s, ":")
	if len(parts) != 4 {
		return Type{}, fmt.Errorf("%w: %q must be {application}:{domain}:{aggregate}:{action}", ErrInvalidType, s)
	}
	for _, p := range parts {
		if !isSegment(p) {
			return Type{}, fmt.Errorf("%w: event type segment must match [a-z][a-z0-9-]*: %q", ErrInvalidType, p)
		}
	}
	return Type{application: parts[0], domain: parts[1], aggregate: parts[2], action: parts[3]}, nil
}

// MustType is [ParseType] for literals; it panics on a malformed type (fwvet checks it).
func MustType(s string) Type {
	t, err := ParseType(s)
	if err != nil {
		panic(err)
	}
	return t
}

// String returns application:domain:aggregate:action.
func (t Type) String() string {
	return t.application + ":" + t.domain + ":" + t.aggregate + ":" + t.action
}

// IsZero reports whether t is the zero Type.
func (t Type) IsZero() bool { return t.application == "" }

// Application returns the application segment.
func (t Type) Application() string { return t.application }

// Source returns application:domain.
func (t Type) Source() string { return t.application + ":" + t.domain }

// Subject returns domain.aggregate.id for one aggregate.
func (t Type) Subject(id string) string { return t.domain + "." + t.aggregate + "." + id }

// MessageGroup returns the default message group, domain:aggregate:id, which keeps one
// aggregate's events in order.
func (t Type) MessageGroup(id string) string { return t.domain + ":" + t.aggregate + ":" + id }

func isSegment(s string) bool {
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

// SpecVersion is the event's spec version.
const SpecVersion = "1.0"

// Metadata is what every event carries besides its data. Make it with a [Factory] (or [New]).
// It is never part of the event's JSON data: the field holding it must be tagged json:"-", and
// marshalling it fails so a missing tag is found at once.
type Metadata struct {
	eventID      string
	typ          Type
	subject      string
	time         time.Time
	correlation  string
	principal    string
	causation    string
	execution    string
	messageGroup string
}

// Fields are the inputs of [New]: the event's own id and time and the subject's id.
type Fields struct {
	EventID   string
	Type      Type
	SubjectID string
	Time      time.Time
}

// New builds metadata from explicit values and the Context (correlation, principal, causation
// and execution ids). A Factory does this with a generated id and the clock's time.
func New(cx fw.Context, f Fields) Metadata {
	m := Metadata{
		eventID:      f.EventID,
		typ:          f.Type,
		subject:      f.Type.Subject(f.SubjectID),
		time:         f.Time,
		correlation:  cx.Correlation().String(),
		messageGroup: f.Type.MessageGroup(f.SubjectID),
	}
	if p, ok := cx.Principal(); ok {
		m.principal = p.String()
	}
	if c, ok := cx.Causation(); ok {
		m.causation = c.String()
	}
	if e, ok := cx.Execution(); ok {
		m.execution = e.String()
	}
	return m
}

// IsZero reports whether m is the zero Metadata, which the runner refuses.
func (m Metadata) IsZero() bool { return m.eventID == "" }

// EventID returns the event's id (a TSID).
func (m Metadata) EventID() string { return m.eventID }

// SpecVersion returns "1.0".
func (m Metadata) SpecVersion() string { return SpecVersion }

// Type returns the event type.
func (m Metadata) Type() Type { return m.typ }

// Source returns application:domain.
func (m Metadata) Source() string { return m.typ.Source() }

// Subject returns domain.aggregate.id.
func (m Metadata) Subject() string { return m.subject }

// Time returns when the event was made.
func (m Metadata) Time() time.Time { return m.time }

// CorrelationID returns the Context's correlation id.
func (m Metadata) CorrelationID() string { return m.correlation }

// PrincipalID returns the acting principal, if any.
func (m Metadata) PrincipalID() (string, bool) { return m.principal, m.principal != "" }

// CausationID returns the Context's causation id, if any.
func (m Metadata) CausationID() (string, bool) { return m.causation, m.causation != "" }

// ExecutionID returns the Context's execution id, if any.
func (m Metadata) ExecutionID() (string, bool) { return m.execution, m.execution != "" }

// MessageGroup returns the message group: domain:aggregate:id unless changed.
func (m Metadata) MessageGroup() string { return m.messageGroup }

// WithMessageGroup returns a copy with another message group; events sharing one are delivered
// in order.
func (m Metadata) WithMessageGroup(group string) Metadata {
	m.messageGroup = group
	return m
}

// MarshalJSON always fails: metadata is not part of an event's data.
func (Metadata) MarshalJSON() ([]byte, error) {
	return nil, errors.New(`event.Metadata is not part of the event's data: tag the field json:"-"`)
}

// Event is a domain event: a struct whose JSON fields are its data and which returns its
// metadata.
type Event interface {
	Metadata() Metadata
}

// Factory makes metadata with a generated TSID and the application's clock. An application has
// one, from NewFactory(b.Clock(), b.Ids()).
type Factory struct {
	clock fw.Clock
	ids   *fw.Ids
}

// NewFactory returns a factory reading clock and ids.
func NewFactory(clock fw.Clock, ids *fw.Ids) *Factory { return &Factory{clock: clock, ids: ids} }

// Metadata returns metadata for an event of type t about the aggregate subjectID.
func (f *Factory) Metadata(cx fw.Context, t Type, subjectID string) Metadata {
	return New(cx, Fields{EventID: f.ids.Generate(), Type: t, SubjectID: subjectID, Time: f.clock.Now()})
}
