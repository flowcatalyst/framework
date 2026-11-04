package units_test

import (
	"encoding/json/v2"
	"errors"
	"testing"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/money"
	"github.com/flowcatalyst/framework/go/units"
)

func code(err error) string {
	if de, ok := errors.AsType[*decimal.Error](err); ok {
		return de.Code()
	}
	return "NOT_A_VALIDATION_ERROR"
}

func d(s string) decimal.Decimal { return decimal.MustParse(s) }

func q(v string, u units.Unit) units.Quantity { return units.NewQuantity(d(v), u) }

func TestUnitsParseByCodeAndAlias(t *testing.T) {
	t.Parallel()
	for in, want := range map[string]units.Unit{
		"KGM": units.KGM, "kg": units.KGM, "KG": units.KGM, "m3": units.MTQ, "L": units.LTR, "degC": units.CEL, "PX": units.XPX,
		"short ton": units.STN, "US gal": units.GLL, "pallet": units.XPX,
	} {
		if got, err := units.Parse(in); err != nil || got != want {
			t.Errorf("Parse(%q) = %v, %v; want %v", in, got, err, want)
		}
	}
	for _, bad := range []string{"kgm", "furlong", "", "mtr"} {
		if _, err := units.Parse(bad); code(err) != "UNIT_UNKNOWN" {
			t.Errorf("Parse(%q): %v", bad, err)
		}
	}
	if units.KGM.Symbol() != "kg" || units.MTK.Symbol() != "m²" || !units.XPX.IsPackaging() || units.KGM.Dimension() != units.Mass {
		t.Error("symbol, dimension")
	}
}

func TestEveryDeclaredUnitIsInTheTable(t *testing.T) {
	t.Parallel()
	all := []units.Unit{
		units.MGM, units.GRM, units.KGM, units.TNE, units.LBR, units.ONZ, units.STN, units.LTN, units.MMT, units.CMT, units.MTR, units.KMT,
		units.INH, units.FOT, units.YRD, units.SMI, units.NMI, units.MTK, units.FTK, units.CMQ, units.MLT, units.LTR, units.MTQ, units.INQ,
		units.FTQ, units.GLL, units.GLI, units.C62, units.EA, units.H87, units.DZN, units.KEL, units.CEL, units.FAH, units.XPX, units.XCT,
		units.XCS, units.XBX, units.XPK, units.XBG, units.XCR, units.XDR,
	}
	if len(all) != 42 {
		t.Fatalf("%d units", len(all))
	}
	for _, u := range all {
		if got, err := units.Parse(u.Code()); err != nil || got != u || u.Symbol() == "" || u.Dimension() == 0 {
			t.Errorf("%v is not in the table: %v", u, err)
		}
	}
}

func TestAnApplicationRegistersItsPartnersCodes(t *testing.T) {
	t.Parallel()
	r, err := units.Builtin().With("KILO", units.KGM)
	if err != nil {
		t.Fatal(err)
	}
	if got, err := r.Parse("kilo"); err != nil || got != units.KGM {
		t.Errorf("kilo: %v, %v", got, err)
	}
	if _, err := units.Builtin().Parse("KILO"); code(err) != "UNIT_UNKNOWN" {
		t.Errorf("the built-in registry changed: %v", err)
	}
	if _, err := units.Builtin().With("kg", units.GRM); code(err) != "UNIT_UNKNOWN" {
		t.Errorf("a built-in alias was replaced: %v", err)
	}
}

func TestConversionIsExactAndRoundedOnce(t *testing.T) {
	t.Parallel()
	got, err := q("7831.2", units.KGM).To(units.TNE, 6, decimal.HalfEven)
	if err != nil || got.String() != "7.831200 TNE" {
		t.Errorf("to tonnes: %v, %v", got, err)
	}
	got, _ = q("7831.2", units.KGM).To(units.LBR, 4, decimal.HalfEven)
	if got.Value().String() != "17264.8407" {
		t.Errorf("to pounds: %v", got)
	}
	if got, _ := q("1", units.LBR).ToExact(units.KGM); got.Value().String() != "0.45359237" {
		t.Errorf("lb: %v", got)
	}
	if got, _ := q("1", units.INH).ToExact(units.MMT); got.Value().String() != "25.4" {
		t.Errorf("in: %v", got)
	}
	if _, err := q("1", units.KGM).ToExact(units.LBR); code(err) != "CONVERSION_INEXACT" {
		t.Errorf("kg to lb: %v", err)
	}
	if _, err := q("1", units.KGM).To(units.MTR, 2, decimal.Up); code(err) != "DIMENSION_MISMATCH" {
		t.Errorf("dimension: %v", err)
	}
	if _, err := q("1", units.XPX).To(units.XCT, 2, decimal.Up); code(err) != "NO_CONVERSION" {
		t.Errorf("packaging: %v", err)
	}
}

func TestTemperatureConvertsWithItsOffset(t *testing.T) {
	t.Parallel()
	if got, _ := q("100", units.CEL).To(units.FAH, 2, decimal.HalfEven); got.Value().String() != "212.00" {
		t.Errorf("100 C: %v", got)
	}
	if got, _ := q("-40", units.CEL).To(units.FAH, 2, decimal.HalfEven); got.Value().String() != "-40.00" {
		t.Errorf("-40 C: %v", got)
	}
	if got, _ := q("0", units.CEL).ToExact(units.KEL); got.Value().String() != "273.15" {
		t.Errorf("0 C: %v", got)
	}
	if _, err := q("1", units.CEL).Plus(q("1", units.CEL)); code(err) != "TEMPERATURE_NOT_ADDITIVE" {
		t.Errorf("plus: %v", err)
	}
	if _, err := q("1", units.CEL).Times(d("2")); code(err) != "TEMPERATURE_NOT_ADDITIVE" {
		t.Errorf("times: %v", err)
	}
}

func TestAdditionAndComparison(t *testing.T) {
	t.Parallel()
	if got, _ := q("1", units.KGM).Plus(q("500", units.GRM)); !got.Equal(q("1.5", units.KGM)) {
		t.Errorf("plus: %v", got)
	}
	if got, _ := q("2", units.TNE).Minus(q("500", units.KGM)); !got.Equal(q("1.5", units.TNE)) {
		t.Errorf("minus: %v", got)
	}
	if got, _ := q("1.5", units.KGM).Plus(q("1.50", units.KGM)); got.Value().String() != "3.00" {
		t.Errorf("same unit keeps the scale: %v", got)
	}
	if _, err := q("1", units.LBR).Plus(q("1", units.KGM)); code(err) != "CONVERSION_INEXACT" {
		t.Errorf("inexact: %v", err)
	}
	if c, err := q("1000", units.GRM).Cmp(q("1", units.KGM)); err != nil || c != 0 {
		t.Errorf("cmp: %v, %v", c, err)
	}
	if q("1000", units.GRM).Equal(q("1", units.KGM)) {
		t.Error("1000 GRM equals 1 KGM")
	}
	if c, _ := q("20", units.CEL).Cmp(q("68", units.FAH)); c != 0 {
		t.Errorf("20 C vs 68 F: %d", c)
	}
	if _, err := q("1", units.KGM).Cmp(q("1", units.MTR)); code(err) != "DIMENSION_MISMATCH" {
		t.Errorf("cmp dimension: %v", err)
	}
}

func TestPackagingConvertsAndBreaksDown(t *testing.T) {
	t.Parallel()
	p, err := units.NewPackaging(units.C62)
	if err == nil {
		p, err = p.Level(units.XCT, 24)
	}
	if err == nil {
		p, err = p.Level(units.XPX, 40)
	}
	if err != nil {
		t.Fatal(err)
	}
	if got, _ := p.ConvertExact(q("1", units.XPX), units.C62); !got.Equal(q("960", units.C62)) {
		t.Errorf("down: %v", got)
	}
	if got, _ := p.ConvertExact(q("48", units.XCT), units.XPX); got.String() != "1.2 XPX" {
		t.Errorf("up: %v", got)
	}
	if got, _ := p.Convert(q("1", units.C62), units.XPX, 4, decimal.HalfEven); got.String() != "0.0010 XPX" {
		t.Errorf("rounded: %v", got)
	}
	parts, err := p.Breakdown(q("1000", units.C62))
	if err != nil || len(parts) != 3 || parts[0].String() != "1 XPX" || parts[1].String() != "1 XCT" || parts[2].String() != "16 C62" {
		t.Errorf("breakdown: %v, %v", parts, err)
	}
	if _, err := p.Convert(q("1", units.XBX), units.C62, 2, decimal.Up); code(err) != "UNIT_NOT_IN_PACKAGING" {
		t.Errorf("not a level: %v", err)
	}
	if _, err := p.ConvertExact(q("1", units.C62), units.XPX); code(err) != "CONVERSION_INEXACT" {
		t.Errorf("inexact: %v", err)
	}
}

func TestPackagingRefusesBadLevels(t *testing.T) {
	t.Parallel()
	for _, u := range []units.Unit{units.MTR, units.XPX} {
		if _, err := units.NewPackaging(u); code(err) != "PACKAGING_INVALID" {
			t.Errorf("first unit %v: %v", u, err)
		}
	}
	p, _ := units.NewPackaging(units.C62)
	if _, err := p.Level(units.KGM, 2); code(err) != "PACKAGING_INVALID" {
		t.Errorf("a non-packaging level: %v", err)
	}
	if _, err := p.Level(units.XCT, 0); code(err) != "PACKAGING_INVALID" {
		t.Errorf("a zero count: %v", err)
	}
	two, _ := p.Level(units.XCT, 2)
	if _, err := two.Level(units.XCT, 2); code(err) != "PACKAGING_INVALID" {
		t.Errorf("a repeated unit: %v", err)
	}
}

func TestAPricePerUnitTimesAQuantity(t *testing.T) {
	t.Parallel()
	price, err := units.NewUnitPrice(money.MustParse("0.40", "ZAR"), units.KGM)
	if err != nil {
		t.Fatal(err)
	}
	tonnes := q("7.8312", units.TNE)
	if got, err := price.TimesExact(tonnes); err != nil || got.String() != "3132.480 ZAR" {
		t.Errorf("exact: %v, %v", got, err)
	}
	if got, err := price.Times(tonnes, 2, decimal.HalfEven); err != nil || got.String() != "3132.48 ZAR" {
		t.Errorf("rounded: %v, %v", got, err)
	}
	perPound, _ := units.NewUnitPrice(money.MustParse("12.50", "USD"), units.LBR)
	if got, err := perPound.Times(q("10", units.KGM), 2, decimal.HalfEven); err != nil || got.Amount().String() != "275.58" {
		t.Errorf("rounded once across an inexact conversion: %v, %v", got, err)
	}
	if _, err := perPound.TimesExact(q("10", units.KGM)); code(err) != "CONVERSION_INEXACT" {
		t.Errorf("exact: %v", err)
	}
	if _, err := units.NewUnitPrice(money.MustParse("1", "ZAR"), units.XPX); code(err) != "NO_CONVERSION" {
		t.Errorf("packaging: %v", err)
	}
	if _, err := units.NewUnitPrice(money.MustParse("1", "ZAR"), units.CEL); code(err) != "TEMPERATURE_NOT_ADDITIVE" {
		t.Errorf("temperature: %v", err)
	}
}

func TestChargeableWeightIsTheLargerOfActualAndVolume(t *testing.T) {
	t.Parallel()
	air, _ := units.KgPerCubicMetre(d("167"))
	six, _ := units.Divisor(d("6000"))
	cases := []struct {
		actual, volume units.Quantity
		f              units.DensityFactor
		scale          int
		want           string
	}{
		{q("100", units.KGM), q("1", units.MTQ), air, 0, "167 KGM"},
		{q("100", units.KGM), q("0.5", units.MTQ), air, 0, "100 KGM"},
		{q("100", units.KGM), q("1000000", units.CMQ), six, 3, "166.667 KGM"},
		{q("220.462", units.LBR), q("1", units.MTQ), air, 1, "167.0 KGM"},
	}
	for _, c := range cases {
		got, err := units.ChargeableWeight(c.actual, c.volume, c.f, c.scale, decimal.HalfEven)
		if err != nil || got.String() != c.want {
			t.Errorf("%v %v: %v, %v; want %s", c.actual, c.volume, got, err, c.want)
		}
	}
	if _, err := units.ChargeableWeight(q("1", units.MTR), q("1", units.MTQ), six, 0, decimal.Up); code(err) != "DIMENSION_MISMATCH" {
		t.Errorf("actual not a mass: %v", err)
	}
	if _, err := units.ChargeableWeight(q("1", units.KGM), q("1", units.MTK), six, 0, decimal.Up); code(err) != "DIMENSION_MISMATCH" {
		t.Errorf("volume not a volume: %v", err)
	}
	if _, err := units.Divisor(d("0")); code(err) != "RATE_INVALID" {
		t.Errorf("zero divisor: %v", err)
	}
}

func TestWireForms(t *testing.T) {
	t.Parallel()
	type shipment struct {
		Weight units.Quantity  `json:"weight"`
		Rate   units.UnitPrice `json:"rate"`
		Unit   units.Unit      `json:"unit"`
	}
	rate, _ := units.NewUnitPrice(money.MustParse("0.40", "ZAR"), units.KGM)
	in := shipment{Weight: q("7831.2", units.KGM), Rate: rate, Unit: units.MTQ}
	b, err := json.Marshal(in)
	want := `{"weight":{"value":"7831.2","unit":"KGM"},"rate":{"price":{"amount":"0.40","currency":"ZAR"},"per":"KGM"},"unit":"MTQ"}`
	if err != nil || string(b) != want {
		t.Fatalf("marshal %s, %v", b, err)
	}
	var back shipment
	if err := json.Unmarshal(b, &back); err != nil || !back.Weight.Equal(in.Weight) || back.Rate.Per() != units.KGM || back.Rate.Price().Amount().String() != "0.40" {
		t.Fatalf("unmarshal %v, %v", back, err)
	}
	for _, c := range []struct{ json, code string }{
		{`{"weight":{"value":7831.2,"unit":"KGM"}}`, ""},
		{`{"weight":{"value":"1","unit":"furlong"}}`, "UNIT_UNKNOWN"},
		{`{"weight":{"value":"x","unit":"KGM"}}`, "DECIMAL_INVALID"},
		{`{"rate":{"price":{"amount":"1","currency":"ZAR"},"per":"XPX"}}`, "NO_CONVERSION"},
		{`{"unit":"kgm"}`, "UNIT_UNKNOWN"},
	} {
		var s shipment
		err := json.Unmarshal([]byte(c.json), &s)
		if err == nil {
			t.Errorf("%s accepted", c.json)
		} else if c.code != "" && code(err) != c.code {
			t.Errorf("%s: %v, want %s", c.json, err, c.code)
		}
	}
}

func TestUnitInTheDatabase(t *testing.T) {
	t.Parallel()
	if v, err := units.KGM.Value(); err != nil || v != "KGM" {
		t.Fatalf("Value %v, %v", v, err)
	}
	var u units.Unit
	if err := u.Scan("m3"); err != nil || u != units.MTQ {
		t.Errorf("Scan %v, %v", u, err)
	}
	if err := u.Scan(42); err == nil {
		t.Error("an int was scanned")
	}
	if _, err := (units.Unit{}).Value(); err == nil {
		t.Error("the zero unit was stored")
	}
}
