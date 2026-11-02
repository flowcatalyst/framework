package orders

import (
	"cmp"
	"fmt"
	"slices"
	"strings"
	"sync"
	"time"
)

// Listing is one page of a listing.
type Listing struct {
	Rows  []Order // the orders on the page
	Total int     // how many orders match
	Pages int     // how many pages there are
	Page  int     // this page, clamped into range
}

// Store holds the demo's orders in memory. The demo has no database and no sign-in.
type Store struct {
	mu       sync.Mutex
	orders   []Order
	sequence int
}

// Seeded is a store with a small seed, dated from now.
func Seeded(now time.Time) *Store {
	s := &Store{}
	names := []string{
		"Ada Lovelace", "Grace Hopper", "Alan Turing", "Edsger Dijkstra", "Barbara Liskov", "Donald Knuth",
		"Margaret Hamilton", "Dennis Ritchie", "Ken Thompson", "Frances Allen", "Tony Hoare", "Leslie Lamport",
	}
	statuses := []string{"PENDING", "PAID", "SHIPPED", "PAID", "CANCELLED", "PENDING"}
	for i := range 23 {
		s.Add(names[i%len(names)], statuses[i%len(statuses)], 1_250+(int64(i)*3_719)%98_000,
			now.Add(-time.Duration(i*7+1)*time.Hour))
	}
	return s
}

// Add adds an order and returns it.
func (s *Store) Add(customer, status string, totalCents int64, createdAt time.Time) Order {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sequence++
	o := Order{ID: fmt.Sprintf("ord-%04d", s.sequence), Customer: customer, Status: status, TotalCents: totalCents, CreatedAt: createdAt}
	s.orders = append(s.orders, o)
	return o
}

// Find returns one order.
func (s *Store) Find(id string) (Order, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, o := range s.orders {
		if o.ID == id {
			return o, true
		}
	}
	return Order{}, false
}

// Cancel cancels an order that can still be cancelled; it reports false when there is none or it
// cannot be cancelled.
func (s *Store) Cancel(id string) (Order, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, o := range s.orders {
		if o.ID == id {
			if !o.Cancellable() {
				return Order{}, false
			}
			o.Status = "CANCELLED"
			s.orders[i] = o
			return o, true
		}
	}
	return Order{}, false
}

// List lists orders: q is a case-insensitive part of the customer's name ("" for any), status a
// status ("" for all), sort one of customer, status, total or created, page is 1-based.
func (s *Store) List(q, status, sort string, descending bool, page, rows int) Listing {
	s.mu.Lock()
	needle := strings.ToLower(strings.TrimSpace(q))
	matching := []Order{}
	for _, o := range s.orders {
		if (needle == "" || strings.Contains(strings.ToLower(o.Customer), needle)) && (status == "" || o.Status == status) {
			matching = append(matching, o)
		}
	}
	s.mu.Unlock()
	by := func(a, b Order) int {
		switch sort {
		case "customer":
			return cmp.Compare(strings.ToLower(a.Customer), strings.ToLower(b.Customer))
		case "status":
			return cmp.Compare(a.Status, b.Status)
		case "total":
			return cmp.Compare(a.TotalCents, b.TotalCents)
		default:
			return a.CreatedAt.Compare(b.CreatedAt)
		}
	}
	slices.SortStableFunc(matching, func(a, b Order) int {
		c := by(a, b)
		if descending {
			c = -c
		}
		return cmp.Or(c, cmp.Compare(a.ID, b.ID))
	})
	pages := max(1, (len(matching)+rows-1)/rows)
	p := min(max(page, 1), pages)
	from := (p - 1) * rows
	return Listing{Rows: slices.Clone(matching[from:min(from+rows, len(matching))]), Total: len(matching), Pages: pages, Page: p}
}
