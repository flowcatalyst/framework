@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.PickList p
<form id="${id(p.id())}" class="grid grid-cols-1 gap-4 sm:grid-cols-2" data-signals="${p.signals()}" data-init="${"el.dataset.snapshot = JSON.stringify($" + signal(p.signal()) + ")"}"$unsafe{attrs(p.formAttributes())}>
<div class="flex min-w-0 flex-col overflow-hidden rounded-lg border border-border">
<div class="flex flex-col gap-2 border-b border-border bg-subtle p-3">
<h4 class="text-[12px] font-semibold tracking-wider text-subtle-foreground uppercase">${p.availableTitle()}</h4>
<input class="h-9 w-full min-w-0 rounded-lg border border-input bg-card px-3 text-sm transition-colors outline-none placeholder:text-muted-foreground file:mr-3 file:h-full file:border-0 file:bg-transparent file:text-sm file:font-medium focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50" type="search" placeholder="${p.filterPlaceholder()}" aria-label="${p.filterPlaceholder()}"$unsafe{attrs(java.util.Map.<String, Object>of("data-bind:" + signalAttr(p.filterSignal()), true))}>
</div>
<div class="h-[200px] overflow-y-auto p-1">
@if(p.items() != null)@for(var it : p.items())<label for="${p.itemId(it.value())}" data-show="${p.filterExpr()}" data-filter-text="${Props.PickList.filterText(it)}" class="flex cursor-pointer items-center justify-between gap-3 rounded-md px-2 py-1.5 hover:bg-foreground/5 has-[:checked]:bg-primary/10">
@template.kit.pick_text(it = it)
<span class="peer relative inline-flex shrink-0 has-[:disabled]:opacity-50"><input type="checkbox" class="peer size-4 shrink-0 appearance-none rounded-[4px] border border-input bg-background transition-colors outline-none checked:border-primary checked:bg-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none" id="${p.itemId(it.value())}" name="${p.name()}" value="${it.value()}" checked="${it.checked()}"$unsafe{attrs(java.util.Map.<String, Object>of("data-bind:" + signalAttr(p.signal()), true))}>@template.kit.icon(name = "check", cls = "pointer-events-none absolute inset-0 m-auto size-3.5 text-primary-foreground opacity-0 peer-checked:opacity-100")</span>
</label>@endfor@endif
</div>
</div>
<div class="flex min-w-0 flex-col overflow-hidden rounded-lg border border-border">
<div class="border-b border-border bg-subtle p-3"><h4 class="text-[12px] font-semibold tracking-wider text-subtle-foreground uppercase">${or(p.selectedTitle(), "Selected")} (<span data-text="${"$" + signal(p.signal()) + ".filter(Boolean).length"}">${p.picked()}</span>)</h4></div>
<div class="h-[200px] overflow-y-auto p-1">
@if(p.items() != null)@for(var it : p.items())<div data-value="${it.value()}" data-show="${"$" + signal(p.signal()) + ".includes(el.dataset.value)"}" style="${it.checked() ? null : "display: none"}" class="flex items-center justify-between gap-3 rounded-md px-2 py-1.5">
@template.kit.pick_text(it = it)
<label for="${p.itemId(it.value())}" title="Remove" aria-label="${"Remove " + it.name()}" class="${cx(Classes.button("ghost", "icon"), "[&]:size-7 [&]:rounded-full cursor-pointer text-destructive")}">@template.kit.icon(name = "x", cls = "size-[0.9rem]")</label>
</div>@endfor@endif
<p data-show="${"$" + signal(p.signal()) + ".filter(Boolean).length === 0"}" style="${p.picked() > 0 ? "display: none" : null}" class="p-3 text-center text-muted-foreground">${p.emptyText()}</p>
</div>
</div>
<div class="sm:col-span-2 flex justify-end gap-2">
<button type="submit" disabled data-attr:disabled="${"JSON.stringify($" + signal(p.signal()) + ") === el.form.dataset.snapshot"}" class="${Classes.button("primary", "md")}">@template.kit.icon(name = "check") ${p.saveLabel()}</button>
</div>
</form>
