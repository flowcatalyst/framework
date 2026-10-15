package orders

import (
	"context"
	"net/http"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/domain"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/internal/dbq"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/validate"
	"github.com/flowcatalyst/framework/go/id"
	"github.com/flowcatalyst/framework/go/usecase"
)

// The permissions of the orders API (the Java demo's OrdersApi.READ and MANAGE).
var (
	// Read lets a caller read orders.
	Read = fw.MustPermission("orders:order:read")
	// Manage lets a caller place and change orders.
	Manage = fw.MustPermission("orders:order:manage")
)

// ---- wire types --------------------------------------------------------------------------------
//
// The records are the wire types, separate from the aggregate: the OpenAPI document is written
// from them (docs/contracts/openapi.md section 5), and conformance/openapi/orders-demo.json is the
// Java demo's document for the same API.

// OrderStatus is open or cancelled.
type OrderStatus string

// The statuses.
const (
	StatusOpen      OrderStatus = domain.StatusOpen
	StatusCancelled OrderStatus = domain.StatusCancelled
)

// EnumValues implements openapi.EnumValuer.
func (OrderStatus) EnumValues() []string { return []string{domain.StatusOpen, domain.StatusCancelled} }

// OrderLineView is one line of an order.
type OrderLineView struct {
	LineID         string `json:"lineId"`
	SKU            string `json:"sku"`
	Quantity       int32  `json:"quantity"`
	UnitPriceCents int64  `json:"unitPriceCents"`
	TotalCents     int64  `json:"totalCents"`
}

// OrderView is an order with its lines.
type OrderView struct {
	ID         string          `json:"id"`
	Customer   string          `json:"customer"`
	Status     OrderStatus     `json:"status"`
	Version    int64           `json:"version"`
	Lines      []OrderLineView `json:"lines"`
	TotalCents int64           `json:"totalCents"`
}

// OrderSummaryView is an order in a list; FirstSku is absent for an order without lines.
type OrderSummaryView struct {
	ID         string      `json:"id"`
	Customer   string      `json:"customer"`
	Status     OrderStatus `json:"status"`
	Version    int64       `json:"version"`
	LineCount  int32       `json:"lineCount"`
	TotalCents int64       `json:"totalCents"`
	FirstSku   *string     `json:"firstSku,omitzero"`
}

// Page is one page of a list: NextCursor goes in as "after" for the next page and is absent on the
// last one.
type Page[T any] struct {
	Items      []T     `json:"items"`
	NextCursor *string `json:"nextCursor,omitzero"`
}

// PlaceOrderBody places an order.
type PlaceOrderBody struct {
	Customer string `json:"customer"`
}

// AddLineBody adds a line: an upper-case SKU, a quantity of 1 to 10 000 and the price of one, in
// cents.
type AddLineBody struct {
	SKU            string `json:"sku"`
	Quantity       int32  `json:"quantity"`
	UnitPriceCents int64  `json:"unitPriceCents"`
}

// CancelOrderBody cancels an order.
type CancelOrderBody struct {
	Reason string `json:"reason"`
}

var (
	placeOrderRules = validate.Of(
		validate.Field("customer", func(b PlaceOrderBody) string { return b.Customer }, validate.NotBlank(), validate.MaxLength(200)))
	addLineRules = validate.Of(
		validate.Field("sku", func(b AddLineBody) string { return b.SKU }, validate.NotBlank(), validate.MaxLength(64), validate.Pattern("[A-Z0-9][A-Z0-9-]*")),
		validate.Field("quantity", func(b AddLineBody) int32 { return b.Quantity }, validate.Range[int32](1, 10_000)),
		validate.Field("unitPriceCents", func(b AddLineBody) int64 { return b.UnitPriceCents }, validate.Range[int64](0, 100_000_000)))
	cancelOrderRules = validate.Of(
		validate.Field("reason", func(b CancelOrderBody) string { return b.Reason }, validate.NotBlank(), validate.MaxLength(500)))
)

// ---- operations --------------------------------------------------------------------------------

var (
	orderID = httpfw.Path[string]("orderId")
	status  = httpfw.Query[OrderStatus]("status").Optional()
	limit   = httpfw.Query[int32]("limit").Default(50)
	after   = httpfw.Query[string]("after").Optional()
)

// The orders API as typed operations: values written once, declared by [DeclareAPI] and bound by
// [API.Bind].
var (
	// ListOrdersOp is GET /api/orders.
	ListOrdersOp = httpfw.Get[Page[OrderSummaryView]]("/api/orders").
			ID("listOrders").Summary("List the client's orders").Tag("orders").
			Query(status, limit, after).Permission(Read).Build()

	// GetOrderOp is GET /api/orders/{orderId}.
	GetOrderOp = httpfw.Get[OrderView]("/api/orders/{orderId}").
			ID("getOrder").Summary("Read an order").Tag("orders").
			Path(orderID).Permission(Read).
			Error(404, "ORDER_NOT_FOUND", "there is no such order").Build()

	// PlaceOrderOp is POST /api/orders.
	PlaceOrderOp = httpfw.Post[PlaceOrderBody, OrderView]("/api/orders", placeOrderRules).
			ID("placeOrder").Summary("Place an order").Tag("orders").
			Status(http.StatusCreated).Permission(Manage).Build()

	// AddOrderLineOp is POST /api/orders/{orderId}/lines.
	AddOrderLineOp = httpfw.Post[AddLineBody, OrderView]("/api/orders/{orderId}/lines", addLineRules).
			ID("addOrderLine").Summary("Add a line to an open order").Tag("orders").
			Path(orderID).Permission(Manage).
			Error(404, "ORDER_NOT_FOUND", "there is no such order").
			Error(409, "ORDER_NOT_OPEN", "the order is cancelled and cannot change").
			Error(409, "STALE_AGGREGATE", "the order was changed by someone else; reload it and try again").Build()

	// CancelOrderOp is POST /api/orders/{orderId}/cancel.
	CancelOrderOp = httpfw.Post[CancelOrderBody, OrderView]("/api/orders/{orderId}/cancel", cancelOrderRules).
			ID("cancelOrder").Summary("Cancel an open order").Tag("orders").
			Path(orderID).Permission(Manage).
			Error(404, "ORDER_NOT_FOUND", "there is no such order").
			Error(409, "ORDER_NOT_OPEN", "the order is already cancelled").
			Error(409, "STALE_AGGREGATE", "the order was changed by someone else; reload it and try again").Build()
)

// DeclareAPI declares the orders API for the OpenAPI document: at declaration time, so
// `orders-demo openapi` needs no database or configuration.
func DeclareAPI(web *httpfw.Declared) {
	web.Info("orders-demo", "1")
	web.Operations(ListOrdersOp, GetOrderOp, PlaceOrderOp, AddOrderLineOp, CancelOrderOp)
}

// ---- handlers ----------------------------------------------------------------------------------

// API serves the orders operations: the use cases decide, a failed one is answered with its
// *usecase.Error.
type API struct {
	Service *Service
	DB      *database.DB
	Store   Store
}

// Bind registers the five operations.
func (a *API) Bind(rt *httpfw.Routes) {
	rt.Bind(httpfw.Handle(ListOrdersOp, a.list))
	rt.Bind(httpfw.Handle(GetOrderOp, a.get))
	rt.Bind(httpfw.Handle(PlaceOrderOp, a.place))
	rt.Bind(httpfw.Handle(AddOrderLineOp, a.addLine))
	rt.Bind(httpfw.Handle(CancelOrderOp, a.cancel))
}

func (a *API) list(ctx context.Context, cx fw.Context, in httpfw.In[httpfw.NoBody]) (Page[OrderSummaryView], error) {
	rows, err := dbq.New(a.DB.Pool()).Summaries(ctx, cx.Client().String())
	if err != nil {
		return Page[OrderSummaryView]{}, err
	}
	want, filter := status.Value(in.Params)
	cursor, hasCursor := after.Value(in.Params)
	size := int(max(1, min(limit.Value(in.Params), 200)))
	// the rows come sorted by id; the cursor is the last id of the previous page
	var rest []OrderSummaryView
	for _, r := range rows {
		if (filter && OrderStatus(r.Status) != want) || (hasCursor && r.ID.String() <= cursor) {
			continue
		}
		v := OrderSummaryView{
			ID: r.ID.String(), Customer: r.Customer, Status: OrderStatus(r.Status), Version: r.Version,
			LineCount: int32(r.LineCount), TotalCents: r.TotalCents, //nolint:gosec // a line count fits
		}
		if r.FirstSku != "" {
			sku := r.FirstSku
			v.FirstSku = &sku
		}
		rest = append(rest, v)
	}
	page := Page[OrderSummaryView]{Items: rest[:min(size, len(rest))]}
	if len(rest) > size {
		next := page.Items[len(page.Items)-1].ID
		page.NextCursor = &next
	}
	return page, nil
}

func (a *API) get(ctx context.Context, cx fw.Context, in httpfw.In[httpfw.NoBody]) (OrderView, error) {
	return a.view(ctx, cx, orderID.Value(in.Params))
}

func (a *API) place(ctx context.Context, cx fw.Context, in httpfw.In[PlaceOrderBody]) (OrderView, error) {
	customer, err := domain.ParseCustomerName(in.Body.Customer)
	if err != nil {
		return OrderView{}, err
	}
	done, err := a.Service.PlaceOrder.Run(ctx, cx, PlaceOrder{Customer: customer})
	if err != nil {
		return OrderView{}, err
	}
	view, err := a.view(ctx, cx, done.Event().OrderID.String())
	if err == nil {
		in.Header.Add("Location", "/api/orders/"+view.ID)
	}
	return view, err
}

func (a *API) addLine(ctx context.Context, cx fw.Context, in httpfw.In[AddLineBody]) (OrderView, error) {
	raw := orderID.Value(in.Params)
	oid, err := id.Parse[domain.OrderKind](raw)
	if err != nil {
		return OrderView{}, orderNotFound()
	}
	cmd, err := ParseAddLine(oid, in.Body.SKU, int64(in.Body.Quantity), in.Body.UnitPriceCents)
	if err != nil {
		return OrderView{}, err
	}
	if _, err := a.Service.AddLine.Run(ctx, cx, cmd); err != nil {
		return OrderView{}, err
	}
	return a.view(ctx, cx, raw)
}

func (a *API) cancel(ctx context.Context, cx fw.Context, in httpfw.In[CancelOrderBody]) (OrderView, error) {
	raw := orderID.Value(in.Params)
	oid, err := id.Parse[domain.OrderKind](raw)
	if err != nil {
		return OrderView{}, orderNotFound()
	}
	reason, err := domain.ParseCancelReason(in.Body.Reason)
	if err != nil {
		return OrderView{}, err
	}
	if _, err := a.Service.CancelOrder.Run(ctx, cx, CancelOrder{Order: oid, Reason: reason}); err != nil {
		return OrderView{}, err
	}
	return a.view(ctx, cx, raw)
}

func orderNotFound() error { return usecase.NotFound(OrderNotFound, "There is no such order.") }

// view reads an order of the caller's client as the wire type; there is none for another client's
// order or a malformed id.
func (a *API) view(ctx context.Context, cx fw.Context, raw string) (OrderView, error) {
	oid, err := id.Parse[domain.OrderKind](raw)
	if err != nil {
		return OrderView{}, orderNotFound()
	}
	loaded, found, err := a.Store.Find(ctx, a.DB, cx.Client(), oid)
	if err != nil {
		return OrderView{}, err
	}
	if !found {
		return OrderView{}, orderNotFound()
	}
	order := loaded.Get()
	v := OrderView{
		ID: order.ID().String(), Customer: order.Customer().String(), Status: OrderStatus(domain.StatusOf(order)),
		Version: loaded.Version(), Lines: []OrderLineView{},
	}
	for _, l := range order.Lines() {
		total := int64(l.Quantity().Int()) * l.UnitPrice().Int()
		v.Lines = append(v.Lines, OrderLineView{
			LineID: l.ID().String(), SKU: l.SKU().String(), Quantity: l.Quantity().Int(),
			UnitPriceCents: l.UnitPrice().Int(), TotalCents: total,
		})
		v.TotalCents += total
	}
	return v, nil
}
