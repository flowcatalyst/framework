@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.TR p
@param gg.jte.Content body = null
<tr id="${nz(p.id())}" class="${cx("border-b border-border transition-colors hover:bg-foreground/5" + (p.isStatic() ? " hover:bg-transparent" : "") + (p.clickable() ? " cursor-pointer" : ""), p.cls())}"$unsafe{attrs(p.attrs())}>@if(body != null)${body}@endif</tr>
