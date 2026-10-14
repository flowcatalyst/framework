package javafmt_test

import (
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// jacksonOutput is what Jackson 3.2.3 with the outbox mapper's configuration (the Java
// framework's framework-usecase Json.newMapper: WRITE_DATES_AS_TIMESTAMPS off, NON_NULL for
// properties) wrote on 2026-10-14 for the record below, run with the local JDK 25. It is not a
// shared conformance vector (no Java test writes it); it pins the escaping, number and time forms
// the outbox payloads depend on.
const jacksonOutput = `{"text":"q\" b\\ s/ t\t n\n r\r b\b f\f c1\u0001 c1f\u001F del` + "\u007f" + ` ls` + " " + ` ps` + " " +
	` html<>&' eé emoji😀 nul\u0000","d1":1.0,"d2":0.1,"d3":1.0E10,"d4":1.0E-5,"d5":100.5,"f1":1.5,` +
	`"big":9223372036854775807,"small":-7,"flag":true,"at":"2026-10-08T10:15:31.500Z",` +
	`"at2":"2026-10-08T10:15:30.000000001Z","nums":[1,2],"m":{"z":1,"a":null,"m":"x"}}`

type ordered struct {
	K string
	V any
}

type orderedMap []ordered

type record struct {
	Text    string     `json:"text"`
	D1      float64    `json:"d1"`
	D2      float64    `json:"d2"`
	D3      float64    `json:"d3"`
	D4      float64    `json:"d4"`
	D5      float64    `json:"d5"`
	F1      float32    `json:"f1"`
	Big     int64      `json:"big"`
	Small   int        `json:"small"`
	Flag    bool       `json:"flag"`
	At      time.Time  `json:"at"`
	At2     time.Time  `json:"at2"`
	Nothing *string    `json:"nothing,omitzero"`
	Nums    []int      `json:"nums"`
	M       orderedMap `json:"m"`
}

// MarshalJSONTo is not used: the map is given as raw JSON to keep Java's insertion order.
func (m orderedMap) MarshalJSON() ([]byte, error) {
	out := []byte{'{'}
	for i, e := range m {
		if i > 0 {
			out = append(out, ',')
		}
		k, err := javafmt.MarshalJSON(e.K)
		if err != nil {
			return nil, err
		}
		v, err := javafmt.MarshalJSON(e.V)
		if err != nil {
			return nil, err
		}
		out = append(append(append(out, k...), ':'), v...)
	}
	return append(out, '}'), nil
}

func TestJSONMatchesJackson(t *testing.T) {
	t.Parallel()
	r := record{
		Text: "q\" b\\ s/ t\t n\n r\r b\b f\f c1\u0001 c1f\u001f del\u007f ls  ps  html<>&' eé emoji😀 nul\u0000",
		D1:   1.0, D2: 0.1, D3: 1e10, D4: 1e-5, D5: 100.5, F1: 1.5,
		Big: 9223372036854775807, Small: -7, Flag: true,
		At:   time.Date(2026, 10, 8, 10, 15, 31, 500_000_000, time.UTC),
		At2:  time.Date(2026, 10, 8, 10, 15, 30, 1, time.UTC),
		Nums: []int{1, 2},
		M:    orderedMap{{"z", 1}, {"a", nil}, {"m", "x"}},
	}
	got, err := javafmt.MarshalJSON(r)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != jacksonOutput {
		t.Errorf("JSON differs from Jackson's\n got: %s\nwant: %s", got, jacksonOutput)
	}
}

func TestUpperHexEscapes(t *testing.T) {
	t.Parallel()
	cases := map[string]string{
		`"\u001f"`:          `"\u001F"`,
		`"\\u001f"`:         `"\\u001f"`, // an escaped backslash, then text
		`"\\«"`:             `"\\«"`,
		`{"a":"x","b":"y"}`: `{"a":"x","b":"y"}`,
	}
	for in, want := range cases {
		if got := string(javafmt.UpperHexEscapes([]byte(in))); got != want {
			t.Errorf("%s -> %s, want %s", in, got, want)
		}
	}
}

func TestFloat(t *testing.T) {
	t.Parallel()
	for v, want := range map[float32]string{0.1: "0.1", 1.5: "1.5", 1e10: "1.0E10", 3.4028235e38: "3.4028235E38"} {
		if got := javafmt.Float(v); got != want {
			t.Errorf("Float(%v) = %s, want %s", v, got, want)
		}
	}
}
