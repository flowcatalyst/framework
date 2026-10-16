@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.DataTable p
@param gg.jte.Content body = null
<div id="${nz(p.id())}" class="w-full overflow-x-auto">
<table class="${cx("w-full caption-bottom border-collapse text-sm", "[&_td]:py-[0.4rem] [&_td]:px-3 [&_th]:px-3")}">
<thead class="${cx("[&_tr]:border-b", "bg-subtle [&_th]:h-9 [&_th]:text-[12px] [&_th]:font-semibold [&_th]:tracking-wider [&_th]:text-subtle-foreground [&_th]:uppercase")}">
<tr class="border-b border-border transition-colors hover:bg-foreground/5 hover:bg-transparent">
@if(p.columns() != null)@for(var c : p.columns())@if(nz(c.href()) != null)@template.kit.sortable_header(p = new Props.SortableHeader(c.label(), c.href(), c.direction(), c.cls(), c.linkAttrs()))@else<th class="${cx("h-10 px-3 text-left align-middle font-medium whitespace-nowrap text-muted-foreground", c.cls())}" scope="col">${c.label()}</th>@endif@endfor@endif
</tr>
</thead>
<tbody id="${nz(p.bodyId())}" class="${cx("[&_tr:last-child]:border-0", "[&_tr:nth-child(even)]:bg-subtle")}">
@if(p.empty() != null)@template.kit.empty_row(p = new Props.EmptyRow(p.columns() == null ? 0 : p.columns().size(), p.empty()))@elseif(body != null)${body}@endif
</tbody>
</table>
</div>
