@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.FormField p
@param gg.jte.Content body = null
<div class="${p.span() ? "fc-form-field fc-span-2" : "fc-form-field"}">
<label for="${p.forId()}" class="fc-field-label">${p.label()}@if(p.required())<span class="fc-required">*</span>@endif</label>
@if(body != null)${body}@endif
@if(nz(p.error()) != null)<small id="${p.forId() + "-msg"}" class="fc-field-error">${p.error()}</small>@elseif(nz(p.help()) != null)<small class="fc-field-help">${p.help()}</small>@endif
</div>
