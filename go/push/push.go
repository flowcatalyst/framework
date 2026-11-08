package push

import (
	"github.com/flowcatalyst/framework/go/internal/pushrow"
)

// ErrInvalidPush is what every refused topic, type or data matches with errors.Is.
var ErrInvalidPush = pushrow.ErrInvalid

// InvalidPushError is a refused push: Reason is one of the codes of conformance/push/topics.json
// (TOPIC_INVALID, TYPE_INVALID, TYPE_RESERVED, DATA_INVALID, DATA_NOT_OBJECT, DATA_TOO_LARGE).
type InvalidPushError = pushrow.Error

// The limits of push contract section 1.
const (
	// MaxTopicLength is the longest topic, in characters.
	MaxTopicLength = pushrow.MaxTopicLength
	// MaxTypeLength is the longest type, in characters.
	MaxTypeLength = pushrow.MaxTypeLength
	// MaxDataBytes is the most push.max-data-bytes may be.
	MaxDataBytes = pushrow.MaxDataBytes
)

// Push is a checked push: a topic, a type and the data as compact JSON text.
type Push struct {
	topic, typ, data string
}

// New checks and returns a push (push contract section 1): topic
// {application}:{segment}(:{segment})*, type [a-z][a-z0-9-]*(.[a-z][a-z0-9-]*)* and not reserved,
// data a JSON object of at most [MaxDataBytes] (the configured push.max-data-bytes is checked when
// it is published). data is marshalled (map keys sorted) unless it is JSON text (jsontext.Value or
// []byte). The error is an [*InvalidPushError].
func New(topic, typ string, data any) (Push, error) {
	if err := pushrow.CheckTopic(topic); err != nil {
		return Push{}, err
	}
	if err := pushrow.CheckType(typ); err != nil {
		return Push{}, err
	}
	text, err := pushrow.Encode(data, pushrow.MaxDataBytes)
	if err != nil {
		return Push{}, err
	}
	return Push{topic: topic, typ: typ, data: text}, nil
}

// Topic returns the push's topic.
func (p Push) Topic() string { return p.topic }

// Type returns the push's type.
func (p Push) Type() string { return p.typ }

// Data returns the push's data, compact JSON.
func (p Push) Data() string { return p.data }

// IsZero reports whether p is the zero Push, which nothing publishes.
func (p Push) IsZero() bool { return p.topic == "" }

// CheckTopic checks a topic against push contract section 1; the error is an [*InvalidPushError].
func CheckTopic(topic string) error { return pushrow.CheckTopic(topic) }

// CheckType checks a type against push contract section 1; the error is an [*InvalidPushError].
func CheckType(typ string) error { return pushrow.CheckType(typ) }
