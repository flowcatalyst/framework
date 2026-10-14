package javafmt_test

import (
	"math"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// Expected texts are Java's (JDK 25) Double.toString results.
func TestDouble(t *testing.T) {
	t.Parallel()
	cases := map[float64]string{
		1:                           "1.0",
		-1:                          "-1.0",
		0.1:                         "0.1",
		100:                         "100.0",
		1234.5:                      "1234.5",
		0.001:                       "0.001",
		0.0001:                      "1.0E-4",
		9999999:                     "9999999.0",
		1e7:                         "1.0E7",
		1.5e10:                      "1.5E10",
		123456789:                   "1.23456789E8",
		1e23:                        "1.0E23",
		2e-3:                        "0.002",
		0.005:                       "0.005",
		2.5:                         "2.5",
		math.MaxFloat64:             "1.7976931348623157E308",
		math.SmallestNonzeroFloat64: "4.9E-324",
		math.Inf(1):                 "Infinity",
		math.Inf(-1):                "-Infinity",
		0:                           "0.0",
		1.0 / 3:                     "0.3333333333333333",
		12345678.9:                  "1.23456789E7",
	}
	for v, want := range cases {
		if got := javafmt.Double(v); got != want {
			t.Errorf("Double(%v) = %q, want %q", v, got, want)
		}
	}
	if got := javafmt.Double(math.Copysign(0, -1)); got != "-0.0" {
		t.Errorf("Double(-0) = %q", got)
	}
	if got := javafmt.Double(math.NaN()); got != "NaN" {
		t.Errorf("Double(NaN) = %q", got)
	}
}

func TestInstant(t *testing.T) {
	t.Parallel()
	base := time.Date(2026, 10, 8, 10, 15, 30, 0, time.UTC)
	cases := map[time.Duration]string{
		0:                           "2026-10-08T10:15:30Z",
		123 * time.Millisecond:      "2026-10-08T10:15:30.123Z",
		500 * time.Millisecond:      "2026-10-08T10:15:30.500Z",
		123 * time.Microsecond:      "2026-10-08T10:15:30.000123Z",
		1:                           "2026-10-08T10:15:30.000000001Z",
		123456700 * time.Nanosecond: "2026-10-08T10:15:30.123456700Z",
	}
	for d, want := range cases {
		if got := javafmt.Instant(base.Add(d)); got != want {
			t.Errorf("Instant(+%v) = %q, want %q", d, got, want)
		}
	}
	if got := javafmt.Instant(base.In(time.FixedZone("x", 3600))); got != "2026-10-08T10:15:30Z" {
		t.Errorf("Instant in another zone = %q", got)
	}
}
