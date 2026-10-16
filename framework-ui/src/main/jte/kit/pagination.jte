@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Pagination p
!{var ghost = Classes.button("ghost", "md"); var ghostIcon = Classes.button("ghost", "icon"); var current = Classes.button("outline", "icon") + (p.primaryCurrent() ? " [&]:border-primary [&]:bg-primary [&]:text-primary-foreground" : "");}
<nav aria-label="pagination" class="@container mx-auto flex w-full justify-center">
<ul class="flex flex-row flex-wrap items-center justify-center gap-1">
<li>@if(nz(p.prevHref()) != null)<a href="${p.prevHref()}" class="${ghost}"$unsafe{attrs(p.linkAttrs())}>@template.kit.icon(name = "chevron-left")<span class="sr-only @xs:not-sr-only">Previous</span></a>@else<a aria-disabled="true" class="${cx(ghost, "pointer-events-none opacity-50")}">@template.kit.icon(name = "chevron-left")<span class="sr-only @xs:not-sr-only">Previous</span></a>@endif</li>
@if(p.items() != null)@for(var it : p.items())<li>@if(it.ellipsis())<span class="flex size-9 items-center justify-center text-muted-foreground">@template.kit.icon(name = "ellipsis", cls = "size-4")<span class="sr-only">More pages</span></span>@elseif(it.page() == p.current())<a href="${it.href()}" aria-current="page" class="${current}"$unsafe{attrs(p.linkAttrs())}>${it.page()}</a>@else<a href="${it.href()}" class="${ghostIcon}"$unsafe{attrs(p.linkAttrs())}>${it.page()}</a>@endif</li>@endfor@endif
<li>@if(nz(p.nextHref()) != null)<a href="${p.nextHref()}" class="${ghost}"$unsafe{attrs(p.linkAttrs())}><span class="sr-only @xs:not-sr-only">Next</span>@template.kit.icon(name = "chevron-right")</a>@else<a aria-disabled="true" class="${cx(ghost, "pointer-events-none opacity-50")}"><span class="sr-only @xs:not-sr-only">Next</span>@template.kit.icon(name = "chevron-right")</a>@endif</li>
</ul>
</nav>
