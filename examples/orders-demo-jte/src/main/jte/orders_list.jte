@import io.flowcatalyst.framework.examples.ordersjte.*
@import io.flowcatalyst.framework.ui.kit.*
@import java.util.List
@param OrdersView v

!{var q = v.query(); var l = v.listing();}
<div id="orders-region">
${Kit.tableToolbar(
    new Props.TableToolbar("orders-list", "/orders", "Search customers", q.q(), false, null, false,
        q.filtered() ? "/orders" : null, null, q.activeFilters(),
        List.of(new Props.HiddenField("sort", q.sort(), null), new Props.HiddenField("dir", q.desc() ? "desc" : "asc", null), new Props.HiddenField("rows", String.valueOf(q.rows()), null)),
        null, null),
    @`${Kit.filterSelect(new Props.FilterSelect("status", "Status", "All statuses",
        List.of(new Props.Option("PENDING", "Pending"), new Props.Option("PAID", "Paid"), new Props.Option("SHIPPED", "Shipped"), new Props.Option("CANCELLED", "Cancelled")),
        q.status(), true, null))}`)}

${Kit.dataTable(
    new Props.DataTable("orders-table", "orders-rows",
        List.of(
            new Props.DataColumn("Customer", q.sortHref("customer"), q.direction("customer"), null, null),
            new Props.DataColumn("Status", q.sortHref("status"), q.direction("status"), null, null),
            new Props.DataColumn("Total", q.sortHref("total"), q.direction("total"), null, null),
            new Props.DataColumn("Placed", q.sortHref("created"), q.direction("created"), null, null),
            new Props.DataColumn("Actions", null, null, "w-px text-right", null)),
        l.rows().isEmpty() ? new Props.EmptyState("No orders match.", "inbox", q.filtered() ? "/orders" : null, true) : null),
    @`@for(var o : l.rows())@template.order_row(o = o)@endfor`)}

${Kit.paginator(new Props.Paginator("orders-pager", l.page(), l.pages(), l.total(), q.rows(), Query.ROWS, "orders", q.pagePattern(), "orders-list", null))}
</div>
