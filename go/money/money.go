package money

import (
	"encoding/json/v2"
	"errors"
	"math/big"
	"time"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/httpfw/openapi"
)

// Money is an amount in a currency, immutable. Equality is numeric amount and currency ([Money.Equal]):
// 10.0 ZAR equals 10.00 ZAR; compare with Equal, not ==.
type Money struct {
	amount   decimal.Decimal
	currency Currency
}

// New returns the amount in the currency.
func New(amount decimal.Decimal, currency Currency) Money {
	return Money{amount: amount, currency: currency}
}

// Parse returns money from plain decimal text and a currency code.
func Parse(amount, currency string) (Money, error) {
	a, err := decimal.Parse(amount)
	if err != nil {
		return Money{}, err
	}
	c, err := CurrencyOf(currency)
	if err != nil {
		return Money{}, err
	}
	return New(a, c), nil
}

// MustParse is [Parse] for constants; it panics on bad input.
func MustParse(amount, currency string) Money {
	m, err := Parse(amount, currency)
	if err != nil {
		panic(err)
	}
	return m
}

// Zero returns zero in the currency, at the currency's minor-unit scale.
func Zero(c Currency) Money { return New(decimal.New(0, c.MinorUnits()), c) }

// Amount returns the amount.
func (m Money) Amount() decimal.Decimal { return m.amount }

// Currency returns the currency.
func (m Money) Currency() Currency { return m.currency }

// String returns "3132.48 ZAR".
func (m Money) String() string { return m.amount.String() + " " + m.currency.code }

// IsZero reports whether the amount is zero.
func (m Money) IsZero() bool { return m.amount.IsZero() }

// Signum returns the sign of the amount: -1, 0 or 1.
func (m Money) Signum() int { return m.amount.Sign() }

// Equal reports whether both the amount (numerically) and the currency are equal.
func (m Money) Equal(o Money) bool { return m.currency == o.currency && m.amount.Equal(o.amount) }

func (m Money) same(o Money) error {
	if m.currency != o.currency {
		return decimal.NewError(codeMismatch, "the currencies differ")
	}
	return nil
}

// Plus returns m + o at the larger scale; CURRENCY_MISMATCH when the currencies differ.
func (m Money) Plus(o Money) (Money, error) {
	if err := m.same(o); err != nil {
		return Money{}, err
	}
	return New(m.amount.Add(o.amount), m.currency), nil
}

// Minus returns m - o at the larger scale; CURRENCY_MISMATCH when the currencies differ.
func (m Money) Minus(o Money) (Money, error) {
	if err := m.same(o); err != nil {
		return Money{}, err
	}
	return New(m.amount.Sub(o.amount), m.currency), nil
}

// Negate returns -m.
func (m Money) Negate() Money { return New(m.amount.Neg(), m.currency) }

// Abs returns |m|.
func (m Money) Abs() Money { return New(m.amount.Abs(), m.currency) }

// Times returns m x f, unrounded; the scales add.
func (m Money) Times(f decimal.Decimal) Money { return New(m.amount.Mul(f), m.currency) }

// Cmp compares amounts of one currency: -1, 0 or 1; CURRENCY_MISMATCH when the currencies differ.
func (m Money) Cmp(o Money) (int, error) {
	if err := m.same(o); err != nil {
		return 0, err
	}
	return m.amount.Cmp(o.amount), nil
}

// Sum returns the sum of a non-empty list in one currency; an empty list is SUM_EMPTY and a second currency
// CURRENCY_MISMATCH.
func Sum(values []Money) (Money, error) {
	if len(values) == 0 {
		return Money{}, decimal.NewError(codeSum, "the sum of no amounts has no currency")
	}
	total := values[0]
	for _, v := range values[1:] {
		var err error
		if total, err = total.Plus(v); err != nil {
			return Money{}, err
		}
	}
	return total, nil
}

// Round returns m rounded to the currency's minor units with mode.
func (m Money) Round(mode decimal.RoundingMode) Money {
	return m.RoundTo(m.currency.MinorUnits(), mode)
}

// RoundTo returns m rounded to any scale with mode.
func (m Money) RoundTo(scale int, mode decimal.RoundingMode) Money {
	return New(m.amount.Round(scale, mode), m.currency)
}

// Allocate splits m into parts proportional to the ratios. The amount is first rounded to the
// currency's minor units with mode; each part is the floor of amount x ratio / total in minor units;
// the leftover minor units go one each to the parts with a non-zero ratio, in order. The parts add up
// exactly to the rounded amount. A negative amount is allocated as its absolute value and every part
// negated; a part with a zero ratio is always zero. No ratios, a negative ratio, or all ratios zero is
// ALLOCATION_INVALID.
func (m Money) Allocate(ratios []decimal.Decimal, mode decimal.RoundingMode) ([]Money, error) {
	if len(ratios) == 0 {
		return nil, decimal.NewError(codeAlloc, "no ratios")
	}
	var total decimal.Decimal
	for _, r := range ratios {
		if r.Sign() < 0 {
			return nil, decimal.NewError(codeAlloc, "a ratio is negative")
		}
		total = total.Add(r)
	}
	if total.IsZero() {
		return nil, decimal.NewError(codeAlloc, "every ratio is zero")
	}
	scale := m.currency.MinorUnits()
	rounded := m.amount.Round(scale, mode)
	negative := rounded.Sign() < 0
	units := decimal.NewBig(rounded.Abs().Unscaled(), 0) // whole minor units
	parts := make([]*big.Int, len(ratios))
	given := new(big.Int)
	for i, r := range ratios {
		p, err := units.Mul(r).Div(total, 0, decimal.Floor)
		if err != nil {
			return nil, err // total is not zero
		}
		parts[i] = p.Unscaled()
		given.Add(given, parts[i])
	}
	leftover := new(big.Int).Sub(units.Unscaled(), given)
	one := big.NewInt(1)
	for i := 0; i < len(parts) && leftover.Sign() > 0; i++ {
		if ratios[i].Sign() > 0 {
			parts[i].Add(parts[i], one)
			leftover.Sub(leftover, one)
		}
	}
	out := make([]Money, len(parts))
	for i, p := range parts {
		amount := decimal.NewBig(p, scale)
		if negative {
			amount = amount.Neg()
		}
		out[i] = New(amount, m.currency)
	}
	return out, nil
}

// ExchangeRate is a rate from one currency to another as of an instant. There is no rate provider:
// where rates come from is the application's, which stores the rate it used next to the converted
// amount.
type ExchangeRate struct {
	from, to Currency
	rate     decimal.Decimal
	asOf     time.Time
}

// NewExchangeRate returns a rate: units of to per one unit of from. A rate that is not greater than
// zero is RATE_INVALID.
func NewExchangeRate(from, to Currency, rate decimal.Decimal, asOf time.Time) (ExchangeRate, error) {
	if rate.Sign() <= 0 {
		return ExchangeRate{}, decimal.NewError(codeRate, "the exchange rate must be greater than zero")
	}
	return ExchangeRate{from: from, to: to, rate: rate, asOf: asOf}, nil
}

// From returns the currency converted from.
func (r ExchangeRate) From() Currency { return r.from }

// To returns the currency converted to.
func (r ExchangeRate) To() Currency { return r.to }

// Rate returns the rate.
func (r ExchangeRate) Rate() decimal.Decimal { return r.rate }

// AsOf returns when the rate was valid.
func (r ExchangeRate) AsOf() time.Time { return r.asOf }

// Convert returns amount x rate in the target currency, rounded to its minor units with mode. Money
// not in the rate's source currency is CURRENCY_MISMATCH.
func (r ExchangeRate) Convert(m Money, mode decimal.RoundingMode) (Money, error) {
	if m.currency != r.from {
		return Money{}, decimal.NewError(codeMismatch, "the amount is not in the rate's source currency")
	}
	return New(m.amount.Mul(r.rate), r.to).Round(mode), nil
}

// errMalformed is a body that is not the {amount, currency} object: a number for the amount, a missing
// or an unknown member. The HTTP layer reports it as wrong type or format at the property's path.
var errMalformed = errors.New("money: not an object of an amount and a currency")

// wire is Money's JSON form; the members are read as strings so a number or a null is never an amount.
type wire struct {
	Amount   string `json:"amount"`
	Currency string `json:"currency"`
}

// MarshalJSON writes {"amount": "3132.48", "currency": "ZAR"}; the zero Currency is an error.
func (m Money) MarshalJSON() ([]byte, error) {
	if m.currency.IsZero() {
		return nil, decimal.NewError(codeUnknown, "the zero currency")
	}
	return json.Marshal(wire{Amount: m.amount.String(), Currency: m.currency.code})
}

// UnmarshalJSON reads the object of [Money.MarshalJSON]: both members, no others, the amount a JSON
// string. Failures carry the contract's codes (DECIMAL_INVALID, CURRENCY_UNKNOWN).
func (m *Money) UnmarshalJSON(b []byte) error {
	var w wire
	if err := json.Unmarshal(b, &w, json.RejectUnknownMembers(true)); err != nil {
		return errMalformed // not the decoder's error: its path would extend the property's
	}
	v, err := Parse(w.Amount, w.Currency)
	if err != nil {
		return err
	}
	*m = v
	return nil
}

// JSONSchema implements openapi.SchemaProvider: the object {amount, currency}, both required.
func (Money) JSONSchema() openapi.Schema {
	return openapi.Object(
		openapi.Prop{Name: "amount", Schema: openapi.String().Format("decimal")},
		openapi.Prop{Name: "currency", Schema: openapi.String().Pattern("^[A-Z]{3}$")},
	)
}
