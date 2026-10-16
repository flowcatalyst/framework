@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.SortableHeader p
!{var dir = Classes.oneOf(p.direction(), "", "sort direction", "asc", "desc");}
<th class="${cx("h-10 px-3 text-left align-middle font-medium whitespace-nowrap text-muted-foreground", p.cls())}" scope="col" aria-sort="${dir.equals("asc") ? "ascending" : dir.equals("desc") ? "descending" : null}">
<a href="${url(p.href())}" class="inline-flex items-center gap-1 text-inherit no-underline hover:text-foreground"$unsafe{attrs(p.linkAttrs())}>${p.label()}
@if(dir.equals("asc"))@template.kit.icon(name = "arrow-up", cls = "size-[0.85rem]")@elseif(dir.equals("desc"))@template.kit.icon(name = "arrow-down", cls = "size-[0.85rem]")@else@template.kit.icon(name = "arrow-up-down", cls = "size-[0.85rem] opacity-40")@endif
</a>
</th>
