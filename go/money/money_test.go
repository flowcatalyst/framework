package money_test

import (
	"encoding/json/v2"
	"errors"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/money"
)

func code(err error) string {
	if de, ok := errors.AsType[*decimal.Error](err); ok {
		return de.Code()
	}
	return "NOT_A_VALIDATION_ERROR"
}

func d(s string) decimal.Decimal { return decimal.MustParse(s) }

func TestCurrencyKnowsItsMinorUnits(t *testing.T) {
	t.Parallel()
	for c, want := range map[string]int{"ZAR": 2, "JPY": 0, "BHD": 3, "USD": 2} {
		if got := money.MustCurrency(c).MinorUnits(); got != want {
			t.Errorf("%s: %d, want %d", c, got, want)
		}
	}
	for _, bad := range []string{"ZZZ", "zar", "", "ZA", "XAU"} {
		if _, err := money.CurrencyOf(bad); code(err) != "CURRENCY_UNKNOWN" {
			t.Errorf("CurrencyOf(%q): %v", bad, err)
		}
	}
}

func TestPrecisionIsKeptUntilRounded(t *testing.T) {
	t.Parallel()
	total := money.MustParse("0.40", "ZAR").Times(d("7831.2"))
	if total.Amount().String() != "3132.480" {
		t.Errorf("times: %v", total)
	}
	if got := total.Round(decimal.HalfEven); got.Amount().String() != "3132.48" {
		t.Errorf("round: %v", got)
	}
	if got := money.MustParse("2.5", "JPY").Round(decimal.HalfEven); got.Amount().String() != "2" {
		t.Errorf("JPY: %v", got)
	}
	if got := total.RoundTo(1, decimal.Up); got.Amount().String() != "3132.5" {
		t.Errorf("RoundTo: %v", got)
	}
}

func TestArithmeticIsWithinOneCurrency(t *testing.T) {
	t.Parallel()
	a := money.MustParse("10.00", "ZAR")
	got, err := a.Plus(money.MustParse("2.555", "ZAR"))
	if err != nil || got.Amount().String() != "12.555" {
		t.Errorf("plus: %v, %v", got, err)
	}
	if _, err := a.Plus(money.MustParse("1", "USD")); code(err) != "CURRENCY_MISMATCH" {
		t.Errorf("mismatch: %v", err)
	}
	if _, err := a.Cmp(money.MustParse("1", "USD")); code(err) != "CURRENCY_MISMATCH" {
		t.Errorf("cmp mismatch: %v", err)
	}
	sum, err := money.Sum([]money.Money{a, a, a})
	if err != nil || sum.Amount().String() != "30.00" {
		t.Errorf("sum: %v, %v", sum, err)
	}
	if _, err := money.Sum(nil); code(err) != "SUM_EMPTY" {
		t.Errorf("empty sum: %v", err)
	}
	if !money.MustParse("10.0", "ZAR").Equal(money.MustParse("10.00", "ZAR")) || money.MustParse("10", "ZAR").Equal(money.MustParse("10", "USD")) {
		t.Error("equality is numeric amount and currency")
	}
	if !money.Zero(money.MustCurrency("ZAR")).IsZero() || a.Negate().Signum() != -1 || a.Negate().Abs().Amount().String() != "10.00" {
		t.Error("zero, negate, abs")
	}
}

func parts(t *testing.T, amount, currency string, ratios []string, mode decimal.RoundingMode) []string {
	t.Helper()
	var rs []decimal.Decimal
	for _, r := range ratios {
		rs = append(rs, d(r))
	}
	out, err := money.MustParse(amount, currency).Allocate(rs, mode)
	if err != nil {
		t.Fatalf("allocate %s %v: %v", amount, ratios, err)
	}
	var texts []string
	for _, p := range out {
		texts = append(texts, p.Amount().String())
	}
	return texts
}

func equal(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestAllocationAddsUpExactly(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		amount, currency string
		ratios           []string
		want             []string
	}{
		{"100.00", "ZAR", []string{"1", "1", "1"}, []string{"33.34", "33.33", "33.33"}},
		{"-100.00", "ZAR", []string{"1", "1", "1"}, []string{"-33.34", "-33.33", "-33.33"}},
		{"0.05", "ZAR", []string{"0", "1", "1"}, []string{"0.00", "0.03", "0.02"}},
		{"100", "JPY", []string{"1", "2"}, []string{"34", "66"}},
		{"0.005", "USD", []string{"1", "1"}, []string{"0.01", "0.00"}},
		{"10", "ZAR", []string{"1"}, []string{"10.00"}},
		{"0", "ZAR", []string{"1", "1"}, []string{"0.00", "0.00"}},
	} {
		if got := parts(t, c.amount, c.currency, c.ratios, decimal.HalfUp); !equal(got, c.want) {
			t.Errorf("allocate %s %v = %v, want %v", c.amount, c.ratios, got, c.want)
		}
	}
	for _, ratios := range [][]decimal.Decimal{nil, {d("0"), d("0")}, {d("1"), d("-1")}} {
		if _, err := money.MustParse("1", "ZAR").Allocate(ratios, decimal.Up); code(err) != "ALLOCATION_INVALID" {
			t.Errorf("ratios %v: %v", ratios, err)
		}
	}
}

func TestExchangeRateConvertsToTheTargetCurrency(t *testing.T) {
	t.Parallel()
	usd, zar := money.MustCurrency("USD"), money.MustCurrency("ZAR")
	rate, err := money.NewExchangeRate(usd, zar, d("18.3456"), time.Date(2026, 11, 3, 0, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatal(err)
	}
	got, err := rate.Convert(money.MustParse("100.00", "USD"), decimal.HalfEven)
	if err != nil || got.String() != "1834.56 ZAR" {
		t.Errorf("convert: %v, %v", got, err)
	}
	if _, err := rate.Convert(money.MustParse("1", "ZAR"), decimal.Up); code(err) != "CURRENCY_MISMATCH" {
		t.Errorf("wrong source: %v", err)
	}
	if _, err := money.NewExchangeRate(usd, zar, d("0"), time.Time{}); code(err) != "RATE_INVALID" {
		t.Errorf("zero rate: %v", err)
	}
}

func TestMoneyJSON(t *testing.T) {
	t.Parallel()
	type body struct {
		Charge money.Money    `json:"charge"`
		In     money.Currency `json:"in"`
	}
	in := body{Charge: money.MustParse("3132.480", "ZAR"), In: money.MustCurrency("USD")}
	b, err := json.Marshal(in)
	if err != nil || string(b) != `{"charge":{"amount":"3132.480","currency":"ZAR"},"in":"USD"}` {
		t.Fatalf("marshal %s, %v", b, err)
	}
	var back body
	if err := json.Unmarshal(b, &back); err != nil || !back.Charge.Equal(in.Charge) || back.Charge.Amount().Scale() != 3 {
		t.Fatalf("unmarshal %v, %v", back, err)
	}
	for _, c := range []struct{ json, code string }{
		{`{"amount":3132.48,"currency":"ZAR"}`, ""},
		{`{"amount":"1"}`, ""},
		{`{"amount":"1","currency":"ZAR","x":1}`, ""},
		{`{"amount":null,"currency":"ZAR"}`, "DECIMAL_INVALID"},
		{`{"amount":"1e3","currency":"ZAR"}`, "DECIMAL_INVALID"},
		{`{"amount":"1","currency":"ZZZ"}`, "CURRENCY_UNKNOWN"},
		{`null`, "DECIMAL_INVALID"},
	} {
		var m money.Money
		err := json.Unmarshal([]byte(c.json), &m)
		if err == nil {
			t.Errorf("%s accepted as %v", c.json, m)
			continue
		}
		if c.code != "" && code(err) != c.code {
			t.Errorf("%s: %v, want %s", c.json, err, c.code)
		}
	}
}

func TestMoneyAndCurrencyInTheDatabase(t *testing.T) {
	t.Parallel()
	v, err := money.MustCurrency("ZAR").Value()
	if err != nil || v != "ZAR" {
		t.Fatalf("Value %v, %v", v, err)
	}
	var c money.Currency
	if err := c.Scan("USD"); err != nil || c.Code() != "USD" {
		t.Errorf("Scan %v, %v", c, err)
	}
	if err := c.Scan("QQQ"); code(err) != "CURRENCY_UNKNOWN" {
		t.Errorf("Scan unknown: %v", err)
	}
	if _, err := (money.Currency{}).Value(); err == nil {
		t.Error("the zero currency was stored")
	}
}
