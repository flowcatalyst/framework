@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.SearchSelect p
<div id="${id(p.id()) + "-field"}" class="${cx("fc-form-field", p.cls())}">
<span id="${p.id() + "-label"}" class="fc-field-label">${p.label()}</span>
<details class="fc-multiselect">
<summary class="fc-select fc-multiselect-trigger" aria-labelledby="${p.id() + "-label " + p.id() + "-summary"}"><span id="${p.id() + "-summary"}" class="fc-multiselect-summary">${p.summary()}</span></summary>
<div class="fc-multiselect-panel"$unsafe{attrs(java.util.Map.<String, Object>of("data-signals:" + signalAttr(p.signal()), "''"))}>
<input type="search" class="fc-input fc-multiselect-search" placeholder="${Kit.t("kit.search_select.search")}" aria-label="${Kit.t("kit.search_select.search_for", "label", p.label() == null ? "" : p.label())}" autocomplete="off"$unsafe{attrs(java.util.Map.<String, Object>of("data-bind:" + signalAttr(p.signal()), true))}>
<div id="${p.id() + "-options"}" class="fc-multiselect-options" role="listbox" aria-labelledby="${p.id() + "-label"}">
@if(p.opts().isEmpty())<p class="fc-multiselect-empty">${or(p.emptyText(), Kit.t("kit.search_select.no_options"))}</p>@endif
@for(var o : p.opts())<a class="fc-multiselect-option" role="option" href="${url(p.href().apply(o.value()))}" aria-selected="${o.value().equals(p.selected()) ? "true" : "false"}"$unsafe{attrs(p.optionAttributes(o))}>${o.label()}</a>
@endfor
</div>
</div>
</details>
</div>
