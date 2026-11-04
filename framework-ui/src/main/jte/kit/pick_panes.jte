@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.PickList p
<div class="flex min-w-0 flex-col overflow-hidden rounded-lg border border-border">
<div class="flex flex-col gap-2 border-b border-border bg-subtle p-3">
<h4 class="text-[12px] font-semibold tracking-wider text-subtle-foreground uppercase">${p.availableTitle()}</h4>
<input class="h-9 w-full min-w-0 rounded-lg border border-input bg-card px-3 text-sm transition-colors outline-none placeholder:text-muted-foreground file:mr-3 file:h-full file:border-0 file:bg-transparent file:text-sm file:font-medium focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50" type="search" placeholder="${p.filterPlaceholder()}" aria-label="${p.filterPlaceholder()}"$unsafe{attrs(java.util.Map.<String, Object>of("data-bind:" + signalAttr(p.filterSignal()), true))}>
@if(p.bulk())<div class="flex gap-2"><button type="button" class="${Classes.button("outline", "sm")}" data-on:click="${p.selectShownExpr()}">${Kit.t("kit.picklist.select_shown")}</button> <button type="button" class="${Classes.button("ghost", "sm")}" data-on:click="${p.clearExpr()}">${Kit.t("kit.picklist.clear")}</button></div>@endif
</div>
<div class="h-[200px] overflow-y-auto p-1">
@for(var run : p.runs())@if(run.group() != null)<div data-show="${p.filterExpr()}" data-filter-text="${run.filterText()}" class="px-2 pt-2 pb-1 text-[11px] font-semibold tracking-wider text-subtle-foreground uppercase">${run.group()}</div>@endif
@for(var it : run.items())<label for="${p.itemId(it.token())}" data-show="${p.filterExpr()}" data-filter-text="${Props.PickList.filterText(it)}" class="${cx("flex cursor-pointer items-center justify-between gap-3 rounded-md px-2 py-1.5 hover:bg-foreground/5 has-[:checked]:bg-primary/10", it.locked() ? "cursor-default opacity-70" : null)}">
@template.kit.pick_text(it = it)
<span class="peer relative inline-flex shrink-0 has-[:disabled]:opacity-50"><input type="checkbox" class="peer size-4 shrink-0 appearance-none rounded-[4px] border border-input bg-background transition-colors outline-none checked:border-primary checked:bg-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none" id="${p.itemId(it.token())}" name="${it.locked() ? null : p.name()}" value="${it.locked() ? null : it.value()}" checked="${it.picked()}" disabled="${it.locked()}"$unsafe{attrs(p.bindAttrs(it))}>@template.kit.icon(name = "check", cls = "pointer-events-none absolute inset-0 m-auto size-3.5 text-primary-foreground opacity-0 peer-checked:opacity-100")@if(it.locked())<input type="hidden" name="${p.name()}" value="${it.value()}">@endif</span>
</label>@endfor@endfor
</div>
</div>
<div class="flex min-w-0 flex-col overflow-hidden rounded-lg border border-border">
<div class="border-b border-border bg-subtle p-3"><h4 class="text-[12px] font-semibold tracking-wider text-subtle-foreground uppercase">${or(p.selectedTitle(), Kit.t("kit.picklist.selected"))} (<span data-text="${p.countExpr()}">${p.picked()}</span>)</h4></div>
<div class="h-[200px] overflow-y-auto p-1">
@for(var run : p.runs())@if(run.group() != null && run.hasLocked())<div class="px-2 pt-2 pb-1 text-[11px] font-semibold tracking-wider text-subtle-foreground uppercase">${run.group()}</div>@elseif(run.group() != null)<div data-members="${run.members()}" data-show="${p.groupPickedExpr()}" style="${run.hasPick() ? null : "display: none"}" class="px-2 pt-2 pb-1 text-[11px] font-semibold tracking-wider text-subtle-foreground uppercase">${run.group()}</div>@endif
@for(var it : run.items())@if(it.locked())<div class="flex items-center justify-between gap-3 rounded-md px-2 py-1.5">
@template.kit.pick_text(it = it)
<span class="inline-flex size-7 shrink-0 items-center justify-center text-muted-foreground" role="img" title="${Kit.t("kit.picklist.locked")}" aria-label="${Kit.t("kit.picklist.locked_named", "name", it.name())}">@template.kit.icon(name = "lock", cls = "size-[0.9rem]")</span>
</div>@else<div data-value="${it.value()}" data-show="${"$" + signal(p.signal()) + ".includes(el.dataset.value)"}" style="${it.checked() ? null : "display: none"}" class="flex items-center justify-between gap-3 rounded-md px-2 py-1.5">
@template.kit.pick_text(it = it)
<label for="${p.itemId(it.token())}" title="${Kit.t("kit.picklist.remove")}" aria-label="${Kit.t("kit.picklist.remove_named", "name", it.name())}" class="${cx(Classes.button("ghost", "icon"), "[&]:size-7 [&]:rounded-full cursor-pointer text-destructive")}">@template.kit.icon(name = "x", cls = "size-[0.9rem]")</label>
</div>@endif@endfor@endfor
<p data-show="${p.countExpr() + " === 0"}" style="${p.picked() > 0 ? "display: none" : null}" class="p-3 text-center text-muted-foreground">${p.emptyText()}</p>
</div>
</div>
