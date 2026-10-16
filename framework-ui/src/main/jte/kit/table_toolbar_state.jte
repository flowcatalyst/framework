@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.TableToolbarState p
<div id="${id(p.formId()) + "-state"}" class="fc-toolbar-state">
@if(p.hidden() != null)@for(var h : p.hidden())<input type="hidden" name="${h.name()}" value="${h.value()}"$unsafe{attrs(h.attrs())}>@endfor@endif
@if(nz(p.clearHref()) != null)<a href="${url(p.clearHref())}" class="fc-btn fc-btn-text"$unsafe{attrs(p.clearAttrs())}>@template.kit.icon(name = "funnel-x", cls = "size-[1rem]") Clear All</a>@endif
@if(p.filters())<button type="button" class="fc-btn fc-btn-primary-outline" popovertarget="${id(p.formId()) + "-filters"}">@template.kit.icon(name = "funnel", cls = "size-[1rem]") Filters
@if(p.activeFilterCount() > 0)<span class="fc-badge">${p.activeFilterCount()}</span>@endif
</button>@endif
</div>
