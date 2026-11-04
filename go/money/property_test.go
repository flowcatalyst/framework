package money_test

import (
	"math/rand/v2"
	"testing"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/money"
)

// Property: whatever the amount, currency, ratios and mode, the parts of Allocate add up exactly to the
// amount rounded to the currency's minor units, every part is at the minor-unit scale, a part with a zero
// ratio is zero, and the parts share the amount's sign. A seeded loop, so a failure repeats.
func TestAllocatePartsAlwaysSumToTheRoundedWhole(t *testing.T) {
	t.Parallel()
	rng := rand.New(rand.NewPCG(20261103, 1))
	currencies := []string{"ZAR", "JPY", "BHD", "USD"}
	modes := []decimal.RoundingMode{decimal.Up, decimal.Down, decimal.Ceiling, decimal.Floor, decimal.HalfUp, decimal.HalfDown, decimal.HalfEven}
	for i := range 3000 {
		cur := money.MustCurrency(currencies[rng.IntN(len(currencies))])
		amount := decimal.New(rng.Int64N(2_000_000_000)-1_000_000_000, rng.IntN(6))
		n := 1 + rng.IntN(7)
		ratios := make([]decimal.Decimal, n)
		anyPositive := false
		for j := range ratios {
			ratios[j] = decimal.New(rng.Int64N(50), rng.IntN(3))
			if rng.IntN(4) == 0 {
				ratios[j] = decimal.New(0, 0)
			}
			anyPositive = anyPositive || ratios[j].Sign() > 0
		}
		if !anyPositive {
			ratios[0] = decimal.FromInt(1)
		}
		mode := modes[rng.IntN(len(modes))]
		m := money.New(amount, cur)
		parts, err := m.Allocate(ratios, mode)
		if err != nil {
			t.Fatalf("#%d %v %v: %v", i, m, ratios, err)
		}
		whole := m.Round(mode)
		sum, err := money.Sum(parts)
		if err != nil || !sum.Equal(whole) {
			t.Fatalf("#%d %v %v %v: parts %v sum to %v, want %v", i, m, ratios, mode, parts, sum, whole)
		}
		for j, p := range parts {
			if p.Amount().Scale() != cur.MinorUnits() {
				t.Fatalf("#%d part %d scale %d, want %d", i, j, p.Amount().Scale(), cur.MinorUnits())
			}
			if ratios[j].Sign() == 0 && !p.IsZero() {
				t.Fatalf("#%d part %d has a zero ratio but is %v", i, j, p)
			}
			if p.Signum()*whole.Signum() < 0 {
				t.Fatalf("#%d part %d %v has the wrong sign (whole %v)", i, j, p, whole)
			}
		}
	}
}
