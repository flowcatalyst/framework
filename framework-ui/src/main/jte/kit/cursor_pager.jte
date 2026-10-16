@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.CursorPager p
<div id="${nz(p.id())}" class="fc-table-footer">
<span>${p.summary()}</span>
<nav class="flex items-center gap-2" aria-label="Pagination">
@if(nz(p.newestHref()) != null)<a class="fc-btn fc-btn-outline fc-btn-sm" href="${url(p.newestHref())}"$unsafe{attrs(p.linkAttrs())}>@template.kit.icon(name = "chevrons-left") Newest</a>@else<span class="fc-btn fc-btn-outline fc-btn-sm" aria-disabled="true">@template.kit.icon(name = "chevrons-left") Newest</span>@endif
@if(nz(p.olderHref()) != null)<a class="fc-btn fc-btn-outline fc-btn-sm" href="${url(p.olderHref())}"$unsafe{attrs(p.linkAttrs())}>@template.kit.icon(name = "chevron-right") Older</a>@else<span class="fc-btn fc-btn-outline fc-btn-sm" aria-disabled="true">@template.kit.icon(name = "chevron-right") Older</span>@endif
</nav>
</div>
