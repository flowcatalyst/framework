@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.MultiSelect p
<div id="${id(p.id()) + "-field"}" class="${cx("fc-form-field", p.cls())}">
<span id="${p.id() + "-label"}" class="fc-field-label">${p.label()}</span>
<details class="fc-multiselect"$unsafe{attrs(p.detailsAttrs())}>
<summary class="fc-select fc-multiselect-trigger" aria-labelledby="${p.id() + "-label " + p.id() + "-summary"}">@template.kit.multi_select_summary(p = p)</summary>
@if(p.search())<div class="fc-multiselect-panel"$unsafe{attrs(p.signalAttrs("data-signals"))}>
<input type="search" class="fc-input fc-multiselect-search" placeholder="Search" aria-label="${"Search " + p.label()}" autocomplete="off"$unsafe{attrs(p.signalAttrs("data-bind"))}>
@template.kit.multi_select_options(p = p)
</div>@else<div class="fc-multiselect-panel">@template.kit.multi_select_options(p = p)</div>@endif
</details>
</div>
