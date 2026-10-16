@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Alert p
@param gg.jte.Content body = null
<div id="${nz(p.id())}" class="${cx("grid w-full grid-cols-[0_1fr] items-start gap-y-1 rounded-lg border bg-background px-4 py-3 text-sm has-[>svg]:grid-cols-[1rem_1fr] has-[>svg]:gap-x-3 [&>svg]:size-4 [&>svg]:translate-y-0.5", Classes.alertVariant(p.variant()), p.cls())}" role="${nz(p.role())}">
@if(nz(p.icon()) != null)@template.kit.icon(name = p.icon())@endif
@if(nz(p.title()) != null)<p class="col-start-2 font-medium tracking-tight">${p.title()}</p>@endif
@if(nz(p.description()) != null)<div class="col-start-2 text-sm text-muted-foreground">${p.description()}</div>@endif
@if(body != null)${body}@endif
</div>
