@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Breadcrumb p
<nav aria-label="breadcrumb">
<ol class="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
@for(int i = 0; i < p.items().size(); i++)@if(i > 0)<li aria-hidden="true">@template.kit.icon(name = "chevron-right", cls = "size-3.5")</li>@endif
<li class="inline-flex items-center gap-2">@if(nz(p.items().get(i).href()) != null && i < p.items().size() - 1)<a href="${p.items().get(i).href()}" class="transition-colors hover:text-foreground">${p.items().get(i).label()}</a>@else<span aria-current="page" class="font-medium text-foreground">${p.items().get(i).label()}</span>@endif</li>
@endfor
</ol>
</nav>
