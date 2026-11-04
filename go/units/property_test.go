package units_test

import (
	"math/rand/v2"
	"testing"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/units"
)

// Property: converting to another unit of the dimension and back, each at scale 12 with HALF_EVEN, lands
// within a few units in the twelfth decimal of the start (the error of the first rounding, multiplied by
// the factor between the units, plus the second rounding). Temperature is affine and left out. A seeded
// loop over every ordered pair of the table's units.
func TestConversionThereAndBackIsWithinTheRoundingError(t *testing.T) {
	t.Parallel()
	rng := rand.New(rand.NewPCG(20261103, 2))
	byDim := map[units.Dimension][]units.Unit{}
	for _, c := range []string{
		"MGM", "GRM", "KGM", "TNE", "LBR", "ONZ", "STN", "LTN", "MMT", "CMT", "MTR", "KMT", "INH", "FOT", "YRD", "SMI", "NMI", "MTK", "FTK",
		"CMQ", "MLT", "LTR", "MTQ", "INQ", "FTQ", "GLL", "GLI", "C62", "EA", "H87", "DZN",
	} {
		u := units.MustParse(c)
		byDim[u.Dimension()] = append(byDim[u.Dimension()], u)
	}
	ulp := decimal.New(1, 12)
	checked := 0
	for _, us := range byDim {
		for _, from := range us {
			for _, to := range us {
				// how many of from one of to is worth, rounded up: the first rounding's error grows by it
				ratio, err := units.NewQuantity(decimal.FromInt(1), to).To(from, 0, decimal.Up)
				if err != nil {
					t.Fatal(err)
				}
				tolerance := ratio.Value().Add(decimal.FromInt(2)).Mul(ulp)
				for range 40 {
					v := decimal.New(rng.Int64N(2_000_000_000)-1_000_000_000, rng.IntN(5))
					start := units.NewQuantity(v, from)
					there, err := start.To(to, 12, decimal.HalfEven)
					if err != nil {
						t.Fatal(err)
					}
					back, err := there.To(from, 12, decimal.HalfEven)
					if err != nil {
						t.Fatal(err)
					}
					if diff := back.Value().Sub(v).Abs(); diff.Cmp(tolerance) > 0 {
						t.Fatalf("%v -> %v -> %v: off by %v, tolerance %v", start, to, back, diff, tolerance)
					}
					checked++
				}
			}
		}
	}
	if checked < 5000 {
		t.Fatalf("only %d conversions checked", checked)
	}
}
