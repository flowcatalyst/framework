package orders

import (
	"net/http"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/ui/ds"
	"github.com/flowcatalyst/framework/go/ui/kit"
	"github.com/flowcatalyst/framework/go/ui/views"
)

// Flashes are the messages a flash key in a redirect may stand for. The text never comes from the
// request: an unknown key shows nothing.
var Flashes = map[string]kit.FlashMessageProps{
	"created":         {Kind: kit.FlashSuccess, Summary: "Order created."},
	"cancelled":       {Kind: kit.FlashSuccess, Summary: "Order cancelled."},
	"not-cancellable": {Kind: kit.FlashError, Summary: "That order cannot be cancelled.", Detail: "Only pending or paid orders can be."},
}

// Shell is the shell around every page: the navigation and the signed-out demo user.
func Shell(*http.Request) kit.AppShellProps {
	return kit.AppShellProps{
		LogoHref: "/orders",
		Nav: []kit.NavGroup{{
			Label: "Demo",
			Items: []kit.NavItem{{Label: "Orders", Href: "/orders", Icon: "list", Current: true}},
		}},
		User:          kit.ShellUser{Name: "Demo user", Email: "demo@example.com", Initials: "D"},
		Version:       "demo",
		ProfileHref:   "/orders",
		SignOutAction: "/orders",
	}
}

// UI is the orders pages: a list with search, a status filter, sort and paging; a record drawer
// loaded by the server; a create dialog validated by the server; a confirmed cancel. Every route
// answers a browser navigation with a full page and a Datastar request with patches, so the pages
// work without JavaScript too (links, GET and POST forms, post/redirect/get).
//
// The demo has no sign-in and no sessions, so httpfw.AcceptForms (which the sessions guard
// honours) does not apply to its routes. A real application authorises each handler; the one guard
// here is the same-origin check every POST needs, because a Datastar action carries no CSRF token.
type UI struct {
	views *views.Views
	store *Store
	clock fw.Clock
}

// New is the pages over a store.
func New(v *views.Views, store *Store, clock fw.Clock) *UI {
	return &UI{views: v, store: store, clock: clock}
}

// Register adds the routes to a table.
func (u *UI) Register(rt *httpfw.Routes) {
	rt.Handle("GET /", func(w http.ResponseWriter, _ *http.Request) error {
		httpfw.Redirect(w, "/orders")
		return nil
	})
	rt.Handle("GET /orders", u.list)
	rt.Handle("GET /orders/{id}", u.detail)
	rt.Handle("POST /orders", u.create)
	rt.Handle("POST /orders/{id}/cancel", u.cancel)
}

func (u *UI) list(w http.ResponseWriter, r *http.Request) error {
	return u.listPage(w, r, http.StatusOK, EmptyForm())
}

func (u *UI) listPage(w http.ResponseWriter, r *http.Request, status int, form NewOrderForm) error {
	q := QueryOf(r)
	l := u.store.List(q.Q, q.Status, q.Sort, q.Desc, q.Page, q.Rows)
	var flashes []kit.FlashMessageProps
	if m, ok := Flashes[r.URL.Query().Get("flash")]; ok {
		flashes = append(flashes, m)
	}
	return u.views.Page(w, r, "Orders", ordersPage(ordersView{Query: q, Listing: l, Form: form}),
		views.WithStatus(status), views.WithPageFlashes(flashes...))
}

func (u *UI) detail(w http.ResponseWriter, r *http.Request) error {
	id := r.PathValue("id")
	found, ok := u.store.Find(id)
	if ds.IsDatastar(r) {
		return u.views.Stream(w, r, func(p *views.Patches) error {
			if !ok {
				if err := p.Signals(map[string]any{"selOrder": ""}); err != nil {
					return err
				}
				return p.Flash(kit.FlashError, "That order no longer exists.", "")
			}
			if err := p.Signals(map[string]any{"selOrder": id}); err != nil {
				return err
			}
			return p.Inner("#order-drawer", orderDrawer(found))
		})
	}
	if !ok {
		return u.views.Page(w, r, "Not found", notFoundPage(), views.WithStatus(http.StatusNotFound))
	}
	return u.views.Page(w, r, "Order "+id, orderPage(found))
}

func (u *UI) create(w http.ResponseWriter, r *http.Request) error {
	if refuseCrossSite(w, r) {
		return nil
	}
	f := Validate(formValues(r))
	if len(f.Errors) > 0 {
		// an expected failure: the form again with its errors (200 for Datastar, 422 for a navigation)
		if ds.IsDatastar(r) {
			return u.views.Stream(w, r, func(p *views.Patches) error { return p.Outer(newOrderDialog(f)) })
		}
		return u.listPage(w, r, http.StatusUnprocessableEntity, f)
	}
	o := u.store.Add(f.Customer, f.Status, Cents(f.Amount), u.clock.Now())
	if ds.IsDatastar(r) {
		return u.views.Stream(w, r, func(p *views.Patches) error {
			// The list is reloaded from the defaults and the dialog is replaced by a fresh one: the
			// old element leaves the top layer with it, which a morph would not do.
			q := DefaultQuery()
			l := u.store.List("", "", q.Sort, q.Desc, 1, q.Rows)
			if err := p.Outer(ordersList(ordersView{Query: q, Listing: l, Form: EmptyForm()})); err != nil {
				return err
			}
			if err := p.Replace("#new-order", newOrderDialog(EmptyForm())); err != nil {
				return err
			}
			if err := p.Flash(kit.FlashSuccess, "Order created.", o.Customer+" ("+o.ID+")"); err != nil {
				return err
			}
			return p.Focus("new-order-open")
		})
	}
	return u.views.SeeOther(w, r, "/orders?flash=created", "")
}

func (u *UI) cancel(w http.ResponseWriter, r *http.Request) error {
	if refuseCrossSite(w, r) {
		return nil
	}
	cancelled, ok := u.store.Cancel(r.PathValue("id"))
	if ds.IsDatastar(r) {
		return u.views.Stream(w, r, func(p *views.Patches) error {
			if !ok {
				return p.Flash(kit.FlashError, "That order cannot be cancelled.", "Only pending or paid orders can be.")
			}
			if err := p.Outer(orderRow(cancelled)); err != nil {
				return err
			}
			return p.Flash(kit.FlashSuccess, "Order cancelled.", cancelled.Customer)
		})
	}
	key := "not-cancellable"
	if ok {
		key = "cancelled"
	}
	return u.views.SeeOther(w, r, "/orders?flash="+key, "")
}

// formValues is the first value of each field of an application/x-www-form-urlencoded body.
func formValues(r *http.Request) map[string]string {
	out := map[string]string{}
	if r.ParseForm() != nil {
		return out
	}
	for name, vs := range r.PostForm {
		if len(vs) > 0 {
			out[name] = vs[0]
		}
	}
	return out
}

// refuseCrossSite is the CSRF defence of a POST that carries no token: the browser says where the
// request came from (Sec-Fetch-Site, then Origin), and anything but this site is refused with 403
// (it reports true then). A request with neither header passes, as in the Java demo.
func refuseCrossSite(w http.ResponseWriter, r *http.Request) bool {
	if site := r.Header.Get("Sec-Fetch-Site"); site != "" {
		if site == "same-origin" || site == "none" {
			return false
		}
		return forbidden(w)
	}
	if origin := r.Header.Get("Origin"); origin != "" {
		if r.Host != "" && (origin == "http://"+r.Host || origin == "https://"+r.Host) {
			return false
		}
		return forbidden(w)
	}
	return false
}

func forbidden(w http.ResponseWriter) bool {
	httpfw.Text(w, http.StatusForbidden, "cross-site request refused")
	return true
}
