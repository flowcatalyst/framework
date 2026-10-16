@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Table p
@param gg.jte.Content body = null
<div id="${nz(p.id())}" class="w-full overflow-x-auto">
<table class="${cx("w-full caption-bottom border-collapse text-sm", p.dense() ? "[&_td]:py-[0.4rem] [&_td]:px-3 [&_th]:px-3" : null, p.cls())}"$unsafe{attrs(p.attrs())}>
@if(nz(p.caption()) != null)<caption class="mt-4 text-sm text-muted-foreground">${p.caption()}</caption>@endif
@if(body != null)${body}@endif
</table>
</div>
