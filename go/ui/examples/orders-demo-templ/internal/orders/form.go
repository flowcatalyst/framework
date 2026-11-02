package orders

import (
	"regexp"
	"slices"
	"strings"
	"unicode/utf8"
)

// NewOrderForm is the create form's state: what the user typed, the problems found and whether to
// show it open.
type NewOrderForm struct {
	Customer  string
	Amount    string // in currency units
	Status    string
	Errors    map[string]string // field name to message
	FormError string            // a problem that belongs to no field
	Open      bool              // whether the dialog renders open (a failed submit)
}

// EmptyForm is an empty, closed form.
func EmptyForm() NewOrderForm { return NewOrderForm{Status: "PENDING"} }

// Error is the message of a field ("" when it has none).
func (f NewOrderForm) Error(field string) string { return f.Errors[field] }

// Validate checks submitted values (the first value of each field): the form is closed and valid,
// or open with its errors.
func Validate(values map[string]string) NewOrderForm {
	customer := strings.TrimSpace(values["customer"])
	amount := strings.TrimSpace(values["amount"])
	status, ok := values["status"]
	if !ok {
		status = "PENDING"
	}
	errs := map[string]string{}
	if n := utf8.RuneCountInString(customer); n < 2 {
		errs["customer"] = "Enter the customer's name (at least 2 characters)."
	} else if n > 80 {
		errs["customer"] = "The name is too long (80 characters at most)."
	}
	if Cents(amount) <= 0 {
		errs["amount"] = "Enter an amount greater than zero, for example 49.90."
	}
	if !slices.Contains(Statuses, status) || status == "CANCELLED" {
		errs["status"] = "Choose a status."
	}
	return NewOrderForm{Customer: customer, Amount: amount, Status: status, Errors: errs, Open: len(errs) > 0}
}

var amountPattern = regexp.MustCompile(`^(\d{1,9})(?:\.(\d{1,2}))?$`)

// Cents is the amount in cents: 0 when the text is not a positive amount with at most two decimals.
func Cents(amount string) int64 {
	m := amountPattern.FindStringSubmatch(amount)
	if m == nil {
		return 0
	}
	var whole int64
	for _, c := range m[1] {
		whole = whole*10 + int64(c-'0')
	}
	frac := (m[2] + "00")[:2]
	return whole*100 + int64(frac[0]-'0')*10 + int64(frac[1]-'0')
}
