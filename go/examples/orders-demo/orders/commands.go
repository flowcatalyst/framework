package orders

import (
	"github.com/flowcatalyst/framework/go/examples/orders-demo/domain"
	"github.com/flowcatalyst/framework/go/usecase"
)

// PlaceOrder places an order for a customer. A command is parsed at the edge, so one that exists
// is valid: its fields are value types.
type PlaceOrder struct {
	Customer domain.CustomerName
}

// AuditData records the customer in the audit row.
func (c PlaceOrder) AuditData() []usecase.AuditField {
	return []usecase.AuditField{usecase.Field("customer", c.Customer.String())}
}

// AddLine adds a line to an open order.
type AddLine struct {
	Order     domain.OrderID
	SKU       domain.SKU
	Quantity  domain.Quantity
	UnitPrice domain.Cents
}

// AuditData records the order, SKU and quantity.
func (c AddLine) AuditData() []usecase.AuditField {
	return []usecase.AuditField{
		usecase.Field("orderId", c.Order.String()), usecase.Field("sku", c.SKU.String()), usecase.Field("quantity", c.Quantity.Int()),
	}
}

// CancelOrder cancels an open order.
type CancelOrder struct {
	Order  domain.OrderID
	Reason domain.CancelReason
}

// AuditData records the order and the reason.
func (c CancelOrder) AuditData() []usecase.AuditField {
	return []usecase.AuditField{usecase.Field("orderId", c.Order.String()), usecase.Field("reason", c.Reason.String())}
}

// ParseAddLine builds an AddLine from raw input, collecting every field's problem: the edge's
// parse (an HTTP handler in G3).
func ParseAddLine(order domain.OrderID, sku string, quantity, unitPriceCents int64) (AddLine, error) {
	s, errSKU := domain.ParseSKU(sku)
	q, errQ := domain.NewQuantity(quantity)
	p, errP := domain.NewCents(unitPriceCents)
	for _, err := range []error{errSKU, errQ, errP} {
		if err != nil {
			return AddLine{}, err
		}
	}
	return AddLine{Order: order, SKU: s, Quantity: q, UnitPrice: p}, nil
}
