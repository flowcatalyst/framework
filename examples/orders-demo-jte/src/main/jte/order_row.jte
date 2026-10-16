@import io.flowcatalyst.framework.examples.ordersjte.*
@import io.flowcatalyst.framework.ui.kit.*
@import java.util.List
@import java.util.Map
@param Order o

${Kit.tableRow(new Props.TR(false, false, "row-" + o.id(), null, null), @`
${Kit.tableCell(new Props.TD(0, null, null), @`<a href="/orders/${o.id()}" data-id="${o.id()}" class="font-medium text-foreground no-underline" data-on:click__prevent="${OrdersUi.OPEN_ORDER}">${o.customer()}</a>`)}
${Kit.tableCell(new Props.TD(0, null, null), @`${Kit.badge(new Props.Badge(o.badgeVariant(), null, null, null), @`${o.statusLabel()}`)}`)}
${Kit.tableCell(new Props.TD(0, "tabular-nums", null), @`${o.total()}`)}
${Kit.tableCell(new Props.TD(0, null, null), @`${Kit.localTime(new Props.LocalTime(o.createdAt(), false, null))}`)}
${Kit.rowActions(new Props.RowActions("row-" + o.id() + "-act", o.customer(),
    List.of(new Props.RowAction("eye", "View", "/orders/" + o.id(), Map.of("data-id", o.id(), "data-on:click__prevent", OrdersUi.OPEN_ORDER), false, null)),
    List.of(new Props.RowAction("ban", "Cancel order", null,
        Map.of("data-action", "/orders/" + o.id() + "/cancel", "data-on:click", Markup.openConfirm("cancel-order")),
        true, o.cancellable() ? null : "Only pending or paid orders can be cancelled")),
    null))}
`)}
