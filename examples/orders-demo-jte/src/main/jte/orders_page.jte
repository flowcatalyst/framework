@import io.flowcatalyst.framework.examples.ordersjte.*
@import io.flowcatalyst.framework.ui.kit.*
@import java.util.List
@import java.util.Map
@param OrdersView v

<div data-signals:sel-order="''">
${Kit.pageHeader(new Props.PageHeader("Orders", "Server-rendered with JTE and Datastar"), @`${Kit.button(new Props.Button("primary", null, null, null, false, "plus", null, Map.of("id", "new-order-open", "data-on:click", Markup.showModal("new-order"), "aria-haspopup", "dialog")), @`New order`)}`)}

@template.orders_list(v = v)

${Kit.drawer(
    new Props.Drawer("order-drawer", "selOrder", "Order", null, null, null, null, null, "order-drawer-body"),
    @`<p class="text-sm text-muted-foreground">Select an order to see it here.</p>`, null, null)}

${Kit.confirmDialog(new Props.ConfirmDialog("cancel-order", "#", "Cancel order", "Cancel this order? It cannot be restored.", "Cancel order", "danger", null, Map.of("data-on:submit", Markup.SUBMIT_CONFIRM)))}

@template.new_order_dialog(f = v.form())
</div>
