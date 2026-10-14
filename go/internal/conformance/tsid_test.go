package conformance_test

import (
	"strconv"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/internal/conformance"
)

const tsidVector = "tsid/tsid.json"

type tsidDoc struct {
	Decode []struct {
		Text      string  `json:"text"`
		Value     *string `json:"value"`
		Canonical *bool   `json:"canonical"`
	} `json:"decode"`
	Prefixes []struct {
		Prefix string `json:"prefix"`
		Valid  bool   `json:"valid"`
	} `json:"prefixes"`
	Layout []struct {
		Millis     int64  `json:"millis"`
		MillisBits string `json:"millisBits"`
	} `json:"layout"`
}

func TestTSIDVector(t *testing.T) {
	t.Parallel()
	var doc tsidDoc
	conformance.ReadJSON(t, tsidVector, &doc)
	if len(doc.Decode) == 0 || len(doc.Prefixes) == 0 || len(doc.Layout) == 0 {
		t.Fatalf("%s: a section is empty", tsidVector)
	}

	for _, c := range doc.Decode {
		where := conformance.Where(tsidVector, "decode "+strconv.Quote(c.Text), "value")
		got, ok := fw.DecodeTSID(c.Text)
		if c.Value == nil {
			if ok {
				t.Errorf("%s: want no value (not a TSID), got %d", where, got)
			}
			continue
		}
		if !ok {
			t.Errorf("%s: want %s, got not a TSID", where, *c.Value)
			continue
		}
		if s := strconv.FormatUint(got, 10); s != *c.Value {
			t.Errorf("%s: want %s, got %s", where, *c.Value, s)
		}
		if c.Canonical == nil {
			t.Errorf("%s: a decodable case must say whether it is canonical", where)
			continue
		}
		canonical := fw.CanonicalTSID(c.Text)
		if canonical != *c.Canonical {
			t.Errorf("%s: canonical: want %v, got %v (encoding gives %s)", conformance.Where(tsidVector, c.Text, "canonical"),
				*c.Canonical, canonical, fw.EncodeTSID(got))
		}
	}

	for _, c := range doc.Prefixes {
		_, err := fw.ParseIDPrefix(c.Prefix)
		if (err == nil) != c.Valid {
			t.Errorf("%s: want valid=%v, got error %v", conformance.Where(tsidVector, "prefix "+strconv.Quote(c.Prefix), "valid"), c.Valid, err)
		}
	}

	for _, c := range doc.Layout {
		clock := fwtest.NewClock(time.UnixMilli(c.Millis))
		id := fw.NewIds(clock).Generate()
		v, ok := fw.DecodeTSID(id)
		if !ok {
			t.Fatalf("%s: generated %q does not decode", conformance.Where(tsidVector, c.Millis, "layout"), id)
		}
		if got := strconv.FormatUint(v>>22, 10); got != c.MillisBits {
			t.Errorf("%s: bits 63..22 of %s: want %s, got %s", conformance.Where(tsidVector, c.Millis, "millisBits"), id, c.MillisBits, got)
		}
	}
}
