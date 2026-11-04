// Package money is a currency and an amount in it (the measure contract's section 2): [Currency] is an
// ISO 4217 code with its minor units from the embedded table conformance/measure/iso4217.json (the
// table Java embeds too), [Money] an exact [decimal.Decimal] amount in one currency, and
// [ExchangeRate] a rate between two currencies.
//
// Precision is kept: no operation rounds unless asked ([Money.Round], [Money.RoundTo]). Mixing
// currencies is an error, never a conversion. Money marshals as {"amount": "3132.48", "currency":
// "ZAR"}: the amount is always a string, so no JSON number ever passes through a float.
//
//	rate := money.MustParse("0.40", "ZAR")
//	charge := rate.Times(decimal.MustParse("7831.2")).Round(decimal.HalfEven) // 3132.48 ZAR
//
// Failures are [decimal.Error] values with the contract's codes (CURRENCY_UNKNOWN,
// CURRENCY_MISMATCH, SUM_EMPTY, ALLOCATION_INVALID, RATE_INVALID, and decimal's).
package money

import (
	"database/sql/driver"
	_ "embed" // the currency table
	"encoding/json/v2"
	"fmt"
	"sync"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/httpfw/openapi"
)

// The error codes of this package (measure contract section 6).
const (
	// CodeCurrencyUnknown is the code of a code that is not in the ISO 4217 table.
	CodeCurrencyUnknown = "CURRENCY_UNKNOWN"
	// CodeCurrencyMismatch is the code of an operation on two currencies.
	CodeCurrencyMismatch = "CURRENCY_MISMATCH"
	// CodeSumEmpty is the code of the sum of an empty list.
	CodeSumEmpty = "SUM_EMPTY"
	// CodeAllocationInvalid is the code of allocation ratios that cannot be used.
	CodeAllocationInvalid = "ALLOCATION_INVALID"
	// CodeRateInvalid is the code of an exchange rate that is not positive.
	CodeRateInvalid = "RATE_INVALID"
)

var (
	codeUnknown  = CodeCurrencyUnknown
	codeMismatch = CodeCurrencyMismatch
	codeSum      = CodeSumEmpty
	codeAlloc    = CodeAllocationInvalid
	codeRate     = CodeRateInvalid
)

//go:embed iso4217.json
var currencyTable []byte

var table = sync.OnceValue(func() map[string]int {
	var doc struct {
		Currencies []struct {
			Code       string `json:"code"`
			MinorUnits int    `json:"minorUnits"`
		} `json:"currencies"`
	}
	if err := json.Unmarshal(currencyTable, &doc); err != nil {
		panic(fmt.Sprintf("money: iso4217.json: %v", err))
	}
	m := make(map[string]int, len(doc.Currencies))
	for _, c := range doc.Currencies {
		m[c.Code] = c.MinorUnits
	}
	return m
})

// Currency is an ISO 4217 alphabetic code in the embedded table. The zero Currency is invalid and
// is refused when written.
type Currency struct{ code string }

// CurrencyOf returns the currency with a code ("ZAR"): three upper-case letters in the table, else a
// CURRENCY_UNKNOWN validation error.
func CurrencyOf(code string) (Currency, error) {
	if _, ok := table()[code]; !ok {
		return Currency{}, decimal.NewError(codeUnknown, "unknown currency code")
	}
	return Currency{code: code}, nil
}

// MustCurrency is [CurrencyOf] for constants; it panics on an unknown code.
func MustCurrency(code string) Currency {
	c, err := CurrencyOf(code)
	if err != nil {
		panic(err)
	}
	return c
}

// Code returns the code, "ZAR".
func (c Currency) Code() string { return c.code }

// String returns the code.
func (c Currency) String() string { return c.code }

// IsZero reports whether c is the zero Currency.
func (c Currency) IsZero() bool { return c.code == "" }

// MinorUnits returns the digits of the smallest unit: ZAR 2, JPY 0, BHD 3.
func (c Currency) MinorUnits() int { return table()[c.code] }

// MarshalText returns the code; the zero Currency is an error.
func (c Currency) MarshalText() ([]byte, error) {
	if c.IsZero() {
		return nil, decimal.NewError(codeUnknown, "the zero currency")
	}
	return []byte(c.code), nil
}

// UnmarshalText parses a code.
func (c *Currency) UnmarshalText(b []byte) error {
	v, err := CurrencyOf(string(b))
	if err != nil {
		return err
	}
	*c = v
	return nil
}

// Value implements driver.Valuer: the code (a CHAR(3) column); the zero Currency is an error.
func (c Currency) Value() (driver.Value, error) {
	if c.IsZero() {
		return nil, decimal.NewError(codeUnknown, "the zero currency cannot be stored")
	}
	return c.code, nil
}

// Scan implements sql.Scanner for a text column.
func (c *Currency) Scan(src any) error {
	switch v := src.(type) {
	case string:
		return c.UnmarshalText([]byte(v))
	case []byte:
		return c.UnmarshalText(v)
	}
	return fmt.Errorf("money: cannot scan %T into a Currency", src)
}

// JSONSchema implements openapi.SchemaProvider: {"type": "string", "pattern": "^[A-Z]{3}$"}.
func (Currency) JSONSchema() openapi.Schema { return openapi.String().Pattern("^[A-Z]{3}$") }
