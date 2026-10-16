@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.DateRange p
<div id="${id(p.id())}" class="fc-form-field" role="group" aria-labelledby="${p.id() + "-label"}">
<span id="${p.id() + "-label"}" class="fc-field-label">${p.label()}</span>
<div class="flex items-center gap-2">
<input type="date" id="${p.id() + "-from"}" name="${or(p.fromName(), "from")}" class="fc-input min-w-0 flex-1" aria-label="${or(p.fromLabel(), "From")}" value="${nz(p.from())}" max="${nz(p.to())}" data-on:focus="${"el.max = document.getElementById('" + id(p.id() + "-to") + "').value"}"$unsafe{attrs(p.attrs())}>
<span class="text-muted-foreground" aria-hidden="true">–</span>
<input type="date" id="${p.id() + "-to"}" name="${or(p.toName(), "to")}" class="fc-input min-w-0 flex-1" aria-label="${or(p.toLabel(), "To")}" value="${nz(p.to())}" min="${nz(p.from())}" data-on:focus="${"el.min = document.getElementById('" + id(p.id() + "-from") + "').value"}"$unsafe{attrs(p.attrs())}>
</div>
</div>
