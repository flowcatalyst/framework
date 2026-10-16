@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.MultiSelect p
<div id="${Props.MultiSelect.optionsId(p.id())}" class="fc-multiselect-options" role="group" aria-labelledby="${p.id() + "-label"}" aria-describedby="${nz(p.describedBy())}">
@if(p.options() == null || p.options().isEmpty())<p class="fc-multiselect-empty">${or(p.emptyText(), "No options")}</p>@endif
@if(p.options() != null)@for(var o : p.options())<label class="fc-multiselect-option"$unsafe{attrs(p.optionLabelAttrs(o))}><input type="checkbox" name="${p.name()}" value="${o.value()}" checked="${p.selected() != null && p.selected().contains(o.value())}"$unsafe{attrs(p.optionAttrs())}><span>${o.label()}</span></label>@endfor@endif
</div>
