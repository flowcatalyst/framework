package clientsettings

import (
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"
	"maps"
	"math"
	"strconv"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/usecase"
)

// ErrDecode is wrapped by the error [Get] returns when a stored value does not decode as the
// setting's type: a defect found where it happens, never silently replaced by the default.
var ErrDecode = errors.New("a stored client setting does not decode")

// Settings is one client's settings: the aggregate (client-settings contract section 3), id = the
// client's code. It is immutable: [With] and [Without] return a changed copy. The version lives in
// the [usecase.Loaded] or [Current] that holds it.
type Settings struct {
	client fw.ClientID
	values map[string]jsontext.Value
}

// Empty returns a client's settings with no values: every default applies.
func Empty(client fw.ClientID) Settings {
	return Settings{client: client, values: map[string]jsontext.Value{}}
}

// Client returns the client the settings belong to.
func (s Settings) Client() fw.ClientID { return s.client }

// ID returns the aggregate's id: the client's code.
func (s Settings) ID() string { return s.client.String() }

// Values returns a copy of the stored values: setting key to the value's JSON.
func (s Settings) Values() map[string]jsontext.Value { return maps.Clone(s.values) }

// IsSet reports whether the client has a value of its own for the setting.
func IsSet[T any](s Settings, set Setting[T]) bool {
	_, ok := s.values[set.key]
	return ok
}

// AuditEntity names the aggregate for the audit row.
func (s Settings) AuditEntity() usecase.Entity {
	return usecase.Entity{Type: "ClientSettings", ID: s.ID()}
}

// Get returns the client's value of the setting, or the default when it has none (or a stored JSON
// null). A stored value that does not decode as T is an error wrapping [ErrDecode] and naming the
// key and the client.
func Get[T any](s Settings, set Setting[T]) (T, error) {
	raw, ok := s.values[set.key]
	if !ok || raw.Kind() == 'n' {
		return set.def, nil
	}
	var v T
	if err := json.Unmarshal(raw, &v, decodeOptions); err != nil {
		return set.def, fmt.Errorf("%w: the stored value of client setting %s for client %s does not decode as %T: %w", ErrDecode, set.key, s.client, v, err)
	}
	return v, nil
}

// With returns the settings with the setting's value changed, after running its rule: a refusal is an
// error "client setting <key>: <message>" (Java's IllegalArgumentException). A use case checks
// first ([Setting.Check] in its Validate) so that a refusal is a VALIDATION error; this is the backstop.
func With[T any](s Settings, set Setting[T], v T) (Settings, error) {
	if msg := set.Check(v); msg != "" {
		return s, fmt.Errorf("client setting %s: %s", set.key, msg)
	}
	raw, err := json.Marshal(v, json.Deterministic(true))
	if err != nil {
		return s, fmt.Errorf("client setting %s: the value cannot be written as JSON: %w", set.key, err)
	}
	next := maps.Clone(s.values)
	next[set.key] = jsontext.Value(raw)
	return Settings{client: s.client, values: next}, nil
}

// Without returns the settings with the client's value removed, so the default applies again.
func Without[T any](s Settings, set Setting[T]) Settings {
	next := maps.Clone(s.values)
	delete(next, set.key)
	return Settings{client: s.client, values: next}
}

// decodeOptions makes the integer types read a JSON number with a zero fraction ("12.0", "1.2e1")
// as an integer, as Jackson does in the Java framework (Jackson also truncates a real fraction;
// Go refuses it). It applies to the exact types int, int32 and int64, wherever they occur in the
// setting's value.
var decodeOptions = json.WithUnmarshalers(json.JoinUnmarshalers(
	integral[int](64), integral[int32](32), integral[int64](64),
))

func integral[T ~int | ~int32 | ~int64](bits int) *json.Unmarshalers {
	return json.UnmarshalFromFunc(func(dec *jsontext.Decoder, p *T) error {
		if dec.PeekKind() != '0' {
			return errors.ErrUnsupported // not a number: the default decoding answers (and fails)
		}
		val, err := dec.ReadValue()
		if err != nil {
			return err
		}
		text := string(val)
		if n, err := strconv.ParseInt(text, 10, bits); err == nil {
			*p = T(n)
			return nil
		}
		f, err := strconv.ParseFloat(text, 64)
		if err != nil || f != math.Trunc(f) || math.Abs(f) >= 1<<62 {
			return fmt.Errorf("JSON number %s is not an integer", text)
		}
		n, err := strconv.ParseInt(strconv.FormatFloat(f, 'f', 0, 64), 10, bits)
		if err != nil {
			return fmt.Errorf("JSON number %s is out of range", text)
		}
		*p = T(n)
		return nil
	})
}
