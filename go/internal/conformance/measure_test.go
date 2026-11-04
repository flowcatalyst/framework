package conformance_test

import (
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/money"
	"github.com/flowcatalyst/framework/go/units"
)

// The measure vectors (conformance/measure/*.json, written by Java's MeasureConformanceTest): every case
// is computed with the Go packages and compared as text, so a decimal, a rounding mode or a conversion
// that differs from Java's by one digit fails here with the file, the case and the field.

const measureDir = "measure/"

type moneyJSON struct {
	Amount   string `json:"amount"`
	Currency string `json:"currency"`
}

type quantityJSON struct {
	Value string `json:"value"`
	Unit  string `json:"unit"`
}

func (m moneyJSON) parse(t *testing.T) money.Money {
	t.Helper()
	v, err := money.Parse(m.Amount, m.Currency)
	if err != nil {
		t.Fatalf("vector money %v: %v", m, err)
	}
	return v
}

func (q quantityJSON) parse(t *testing.T) units.Quantity {
	t.Helper()
	v, err := units.ParseQuantity(q.Value, q.Unit)
	if err != nil {
		t.Fatalf("vector quantity %v: %v", q, err)
	}
	return v
}

func mustDec(t *testing.T, s string) decimal.Decimal {
	t.Helper()
	d, err := decimal.Parse(s)
	if err != nil {
		t.Fatalf("vector decimal %q: %v", s, err)
	}
	return d
}

func mustMode(t *testing.T, s string) decimal.RoundingMode {
	t.Helper()
	m, err := decimal.ParseRoundingMode(s)
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func codeOf(err error) string {
	if de, ok := errors.AsType[*decimal.Error](err); ok {
		return de.Code()
	}
	if err != nil {
		return "NOT_MEASURE_ERROR:" + err.Error()
	}
	return ""
}

// check compares a computed text with expect, or the error's code with wantErr.
func check(t *testing.T, where, want, wantErr string, got string, err error) {
	t.Helper()
	switch {
	case wantErr != "":
		if c := codeOf(err); c != wantErr {
			t.Errorf("%s: want error %s, got %q (result %q)", where, wantErr, c, got)
		}
	case err != nil:
		t.Errorf("%s: want %q, got error %v", where, want, err)
	case got != want:
		t.Errorf("%s: want %q, got %q", where, want, got)
	}
}

func num(t *testing.T, v jsontext.Value) int {
	t.Helper()
	var n int
	if len(v) > 0 {
		if err := json.Unmarshal(v, &n); err != nil {
			t.Fatalf("expect is not a number: %s", v)
		}
	}
	return n
}

func moneyText(m money.Money) string { return m.Amount().String() + " " + m.Currency().Code() }

func (m moneyJSON) text() string { return m.Amount + " " + m.Currency }

func moneyList(t *testing.T, v jsontext.Value) []string {
	t.Helper()
	var l []moneyJSON
	if len(v) > 0 {
		if err := json.Unmarshal(v, &l); err != nil {
			t.Fatalf("expect is not a list of money: %s", v)
		}
	}
	out := make([]string, len(l))
	for i, m := range l {
		out[i] = m.text()
	}
	return out
}

func quantityText(q units.Quantity) string { return q.Value().String() + " " + q.Unit().Code() }

func equalLists(a, b []string) bool {
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

func TestMeasureDecimalVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Parse []struct {
			Input  string `json:"input"`
			Expect string `json:"expect"`
			Scale  int    `json:"scale"`
			Error  string `json:"error"`
		} `json:"parse"`
		Arithmetic []struct {
			Op, A, B, Expect string
		} `json:"arithmetic"`
		Compare []struct {
			A, B   string
			Expect int
		} `json:"compare"`
	}
	readMeasure(t, measureDir+"decimal.json", &doc)
	if len(doc.Parse) == 0 || len(doc.Arithmetic) == 0 || len(doc.Compare) == 0 {
		t.Fatal("decimal.json: a section is empty")
	}
	for _, c := range doc.Parse {
		where := conformance.Where("decimal.json", fmt.Sprintf("parse %q", c.Input), "")
		d, err := decimal.Parse(c.Input)
		check(t, where, c.Expect, c.Error, d.String(), err)
		if c.Error == "" && err == nil && d.Scale() != c.Scale {
			t.Errorf("%s: scale want %d, got %d", where, c.Scale, d.Scale())
		}
	}
	for _, c := range doc.Arithmetic {
		a, b := mustDec(t, c.A), mustDec(t, c.B)
		var r decimal.Decimal
		switch c.Op {
		case "add":
			r = a.Add(b)
		case "subtract":
			r = a.Sub(b)
		case "multiply":
			r = a.Mul(b)
		default:
			t.Fatalf("decimal.json: op %q", c.Op)
		}
		check(t, conformance.Where("decimal.json", c.Op+" "+c.A+" "+c.B, ""), c.Expect, "", r.String(), nil)
	}
	for _, c := range doc.Compare {
		if got := mustDec(t, c.A).Cmp(mustDec(t, c.B)); got != c.Expect {
			t.Errorf("decimal.json compare %s %s: want %d, got %d", c.A, c.B, c.Expect, got)
		}
	}
}

func TestMeasureRoundingVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Round []struct {
			Value  string
			Scale  int
			Mode   string
			Expect string
		} `json:"round"`
		Divide []struct {
			A, B   string
			Scale  int
			Mode   string
			Expect string
			Error  string
		} `json:"divide"`
	}
	readMeasure(t, measureDir+"rounding.json", &doc)
	if len(doc.Round) == 0 || len(doc.Divide) == 0 {
		t.Fatal("rounding.json: a section is empty")
	}
	for _, c := range doc.Round {
		got := mustDec(t, c.Value).Round(c.Scale, mustMode(t, c.Mode))
		check(t, conformance.Where("rounding.json", fmt.Sprintf("round %s to %d %s", c.Value, c.Scale, c.Mode), ""), c.Expect, "", got.String(), nil)
	}
	for _, c := range doc.Divide {
		got, err := mustDec(t, c.A).Div(mustDec(t, c.B), c.Scale, mustMode(t, c.Mode))
		check(t, conformance.Where("rounding.json", fmt.Sprintf("divide %s / %s to %d %s", c.A, c.B, c.Scale, c.Mode), ""), c.Expect, c.Error, got.String(), err)
	}
}

func TestMeasureMoneyVector(t *testing.T) {
	t.Parallel()
	type pair struct {
		A, B   moneyJSON
		Expect jsontext.Value
		Error  string
	}
	var doc struct {
		Currency []struct {
			Code   string
			Expect jsontext.Value
			Error  string
		} `json:"currency"`
		Plus    []pair `json:"plus"`
		Minus   []pair `json:"minus"`
		Compare []pair `json:"compare"`
		Times   []struct {
			A      moneyJSON
			Factor string
			Expect jsontext.Value
			Error  string
		} `json:"times"`
		Round []struct {
			A      moneyJSON
			Mode   string
			Scale  *int
			Expect jsontext.Value
			Error  string
		} `json:"round"`
		Sum []struct {
			Values []moneyJSON
			Expect jsontext.Value
			Error  string
		} `json:"sum"`
		Allocate []struct {
			Amount moneyJSON
			Ratios []string
			Mode   string
			Expect jsontext.Value
			Error  string
		} `json:"allocate"`
		Exchange []struct {
			From, To, Rate string
			Amount         moneyJSON
			Mode           string
			Expect         jsontext.Value
			Error          string
		} `json:"exchange"`
	}
	readMeasure(t, measureDir+"money.json", &doc)
	if len(doc.Currency) == 0 || len(doc.Plus) == 0 || len(doc.Round) == 0 || len(doc.Allocate) == 0 || len(doc.Exchange) == 0 {
		t.Fatal("money.json: a section is empty")
	}
	for _, c := range doc.Currency {
		cur, err := money.CurrencyOf(c.Code)
		want := ""
		if c.Error == "" {
			want = fmt.Sprint(num(t, c.Expect))
		}
		check(t, conformance.Where("money.json", "currency "+c.Code, ""), want, c.Error, fmt.Sprint(cur.MinorUnits()), err)
	}
	for _, c := range doc.Plus {
		got, err := c.A.parse(t).Plus(c.B.parse(t))
		check(t, conformance.Where("money.json", "plus "+c.A.text()+" + "+c.B.text(), ""), moneyJSONText(t, c.Expect), c.Error, moneyText(got), err)
	}
	for _, c := range doc.Minus {
		got, err := c.A.parse(t).Minus(c.B.parse(t))
		check(t, conformance.Where("money.json", "minus "+c.A.text()+" - "+c.B.text(), ""), moneyJSONText(t, c.Expect), c.Error, moneyText(got), err)
	}
	for _, c := range doc.Compare {
		got, err := c.A.parse(t).Cmp(c.B.parse(t))
		check(t, conformance.Where("money.json", "compare "+c.A.text()+" "+c.B.text(), ""), fmt.Sprint(numOrZero(t, c.Expect, c.Error)), c.Error, fmt.Sprint(got), err)
	}
	for _, c := range doc.Times {
		got := c.A.parse(t).Times(mustDec(t, c.Factor))
		check(t, conformance.Where("money.json", "times "+c.A.text()+" x "+c.Factor, ""), moneyJSONText(t, c.Expect), c.Error, moneyText(got), nil)
	}
	for _, c := range doc.Round {
		m, mode := c.A.parse(t), mustMode(t, c.Mode)
		got := m.Round(mode)
		if c.Scale != nil {
			got = m.RoundTo(*c.Scale, mode)
		}
		check(t, conformance.Where("money.json", fmt.Sprintf("round %s %s scale %v", c.A.text(), c.Mode, c.Scale), ""), moneyJSONText(t, c.Expect), c.Error, moneyText(got), nil)
	}
	for i, c := range doc.Sum {
		var in []money.Money
		for _, v := range c.Values {
			in = append(in, v.parse(t))
		}
		got, err := money.Sum(in)
		where := conformance.Where("money.json", fmt.Sprintf("sum #%d", i), "")
		if c.Error != "" {
			check(t, where, "", c.Error, "", err)
			continue
		}
		if want := moneyList(t, c.Expect); err != nil || !equalLists(want, []string{moneyText(got)}) {
			t.Errorf("%s: want %v, got %v (%v)", where, want, moneyText(got), err)
		}
	}
	for _, c := range doc.Allocate {
		var ratios []decimal.Decimal
		for _, r := range c.Ratios {
			ratios = append(ratios, mustDec(t, r))
		}
		parts, err := c.Amount.parse(t).Allocate(ratios, mustMode(t, c.Mode))
		where := conformance.Where("money.json", fmt.Sprintf("allocate %s %v %s", c.Amount.text(), c.Ratios, c.Mode), "")
		if c.Error != "" {
			check(t, where, "", c.Error, "", err)
			continue
		}
		got := make([]string, len(parts))
		for i, p := range parts {
			got[i] = moneyText(p)
		}
		if want := moneyList(t, c.Expect); err != nil || !equalLists(want, got) {
			t.Errorf("%s: want %v, got %v (%v)", where, want, got, err)
		}
	}
	for _, c := range doc.Exchange {
		where := conformance.Where("money.json", fmt.Sprintf("exchange %s->%s @%s %s %s", c.From, c.To, c.Rate, c.Amount.text(), c.Mode), "")
		rate, err := money.NewExchangeRate(money.MustCurrency(c.From), money.MustCurrency(c.To), mustDec(t, c.Rate), time.Time{})
		var got money.Money
		if err == nil {
			got, err = rate.Convert(c.Amount.parse(t), mustMode(t, c.Mode))
		}
		check(t, where, moneyJSONText(t, c.Expect), c.Error, moneyText(got), err)
	}
}

func numOrZero(t *testing.T, v jsontext.Value, errCode string) int {
	t.Helper()
	if errCode != "" {
		return 0
	}
	return num(t, v)
}

func moneyJSONText(t *testing.T, v jsontext.Value) string {
	t.Helper()
	if len(v) == 0 {
		return ""
	}
	var m moneyJSON
	if err := json.Unmarshal(v, &m, json.MatchCaseInsensitiveNames(true)); err != nil {
		t.Fatalf("expect is not money: %s", v)
	}
	return m.text()
}

func quantityJSONText(t *testing.T, v jsontext.Value) string {
	t.Helper()
	if len(v) == 0 {
		return ""
	}
	var q quantityJSON
	if err := json.Unmarshal(v, &q); err != nil {
		t.Fatalf("expect is not a quantity: %s", v)
	}
	return q.Value + " " + q.Unit
}

func TestMeasureConversionsVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Parse []struct {
			Input  string
			Expect string
			Error  string
		} `json:"parse"`
		Convert []struct {
			Value, From, To, Round12, Exact, ExactError string
		} `json:"convert"`
		Arithmetic []struct {
			Op     string
			A, B   quantityJSON
			Expect jsontext.Value
			Error  string
		} `json:"arithmetic"`
		Times []struct {
			A      quantityJSON
			Factor string
			Expect jsontext.Value
			Error  string
		} `json:"times"`
		To []struct {
			A      quantityJSON
			To     string
			Expect jsontext.Value
			Error  string
		} `json:"to"`
	}
	readMeasure(t, measureDir+"conversions.json", &doc)
	if len(doc.Parse) == 0 || len(doc.Convert) < 500 || len(doc.Arithmetic) == 0 {
		t.Fatalf("conversions.json: a section is empty (%d convert cases)", len(doc.Convert))
	}
	for _, c := range doc.Parse {
		u, err := units.Parse(c.Input)
		check(t, conformance.Where("conversions.json", fmt.Sprintf("parse %q", c.Input), ""), c.Expect, c.Error, u.Code(), err)
	}
	for _, c := range doc.Convert {
		where := conformance.Where("conversions.json", fmt.Sprintf("%s %s -> %s", c.Value, c.From, c.To), "")
		q := units.NewQuantity(mustDec(t, c.Value), units.MustParse(c.From))
		to := units.MustParse(c.To)
		r, err := q.To(to, 12, decimal.HalfEven)
		check(t, where+" round12", c.Round12, "", r.Value().String(), err)
		e, err := q.ToExact(to)
		check(t, where+" exact", c.Exact, c.ExactError, e.Value().String(), err)
	}
	for _, c := range doc.Arithmetic {
		a, b := c.A.parse(t), c.B.parse(t)
		var got string
		var err error
		switch c.Op {
		case "plus":
			var r units.Quantity
			r, err = a.Plus(b)
			got = quantityText(r)
		case "minus":
			var r units.Quantity
			r, err = a.Minus(b)
			got = quantityText(r)
		case "compare":
			var r int
			r, err = a.Cmp(b)
			got = fmt.Sprint(r)
		default:
			t.Fatalf("conversions.json: op %q", c.Op)
		}
		var want string
		switch {
		case c.Error != "":
		case c.Op == "compare":
			want = fmt.Sprint(num(t, c.Expect))
		default:
			want = quantityJSONText(t, c.Expect)
		}
		check(t, conformance.Where("conversions.json", c.Op+" "+a.String()+" | "+b.String(), ""), want, c.Error, got, err)
	}
	for _, c := range doc.Times {
		got, err := c.A.parse(t).Times(mustDec(t, c.Factor))
		check(t, conformance.Where("conversions.json", "times "+c.A.Value+" "+c.A.Unit+" x "+c.Factor, ""), quantityJSONText(t, c.Expect), c.Error, quantityText(got), err)
	}
	for _, c := range doc.To {
		got, err := c.A.parse(t).To(units.MustParse(c.To), 6, decimal.HalfEven)
		check(t, conformance.Where("conversions.json", "to "+c.A.Value+" "+c.A.Unit+" -> "+c.To, ""), quantityJSONText(t, c.Expect), c.Error, quantityText(got), err)
	}
}

func buildPackaging(t *testing.T, levels, counts []string) (units.Packaging, error) {
	t.Helper()
	first, err := units.Parse(levels[0])
	if err != nil {
		return units.Packaging{}, err
	}
	p, err := units.NewPackaging(first)
	if err != nil {
		return units.Packaging{}, err
	}
	for i := 1; i < len(levels); i++ {
		var n int64
		if _, err := fmt.Sscan(counts[i], &n); err != nil {
			t.Fatalf("packaging count %q", counts[i])
		}
		u, err := units.Parse(levels[i])
		if err != nil {
			return units.Packaging{}, err
		}
		if p, err = p.Level(u, n); err != nil {
			return units.Packaging{}, err
		}
	}
	return p, nil
}

func TestMeasurePackagingVector(t *testing.T) {
	t.Parallel()
	type base struct {
		Levels []string
		Counts []string
	}
	var doc struct {
		New []struct {
			base
			Expect string
			Error  string
		} `json:"new"`
		Convert []struct {
			base
			Quantity   quantityJSON
			To         string
			Round6     string
			Exact      string
			ExactError string
			Error      string
		} `json:"convert"`
		Breakdown []struct {
			base
			Quantity quantityJSON
			Expect   jsontext.Value
			Error    string
		} `json:"breakdown"`
	}
	readMeasure(t, measureDir+"packaging.json", &doc)
	if len(doc.New) == 0 || len(doc.Convert) < 100 || len(doc.Breakdown) == 0 {
		t.Fatal("packaging.json: a section is empty")
	}
	for _, c := range doc.New {
		_, err := buildPackaging(t, c.Levels, c.Counts)
		got := "ok"
		if err != nil {
			got = ""
		}
		check(t, conformance.Where("packaging.json", fmt.Sprintf("new %v %v", c.Levels, c.Counts), ""), c.Expect, c.Error, got, err)
	}
	for _, c := range doc.Convert {
		where := conformance.Where("packaging.json", fmt.Sprintf("convert %v %s %s -> %s", c.Levels, c.Quantity.Value, c.Quantity.Unit, c.To), "")
		p, err := buildPackaging(t, c.Levels, c.Counts)
		if err != nil {
			t.Fatalf("%s: %v", where, err)
		}
		q, to := c.Quantity.parse(t), units.MustParse(c.To)
		if c.Error != "" {
			_, err := p.Convert(q, to, 6, decimal.HalfEven)
			check(t, where, "", c.Error, "", err)
			continue
		}
		r, err := p.Convert(q, to, 6, decimal.HalfEven)
		check(t, where+" round6", c.Round6, "", r.Value().String(), err)
		e, err := p.ConvertExact(q, to)
		check(t, where+" exact", c.Exact, c.ExactError, e.Value().String(), err)
	}
	for _, c := range doc.Breakdown {
		where := conformance.Where("packaging.json", fmt.Sprintf("breakdown %v %s %s", c.Levels, c.Quantity.Value, c.Quantity.Unit), "")
		p, err := buildPackaging(t, c.Levels, c.Counts)
		if err != nil {
			t.Fatalf("%s: %v", where, err)
		}
		parts, err := p.Breakdown(c.Quantity.parse(t))
		if c.Error != "" {
			check(t, where, "", c.Error, "", err)
			continue
		}
		var want []quantityJSON
		if err := json.Unmarshal(c.Expect, &want); err != nil {
			t.Fatalf("%s: expect: %v", where, err)
		}
		got := make([]string, len(parts))
		wantText := make([]string, len(want))
		for i, q := range parts {
			got[i] = quantityText(q)
		}
		for i, q := range want {
			wantText[i] = q.Value + " " + q.Unit
		}
		if err != nil || !equalLists(wantText, got) {
			t.Errorf("%s: want %v, got %v (%v)", where, wantText, got, err)
		}
	}
}

func TestMeasurePricingVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		UnitPrice []struct {
			Price      moneyJSON
			Per        string
			Quantity   quantityJSON
			Scale      int
			Mode       string
			Expect     jsontext.Value
			Error      string
			Exact      jsontext.Value
			ExactError string
		} `json:"unitPrice"`
		Chargeable []struct {
			Actual, Volume quantityJSON
			Factor         struct{ Kind, Value string }
			Scale          int
			Mode           string
			Expect         jsontext.Value
			Error          string
		} `json:"chargeable"`
	}
	readMeasure(t, measureDir+"pricing.json", &doc)
	if len(doc.UnitPrice) == 0 || len(doc.Chargeable) == 0 {
		t.Fatal("pricing.json: a section is empty")
	}
	for _, c := range doc.UnitPrice {
		where := conformance.Where("pricing.json", fmt.Sprintf("unit price %s per %s x %s %d %s", c.Price.text(), c.Per, c.Quantity.Value+c.Quantity.Unit, c.Scale, c.Mode), "")
		up, err := units.NewUnitPrice(c.Price.parse(t), units.MustParse(c.Per))
		if err != nil {
			check(t, where, "", c.Error, "", err)
			check(t, where+" exact", "", c.ExactError, "", err)
			continue
		}
		q := c.Quantity.parse(t)
		got, err := up.Times(q, c.Scale, mustMode(t, c.Mode))
		check(t, where, moneyJSONText(t, c.Expect), c.Error, moneyText(got), err)
		exact, err := up.TimesExact(q)
		check(t, where+" exact", moneyJSONText(t, c.Exact), c.ExactError, moneyText(exact), err)
	}
	for _, c := range doc.Chargeable {
		where := conformance.Where("pricing.json", fmt.Sprintf("chargeable %s %s %s %s %d %s", c.Actual.Value+c.Actual.Unit, c.Volume.Value+c.Volume.Unit, c.Factor.Kind, c.Factor.Value, c.Scale, c.Mode), "")
		var f units.DensityFactor
		var err error
		switch c.Factor.Kind {
		case "divisor":
			f, err = units.Divisor(mustDec(t, c.Factor.Value))
		case "kgPerCubicMetre":
			f, err = units.KgPerCubicMetre(mustDec(t, c.Factor.Value))
		default:
			t.Fatalf("%s: factor kind %q", where, c.Factor.Kind)
		}
		var got units.Quantity
		if err == nil {
			got, err = units.ChargeableWeight(c.Actual.parse(t), c.Volume.parse(t), f, c.Scale, mustMode(t, c.Mode))
		}
		check(t, where, quantityJSONText(t, c.Expect), c.Error, quantityText(got), err)
	}
}

func TestMeasureErrorsVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Cases []struct {
			Op       string
			Input    string
			A, B     jsontext.Value
			Code     string
			Amount   jsontext.Value
			Ratios   []string
			Values   []moneyJSON
			From, To string
			Rate     string
			Quantity quantityJSON
			Factor   string
			Levels   []string
			Counts   []string
			Price    moneyJSON
			Per      string
			Error    string
		} `json:"cases"`
	}
	readMeasure(t, measureDir+"errors.json", &doc)
	seen := map[string]bool{}
	for _, c := range doc.Cases {
		where := conformance.Where("errors.json", c.Op, c.Error)
		var err error
		switch c.Op {
		case "decimal.parse":
			_, err = decimal.Parse(c.Input)
		case "decimal.divide":
			var a, b string
			mustJSON(t, c.A, &a)
			mustJSON(t, c.B, &b)
			_, err = mustDec(t, a).Div(mustDec(t, b), 2, decimal.Up)
		case "currency.of":
			_, err = money.CurrencyOf(c.Code)
		case "money.plus":
			var a, b moneyJSON
			mustJSON(t, c.A, &a)
			mustJSON(t, c.B, &b)
			_, err = a.parse(t).Plus(b.parse(t))
		case "money.sum":
			var in []money.Money
			for _, v := range c.Values {
				in = append(in, v.parse(t))
			}
			_, err = money.Sum(in)
		case "money.allocate":
			var amount moneyJSON
			mustJSON(t, c.Amount, &amount)
			var ratios []decimal.Decimal
			for _, r := range c.Ratios {
				ratios = append(ratios, mustDec(t, r))
			}
			_, err = amount.parse(t).Allocate(ratios, decimal.HalfEven)
		case "exchange.new":
			_, err = money.NewExchangeRate(money.MustCurrency(c.From), money.MustCurrency(c.To), mustDec(t, c.Rate), time.Time{})
		case "unit.of":
			_, err = units.Parse(c.Input)
		case "quantity.to":
			_, err = c.Quantity.parse(t).To(units.MustParse(c.To), 2, decimal.Up)
		case "quantity.toExact":
			_, err = c.Quantity.parse(t).ToExact(units.MustParse(c.To))
		case "quantity.plus":
			var a, b quantityJSON
			mustJSON(t, c.A, &a)
			mustJSON(t, c.B, &b)
			_, err = a.parse(t).Plus(b.parse(t))
		case "quantity.times":
			_, err = c.Quantity.parse(t).Times(mustDec(t, c.Factor))
		case "packaging.new":
			_, err = buildPackaging(t, c.Levels, c.Counts)
		case "packaging.convert":
			var p units.Packaging
			if p, err = buildPackaging(t, c.Levels, c.Counts); err == nil {
				_, err = p.Convert(c.Quantity.parse(t), units.MustParse(c.To), 2, decimal.Up)
			}
		case "unitPrice.new":
			_, err = units.NewUnitPrice(c.Price.parse(t), units.MustParse(c.Per))
		default:
			t.Fatalf("errors.json: op %q", c.Op)
		}
		check(t, where, "", c.Error, "", err)
		seen[c.Error] = true
	}
	for _, code := range []string{
		"DECIMAL_INVALID", "DIVISION_BY_ZERO", "CURRENCY_UNKNOWN", "CURRENCY_MISMATCH", "SUM_EMPTY", "ALLOCATION_INVALID", "UNIT_UNKNOWN",
		"DIMENSION_MISMATCH", "NO_CONVERSION", "CONVERSION_INEXACT", "TEMPERATURE_NOT_ADDITIVE", "UNIT_NOT_IN_PACKAGING",
		"PACKAGING_INVALID", "RATE_INVALID",
	} {
		if !seen[code] {
			t.Errorf("errors.json has no case for %s", code)
		}
	}
}

// readMeasure decodes a measure vector; the Go structs carry no tags, so member names match ignoring case.
func readMeasure(t *testing.T, rel string, v any) {
	t.Helper()
	if err := json.Unmarshal(conformance.ReadFile(t, rel), v, json.MatchCaseInsensitiveNames(true)); err != nil {
		t.Fatalf("conformance: decode %s: %v", rel, err)
	}
}

func mustJSON(t *testing.T, v jsontext.Value, into any) {
	t.Helper()
	if err := json.Unmarshal(v, into); err != nil {
		t.Fatalf("vector member %s: %v", v, err)
	}
}
