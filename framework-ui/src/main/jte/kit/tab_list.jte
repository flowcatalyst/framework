@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.TabList p
@param java.util.Map<String, gg.jte.Content> panels
<div class="${cx("flex flex-col gap-4", p.cls())}"$unsafe{attrs(java.util.Map.<String, Object>of("data-signals:" + signalAttr(p.signal()), "'" + p.initial() + "'"))}>
<div role="tablist" aria-label="${nz(p.label())}" class="${cx("inline-flex w-fit items-center gap-1 rounded-lg border border-border p-1", p.listClass())}" data-on:keydown="${Props.TabList.KEYS_EXPR}">
@for(var t : p.tabs())<a role="tab" id="${p.tabId(t.value())}" href="${url("?" + p.queryParam() + "=" + id(t.value()))}" aria-controls="${p.panelId(t.value())}" aria-selected="${t.value().equals(p.initial()) ? "true" : "false"}" tabindex="${t.value().equals(p.initial()) ? "0" : "-1"}" class="${Classes.TAB_LIST_TRIGGER}" data-attr:aria-selected="${p.is(t.value()) + " ? 'true' : 'false'"}" data-attr:tabindex="${p.is(t.value()) + " ? 0 : -1"}" data-on:click__prevent="${p.select(t.value())}">${t.label()}</a>@endfor
</div>
@for(var t : p.tabs())@if(t.value().equals(p.initial()))<div role="tabpanel" id="${p.panelId(t.value())}" aria-labelledby="${p.tabId(t.value())}" tabindex="0" data-show="${p.is(t.value())}">@if(panels.get(t.value()) != null)${panels.get(t.value())}@endif</div>
@else<div role="tabpanel" id="${p.panelId(t.value())}" aria-labelledby="${p.tabId(t.value())}" tabindex="0" data-show="${p.is(t.value())}" style="display: none">@if(panels.get(t.value()) != null)${panels.get(t.value())}@endif</div>
@endif
@endfor
</div>
