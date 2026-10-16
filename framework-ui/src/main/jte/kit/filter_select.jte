@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.FilterSelect p
<div class="fc-form-field">
<label class="fc-field-label" for="${"filter-" + p.name()}">${p.label()}</label>
<select id="${"filter-" + p.name()}" name="${p.name()}" class="fc-select" data-on:change="${p.autoSubmit() ? SUBMIT_ON_CHANGE : null}"$unsafe{attrs(p.attrs())}>
<option value="">${p.placeholder()}</option>
@if(p.options() != null)@for(var o : p.options())<option value="${o.value()}" selected="${o.value().equals(p.selected())}">${o.label()}</option>@endfor@endif
</select>
</div>
