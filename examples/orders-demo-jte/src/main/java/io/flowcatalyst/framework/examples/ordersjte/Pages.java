package io.flowcatalyst.framework.examples.ordersjte;

import gg.jte.Content;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import java.util.Map;

/** The demo's own templates, precompiled by the build from {@code src/main/jte}. */
final class Pages {

  private Pages() {}

  private static final TemplateEngine ENGINE = TemplateEngine.createPrecompiled(ContentType.Html);

  private static Content render(String template, String param, Object value) {
    return output -> ENGINE.render(template, Map.of(param, value), output);
  }

  static Content orders(OrdersView view) {
    return render("orders_page.jte", "v", view);
  }

  static Content list(OrdersView view) {
    return render("orders_list.jte", "v", view);
  }

  static Content newOrderDialog(NewOrderForm form) {
    return render("new_order_dialog.jte", "f", form);
  }

  static Content row(Order order) {
    return render("order_row.jte", "o", order);
  }

  static Content drawer(Order order) {
    return render("order_drawer.jte", "o", order);
  }

  static Content orderPage(Order order) {
    return render("order_page.jte", "o", order);
  }
}
