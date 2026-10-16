@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.DetailField p
@param String slot = null
<div class="${p.span() ? "fc-detail-field fc-span-2" : "fc-detail-field"}">
<span class="fc-field-label">${p.label()}</span>
<div class="fc-detail-value">@if(nz(p.value()) != null)${p.value()}@elseif(slot != null)$unsafe{slot}@else—@endif</div>
</div>
