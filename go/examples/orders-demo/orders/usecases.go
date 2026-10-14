package orders

import (
	"context"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/domain"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/id"
	"github.com/flowcatalyst/framework/go/usecase"
)

// principalRequired is the demo's authorization, as in Java: a principal must be acting. With
// permissions on the Context (M4c) it becomes usecase.Require(cx, OrdersWrite).
func principalRequired(cx fw.Context) (usecase.Grant, error) {
	if _, ok := cx.Principal(); !ok {
		return usecase.Grant{}, usecase.Authorization(PrincipalRequired, "Sign in to change orders.")
	}
	return usecase.Public(), nil
}

// PlaceOrderUseCase places an order.
type PlaceOrderUseCase struct {
	Orders Store
	Ids    *fw.Ids
	Events *event.Factory
}

// Name returns place-order.
func (PlaceOrderUseCase) Name() usecase.Name { return usecase.MustName("place-order") }

// Validate has nothing to check: the command was parsed at the edge.
func (PlaceOrderUseCase) Validate(context.Context, fw.Context, PlaceOrder) error { return nil }

// Authorize requires a principal.
func (PlaceOrderUseCase) Authorize(_ context.Context, cx fw.Context, _ usecase.Valid[PlaceOrder]) (usecase.Grant, error) {
	return principalRequired(cx)
}

// Execute plans the new order: an Insert of a Fresh aggregate, for the caller's client.
func (u PlaceOrderUseCase) Execute(_ context.Context, cx fw.Context, a usecase.Authorized[PlaceOrder], _ *database.DB) (*usecase.Plan[OrderPlaced], error) {
	order := domain.PlaceOrder(id.New[domain.OrderKind](u.Ids), a.Command().Customer)
	ev := OrderPlaced{
		Meta:    u.Events.Metadata(cx, Placed, order.ID().String()),
		OrderID: order.ID(), Customer: order.Customer().String(),
	}
	return usecase.NewPlan(ev, usecase.Insert(usecase.NewFresh[domain.Order](cx, order), u.Orders)), nil
}

// AddLineUseCase adds a line to an open order.
type AddLineUseCase struct {
	Orders Store
	Ids    *fw.Ids
	Events *event.Factory
}

// Name returns add-line.
func (AddLineUseCase) Name() usecase.Name { return usecase.MustName("add-line") }

// Validate has nothing to check.
func (AddLineUseCase) Validate(context.Context, fw.Context, AddLine) error { return nil }

// Authorize requires a principal.
func (AddLineUseCase) Authorize(_ context.Context, cx fw.Context, _ usecase.Valid[AddLine]) (usecase.Grant, error) {
	return principalRequired(cx)
}

// Execute loads the order once; only an open order takes a line.
func (u AddLineUseCase) Execute(ctx context.Context, cx fw.Context, a usecase.Authorized[AddLine], db *database.DB) (*usecase.Plan[OrderLineAdded], error) {
	cmd := a.Command()
	loaded, found, err := u.Orders.Find(ctx, db, cx.Client(), cmd.Order)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, usecase.NotFound(OrderNotFound, "There is no such order.")
	}
	var open domain.OpenOrder
	switch o := loaded.Get().(type) {
	case domain.OpenOrder:
		open = o
	case domain.CancelledOrder:
		return nil, usecase.BusinessRule(OrderNotOpen, "The order is cancelled and cannot change.")
	}
	line := domain.NewOrderLine(id.New[domain.LineKind](u.Ids), cmd.SKU, cmd.Quantity, cmd.UnitPrice)
	open = open.WithLine(line)
	ev := OrderLineAdded{
		Meta:    u.Events.Metadata(cx, LineAdded, cmd.Order.String()),
		OrderID: cmd.Order, LineID: line.ID(), SKU: line.SKU().String(), Quantity: line.Quantity().Int(),
		UnitPriceCents: line.UnitPrice().Int(), LineCount: len(open.Lines()),
	}
	return usecase.NewPlan(ev, usecase.Update(loaded.With(open), u.Orders)), nil
}

// CancelOrderUseCase cancels an open order.
type CancelOrderUseCase struct {
	Orders Store
	Events *event.Factory
}

// Name returns cancel-order.
func (CancelOrderUseCase) Name() usecase.Name { return usecase.MustName("cancel-order") }

// Validate has nothing to check.
func (CancelOrderUseCase) Validate(context.Context, fw.Context, CancelOrder) error { return nil }

// Authorize requires a principal.
func (CancelOrderUseCase) Authorize(_ context.Context, cx fw.Context, _ usecase.Valid[CancelOrder]) (usecase.Grant, error) {
	return principalRequired(cx)
}

// Execute loads the order once and cancels it when it is open.
func (u CancelOrderUseCase) Execute(ctx context.Context, cx fw.Context, a usecase.Authorized[CancelOrder], db *database.DB) (*usecase.Plan[OrderCancelled], error) {
	cmd := a.Command()
	loaded, found, err := u.Orders.Find(ctx, db, cx.Client(), cmd.Order)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, usecase.NotFound(OrderNotFound, "There is no such order.")
	}
	switch o := loaded.Get().(type) {
	case domain.OpenOrder:
		cancelled := o.Cancel()
		ev := OrderCancelled{
			Meta:    u.Events.Metadata(cx, Cancelled, cmd.Order.String()),
			OrderID: cmd.Order, Reason: cmd.Reason.String(), LineCount: len(o.Lines()),
		}
		return usecase.NewPlan(ev, usecase.Update(loaded.With(domain.Order(cancelled)), u.Orders)), nil
	case domain.CancelledOrder:
		return nil, usecase.BusinessRule(OrderNotOpen, "The order is cancelled and cannot change.")
	}
	return nil, usecase.Internal(usecase.MustErrorCode("UNKNOWN_ORDER_STATE"))
}

// Store is what the use cases load and write orders with: [Repository], or a test's wrapper.
type Store = usecase.Repository[domain.Order, domain.OrderID]

// Policies are the use cases' conflict policies.
type Policies struct {
	AddLine, Cancel usecase.ConflictPolicy
}

// DefaultPolicies are the Java demo's: add-line retries 3 times with 25ms; cancel fails at the
// first conflict.
func DefaultPolicies() (Policies, error) {
	retry, err := usecase.Retry(3, 25*time.Millisecond)
	if err != nil {
		return Policies{}, err
	}
	return Policies{AddLine: retry, Cancel: usecase.Fail()}, nil
}

// Service holds the defined use cases.
type Service struct {
	PlaceOrder  *usecase.Defined[PlaceOrder, OrderPlaced]
	AddLine     *usecase.Defined[AddLine, OrderLineAdded]
	CancelOrder *usecase.Defined[CancelOrder, OrderCancelled]
}

// NewService defines the use cases on the repository with the default policies.
func NewService(r *usecase.Runner, ids *fw.Ids, events *event.Factory) (*Service, error) {
	p, err := DefaultPolicies()
	if err != nil {
		return nil, err
	}
	return NewServiceWith(r, ids, events, Repository{}, p)
}

// NewServiceWith defines the use cases on a store with policies (tests).
func NewServiceWith(r *usecase.Runner, ids *fw.Ids, events *event.Factory, store Store, p Policies) (*Service, error) {
	place, err := usecase.Define(r, PlaceOrderUseCase{Orders: store, Ids: ids, Events: events})
	if err != nil {
		return nil, err
	}
	add, err := usecase.Define(r, AddLineUseCase{Orders: store, Ids: ids, Events: events}, usecase.WithConflictPolicy(p.AddLine))
	if err != nil {
		return nil, err
	}
	cancel, err := usecase.Define(r, CancelOrderUseCase{Orders: store, Events: events}, usecase.WithConflictPolicy(p.Cancel))
	if err != nil {
		return nil, err
	}
	return &Service{PlaceOrder: place, AddLine: add, CancelOrder: cancel}, nil
}
