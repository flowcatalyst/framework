// Package orders is the Go counterpart of the Java orders-demo-jte: the same in-memory orders, pages
// and behaviour (a list with search, a status filter, sort and paging; a record drawer loaded by the
// server; a create dialog validated by the server; a confirmed cancel), written as templ templates
// on go/ui/kit and answered through go/ui/views.
package orders

import (
	"strconv"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/ui/kit"
)

// Statuses are the order statuses, in the order the filter lists them.
var Statuses = []string{"PENDING", "PAID", "SHIPPED", "CANCELLED"}

// Order is an order as the demo pages show it.
type Order struct {
	ID         string // ord-0001
	Customer   string
	Status     string // PENDING, PAID, SHIPPED or CANCELLED
	TotalCents int64
	CreatedAt  time.Time
}

// Total is the total as money, for example 1,234.50.
func (o Order) Total() string {
	whole := strconv.FormatInt(o.TotalCents/100, 10)
	var b strings.Builder
	for i, c := range whole {
		if i > 0 && (len(whole)-i)%3 == 0 {
			b.WriteByte(',')
		}
		b.WriteRune(c)
	}
	frac := strconv.FormatInt(o.TotalCents%100, 10)
	if len(frac) < 2 {
		frac = "0" + frac
	}
	return b.String() + "." + frac
}

// BadgeVariant is the badge variant of the status.
func (o Order) BadgeVariant() kit.BadgeVariant {
	switch o.Status {
	case "PAID":
		return kit.BadgeSuccess
	case "SHIPPED":
		return kit.BadgeInfo
	case "CANCELLED":
		return kit.BadgeDestructive
	default:
		return kit.BadgeSecondary
	}
}

// StatusLabel is the status as a label, for example Pending.
func (o Order) StatusLabel() string {
	return o.Status[:1] + strings.ToLower(o.Status[1:])
}

// Cancellable reports whether the order can still be cancelled (PENDING and PAID).
func (o Order) Cancellable() bool { return o.Status == "PENDING" || o.Status == "PAID" }
