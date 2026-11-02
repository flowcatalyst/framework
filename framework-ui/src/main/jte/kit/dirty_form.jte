@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.DirtyForm p
@param gg.jte.Content body = null
<form id="${id(p.id())}" class="${cx("flex flex-col gap-3", p.cls())}"$unsafe{attrs(p.formAttrs())}>
@if(body != null)${body}@endif
<div class="flex gap-2">
<button type="submit" data-attr:disabled="${"!" + p.dirty()}" disabled class="${Classes.button("primary", "md")}">${or(p.saveLabel(), "Save")}</button>
<button type="reset" data-show="${p.dirty()}" style="display: none" class="${Classes.button("ghost", "md")}">${or(p.discardLabel(), "Discard")}</button>
</div>
</form>
