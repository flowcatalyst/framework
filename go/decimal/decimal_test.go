package decimal_test

import (
	"encoding/json/v2"
	"errors"
	"testing"

	"github.com/flowcatalyst/framework/go/decimal"
)

func code(t *testing.T, err error) string {
	t.Helper()
	de, ok := errors.AsType[*decimal.Error](err)
	if !ok {
		t.Fatalf("not a *decimal.Error: %v", err)
	}
	if de.Status() != 400 {
		t.Fatalf("status %d, want 400", de.Status())
	}
	return de.Code()
}

func TestParseKeepsTheScale(t *testing.T) {
	t.Parallel()
	for in, want := range map[string]string{"3132.40": "3132.40", "0.0": "0.0", "007.5": "7.5", "-0.0": "0.0", "-0": "0", "12": "12", "-12.50": "-12.50", "0.000001": "0.000001"} {
		d, err := decimal.Parse(in)
		if err != nil || d.String() != want {
			t.Errorf("Parse(%q) = %q, %v; want %q", in, d, err, want)
		}
	}
	if decimal.MustParse("3132.40").Scale() != 2 {
		t.Error("scale of 3132.40")
	}
}

func TestParseRefusesAnythingButPlainDecimals(t *testing.T) {
	t.Parallel()
	for _, in := range []string{"", "-", "1e3", "1E-2", "+1", " 1", "1 ", "1.", ".5", "1,000", "1.2.3", "NaN", "0x10", "٣", "--1", "1_000"} {
		_, err := decimal.Parse(in)
		if err == nil || code(t, err) != "DECIMAL_INVALID" {
			t.Errorf("Parse(%q) accepted or wrong error: %v", in, err)
		}
	}
}

func TestScalesFollowBigDecimal(t *testing.T) {
	t.Parallel()
	a, b := decimal.MustParse("1.5"), decimal.MustParse("0.25")
	if got := a.Add(b); got.Scale() != 2 || got.String() != "1.75" {
		t.Errorf("add %v", got)
	}
	if got := a.Sub(b); got.String() != "1.25" {
		t.Errorf("sub %v", got)
	}
	if got := a.Mul(b); got.Scale() != 3 || got.String() != "0.375" {
		t.Errorf("mul %v", got)
	}
	if !decimal.MustParse("2.0").Equal(decimal.MustParse("2.00")) || decimal.MustParse("2.0").Cmp(decimal.MustParse("2.1")) != -1 {
		t.Error("compare is numeric")
	}
	if got := decimal.MustParse("-1.50").Abs(); got.String() != "1.50" {
		t.Errorf("abs %v", got)
	}
	if got := (decimal.Decimal{}); got.String() != "0" || !got.IsZero() || got.Add(decimal.FromInt(2)).String() != "2" {
		t.Errorf("zero value %q", got)
	}
}

func TestEveryModeAtTheHalfWayPoints(t *testing.T) {
	t.Parallel()
	type row struct {
		mode decimal.RoundingMode
		want map[string]string // value -> rounded to a whole number
	}
	rows := []row{
		{decimal.Up, map[string]string{"2.5": "3", "-2.5": "-3", "2.1": "3", "-2.1": "-3", "-0.1": "-1"}},
		{decimal.Down, map[string]string{"2.5": "2", "-2.5": "-2", "2.9": "2", "-0.9": "0"}},
		{decimal.Ceiling, map[string]string{"2.5": "3", "-2.5": "-2", "-0.1": "0", "0.1": "1"}},
		{decimal.Floor, map[string]string{"2.5": "2", "-2.5": "-3", "-0.1": "-1", "0.9": "0"}},
		{decimal.HalfUp, map[string]string{"2.5": "3", "-2.5": "-3", "2.4": "2", "2.6": "3", "-0.5": "-1"}},
		{decimal.HalfDown, map[string]string{"2.5": "2", "-2.5": "-2", "2.6": "3", "0.5": "0"}},
		{decimal.HalfEven, map[string]string{"2.5": "2", "3.5": "4", "-2.5": "-2", "-3.5": "-4", "0.5": "0", "1.5": "2"}},
	}
	for _, r := range rows {
		for in, want := range r.want {
			if got := decimal.MustParse(in).Round(0, r.mode); got.String() != want {
				t.Errorf("%v.Round(0, %v) = %v, want %v", in, r.mode, got, want)
			}
		}
	}
	if got := decimal.MustParse("1.5").Round(3, decimal.Up); got.String() != "1.500" {
		t.Errorf("raising the scale appends zeros: %v", got)
	}
	if got := decimal.MustParse("1250").Round(-2, decimal.HalfEven); got.String() != "1200" || got.Scale() != -2 {
		t.Errorf("negative scale: %v", got)
	}
}

func TestDivisionNamesScaleAndMode(t *testing.T) {
	t.Parallel()
	got, err := decimal.MustParse("1").Div(decimal.MustParse("3"), 4, decimal.HalfEven)
	if err != nil || got.String() != "0.3333" {
		t.Errorf("1/3 = %v, %v", got, err)
	}
	got, err = decimal.MustParse("-10").Div(decimal.MustParse("3"), 0, decimal.Floor)
	if err != nil || got.String() != "-4" {
		t.Errorf("-10/3 floor = %v, %v", got, err)
	}
	got, _ = decimal.MustParse("7831.2").Div(decimal.MustParse("1000"), 12, decimal.HalfEven)
	if got.String() != "7.831200000000" {
		t.Errorf("scale up: %v", got)
	}
	if _, err := decimal.MustParse("1").Div(decimal.MustParse("0.0"), 2, decimal.Up); code(t, err) != "DIVISION_BY_ZERO" {
		t.Errorf("zero divisor: %v", err)
	}
}

func TestDivExactHasTheSmallestScale(t *testing.T) {
	t.Parallel()
	for _, c := range []struct{ a, b, want string }{
		{"1000", "1", "1000"}, {"7831.20", "1000", "7.8312"}, {"1", "8", "0.125"}, {"0.0", "3", "0"}, {"-3", "4", "-0.75"}, {"10", "4", "2.5"}, {"1", "0.5", "2"},
	} {
		got, err := decimal.MustParse(c.a).DivExact(decimal.MustParse(c.b))
		if err != nil || got.String() != c.want {
			t.Errorf("%s/%s = %v, %v; want %s", c.a, c.b, got, err, c.want)
		}
	}
	if _, err := decimal.MustParse("1").DivExact(decimal.MustParse("3")); code(t, err) != "CONVERSION_INEXACT" {
		t.Errorf("1/3: %v", err)
	}
	if _, err := decimal.MustParse("1").DivExact(decimal.MustParse("0")); code(t, err) != "DIVISION_BY_ZERO" {
		t.Errorf("1/0: %v", err)
	}
}

func TestJSONIsAStringNeverANumber(t *testing.T) {
	t.Parallel()
	type body struct {
		Amount decimal.Decimal `json:"amount"`
	}
	b, err := json.Marshal(body{Amount: decimal.MustParse("3132.40")})
	if err != nil || string(b) != `{"amount":"3132.40"}` {
		t.Fatalf("marshal %s, %v", b, err)
	}
	var back body
	if err := json.Unmarshal(b, &back); err != nil || back.Amount.String() != "3132.40" {
		t.Fatalf("unmarshal %v, %v", back, err)
	}
	if err := json.Unmarshal([]byte(`{"amount":3132.4}`), &back); err == nil {
		t.Error("a JSON number was accepted")
	}
	if err := json.Unmarshal([]byte(`{"amount":"1e3"}`), &back); err == nil || code(t, err) != "DECIMAL_INVALID" {
		t.Errorf("bad text: %v", err)
	}
}

func TestSQLValueAndScan(t *testing.T) {
	t.Parallel()
	v, err := decimal.MustParse("12.50").Value()
	if err != nil || v != "12.50" {
		t.Fatalf("Value %v, %v", v, err)
	}
	var d decimal.Decimal
	for _, src := range []any{"3.140", []byte("3.140")} {
		if err := d.Scan(src); err != nil || d.String() != "3.140" {
			t.Errorf("Scan(%T) = %v, %v", src, d, err)
		}
	}
	if err := d.Scan(int64(7)); err != nil || d.String() != "7" {
		t.Errorf("Scan(int64) = %v, %v", d, err)
	}
	if d.Scan(1.5) == nil || d.Scan(nil) == nil {
		t.Error("a float or nil was scanned")
	}
}

func TestRoundingModeNames(t *testing.T) {
	t.Parallel()
	for m := decimal.Up; m <= decimal.HalfEven; m++ {
		back, err := decimal.ParseRoundingMode(m.String())
		if err != nil || back != m {
			t.Errorf("%v: %v, %v", m, back, err)
		}
	}
	if _, err := decimal.ParseRoundingMode("UNNECESSARY"); err == nil {
		t.Error("UNNECESSARY exists")
	}
}
