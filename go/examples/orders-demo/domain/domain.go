// Package domain is the orders example's domain: typed ids, value types that are valid when they
// exist, and the order aggregate as one type per state. It knows nothing of storage; the client
// and version of a stored order live in usecase.Loaded.
package domain

import (
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/flowcatalyst/framework/go/id"
	"github.com/flowcatalyst/framework/go/usecase"
)

// OrderKind gives order ids their prefix: id.ID[OrderKind] is not id.ID[LineKind].
type OrderKind struct{}

// LineKind gives line ids their prefix.
type LineKind struct{}

// Prefix returns "ord".
func (OrderKind) Prefix() string { return "ord" }

// Prefix returns "lin".
func (LineKind) Prefix() string { return "lin" }

// OrderID is an order's id, ord_ and a TSID. sqlc.yaml names this alias as the type of the
// orders.id and order_lines.order_id columns.
type OrderID = id.ID[OrderKind]

// LineID is a line's id, lin_ and a TSID.
type LineID = id.ID[LineKind]

// The validation codes of the value types.
var (
	CustomerRequired = usecase.MustErrorCode("CUSTOMER_REQUIRED")
	CustomerTooLong  = usecase.MustErrorCode("CUSTOMER_TOO_LONG")
	SKURequired      = usecase.MustErrorCode("SKU_REQUIRED")
	SKUTooLong       = usecase.MustErrorCode("SKU_TOO_LONG")
	QuantityInvalid  = usecase.MustErrorCode("QUANTITY_INVALID")
	PriceInvalid     = usecase.MustErrorCode("PRICE_INVALID")
	ReasonRequired   = usecase.MustErrorCode("REASON_REQUIRED")
)

// CustomerName is trimmed, not blank and at most 100 characters.
type CustomerName struct{ v string }

// ParseCustomerName checks a customer name.
func ParseCustomerName(raw string) (CustomerName, error) {
	s := strings.TrimSpace(raw)
	switch {
	case s == "":
		return CustomerName{}, usecase.Validation(CustomerRequired, "A customer is required.")
	case utf8.RuneCountInString(s) > 100:
		return CustomerName{}, usecase.Validation(CustomerTooLong, "At most 100 characters.")
	}
	return CustomerName{v: s}, nil
}

// String returns the name.
func (c CustomerName) String() string { return c.v }

// SKU is a stock-keeping unit: not blank, at most 20 characters.
type SKU struct{ v string }

// ParseSKU checks a SKU.
func ParseSKU(raw string) (SKU, error) {
	switch {
	case strings.TrimSpace(raw) == "":
		return SKU{}, usecase.Validation(SKURequired, "A sku is required.")
	case utf8.RuneCountInString(raw) > 20:
		return SKU{}, usecase.Validation(SKUTooLong, "At most 20 characters.")
	}
	return SKU{v: raw}, nil
}

// String returns the SKU.
func (s SKU) String() string { return s.v }

// Quantity is at least 1.
type Quantity struct{ v int32 }

// NewQuantity checks a quantity.
func NewQuantity(n int64) (Quantity, error) {
	if n < 1 || n > 1<<31-1 {
		return Quantity{}, usecase.Validation(QuantityInvalid, "The quantity must be positive.")
	}
	return Quantity{v: int32(n)}, nil
}

// Int returns the quantity.
func (q Quantity) Int() int32 { return q.v }

// Cents is a price in cents, not negative.
type Cents struct{ v int64 }

// NewCents checks a price.
func NewCents(n int64) (Cents, error) {
	if n < 0 {
		return Cents{}, usecase.Validation(PriceInvalid, "The price must not be negative.")
	}
	return Cents{v: n}, nil
}

// Int returns the cents.
func (c Cents) Int() int64 { return c.v }

// CancelReason is not blank.
type CancelReason struct{ v string }

// ParseCancelReason checks a reason.
func ParseCancelReason(raw string) (CancelReason, error) {
	if strings.TrimSpace(raw) == "" {
		return CancelReason{}, usecase.Validation(ReasonRequired, "A reason is required.")
	}
	return CancelReason{v: raw}, nil
}

// String returns the reason.
func (r CancelReason) String() string { return r.v }

// OrderLine is one line of an order.
type OrderLine struct {
	id        LineID
	sku       SKU
	quantity  Quantity
	unitPrice Cents
}

// NewOrderLine returns a line.
func NewOrderLine(lineID LineID, sku SKU, q Quantity, price Cents) OrderLine {
	return OrderLine{id: lineID, sku: sku, quantity: q, unitPrice: price}
}

// ID returns the line's id.
func (l OrderLine) ID() LineID { return l.id }

// SKU returns the SKU.
func (l OrderLine) SKU() SKU { return l.sku }

// Quantity returns the quantity.
func (l OrderLine) Quantity() Quantity { return l.quantity }

// UnitPrice returns the price of one unit.
func (l OrderLine) UnitPrice() Cents { return l.unitPrice }

// Order is the aggregate: an [OpenOrder] or a [CancelledOrder]. A switch over it must name both
// (gochecksumtype), so a new state is handled everywhere.
//
//sumtype:decl
type Order interface {
	ID() OrderID
	Customer() CustomerName
	Lines() []OrderLine
	AuditEntity() usecase.Entity
	isOrder()
}

// OpenOrder accepts lines.
type OpenOrder struct {
	id       OrderID
	customer CustomerName
	lines    []OrderLine
}

// CancelledOrder accepts nothing.
type CancelledOrder struct {
	id       OrderID
	customer CustomerName
	lines    []OrderLine
}

// PlaceOrder returns a new open order.
func PlaceOrder(orderID OrderID, customer CustomerName) OpenOrder {
	return OpenOrder{id: orderID, customer: customer}
}

// Restore rebuilds an order from storage (for the repository): status OPEN or CANCELLED.
func Restore(orderID OrderID, customer CustomerName, status string, lines []OrderLine) (Order, bool) {
	switch status {
	case StatusOpen:
		return OpenOrder{id: orderID, customer: customer, lines: lines}, true
	case StatusCancelled:
		return CancelledOrder{id: orderID, customer: customer, lines: lines}, true
	}
	return nil, false
}

// The stored statuses (the Java demo's OrderStatus names).
const (
	StatusOpen      = "OPEN"
	StatusCancelled = "CANCELLED"
)

// StatusOf returns the stored status of an order.
func StatusOf(o Order) string {
	switch o.(type) {
	case OpenOrder:
		return StatusOpen
	case CancelledOrder:
		return StatusCancelled
	}
	panic("unreachable: gochecksumtype keeps the switch complete")
}

// WithLine returns the order with one more line; only an open order has it.
func (o OpenOrder) WithLine(l OrderLine) OpenOrder {
	o.lines = append(slices.Clip(o.lines), l)
	return o
}

// Cancel returns the cancelled order.
func (o OpenOrder) Cancel() CancelledOrder {
	return CancelledOrder(o)
}

// ID returns the order's id.
func (o OpenOrder) ID() OrderID { return o.id }

// Customer returns the customer.
func (o OpenOrder) Customer() CustomerName { return o.customer }

// Lines returns a copy of the lines.
func (o OpenOrder) Lines() []OrderLine { return slices.Clone(o.lines) }

// AuditEntity names the order for the audit row, as the Java demo's Order record does.
func (o OpenOrder) AuditEntity() usecase.Entity {
	return usecase.Entity{Type: "Order", ID: o.id.String()}
}

func (OpenOrder) isOrder() {}

// ID returns the order's id.
func (o CancelledOrder) ID() OrderID { return o.id }

// Customer returns the customer.
func (o CancelledOrder) Customer() CustomerName { return o.customer }

// Lines returns a copy of the lines.
func (o CancelledOrder) Lines() []OrderLine { return slices.Clone(o.lines) }

// AuditEntity names the order for the audit row.
func (o CancelledOrder) AuditEntity() usecase.Entity {
	return usecase.Entity{Type: "Order", ID: o.id.String()}
}

func (CancelledOrder) isOrder() {}
