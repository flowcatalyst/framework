package io.flowcatalyst.framework.examples.ordersjte;

import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.ui.Views;
import io.flowcatalyst.framework.ui.kit.Kit;
import io.flowcatalyst.framework.ui.kit.Props;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The orders pages: a list with search, a status filter, sort and paging; a record drawer loaded
 * by the server; a create dialog validated by the server; a confirmed cancel. Every route answers
 * a browser navigation with a full page and a Datastar request with patches, so the pages work
 * without JavaScript too (links, GET and POST forms, post/redirect/get).
 *
 * <p>The demo has no sign-in. A real application authorises each handler (see the auth guide);
 * the one guard here is the same-origin check every POST needs, because a Datastar action carries
 * no CSRF token.
 */
public final class OrdersUi {

  /** Selects a record: the click handler of a link whose {@code href} is the record's URL. */
  public static final String OPEN_ORDER = "$selOrder = el.dataset.id; @get(el.href)";

  /** Closes the drawer. */
  public static final String CLOSE_DRAWER = "$selOrder = ''";

  /** Flash keys that may travel in a redirect, and what they say. */
  static final Map<String, Props.FlashMessage> FLASHES =
      Map.of(
          "created", new Props.FlashMessage("success", "Order created.", null, false, null),
          "cancelled", new Props.FlashMessage("success", "Order cancelled.", null, false, null),
          "not-cancellable",
              new Props.FlashMessage("error", "That order cannot be cancelled.", "Only pending or paid orders can be.", false, null));

  private final Views views;
  private final OrderStore store;
  private final InstantSource clock;

  /**
   * The pages over a store.
   *
   * @param views the view helper
   * @param store the orders
   * @param clock the time source
   */
  public OrdersUi(Views views, OrderStore store, InstantSource clock) {
    this.views = views;
    this.store = store;
    this.clock = clock;
  }

  /**
   * Registers the routes.
   *
   * @param routes the module's routes
   */
  public void register(Routes routes) {
    routes.get("/", r -> Response.redirect("/orders"));
    routes.get("/orders", this::list);
    routes.get("/orders/{id}", this::detail);
    routes.post("/orders", this::create);
    routes.post("/orders/{id}/cancel", this::cancel);
  }

  private Response list(Request r) {
    return listPage(r, 200, NewOrderForm.empty());
  }

  private Response listPage(Request r, int status, NewOrderForm form) {
    Query q = Query.of(r);
    OrderStore.Listing l = store.list(q.q(), q.status(), q.sort(), q.desc(), q.page(), q.rows());
    String key = r.query("flash");
    List<Props.FlashMessage> flashes = key != null && FLASHES.containsKey(key) ? List.of(FLASHES.get(key)) : List.of();
    return views.page(r, status, "Orders", Pages.orders(new OrdersView(q, l, form)), flashes);
  }

  private Response detail(Request r) {
    String id = r.pathParam("id");
    var found = store.find(id);
    if (Views.isDatastar(r)) {
      return views.stream(
          r,
          p -> {
            if (found.isEmpty()) {
              p.signals(Map.of("selOrder", ""));
              p.flash("error", "That order no longer exists.", null);
              return;
            }
            p.signals(Map.of("selOrder", id));
            p.inner("#order-drawer", Pages.drawer(found.get()));
          });
    }
    if (found.isEmpty()) {
      return views.page(
          r,
          404,
          "Not found",
          Kit.alert(new Props.Alert("destructive", null, "triangle-alert", "Order not found", "There is no such order.", null, null), null));
    }
    return views.page(r, "Order " + id, Pages.orderPage(found.get()));
  }

  private Response create(Request r) {
    Response refused = requireSameOrigin(r);
    if (refused != null) {
      return refused;
    }
    NewOrderForm f = NewOrderForm.validate(Forms.parse(r.bodyText()));
    if (!f.errors().isEmpty()) {
      // an expected failure: the form again with its errors (200 for Datastar, 422 for a navigation)
      if (Views.isDatastar(r)) {
        return views.stream(r, p -> p.outer(Pages.newOrderDialog(f)));
      }
      return listPage(r, 422, f);
    }
    Order o = store.add(f.customer(), f.status(), NewOrderForm.cents(f.amount()), clock.instant());
    if (Views.isDatastar(r)) {
      return views.stream(
          r,
          p -> {
            // The list is reloaded from the defaults and the dialog is replaced by a fresh one: the
            // old element leaves the top layer with it, which a morph would not do.
            Query q = new Query(null, null, "created", true, 1, 10);
            OrderStore.Listing l = store.list(null, null, q.sort(), q.desc(), 1, q.rows());
            p.outer(Pages.list(new OrdersView(q, l, NewOrderForm.empty())));
            p.replace("#new-order", Pages.newOrderDialog(NewOrderForm.empty()));
            p.flash("success", "Order created.", o.customer() + " (" + o.id() + ")");
            p.focus("new-order-open");
          });
    }
    return Response.text(303, "See Other").header("Location", "/orders?flash=created");
  }

  private Response cancel(Request r) {
    Response refused = requireSameOrigin(r);
    if (refused != null) {
      return refused;
    }
    var cancelled = store.cancel(r.pathParam("id"));
    if (Views.isDatastar(r)) {
      return views.stream(
          r,
          p -> {
            if (cancelled.isEmpty()) {
              p.flash("error", "That order cannot be cancelled.", "Only pending or paid orders can be.");
              return;
            }
            p.outer(Pages.row(cancelled.get()));
            p.flash("success", "Order cancelled.", cancelled.get().customer());
          });
    }
    return Response.text(303, "See Other")
        .header("Location", "/orders?flash=" + (cancelled.isPresent() ? "cancelled" : "not-cancellable"));
  }

  /**
   * The CSRF defence of a POST that carries no token: the browser says where the request came from
   * ({@code Sec-Fetch-Site}, then {@code Origin}), and anything but this site is refused.
   */
  private static @Nullable Response requireSameOrigin(Request r) {
    String site = r.header("Sec-Fetch-Site");
    if (site != null) {
      return site.equals("same-origin") || site.equals("none") ? null : forbidden();
    }
    String origin = r.header("Origin");
    String host = r.header("Host");
    if (origin != null) {
      boolean same = host != null && (origin.equals("http://" + host) || origin.equals("https://" + host));
      return same ? null : forbidden();
    }
    return null;
  }

  private static Response forbidden() {
    return Response.text(403, "cross-site request refused");
  }
}
