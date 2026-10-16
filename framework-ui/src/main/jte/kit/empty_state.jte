@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.EmptyState p
<div class="fc-empty" role="${p.status() ? "status" : null}">
@template.kit.icon(name = or(p.icon(), "inbox"), cls = "size-12")
<span>${p.message()}</span>
@if(nz(p.clearHref()) != null)<a href="${p.clearHref()}" class="fc-btn fc-btn-text-primary">Clear filters</a>@endif
</div>
