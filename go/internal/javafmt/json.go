package javafmt

import (
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"time"
)

// JSONOptions makes encoding/json/v2 write what the Java framework's Jackson 3 mapper (the outbox
// mapper: dates as ISO-8601 text, NON_NULL) writes for the same values: time.Time as Java's
// Instant.toString, float64 and float32 as Java's Double.toString and Float.toString (1.0,
// 1.0E10). Strings, integers, booleans, slices and struct field order already match; NaN and
// infinities are refused (Jackson would write them as strings).
func JSONOptions() json.Options {
	return json.WithMarshalers(json.JoinMarshalers(
		json.MarshalToFunc(func(enc *jsontext.Encoder, t time.Time) error {
			return enc.WriteToken(jsontext.String(Instant(t)))
		}),
		json.MarshalToFunc(func(enc *jsontext.Encoder, v float64) error {
			if v != v || v > maxFloat || v < -maxFloat {
				return errNotFinite
			}
			return enc.WriteValue(jsontext.Value(Double(v)))
		}),
		json.MarshalToFunc(func(enc *jsontext.Encoder, v float32) error {
			f := float64(v)
			if f != f || f > maxFloat || f < -maxFloat {
				return errNotFinite
			}
			return enc.WriteValue(jsontext.Value(Float(v)))
		}),
	))
}

const maxFloat = 1.7976931348623157e308

var errNotFinite = errors.New("NaN and infinities cannot be written as JSON")

// MarshalJSON marshals v as Jackson does ([JSONOptions]) and applies [UpperHexEscapes].
func MarshalJSON(v any) ([]byte, error) {
	b, err := json.Marshal(v, JSONOptions())
	if err != nil {
		return nil, err
	}
	return UpperHexEscapes(b), nil
}

// UpperHexEscapes rewrites the hex digits of every \uXXXX escape in a JSON text to upper case,
// as Jackson writes them (\u001F where encoding/json/v2 writes \u001f). It changes nothing else:
// a backslash outside a string cannot occur in JSON, and an escaped backslash (\\) is skipped.
func UpperHexEscapes(b []byte) []byte {
	for i := 0; i < len(b); i++ {
		if b[i] != '\\' || i+1 >= len(b) {
			continue
		}
		if b[i+1] == 'u' && i+5 < len(b) {
			for j := i + 2; j < i+6; j++ {
				if b[j] >= 'a' && b[j] <= 'f' {
					b[j] -= 'a' - 'A'
				}
			}
			i += 5
			continue
		}
		i++ // skip the escaped character, including a second backslash
	}
	return b
}
