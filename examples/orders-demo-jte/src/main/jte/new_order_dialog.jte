@import io.flowcatalyst.framework.examples.ordersjte.*
@import io.flowcatalyst.framework.ui.kit.*
@import java.util.List
@import java.util.Map
@param NewOrderForm f

${Kit.formDialog(
    new Props.FormDialog("new-order", "New order", "Place an order for a customer.", "narrow", "/orders", "Create order", "newOrder", false, f.open(), f.formError(), null),
    @`
${Kit.field(new Props.Field("Customer", "new-order-customer", null, null, f.error("customer"), null, true, null, Map.of("id", "new-order-customer-field")),
    @`${Kit.input(new Props.Input("new-order-customer", "customer", null, f.customer(), "Ada Lovelace", true, false, f.error("customer") != null, f.error("customer") != null ? "new-order-customer-err" : null, null, Map.of("autofocus", true, "maxlength", "80", "autocomplete", "off")))}`)}
${Kit.field(new Props.Field("Amount", "new-order-amount", null, "In currency units, up to two decimals.", f.error("amount"), null, true, null, Map.of("id", "new-order-amount-field")),
    @`${Kit.input(new Props.Input("new-order-amount", "amount", "text", f.amount(), "49.90", true, false, f.error("amount") != null, f.error("amount") != null ? "new-order-amount-err" : null, null, Map.of("inputmode", "decimal")))}`)}
${Kit.field(new Props.Field("Status", "new-order-status", null, null, f.error("status"), null, false, null, Map.of("id", "new-order-status-field")),
    @`${Kit.select(new Props.Select("new-order-status", "status", List.of(new Props.Option("PENDING", "Pending"), new Props.Option("PAID", "Paid"), new Props.Option("SHIPPED", "Shipped")), f.status(), null, false, false, f.error("status") != null, f.error("status") != null ? "new-order-status-err" : null, null, null))}`)}
`)}
