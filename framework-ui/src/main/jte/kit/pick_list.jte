@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.PickList p
@if(p.embedded())<div id="${id(p.id())}" class="grid grid-cols-1 gap-4 sm:grid-cols-2" data-signals="${p.signals()}">
@template.kit.pick_panes(p = p)
</div>@else<form id="${id(p.id())}" class="grid grid-cols-1 gap-4 sm:grid-cols-2" data-signals="${p.signals()}" data-init="${"el.dataset.snapshot = JSON.stringify($" + signal(p.signal()) + ")"}"$unsafe{attrs(p.formAttributes())}>
@template.kit.pick_panes(p = p)
<div class="sm:col-span-2 flex justify-end gap-2">
<button type="submit" disabled data-attr:disabled="${"JSON.stringify($" + signal(p.signal()) + ") === el.form.dataset.snapshot"}" class="${Classes.button("primary", "md")}">@template.kit.icon(name = "check") ${p.saveLabel()}</button>
</div>
</form>@endif
