@import io.flowcatalyst.framework.examples.ordersjte.*
@import io.flowcatalyst.framework.ui.kit.*
@param Order o

${Kit.drawerContent(
    new Props.Drawer("order-drawer", "selOrder", "Order", null, o.customer(), o.id(), null, null, "order-drawer-body"),
    @`${Kit.formSection(new Props.FormSection("Details", null, false, false), @`<div class="fc-detail-grid">
${Kit.detailField(new Props.DetailField("Order", o.id(), false), null)}
${Kit.detailField(new Props.DetailField("Customer", o.customer(), false), null)}
${Kit.detailField(new Props.DetailField("Total", o.total(), false), null)}
${Kit.detailField(new Props.DetailField("Placed", null, false), @`${Kit.localTime(new Props.LocalTime(o.createdAt(), false, null))}`)}
</div>`, null)}`,
    @`${Kit.badge(new Props.Badge(o.badgeVariant(), null, null, null), @`${o.statusLabel()}`)}`,
    @`<button type="button" class="fc-btn fc-btn-text" data-on:click="${OrdersUi.CLOSE_DRAWER}">Close</button>`)}
