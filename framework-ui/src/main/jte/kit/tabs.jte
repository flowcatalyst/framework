@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Tabs p
@param java.util.Map<String, gg.jte.Content> panels
<div class="${cx("flex flex-col gap-4", p.cls())}"$unsafe{attrs(java.util.Map.<String, Object>of("data-signals:" + signalAttr(p.signal()), "'" + p.initial() + "'"))}>
<div class="${cx("inline-flex w-fit items-center gap-1 rounded-lg border border-border p-1", p.listClass())}">
@for(var t : p.tabs())<a href="${url("#" + p.panelId(t.value()))}" class="inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background text-muted-foreground hover:bg-foreground/5 hover:text-foreground aria-[current=page]:bg-foreground/10 aria-[current=page]:text-foreground aria-[current=page]:hover:bg-foreground/10" data-attr:aria-current="${p.is(t.value()) + " && 'page'"}" data-on:click__prevent="${p.select(t.value())}">${t.label()}</a>
@endfor
</div>
@for(var t : p.tabs())@if(t.value().equals(p.initial()))<div id="${p.panelId(t.value())}" data-show="${p.is(t.value())}">@if(panels.get(t.value()) != null)${panels.get(t.value())}@endif</div>
@else<div id="${p.panelId(t.value())}" data-show="${p.is(t.value())}" style="display: none">@if(panels.get(t.value()) != null)${panels.get(t.value())}@endif</div>
@endif
@endfor
</div>
