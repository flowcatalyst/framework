package orders

import (
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/domain"
)

// OrderPlaced is the result of place-order. Its JSON keys are the Java record's components, so
// the outbox row's data is the same.
type OrderPlaced struct {
	Meta     event.Metadata `json:"-"`
	OrderID  domain.OrderID `json:"orderId"`
	Customer string         `json:"customer"`
}

// Metadata returns the event's metadata.
func (e OrderPlaced) Metadata() event.Metadata { return e.Meta }

// OrderLineAdded is the result of add-line.
type OrderLineAdded struct {
	Meta           event.Metadata `json:"-"`
	OrderID        domain.OrderID `json:"orderId"`
	LineID         domain.LineID  `json:"lineId"`
	SKU            string         `json:"sku"`
	Quantity       int32          `json:"quantity"`
	UnitPriceCents int64          `json:"unitPriceCents"`
	LineCount      int            `json:"lineCount"`
}

// Metadata returns the event's metadata.
func (e OrderLineAdded) Metadata() event.Metadata { return e.Meta }

// OrderCancelled is the result of cancel-order.
type OrderCancelled struct {
	Meta      event.Metadata `json:"-"`
	OrderID   domain.OrderID `json:"orderId"`
	Reason    string         `json:"reason"`
	LineCount int            `json:"lineCount"`
}

// Metadata returns the event's metadata.
func (e OrderCancelled) Metadata() event.Metadata { return e.Meta }
