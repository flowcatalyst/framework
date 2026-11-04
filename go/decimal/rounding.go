package decimal

import (
	"fmt"
	"math/big"
)

// RoundingMode says how a value is rounded when digits are dropped. The seven modes have the meanings
// of Java's java.math.RoundingMode (the measure contract's section 1); there is no UNNECESSARY.
type RoundingMode int

// The rounding modes.
const (
	// Up rounds away from zero.
	Up RoundingMode = iota + 1
	// Down rounds toward zero (truncates).
	Down
	// Ceiling rounds toward positive infinity.
	Ceiling
	// Floor rounds toward negative infinity.
	Floor
	// HalfUp rounds to the nearest neighbour, and away from zero on a tie.
	HalfUp
	// HalfDown rounds to the nearest neighbour, and toward zero on a tie.
	HalfDown
	// HalfEven rounds to the nearest neighbour, and to the even neighbour on a tie.
	HalfEven
)

var modeNames = [...]string{Up: "UP", Down: "DOWN", Ceiling: "CEILING", Floor: "FLOOR", HalfUp: "HALF_UP", HalfDown: "HALF_DOWN", HalfEven: "HALF_EVEN"}

// String returns the mode's name as Java writes it: "HALF_EVEN".
func (m RoundingMode) String() string {
	if m < Up || m > HalfEven {
		return fmt.Sprintf("RoundingMode(%d)", int(m))
	}
	return modeNames[m]
}

// ParseRoundingMode returns the mode with a name ("HALF_UP").
func ParseRoundingMode(name string) (RoundingMode, error) {
	for m := Up; m <= HalfEven; m++ {
		if modeNames[m] == name {
			return m, nil
		}
	}
	return 0, fmt.Errorf("decimal: unknown rounding mode %q", name)
}

// valid reports whether m is one of the seven modes.
func (m RoundingMode) valid() bool { return m >= Up && m <= HalfEven }

// divRound returns num/den rounded to an integer with mode; den is not zero.
func divRound(num, den *big.Int, mode RoundingMode) *big.Int {
	q, r := new(big.Int).QuoRem(num, den, new(big.Int))
	if r.Sign() == 0 {
		return q
	}
	negative := num.Sign()*den.Sign() < 0
	var away bool
	switch mode {
	case Up:
		away = true
	case Down:
		away = false
	case Ceiling:
		away = !negative
	case Floor:
		away = negative
	case HalfUp, HalfDown, HalfEven:
		twice := new(big.Int).Abs(r)
		twice.Lsh(twice, 1)
		switch c := twice.Cmp(new(big.Int).Abs(den)); {
		case c > 0:
			away = true
		case c < 0:
			away = false
		case mode == HalfUp:
			away = true
		case mode == HalfDown:
			away = false
		default:
			away = q.Bit(0) == 1
		}
	}
	if !away {
		return q
	}
	if negative {
		return q.Sub(q, big.NewInt(1))
	}
	return q.Add(q, big.NewInt(1))
}
