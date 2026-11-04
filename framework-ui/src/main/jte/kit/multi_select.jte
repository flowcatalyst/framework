@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.MultiSelect p
<div id="${id(p.id()) + "-field"}" class="${cx("fc-form-field", p.cls())}">
<span id="${p.id() + "-label"}" class="fc-field-label">${p.label()}</span>
<details class="fc-multiselect"$unsafe{attrs(p.detailsAttrs())}>
<summary class="fc-select fc-multiselect-trigger" aria-labelledby="${p.id() + "-label " + p.id() + "-summary"}">@template.kit.multi_select_summary(p = p)</summary>
@if(p.search())<div class="fc-multiselect-panel"$unsafe{attrs(p.signalAttrs("data-signals"))}>
<input type="search" class="fc-input fc-multiselect-search" placeholder="${Kit.t("kit.multiselect.search")}" aria-label="${Kit.t("kit.multiselect.search_for", "label", p.label())}" autocomplete="off"$unsafe{attrs(p.signalAttrs("data-bind"))}>
@template.kit.multi_select_options(p = p)
</div>@else<div class="fc-multiselect-panel">@template.kit.multi_select_options(p = p)</div>@endif
</details>
@if(p.chips())<div id="${id(p.id()) + "-chips"}" class="fc-multiselect-chips" data-on:click="${Props.MultiSelect.REMOVE_CHIP_EXPR}">@for(var o : p.chosen())<span class="fc-multiselect-chip" data-value="${o.value()}"><span>${o.label()}</span><button type="button" class="fc-multiselect-chip-remove" aria-label="${Kit.t("kit.multiselect.remove", "label", o.label())}">&times;</button></span>@endfor</div>@endif
</div>
